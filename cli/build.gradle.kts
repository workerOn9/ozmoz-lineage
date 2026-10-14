import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.application.CreateStartScripts
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":ir"))
    implementation(project(":engine-api"))
    implementation(project(":engine-jsqlparser"))
    implementation(project(":engine-calcite"))
    implementation(project(":engine-jooq"))
    implementation(project(":lineage"))
    implementation(project(":schema"))
    implementation(project(":format"))
    implementation(project(":graph"))
    implementation(project(":server"))
    implementation(project(":conformance"))
    implementation(libs.clikt)
    implementation(libs.serialization.json)
}

application {
    mainClass.set("io.github.workeron9.ozmoz.lineage.cli.MainKt")
    applicationName = "ozml"
    // JDK 24+（JEP 472）对 JNA 经 `System.load` 加载原生库发「受限方法」告警：Clikt
    // 经 Mordant 用 JNA 探测终端，**任一子命令启动**（含 `--help`）都会触发，于是
    // 每个进程往 stderr 吐 4 行 WARNING（容器 / 终端可见）。显式放行——与项目一贯的
    // 「清噪声而非压制」（slf4j-nop 静默 Netty 同理）一致，而非 `-Xlint` 之类掩盖。
    // 该选项自 JDK 17 起被识别，JDK 21 上为无害 no-op。
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// ─────────────────────────────────────────────────────────────────────────────
// 版本身份：构建期把 `project.version` 落成资源，供 `ozml --version` 与
// `ozml upgrade` 读取。唯一来源是根构建的 `OZML_VERSION` / `-PozmlVersion`。
// ─────────────────────────────────────────────────────────────────────────────
val generatedVersionDir = layout.buildDirectory.dir("generated/ozml-version")
val generateVersionResource = tasks.register("generateVersionResource") {
    val outputDir = generatedVersionDir
    val versionValue = project.version.toString()
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().file("ozml-version.properties").asFile
        target.parentFile.mkdirs()
        target.writeText("version=$versionValue\n")
    }
}
sourceSets.named("main") { resources.srcDir(generateVersionResource) }

// ─────────────────────────────────────────────────────────────────────────────
// 自包含分发（B 方案）：`jlink` 裁剪运行时 + 组装 + 归档。
//
// 动机：`installDist` 只是「脚本 + jar」，运行时仍要求用户装 JDK ≥ 25。jlink 按需
// 裁一个最小运行时随包携带，用户解压即用（`ozml/{bin,lib,runtime}`）。
// ─────────────────────────────────────────────────────────────────────────────

// 模块清单与容器侧**同源**（Dockerfile / Dockerfile.vercel 的 jlink stage）——那份是
// **实测圈定**的：`jdeps --print-module-deps` 被 netty 的 OSGi manifest（Bundle-
// SymbolicName / Import-Package）与 guava 包名前缀炸掉，推不出来；其余项覆盖 CLI 全部
// 子命令 + SQLite 图库 + serve 托管（容器内逐项跑通，见 ADR/开发记录）。新增依赖
// 若用到别的 JDK 模块，运行时会 NoClassDefFoundError——按报错补这里与两个 Dockerfile。
// `java.net.http` + `jdk.crypto.ec` 是 `ozml upgrade` 需要的（HttpClient 与 HTTPS EC
// 套件）；容器内 ozml 不自我升级，但两份清单**有意保持一致**，不搞「发行版/容器各一份」。
val jlinkModules = listOf(
    "java.base", "java.logging", "java.management",
    "java.naming", "java.sql", "java.xml", "jdk.unsupported",
    "java.net.http", "jdk.crypto.ec",
)

val jlinkRuntimeDir = layout.buildDirectory.dir("jlink/runtime")

// 目标平台标签：本机构建按宿主推断；CI 交叉命名可 `-PozmlPlatform=linux-arm64` 覆盖。
val hostOs: String = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("mac") || it.contains("darwin") -> "macos"
        it.contains("win") -> "windows"
        else -> "linux"
    }
}
val hostArch: String = System.getProperty("os.arch").lowercase().let {
    if (it.contains("aarch64") || it.contains("arm64")) "arm64" else "x64"
}
val releasePlatform: String = providers.gradleProperty("ozmlPlatform").getOrElse("$hostOs-$hostArch")
val releaseBaseName = "ozml-${project.version}-$releasePlatform"
val isWindowsHost = hostOs == "windows"

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    group = "distribution"
    description = "用 jlink 裁剪出自包含运行时（build/jlink/runtime）"
    val outDir = jlinkRuntimeDir.get().asFile
    inputs.property("modules", jlinkModules.joinToString(","))
    outputs.dir(outDir)
    // jlink 的 `legal/` 等文件是只读的（0444），会让后续 Sync 覆盖失败——先改可写再删。
    doFirst {
        if (outDir.exists()) {
            outDir.walkBottomUp().forEach { it.setWritable(true, false) }
            outDir.deleteRecursively()
        }
    }
    // 必须用**工具链** JDK 的 jlink：Gradle 守护进程可能跑在别的 JDK 上。
    // 配置期解析（Exec 不认 Provider 形式的 executable）；Windows 上是 `jlink.exe`。
    val jlinkExecutable = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
    }.get().metadata.installationPath.file(if (isWindowsHost) "bin/jlink.exe" else "bin/jlink").asFile.absolutePath
    executable(jlinkExecutable)
    args(
        "--add-modules", jlinkModules.joinToString(","),
        "--strip-debug", "--no-man-pages", "--no-header-files", "--compress=zip-6",
        "--output", outDir.absolutePath,
    )
    // 产出改可写：下游 Sync 要能覆盖（jlink 的 legal 文件默认只读）。
    doLast { outDir.walkTopDown().forEach { it.setWritable(true, false) } }
}

val ozmlDistDir = layout.buildDirectory.dir("ozmlDist")
val ozmlDist = tasks.register<Sync>("ozmlDist") {
    group = "distribution"
    description = "组装自包含发行目录 build/ozmlDist/ozml（bin + lib + runtime）"
    dependsOn(tasks.named("installDist"), jlinkRuntime)
    into(ozmlDistDir.map { it.dir("ozml") })
    from(layout.buildDirectory.dir("install/ozml/bin")) { into("bin") }
    from(layout.buildDirectory.dir("install/ozml/lib")) { into("lib") }
    from(jlinkRuntimeDir) { into("runtime") }
    // 上一次残留的 runtime 可能含只读文件，先改可写再让 Sync 收敛。
    doFirst {
        val dest = ozmlDistDir.get().asFile
        if (dest.exists()) dest.walkBottomUp().forEach { it.setWritable(true, false) }
    }
}

val ozmlDistTar = tasks.register<Tar>("ozmlDistTar") {
    group = "distribution"
    description = "打包 tar.gz 发行产物（unix；供 install.sh / curl 使用，保留可执行位）"
    dependsOn(ozmlDist)
    archiveFileName.set("$releaseBaseName.tar.gz")
    compression = Compression.GZIP
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(ozmlDistDir) { include("ozml/**") }
    // Gradle 归档任务为可复现性默认把权限归一成 0644——`bin/`（含 runtime/bin）下的
    // 可执行文件必须保留 0755，否则解压后 `ozml` / `java` 不可执行。Windows 无 POSIX
    // 权限概念，跳过（zip 由 `ozml upgrade` 解压时显式补可执行位）。
    if (!isWindowsHost) useFileSystemPermissions()
}

val ozmlDistZip = tasks.register<Zip>("ozmlDistZip") {
    group = "distribution"
    description = "打包 zip 发行产物（跨平台；ozml upgrade 用它，JDK 原生解压）"
    dependsOn(ozmlDist)
    archiveFileName.set("$releaseBaseName.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(ozmlDistDir) { include("ozml/**") }
    if (!isWindowsHost) useFileSystemPermissions()
}

tasks.register("ozmlDistArchives") {
    group = "distribution"
    description = "产出两种分发归档（tar.gz + zip）"
    dependsOn(ozmlDistTar, ozmlDistZip)
}

// 应用插件默认的 `distTar` / `distZip` 产出**不带运行时**的归档，与「自带 runtime」的
// `ozmlDistArchives` 同名易混——明确关掉，避免误发一个「看着能装、其实要系统 JDK」的
// 产物。`build` 因此不再产归档；发行归档由 release.yml 显式跑 `ozmlDistArchives`。
tasks.named("distTar") { enabled = false }
tasks.named("distZip") { enabled = false }

// 让发行版里的启动脚本自包含：随包带了 runtime/ 就用它，否则回落系统 JDK。
// 注入是无害的——`installDist`（`just serve` / CI 文档数据）没有 runtime/ 目录，
// 走的仍是原逻辑。锚点取自 Gradle 生成的启动脚本模板（模板变则构建期直接失败）。
fun patchStartScript(file: File, anchor: String, snippet: List<String>) {
    val text = file.readText()
    require(text.contains(anchor)) {
        "启动脚本注入锚点未找到（Gradle 模板可能已变）：$anchor"
    }
    val newline = if (text.contains("\r\n")) "\r\n" else "\n"
    file.writeText(text.replaceFirst(anchor, anchor + newline + snippet.joinToString(newline)))
}

// Windows 的 cmd.exe 命令行上限约 8191 字符：Gradle 生成的 .bat 把 100+ jar 的完整
// classpath 塞进一行，展开后必然超限（实测 `The input line is too long.`）。改成 Java
// 通配 classpath `lib\*`——由 JVM 自己在运行时展开，不经过 cmd。**Unix 侧保持 Gradle
// 原样**（无此限制，且保留 Gradle 的显式顺序）。
fun patchWindowsClasspath(file: File) {
    val text = file.readText()
    val pattern = Regex("(?m)^set CLASSPATH=.*$")
    require(pattern.containsMatchIn(text)) {
        "Windows 启动脚本未找到 CLASSPATH 行（Gradle 模板可能已变）"
    }
    file.writeText(pattern.replace(text) { "set CLASSPATH=%APP_HOME%\\lib\\*" })
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        patchStartScript(
            unixScript,
            "APP_HOME=\$( cd -P \"\${APP_HOME:-./}..\" > /dev/null && printf '%s\\n' \"\$PWD\" ) || exit",
            listOf(
                "",
                "# 自包含发行版：随包带了 runtime/ 就优先用它（保证 JDK 版本匹配，避免本机",
                "# 默认 JDK 版本不符的 UnsupportedClassVersionError）；想强制用系统 JDK 时",
                "# 设 OZML_USE_SYSTEM_JAVA=1。",
                "if [ -z \"\${OZML_USE_SYSTEM_JAVA:-}\" ] && [ -x \"\$APP_HOME/runtime/bin/java\" ]; then",
                "    JAVA_HOME=\"\$APP_HOME/runtime\"",
                "fi",
            ),
        )
        patchStartScript(
            windowsScript,
            "for %%i in (\"%APP_HOME%\") do set APP_HOME=%%~fi",
            listOf(
                "@rem 自包含发行版：随包带了 runtime\\ 就优先用它；想强制用系统 JDK 时设 OZML_USE_SYSTEM_JAVA=1。",
                "if not defined OZML_USE_SYSTEM_JAVA if exist \"%APP_HOME%\\runtime\\bin\\java.exe\" set JAVA_HOME=%APP_HOME%\\runtime",
            ),
        )
        patchWindowsClasspath(windowsScript)
    }
}
