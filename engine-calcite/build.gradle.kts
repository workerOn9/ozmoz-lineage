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
    // Calcite 的 babel 方言扩展解析器（同仓同版本，Apache-2.0）：
    // org.apache.calcite.sql.parser.babel.SqlBabelParserImpl.FACTORY——吃下默认语法
    // 之外的方言写法（PostgreSQL 的 `::` 强转等）。**与上面是回退关系而非替代**：
    // babel 不认 CREATE VIEW，DDL 解析器不认 `::`（本机 probe 实测）。
    implementation(libs.calcite.babel)

    // VALIDATE_SCHEMA 的装配测试需要真实的 SchemaProvider（SAM 转换喂入）——
    // 只进 testImplementation，主依赖方向不新增任何模块边。
    testImplementation(project(":schema"))
    testImplementation(libs.h2)
}
