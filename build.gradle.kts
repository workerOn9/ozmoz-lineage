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
        }
    }
}
