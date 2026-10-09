plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":ir"))
    implementation(libs.jgrapht.core)
}
