plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":ir"))
    implementation(project(":engine-api"))
    implementation(project(":engine-jsqlparser"))
    implementation(project(":lineage"))
    implementation(project(":schema"))
    implementation(project(":graph"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.serialization.json)
    testImplementation(libs.ktor.server.test.host)
}

// /api/health 的版本号来自 jar 清单；从源码类路径跑（测试 / 开发）时回落 "dev"。
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version.toString())
    }
}
