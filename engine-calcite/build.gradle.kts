plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":engine-api"))
    api(project(":ir"))
    implementation(libs.calcite.core)
    // DDL 语句（CREATE TABLE / VIEW 等）不在 calcite-core 的默认解析器语法里，
    // 扩展解析器在 calcite-server（同一仓同版本，Apache-2.0）：
    // org.apache.calcite.sql.parser.ddl.SqlDdlParserImpl.FACTORY。
    implementation(libs.calcite.server)
}
