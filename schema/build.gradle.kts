plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":ir"))
    implementation(libs.jsqlparser)

    testImplementation(libs.h2)
}
