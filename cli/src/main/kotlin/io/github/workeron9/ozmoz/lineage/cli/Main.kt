package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption

/**
 * `ozml` = **ozmoz** + **lineage**。
 *
 * 子命令规划见计划 §5：`parse` / `lineage` / `convert` / `impact` / `matrix` / `serve` / `mcp`。
 * M0 落地 [ParseCommand]；M1 落地 [LineageCommand]（列级血缘）；M2 收尾补 [ImpactCommand]；
 * `serve`（Ktor `/api` 骨架）随后落地 [ServeCommand]；M3 起步落地 [MatrixCommand]（兼容性矩阵）；
 * M3 落地 [ConvertCommand]（Calcite 方言转换主力，ADR-0004）；ADR-0013 落地
 * [ValidateCommand]（schema 语义校验）；ADR-0014 落地 [FormatCommand]（SQL 排版）；
 * MCP 落地 [McpCommand]（stdio 工具面，镜像 `/api` 能力）；M5 文档站落地
 * [EnginesCommand]（引擎能力表，文档站「工具百科」数据源）；M1 收尾落地
 * [QualityCommand]（血缘质量报表，计划 §8）；ADR-0021 落地
 * [UpgradeCommand]（自更新，唯一联网子命令）。
 *
 * 顶层带 `--version`（版本身份来自 [BuildInfo]，与产物 / `upgrade` 同一来源）。
 */
public class OzmlCommand : CliktCommand(name = "ozml") {
    init {
        versionOption(BuildInfo.version)
    }

    override fun run() {
        // 顶层只承载子命令；`--help` 由 Clikt 输出用法。
    }
}

public fun main(args: Array<String>) {
    OzmlCommand()
        .subcommands(
            ParseCommand(),
            LineageCommand(),
            ImpactCommand(),
            ServeCommand(),
            MatrixCommand(),
            ConvertCommand(),
            ValidateCommand(),
            FormatCommand(),
            EnginesCommand(),
            QualityCommand(),
            McpCommand(),
            UpgradeCommand(),
        )
        .main(args)
}
