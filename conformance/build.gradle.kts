plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":engine-api"))
    api(project(":ir"))
    api(project(":lineage"))
    // 质量报表要按 case 的 DDL 建 schema（物理表 `*` 展开、裸列消歧）；schema 是 SPI + 实现，
    // 不引入任何引擎适配器（矩阵 / 报表仍只依赖 engine-api 的 SqlEngine SPI）。
    implementation(project(":schema"))
    implementation(libs.serialization.json)

    // 测试期：矩阵测试要真实引擎（主代码只依赖 engine-api SPI，干净）。
    testImplementation(project(":engine-calcite"))
    testImplementation(project(":engine-jooq"))
}
