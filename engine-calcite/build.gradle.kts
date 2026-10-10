plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":engine-api"))
    api(project(":ir"))
    implementation(libs.calcite.core)
}
