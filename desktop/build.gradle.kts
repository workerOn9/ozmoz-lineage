import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation("org.jetbrains.compose.desktop:desktop-jvm-$composeDesktopTarget:$composeVersion")
            // 进程内直调编排层（:pipeline 经 api 传递 ir / engine-api / schema）。
            // 有意不依赖 :cli——UI 进程不进 Clikt（ADR-0022 决定 4）。
            implementation(project(":pipeline"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.workeron9.ozmoz.lineage.desktop.MainKt"
    }
}

// `:desktop:run` 是 JavaExec：缺省拿跑 Gradle 的那个 JVM（本机 21），而模块按工具链 25
// 编译 ⇒ UnsupportedClassVersionError（class 69 > 65）。KMP 的 `jvmToolchain` 不替 JavaExec
// 选 JVM（Kotlin JVM 插件会），所以这里显式把 launcher 指到工具链。
tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkVersion)) },
    )
}
