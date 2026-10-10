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

    // VALIDATE_SCHEMA 的装配测试需要真实的 SchemaProvider（SAM 转换喂入）——
    // 只进 testImplementation，主依赖方向不新增任何模块边。
    testImplementation(project(":schema"))
    testImplementation(libs.h2)
}
