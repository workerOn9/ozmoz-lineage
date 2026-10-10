plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":engine-api"))
    api(project(":ir"))
    api(project(":lineage"))
    implementation(libs.serialization.json)

    // 测试期：矩阵测试要真实引擎（主代码只依赖 engine-api SPI，干净）。
    testImplementation(project(":engine-calcite"))
    testImplementation(project(":engine-jooq"))
}
