plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":ir"))
    implementation(project(":engine-api"))
    implementation(project(":engine-jsqlparser"))
    implementation(libs.clikt)
    implementation(libs.serialization.json)

    // 端到端测试：真实引擎产出语义模型 → lineage 建作用域树。仅测试作用域使用。
    testImplementation(project(":lineage"))
}

application {
    mainClass.set("io.github.workeron9.ozmoz.lineage.cli.MainKt")
    applicationName = "ozml"
}
