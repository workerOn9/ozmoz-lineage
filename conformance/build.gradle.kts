plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":engine-api"))
    api(project(":ir"))
    api(project(":lineage"))
    implementation(libs.serialization.json)
}
