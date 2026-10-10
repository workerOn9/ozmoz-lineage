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
    implementation(project(":format"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.serialization.json)
    // SLF4J 无 provider 时 Netty 启动会打 5 行 NOP 告警；本地工具无日志需求，绑空实现静默
    // （2026-10-10 开发记录踩坑 8 的决策：接受「服务器零日志」换取安静，slf4j-nop MIT）。
    runtimeOnly(libs.slf4j.nop)
    testImplementation(libs.ktor.server.test.host)
}

// /api/health 的版本号来自 jar 清单；从源码类路径跑（测试 / 开发）时回落 "dev"。
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version.toString())
    }
}
