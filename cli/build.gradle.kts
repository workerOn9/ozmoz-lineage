plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":ir"))
    implementation(project(":engine-api"))
    implementation(project(":engine-jsqlparser"))
    implementation(project(":engine-calcite"))
    implementation(project(":engine-jooq"))
    implementation(project(":lineage"))
    implementation(project(":schema"))
    implementation(project(":format"))
    implementation(project(":graph"))
    implementation(project(":server"))
    implementation(project(":conformance"))
    implementation(libs.clikt)
    implementation(libs.serialization.json)
}

application {
    mainClass.set("io.github.workeron9.ozmoz.lineage.cli.MainKt")
    applicationName = "ozml"
    // JDK 24+（JEP 472）对 JNA 经 `System.load` 加载原生库发「受限方法」告警：Clikt
    // 经 Mordant 用 JNA 探测终端，**任一子命令启动**（含 `--help`）都会触发，于是
    // 每个进程往 stderr 吐 4 行 WARNING（容器 / 终端可见）。显式放行——与项目一贯的
    // 「清噪声而非压制」（slf4j-nop 静默 Netty 同理）一致，而非 `-Xlint` 之类掩盖。
    // 该选项自 JDK 17 起被识别，JDK 21 上为无害 no-op。
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
