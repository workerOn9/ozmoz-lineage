package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Files
import java.nio.file.Path

/**
 * `ozml uninstall` —— 卸载（ADR-0021 的第三条腿：安装 / 升级 / 卸载）。
 *
 * 只删**受管安装**（`install.sh` / `ozml upgrade` 建立的那套布局）：
 * 启动器 `$BIN_DIR/ozml`（Windows 还有 `ozml.cmd`）与安装根 `$OZML_ROOT`
 * （`current` / `bin-dir` / `versions/<版本>`）。**不动** `$BIN_DIR` 目录本身（可能还放着
 * 别的工具），也不修改任何 shell 配置里的 `PATH`。
 *
 * 人性化之处：默认**先展示将删什么再确认**（回车即取消）；`--yes` 免确认给脚本用；
 * `--dry-run` 只预览；`--root` 打错或指向非受管目录时**拒绝并给原因**，不误删。
 */
public class UninstallCommand internal constructor(
    private val confirmReader: (String) -> Boolean,
) : CliktCommand(name = "uninstall") {

    public constructor() : this(::readConfirmation)

    private val assumeYes: Boolean by option("-y", "--yes", help = "不询问，直接卸载").flag()

    private val dryRun: Boolean by option("--dry-run", help = "只展示将删除什么，不做任何改动").flag()

    private val rootOption: String? by option(
        "--root",
        help = "安装根目录（含 versions/ 与 current），缺省按运行位置推断或取 \$OZML_ROOT",
    )

    override fun help(context: Context): String =
        "卸载 ozml：删除启动器与安装目录（默认先展示再确认；--yes 免确认，--dry-run 只预览）。"

    override fun run() {
        try {
            performUninstall()
        } catch (e: CliktError) {
            throw e
        } catch (e: Exception) {
            echo("ozml uninstall 失败：${e.message ?: e.toString()}", err = true)
            throw ProgramResult(1)
        }
    }

    private fun performUninstall() {
        val root = RootLayout.resolveRoot(rootOption).toAbsolutePath().normalize()

        when (val state = classify(root)) {
            State.NotInstalled -> {
                echo("未找到 ozml 安装：$root（可能已卸载）")
                return
            }
            State.NotManaged -> {
                echo("拒绝卸载：$root 存在，但不是受管的 ozml 安装目录（缺 versions/ 与 current）", err = true)
                echo("若确实要删，请核对 --root 是否指对了目录。", err = true)
                throw ProgramResult(1)
            }
            State.Managed -> Unit
        }

        val versions = RootLayout.installedVersions(root)
        val current = RootLayout.currentVersion(root)
        val binDir = RootLayout.binDir(root)
        val size = RootLayout.directorySize(root)

        echo("将卸载 ozml：")
        echo("  安装根  : $root")
        echo("  当前版本: ${current ?: "（未知）"}")
        echo("  已装版本: ${versions.ifEmpty { listOf("（无）") }.joinToString(", ")}")
        echo("  启动器  : ${binDir.resolve("ozml")}")
        echo("  占空间  : ${RootLayout.humanSize(size)}")

        if (dryRun) {
            echo("（--dry-run：未做任何改动）")
            return
        }

        if (!assumeYes && !confirmReader("确认删除以上内容？")) {
            echo("已取消，未做任何改动。")
            return
        }

        val removedShims = RootLayout.removeShim(root)
        RootLayout.deleteRecursively(root)

        echo("已卸载。")
        for (shim in removedShims) echo("  删除启动器：$shim")
        echo("  删除安装目录：$root")
        echo("  $binDir 目录本身保留（可能还放着别的工具）；shell 配置里的 PATH 未改动。")
    }

    private enum class State { Managed, NotInstalled, NotManaged }

    /** 判定根目录属于哪种情况——`NotManaged` 会拒绝，防止 `--root` 打错误删。 */
    private fun classify(root: Path): State {
        val hasVersions = Files.isDirectory(root.resolve(RootLayout.VERSIONS_DIR))
        val hasCurrent = root.resolve(RootLayout.CURRENT_FILE).toFile().exists()
        val hasBinDirFile = root.resolve(RootLayout.BIN_DIR_FILE).toFile().exists()
        if (hasVersions || hasCurrent || hasBinDirFile) {
            return if (root.parent == null) State.NotManaged else State.Managed
        }
        val exists = Files.exists(root)
        val empty = exists && Files.list(root).use { !it.findAny().isPresent }
        return if (!exists || empty) State.NotInstalled else State.NotManaged
    }

    private companion object {
        /** 从标准输入读一行确认；默认否（直接回车即取消）。 */
        private fun readConfirmation(question: String): Boolean {
            System.err.print("$question [y/N] ")
            System.err.flush()
            val answer = readlnOrNull()?.trim()?.lowercase()
            return answer == "y" || answer == "yes"
        }
    }
}
