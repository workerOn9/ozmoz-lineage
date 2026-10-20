import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// 桌面端（ADR-0022）只做 desktop(JVM) 单目标，但**按 KMP 分层留门**（决定 2）：
// UI 与呈现状态在 commonMain，引擎访问收在 expect 的 EngineGateway 后面；
// jvmMain 的 actual 进程内直调编排层（:pipeline），不走 HTTP（决定 3）。
// 将来平板 / 浏览器版只换一个 actual（走 HTTP），UI 不重写。
//
// 依赖坐标直接写，不用 compose.* 访问器：CMP 1.12 起访问器整体弃用，官方替代即坐标；
// 版本只来自 catalog（compose-plugin / compose-material3），material3 版本独立于插件版本。
val composeVersion = libs.versions.compose.plugin.get()
val composeMaterial3Version = libs.versions.compose.material3.get()
val jdkVersion = libs.versions.jdk.get().toInt()

// Compose 桌面的 androidx 坐标（`androidx.compose.runtime:runtime-desktop`、`lifecycle-*` …）
// **只发布在 Google Maven**，Maven Central 上没有（2026-10-16 实测 `runtime-desktop-1.12.1.pom`
// 在 repo1.maven.org 返回 404）。仓库 settings 只声明 Maven Central，故本模块自己声明两个官方源；
// PREFER_PROJECT 口径下其余模块不受影响。
repositories {
    mavenCentral()
    google()
}

// 本机宿主 → CMP desktop 产物名（三平台各建各的，见 ADR-0022 决定 6）。
val composeDesktopTarget = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val arm = arch.contains("aarch64") || arch.contains("arm64")
    when {
        os.contains("mac") -> if (arm) "macos-arm64" else "macos-x64"
        os.contains("win") -> if (arm) "windows-arm64" else "windows-x64"
        else -> if (arm) "linux-arm64" else "linux-x64"
    }
}

kotlin {
    explicitApi()
    jvmToolchain(jdkVersion)

    jvm {
        // kotlin-test 依任务配置自动选型（useJUnitPlatform → JUnit 5）。
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    // expect/actual object 在 Kotlin 2.4 仍为 Beta——ADR-0022 决定 2 的分层有意使用，
    // 显式开旗子保持构建日志干净。
    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
            implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
            implementation("org.jetbrains.compose.material3:material3:$composeMaterial3Version")
            implementation("org.jetbrains.compose.ui:ui:$composeVersion")
            // 本地设置的 JSON 持久化（版本跟 `ir` 一致，不另立一份）。
            implementation(libs.serialization.json)
            // D2 起 UI 自己起后台任务（设备发现 / JDBC 连接 / agent 进程），显式声明而不是吃 Compose 的传递依赖。
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation("org.jetbrains.compose.desktop:desktop-jvm-$composeDesktopTarget:$composeVersion")
            // 进程内直调编排层（:pipeline 经 api 传递 ir / engine-api / schema）。
            // 有意不依赖 :cli——UI 进程不进 Clikt（ADR-0022 决定 4）。
            implementation(project(":pipeline"))
            // D2 连接页的默认 JDBC 驱动（SQLite 已在 THIRD-PARTY 登记；其余驱动由用户放进驱动目录）。
            implementation(libs.sqlite.jdbc)
        }
    }
}

// sqlite-jdbc 走原生库；JDK 24+（JEP 472）默认对原生访问发告警。与其它模块同一处置，
// 让 run / test 日志真正零告警。
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // 离屏渲染 / 真 agent 联调是**手动检查**，不进 CI：显式 -PozmlManualChecks 才跑。
    systemProperty("ozml.manual.checks", providers.gradleProperty("ozmlManualChecks").getOrElse("false"))
    // 真 agent 联调（-PozmlLiveProvider=opencode）也走 Gradle 属性，测试 JVM 才看得到。
    systemProperty("ozml.live.provider", providers.gradleProperty("ozmlLiveProvider").getOrElse(""))
    systemProperty("ozml.live.prompt", providers.gradleProperty("ozmlLivePrompt").getOrElse("Reply with exactly: OK"))
}

compose.desktop {
    application {
        mainClass = "io.github.workeron9.ozmoz.lineage.desktop.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
        // `:desktop:run` 的 `executable` 由插件设成**跑 Gradle 的那个 JVM**
        // （`System.getProperty("java.home")`，本机 21），而模块按工具链 25 编译（class 69）⇒
        // 缺省是 UnsupportedClassVersionError（KMP 的 `jvmToolchain` 不替 JavaExec 选 JVM）。
        // 显式设 `javaLauncher` 也不行：Gradle 9 会拿它和插件设的 `executable` 对路径，
        // 不一致直接报 "Toolchain from `executable` property does not match ..."。
        // 所以钉插件自己的旋钮：run / jpackage / jlink 一律用工具链 JDK。
        javaHome = javaToolchains
            .launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkVersion)) }
            .get()
            .metadata.installationPath.asFile.absolutePath

        // 安装包：三平台各建各的（D4 的发布链）。图标由 design/logo/build.sh 生成后入库
        // （desktop/packaging/），打包不依赖本机有没有装 ImageMagick。
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Lineage"
            // jpackage 只认「数字 + 点」，且 macOS 要求**主版本不能是 0**（实测：
            // project version 0.1.0 会被拒）。安装包版本与应用版本（`ozml --version`）
            // 是两件事；D4 发版时统一。这里先给一个合法的 1.0.0。
            packageVersion = "1.0.0"
            // jlink 只自动收集依赖图里出现过的模块；JDBC 是运行期经 DriverManager 反射加载的，
            // 不声明就会在打包版里抛 NoClassDefFoundError: java/sql/SQLException（实测）。
            // java.naming 是 PostgreSQL 等驱动常见的间接依赖；java.net.http 是「下载驱动」用的客户端
            // （不打进 runtime，打包版点下载按钮会直接 NoClassDefFoundError）。
            modules("java.sql", "java.naming", "java.net.http")
            description = "SQL lineage workbench"
            vendor = "ozmoz-lineage"
            copyright = "© 2026 ozmoz-lineage contributors"

            macOS {
                iconFile.set(project.file("packaging/app-icon.icns"))
                bundleID = "io.github.workeron9.ozmoz.lineage"
            }
            windows {
                iconFile.set(project.file("packaging/app-icon.ico"))
                menuGroup = "ozml-lineage"
                // 让安装向导允许用户选择目录；后续 D4 再接签名。
                dirChooser = true
            }
            linux {
                iconFile.set(project.file("packaging/app-icon-512.png"))
                menuGroup = "ozml-lineage"
            }
        }
    }
}
