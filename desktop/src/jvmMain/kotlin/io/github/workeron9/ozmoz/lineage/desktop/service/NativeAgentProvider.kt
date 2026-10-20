package io.github.workeron9.ozmoz.lineage.desktop.service

import io.github.workeron9.ozmoz.lineage.desktop.model.AgentEvent
import io.github.workeron9.ozmoz.lineage.desktop.model.DiscoveredAgent
import io.github.workeron9.ozmoz.lineage.desktop.model.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 本地 agent 的**进程内适配器**：把 opencode / pi 的 CLI 当子进程调，
 * 按行读 NDJSON 并交给 [AgentStreamParser]。
 *
 * 为什么不内置 agent（D2 设计指导 §4.4）：用户机器上已经配好了模型、Key 与 skill，
 * 复用它比重新配一套自然；本类只负责「起进程、喂 prompt、把输出翻译成事件」。
 *
 * 两个 CLI 的调用形状（2026-10-19 本机实测 opencode 2.0.29 / pi 1.3.0）：
 * - `opencode run --format json --standalone [--session <ses…>] <prompt>`
 *   ——`--standalone` 起私有 server，避免连用户正在用的后台服务；session id 必须带 `ses` 前缀；
 * - `pi -p --mode json [--session-id <id>] <prompt>`
 *   ——`--session-id` 同 id 能续上下文（实测第二轮答得出第一轮内容）。
 *
 * skills 与扩展**跟随宿主 agent**（不传 `--no-skills`）：用户写好的 skill 应当被继承。
 * 工具权限也归宿主 agent 自己的配置管——本进程不代它授权。
 */
internal object NativeAgentProvider {

    /** 起进程；失败经 [onFailure] 说明原因并返回 null。 */
    fun launch(
        provider: ProviderConfig,
        sessionId: String,
        prompt: String,
        onFailure: (String) -> Unit,
    ): Process? {
        val command = when (provider.id) {
            "opencode" -> listOf(
                provider.endpoint.ifBlank { "opencode" },
                "run", "--format", "json", "--standalone",
            ) + modelFlag(provider.model) + listOf("--session", sessionId, prompt)

            "pi" -> listOf(
                provider.endpoint.ifBlank { "pi" },
                "-p", "--mode", "json",
            ) + modelFlag(provider.model) + listOf("--session-id", sessionId, prompt)

            else -> {
                onFailure("未知的本地 agent：${provider.id}")
                return null
            }
        }
        return runCatching {
            ProcessBuilder(command)
                // Finder 启动的 .app 只有 launchd 的极简 PATH；agent 自己的壳脚本要靠它找 node 等。
                .also { it.environment()["PATH"] = AgentDiscovery.searchPath() }
                .redirectErrorStream(false).start().also {
                // 立即关掉子进程的 stdin：print 模式不读输入，但打开的管道会让某些 agent
                // 等 EOF（实测 pi 在测试 JVM 里 120s 不出字，shell 里 9s 答完）。
                it.outputStream.close()
            }
        }.getOrElse {
            onFailure("起进程失败：${it.message}")
            null
        }
    }

    /** `--model` 只在用户选过模型时传：空 = 跟随 agent 自己的默认配置。 */
    private fun modelFlag(model: String): List<String> =
        if (model.isBlank()) emptyList() else listOf("--model", model)

    /** 读 stdout 直到进程结束；结束前把 stderr 作为失败原因交出去。 */
    fun consume(process: Process, onEvent: (AgentEvent) -> Unit) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    for (event in AgentStreamParser.parseLine(line)) onEvent(event)
                }
            }
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            val stderr = process.errorStream.bufferedReader().readText().trim()
            if (!finished) {
                process.destroyForcibly()
                onEvent(AgentEvent.Failure("agent 进程超时未退出（已杀掉）"))
            } else if (process.exitValue() != 0) {
                onEvent(AgentEvent.Failure(stderr.ifBlank { "agent 退出码 ${process.exitValue()}" }))
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/**
 * 本地 agent 发现（D2 设计指导 §4.2）：扫 PATH + 读各自的配置目录。
 *
 * **只读**：不改用户配置、不写 skill、不联网。探测失败也返回条目（带 [DiscoveredAgent.note]），
 * 因为「装是装了但读不出模型」与「没装」是两件事，界面要说清。
 *
 * PATH 不只信进程环境：从 Finder 启动的 .app 只有 launchd 的 `/usr/bin:/bin:/usr/sbin:/sbin`，
 * Homebrew / ~/.local/bin 里的 agent 会「装了却扫不到」。所以再扫一组常见安装目录，
 * 并把调用方传进来的已知可执行文件路径（设置文件里存的 provider.endpoint）当候选。
 */
internal object AgentDiscovery {

    fun discover(knownExecutables: List<String> = emptyList()): List<DiscoveredAgent> =
        listOfNotNull(discoverOpencode(knownExecutables), discoverPi(knownExecutables))

    /** 子进程用的 PATH：进程环境 + 常见安装目录（Finder 启动的 .app 里 agent 要靠它找到 node 等）。 */
    fun searchPath(): String =
        (System.getenv("PATH").orEmpty().split(File.pathSeparatorChar) + EXTRA_DIRS)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(File.pathSeparator)

    private val EXTRA_DIRS by lazy {
        listOf(
            "/opt/homebrew/bin",
            "/opt/homebrew/sbin",
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
        ) + listOf(".local/bin", "bin", ".bun/bin", ".npm-global/bin", ".opencode/bin", ".volta/bin").map {
            Path.of(System.getProperty("user.home"), it).toString()
        }
    }

    private fun discoverOpencode(known: List<String>): DiscoveredAgent? {
        val exe = resolve("opencode", known) ?: return null
        val version = run(exe, "--version")
        val configDir = home(".config/opencode")
        val config = configDir.resolve("opencode.jsonc").takeIf { Files.isRegularFile(it) }
        val model = config?.let { readText(it) }
            ?.let { text -> Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) }
        val skills = listSkills(configDir.resolve("skills"))
        return DiscoveredAgent(
            id = "opencode",
            name = "OpenCode",
            executable = exe,
            version = version,
            model = model,
            skills = skills,
            note = if (version == null) "找到了可执行文件，但 --version 没有输出" else null,
            // 不带 --model 时 opencode 会落到它自己的默认/免费额度（用户实测：
            // 「free tier can only be used from within OpenCode」），所以把可选模型都列出来。
            models = runLines(exe, "models").take(500),
        )
    }

    private fun discoverPi(known: List<String>): DiscoveredAgent? {
        val exe = resolve("pi", known) ?: return null
        val version = run(exe, "--version")
        val configDir = home(".pi/agent")
        val settings = configDir.resolve("settings.json").takeIf { Files.isRegularFile(it) }
        val model = settings?.let { readText(it) }
            ?.let { text ->
                val provider = Regex("\"defaultProvider\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                val name = Regex("\"defaultModel\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                listOfNotNull(provider, name).joinToString("/").ifBlank { null }
            }
        val skills = (listSkills(home(".pi/skills")) + listSkills(home(".agents/skills"))).distinct().sorted()
        val models = piModels(settings?.let { readText(it) })
            .let { ids -> if (model != null && model !in ids) listOf(model) + ids else ids }
        return DiscoveredAgent(
            id = "pi",
            name = "pi",
            executable = exe,
            version = version,
            model = model,
            skills = skills,
            note = if (version == null) "找到了可执行文件，但 --version 没有输出" else null,
            models = models,
        )
    }

    /** 设置里存过的绝对路径优先（它绕开 PATH），其次才扫 PATH 与常见目录。 */
    private fun resolve(name: String, known: List<String>): String? =
        known.firstOrNull { candidate ->
            val file = File(candidate)
            candidate.isNotBlank() && file.isFile && file.canExecute() &&
                file.name.substringBeforeLast('.').equals(name, ignoreCase = true)
        } ?: findOnPath(name)

    /** PATH 里的可执行文件；Windows 补 `.exe` / `.cmd` / `.bat`。 */
    private fun findOnPath(name: String): String? {
        val path = searchPath().split(File.pathSeparatorChar)
        val suffixes = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("", ".exe", ".cmd", ".bat")
        } else {
            listOf("")
        }
        for (dir in path) {
            for (suffix in suffixes) {
                val candidate = Path.of(dir, name + suffix)
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toString()
            }
        }
        return null
    }

    private fun run(exe: String, arg: String): String? = runCatching {
        val process = ProcessBuilder(exe, arg).redirectErrorStream(true).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        process.inputStream.bufferedReader().readText().trim().lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.let { line -> line.replace(Regex("^[^0-9]*"), "").ifBlank { line } }
    }.getOrNull()

    /** 读多行输出（`opencode models`）；stderr 丢掉（里面的 warning 不是模型 id）。 */
    private fun runLines(exe: String, arg: String): List<String> = runCatching {
        val process = ProcessBuilder(exe, arg).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return emptyList()
        }
        text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
    }.getOrDefault(emptyList())

    /** pi 的候选模型 = `settings.json` 的 `enabledModels`（`--model` 收这些 id 与模式）。 */
    internal fun piModels(settingsJson: String?): List<String> = runCatching {
        val root = Json.parseToJsonElement(settingsJson ?: return emptyList()).jsonObject
        root["enabledModels"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun listSkills(dir: Path): List<String> =
        if (!Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.list(dir).use { stream ->
                stream.filter { Files.isDirectory(it) || it.fileName.toString().endsWith(".md") }
                    .map { it.fileName.toString().removeSuffix(".md") }
                    .sorted()
                    .toList()
            }
        }

    private fun home(relative: String): Path = Path.of(System.getProperty("user.home"), relative)

    private fun readText(path: Path): String? = runCatching { Files.readString(path) }.getOrNull()
}
