plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

val jdkVersion = libs.versions.jdk.get().toInt()

subprojects {
    group = "io.github.workeron9"
    version = "0.1.0-SNAPSHOT"

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(jdkVersion)
        }
        extensions.configure<BasePluginExtension>("base") {
            archivesName.set("ozmoz-lineage-${project.name}")
        }

        // 测试约定：所有 JVM 模块共用 kotlin-test + JUnit Platform，模块脚本不必重复声明。
        dependencies {
            add("testImplementation", libs.kotlin.test)
        }
        tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
            useJUnitPlatform()
            // 工具链守门测试（ir 模块 ToolchainTest）从构建注入期望的 JDK 大版本：
            // 断言跟 version catalog 走，catalog 变了测试同步变——防的是「工具链
            // 与 catalog 漂移」（比如 CI 镜像装错版本），不是钉死某个数字。
            systemProperty("ozmoz.toolchain.jdk", jdkVersion)
            // JDK 24+（JEP 472）对原生库加载发「受限方法」告警：测试里 sqlite-jdbc
            // 与 JNA（Mordant 终端探测）都会触发，往 stderr 吐 4 行 WARNING。显式放行，
            // 让构建 / CI 日志真正零告警。与 CLI 入口同一处置（见 cli/build.gradle.kts）。
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }
}
