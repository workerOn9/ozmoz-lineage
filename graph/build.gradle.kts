plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":ir"))
    implementation(libs.jgrapht.core)
    implementation(libs.serialization.json)
    implementation(libs.sqlite.jdbc)
}
