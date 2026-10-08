plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":ir"))
    api(project(":engine-api"))
    implementation(libs.serialization.json)
}
