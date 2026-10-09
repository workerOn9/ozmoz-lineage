plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":ir"))
    implementation(project(":engine-api"))
    implementation(project(":engine-jsqlparser"))
    implementation(project(":lineage"))
    implementation(project(":schema"))
    implementation(libs.clikt)
    implementation(libs.serialization.json)
}

application {
    mainClass.set("io.github.workeron9.ozmoz.lineage.cli.MainKt")
    applicationName = "ozml"
}
