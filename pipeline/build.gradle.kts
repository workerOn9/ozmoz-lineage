plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 编排层（ADR-0022 决定 4）：cli / server / desktop 三方共用的组合根。
// 引擎适配器只在这里被点名——UI 进程（desktop）经本模块进程内直调引擎，
// 不反向依赖任何入口层（cli 的 Clikt / server 的 Ktor 不进本模块）。
dependencies {
    api(project(":engine-api"))
    api(project(":ir"))
    api(project(":schema"))
    implementation(project(":engine-jsqlparser"))
    implementation(project(":engine-calcite"))
    implementation(project(":engine-jooq"))
    implementation(project(":lineage"))
}
