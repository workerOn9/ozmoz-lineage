package io.github.workeron9.ozmoz.lineage.cli

import java.util.Properties

/**
 * 构建期注入的版本身份。
 *
 * 值来自资源 `ozml-version.properties`（由 `generateVersionResource` 任务写入，
 * 见 `cli/build.gradle.kts`），唯一来源是根构建的 `OZML_VERSION` / `-PozmlVersion`
 * ——发布 CI 从 git tag 注入，本机缺省为 `0.1.0-SNAPSHOT`。
 *
 * 用途：`ozml --version` 的身份展示，以及 `ozml upgrade` 的「当前版本」比较基准。
 */
public object BuildInfo {

    /** 形如 `0.1.0` 或 `0.1.0-SNAPSHOT`；资源缺失时回落 `0.0.0-unknown`（不抛异常）。 */
    public val version: String by lazy {
        val stream = BuildInfo::class.java.getResourceAsStream("/ozml-version.properties")
            ?: return@lazy "0.0.0-unknown"
        stream.use { input ->
            Properties().apply { load(input) }.getProperty("version") ?: "0.0.0-unknown"
        }
    }
}
