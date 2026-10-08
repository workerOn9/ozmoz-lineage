rootProject.name = "ozmoz-lineage"

// 仓库只声明官方源。国内镜像放在本机 ~/.gradle/init.gradle，不进版本库。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // PREFER_PROJECT：本机 init 脚本可以在项目级加上镜像；
    // CI 没有 init 脚本、模块也不声明仓库时，回落到这里的 Maven Central。
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

include(
    "ir",
    "engine-api",
    "engine-jsqlparser",
    "engine-calcite",
    "engine-antlr",
    "engine-jooq",
    "lineage",
    "graph",
    "schema",
    "format",
    "cli",
    "server",
    "bench",
    "conformance",
)
