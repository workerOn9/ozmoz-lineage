package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.main

/**
 * `ozml` = **ozmoz** + **lineage**。
 *
 * 子命令规划见计划 §5：`parse` / `lineage` / `convert` / `impact` / `matrix` / `serve` / `mcp`。
 * M0 落地 [ParseCommand]；M1 落地 [LineageCommand]（列级血缘）。
 */
public class OzmlCommand : CliktCommand(name = "ozml") {
    override fun run() {
        // 顶层只承载子命令；`--help` 由 Clikt 输出用法。
    }
}

public fun main(args: Array<String>) {
    OzmlCommand()
        .subcommands(ParseCommand(), LineageCommand())
        .main(args)
}
