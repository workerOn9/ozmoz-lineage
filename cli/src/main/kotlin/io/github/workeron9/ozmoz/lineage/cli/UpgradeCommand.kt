package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * `ozml upgrade` —— 自更新（ADR-0021）。
 *
 * 这是**唯一**会联网的子命令，且**只在显式调用时**发生：正常 `parse` / `lineage` /
 * `serve` 等一律离线，不查版本、不回传任何数据。升级路径对齐 `install.sh` 的布局：
 *
 * ```
 * $OZML_ROOT/                    （默认 ~/.local/share/ozml）
 *   current                      ← 文本文件，内容为当前版本号（原子改写）
 *   bin-dir                      ← 安装时 shim 所在目录（upgrade 沿用）
 *   versions/<version>/{bin,lib,runtime}
 * ```
 *
 * 升级 = 下 zip → 校验 sha256 → 解压到 `versions/<新版本>` → 原子改写 `current`。
 * 旧版本目录保留，可手动回滚；正在运行的进程不受影响。
 */
public class UpgradeCommand : CliktCommand(name = "upgrade") {

    private val checkOnly: Boolean by option(
        "--check",
        help = "只查最新版本并比较，不下载、不修改任何文件",
    ).flag()

    private val requestedVersion: String? by option(
        "--version",
        help = "安装指定版本（如 0.2.0 或 v0.2.0），缺省取 GitHub 最新 release",
    )

    private val rootOption: String? by option(
        "--root",
        help = "安装根目录（含 versions/ 与 current），缺省按运行位置推断或取 \$OZML_ROOT",
    )

    private val force: Boolean by option(
        "--force",
        help = "即使目标版本与当前相同也重新安装",
    ).flag()

    override fun help(context: Context): String =
        "自更新：从 GitHub Releases 拉最新版并原子切换（唯一联网子命令，只在显式调用时发生）。"

    override fun run() {
        try {
            performUpgrade()
        } catch (e: CliktError) {
            throw e
        } catch (e: Exception) {
            // 网络 / 校验 / 文件错误统一给一行干净提示 + 非零退出（不吐栈），与项目
            // 其他子命令「错误是数据、退出码表达」的惯例一致。
            echo("ozml upgrade 失败：${e.message ?: e.toString()}", err = true)
            throw ProgramResult(1)
        }
    }

    private fun performUpgrade() {
        val root = RootLayout.resolveRoot(rootOption)
        val updater = SelfUpdater()

        val current = BuildInfo.version
        val target = SelfUpdater.normalizeVersion(requestedVersion ?: updater.latestVersion())

        if (checkOnly) {
            echo("当前版本 : $current")
            echo("最新版本 : $target")
            echo(if (available(target, current)) "有可用更新" else "已是最新")
            return
        }

        if (!available(target, current)) {
            echo("已是最新：$current（用 --force 可强制重装，--version 可指定版本）")
            return
        }

        echo("正在升级：$current → $target")
        val installed = updater.install(root, target)
        RootLayout.writeCurrent(root, target)
        RootLayout.ensureShim(root)
        echo("完成，已安装到 ${installed.toAbsolutePath()}")
        echo("再次运行 `ozml --version` 确认（若是同一路径，即已生效）。")
    }

    private fun available(target: String, current: String): Boolean =
        force || target != SelfUpdater.normalizeVersion(current)
}

/** `ozml upgrade` 的安装布局约定（也是 `install.sh` 写入的约定，两侧必须一致）。 */
internal object RootLayout {

    internal const val DEFAULT_ROOT_SUFFIX = ".local/share/ozml"
    internal const val CURRENT_FILE = "current"
    internal const val BIN_DIR_FILE = "bin-dir"

    /**
     * 解析安装根目录，优先级：`--root` > `$OZML_ROOT` > 从运行位置推断 > `~/.local/share/ozml`。
     *
     * 从运行位置推断：若本进程的 jar 落在 `<root>/versions/<版本>/lib/` 下，则 `<root>`
     * 即安装根——这样 `install.sh` 装的 `ozml` 不带任何环境变量也能 `upgrade` 自己。
     */
    internal fun resolveRoot(override: String?): Path {
        override?.let { return Paths.get(it).toAbsolutePath().normalize() }
        System.getenv("OZML_ROOT")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it).toAbsolutePath().normalize()
        }
        inferFromCodeSource()?.let { return it }
        return Paths.get(System.getProperty("user.home"), DEFAULT_ROOT_SUFFIX)
    }

    private fun inferFromCodeSource(): Path? {
        val location = RootLayout::class.java.protectionDomain?.codeSource?.location ?: return null
        val jar = runCatching { Paths.get(location.toURI()) }.getOrNull() ?: return null
        var dir: Path? = jar.parent
        while (dir != null) {
            if (dir.name == "versions") return dir.parent
            dir = dir.parent
        }
        return null
    }

    internal fun currentVersion(root: Path): String? {
        val file = root.resolve(CURRENT_FILE)
        return if (file.exists()) Files.readString(file).trim().ifBlank { null } else null
    }

    /** 原子改写 `current`（写临时文件再 rename，避免读端看到半截）。 */
    internal fun writeCurrent(root: Path, version: String) {
        Files.createDirectories(root)
        val tmp = root.resolve("$CURRENT_FILE.tmp")
        Files.writeString(tmp, "$version\n")
        val target = root.resolve(CURRENT_FILE)
        runCatching {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    internal fun binDir(root: Path): Path {
        val file = root.resolve(BIN_DIR_FILE)
        if (file.exists()) {
            Files.readString(file).trim().takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        }
        return Paths.get(System.getProperty("user.home"), ".local/bin")
    }

    /** 确保 `ozml` shim 存在且指向 `current`（`install.sh` 建立，upgrade 兜底修复）。 */
    internal fun ensureShim(root: Path) {
        val binDir = binDir(root)
        Files.createDirectories(binDir)
        val shim = binDir.resolve("ozml")
        Files.writeString(shim, unixShim(root))
        setExecutable(shim)
        if (isWindows()) {
            Files.writeString(binDir.resolve("ozml.cmd"), windowsShim(root))
        }
    }

    private fun unixShim(root: Path): String = buildString {
        append("#!/bin/sh\n")
        append("# 由 ozml install.sh / `ozml upgrade` 生成，勿手改。\n")
        append("OZML_ROOT=\"\${OZML_ROOT:-$root}\"\n")
        append("v=\$(cat \"\$OZML_ROOT/$CURRENT_FILE\" 2>/dev/null) || ")
        append("{ echo \"ozml: 未安装（缺 \$OZML_ROOT/$CURRENT_FILE）\" >&2; exit 1; }\n")
        append("exec \"\$OZML_ROOT/versions/\$v/bin/ozml\" \"\$@\"\n")
    }

    private fun windowsShim(root: Path): String = buildString {
        append("@echo off\r\n")
        append("setlocal\r\n")
        append("if not defined OZML_ROOT set \"OZML_ROOT=$root\"\r\n")
        append("for /f \"usebackq delims=\" %%v in (\"%OZML_ROOT%\\$CURRENT_FILE\") do set \"OZML_VER=%%v\"\r\n")
        append("\"%OZML_ROOT%\\versions\\%OZML_VER%\\bin\\ozml.bat\" %*\r\n")
    }

    internal fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    internal fun setExecutable(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        }.onFailure {
            path.toFile().setExecutable(true)
        }
    }
}

/**
 * `ozml upgrade` 的网络 + 文件落地。构造参数留了口子（基址 / 平台 / HttpClient），
 * 便于测试用本地 HTTP 服务驱动完整流程（不做 mock 网络）。
 */
internal class SelfUpdater(
    private val releaseBaseUrl: String,
    private val apiBaseUrl: String,
    private val token: String?,
    private val platform: String,
    private val client: HttpClient,
) {

    internal constructor() : this(
        releaseBaseUrl = envOrNull("OZML_RELEASE_BASE") ?: DEFAULT_RELEASE_BASE,
        apiBaseUrl = envOrNull("OZML_API_BASE") ?: DEFAULT_API_BASE,
        token = defaultToken(),
        platform = platformId(System.getProperty("os.name"), System.getProperty("os.arch")),
        client = defaultClient(),
    )

    /** 查 GitHub 最新 release 的版本号（已去掉前导 `v`）。 */
    internal fun latestVersion(): String {
        val body = getText(
            "$apiBaseUrl/releases/latest",
            accept = "application/vnd.github+json",
        )
        val tag = runCatching {
            JSON.decodeFromString(GithubRelease.serializer(), body).tagName
        }.getOrNull() ?: error("解析 GitHub 响应失败（缺少 tag_name）")
        return normalizeVersion(tag)
    }

    /** 下载指定版本的 zip、校验 sha256、解压到 `<root>/versions/<version>`，返回该目录。 */
    internal fun install(root: Path, version: String): Path {
        val asset = assetName(version, platform)
        val versionsDir = root.resolve("versions")
        Files.createDirectories(versionsDir)

        val tmpDir = Files.createTempDirectory(versionsDir, ".$version.tmp-")
        try {
            val zip = tmpDir.resolve(asset)
            downloadTo("$releaseBaseUrl/download/v$version/$asset", zip)
            val expected = fetchChecksum(version, asset)
            val actual = sha256(zip)
            check(expected.equals(actual, ignoreCase = true)) {
                "校验失败：$asset 的 sha256 不匹配（期望 $expected，实际 $actual）——已中止，未改动任何安装"
            }
            val staged = tmpDir.resolve("payload")
            Files.createDirectories(staged)
            unzipStripTop(zip, staged)

            val target = versionsDir.resolve(version)
            if (target.exists()) deleteRecursively(target)
            move(staged, target)
            return target
        } finally {
            deleteRecursively(tmpDir)
        }
    }

    private fun fetchChecksum(version: String, asset: String): String {
        val tmp = Files.createTempFile("ozml-checksums", ".txt")
        try {
            downloadTo("$releaseBaseUrl/download/v$version/checksums.txt", tmp)
            val map = parseChecksums(Files.readString(tmp))
            return map[asset] ?: error("checksums.txt 中缺少 $asset 的校验值")
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun getText(url: String, accept: String? = null): String {
        val builder = HttpRequest.newBuilder(URI(url)).applyAuth().timeout(Duration.ofSeconds(30)).GET()
        accept?.let { builder.header("Accept", it) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("请求失败：$url → HTTP ${response.statusCode()}（可用 --version 指定版本，或检查网络）")
        }
        return response.body()
    }

    private fun downloadTo(url: String, target: Path) {
        val request = HttpRequest.newBuilder(URI(url)).applyAuth().timeout(Duration.ofMinutes(5)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            error("下载失败：$url → HTTP ${response.statusCode()}")
        }
        response.body().use { input ->
            Files.newOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    private fun HttpRequest.Builder.applyAuth(): HttpRequest.Builder =
        if (token.isNullOrBlank()) this else header("Authorization", "Bearer $token")

    internal companion object {
        internal const val DEFAULT_RELEASE_BASE = "https://github.com/workerOn9/ozmoz-lineage/releases"
        internal const val DEFAULT_API_BASE = "https://api.github.com/repos/workerOn9/ozmoz-lineage"

        private val JSON = Json { ignoreUnknownKeys = true }

        private fun defaultToken(): String? =
            listOf("OZML_GITHUB_TOKEN", "GITHUB_TOKEN", "GH_TOKEN")
                .firstNotNullOfOrNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }

        private fun envOrNull(name: String): String? =
            System.getenv(name)?.takeIf { it.isNotBlank() }

        private fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()

        /** `os.name` / `os.arch` → 发行产物平台标签（与 cli/build.gradle.kts 同规则）。 */
        internal fun platformId(osName: String, osArch: String): String {
            val os = osName.lowercase().let {
                when {
                    it.contains("mac") || it.contains("darwin") -> "macos"
                    it.contains("win") -> "windows"
                    else -> "linux"
                }
            }
            val arch = osArch.lowercase().let {
                if (it.contains("aarch64") || it.contains("arm64")) "arm64" else "x64"
            }
            return "$os-$arch"
        }

        /** 发行资产名：`ozml-<版本>-<平台>.zip`（upgrade 用 zip，JDK 原生解压）。 */
        internal fun assetName(version: String, platform: String): String = "ozml-$version-$platform.zip"

        /** 去掉 GitHub tag 的前导 `v`：`v0.2.0` → `0.2.0`。 */
        internal fun normalizeVersion(tag: String): String = tag.trim().removePrefix("v")

        /** 解析 `checksums.txt`：兼容 `<sha256>␣␣<文件名>` 与 `<sha256> *<文件名>`。 */
        internal fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val name = parts[1].trim().removePrefix("*")
                name to parts[0].lowercase()
            }
            .toMap()

        internal fun sha256(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** 解压 zip 并剥掉顶层 `ozml/` 目录；防 zip-slip。 */
        internal fun unzipStripTop(zip: Path, target: Path) {
            val normalizedTarget = target.toAbsolutePath().normalize()
            ZipInputStream(Files.newInputStream(zip)).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.startsWith("ozml/")) {
                        val relative = name.removePrefix("ozml/")
                        val out = normalizedTarget.resolve(relative).normalize()
                        check(out.startsWith(normalizedTarget)) { "归档条目逃逸目标目录：$name" }
                        if (entry.isDirectory) {
                            Files.createDirectories(out)
                        } else {
                            Files.createDirectories(out.parent)
                            Files.newOutputStream(out).use { output -> input.copyTo(output) }
                            // `bin/ozml` 与 `runtime/bin/java` 等都要可执行——判据同打包侧
                            // （任何位于 `bin/` 目录下的文件），否则自带 java 跑不起来。
                            if (relative.startsWith("bin/") || relative.contains("/bin/")) {
                                RootLayout.setExecutable(out)
                            }
                        }
                    }
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
        }

        private fun move(from: Path, to: Path) {
            runCatching {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(from, to)
            }
        }

        private fun deleteRecursively(path: Path) {
            if (!path.exists()) return
            runCatching {
                Files.walk(path).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }
}

@Serializable
private data class GithubRelease(@SerialName("tag_name") val tagName: String)
