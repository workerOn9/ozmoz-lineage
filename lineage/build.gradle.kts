plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":ir"))
    api(project(":engine-api"))
    api(project(":schema"))
    implementation(libs.serialization.json)
}
