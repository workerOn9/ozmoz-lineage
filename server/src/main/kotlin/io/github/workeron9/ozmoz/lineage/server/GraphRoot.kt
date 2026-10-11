package io.github.workeron9.ozmoz.lineage.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * 图库白名单根（ADR-0015）——**请求体里的 `graph` 只能是这个目录下的 `.db` 文件**。
 *
 * 为什么需要它：`/api/impact` / `/api/path` 支持「读已落库的全库血缘图」（
 * `ozml lineage --graph` 的产物）。若直接按请求给的路径开库，HTTP 层就变成了
 * **任意文件打开面**；而本服务在 Vercel 上是 `--host 0.0.0.0` + **零鉴权**的公开
 * demo，等于把服务端文件系统探测面暴露到公网（ADR-0015 的背景节）。
 *
 * 因此：根目录由**部署者**用 `ozml serve --graph-root <dir>` 显式开启；
 * **不给该参数 = 图库能力整体关闭**（本类实例为 null）。前端只传**库名**
 * （如 `reports.db`），看不到也不需要知道目录。
 *
 * [resolve] 的防护与 `staticWeb` 的静态托管同款：
 * 规范化后必须落在根目录树内，且拒绝对路径 / 上级逃逸 / 非 `.db` 后缀。
 */
public class GraphRoot private constructor(private val root: Path) {

    /** 列出根目录下的 `.db` 文件（按文件名排序，确定性）。目录不存在 → 空列表。 */
    fun list(): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.name.endsWith(SUFFIX) }
                .sorted()
                .toList()
        }
    }

    /**
     * 把请求体的**库名**解析成根目录内的绝对路径。
     *
     * 返回 null 表示**请求本身非法**（HTTP 层 400 `invalid_graph_ref`）：
     * 绝对路径 / 含目录分隔符逃逸（`..`、子路径）/ 非 `.db` 后缀 / 解析后不在根内。
     * 「名字合法但文件不存在」不在此拒绝——那是 422 `graph_not_found`（由调用方判）。
     */
    fun resolve(name: String): Path? {
        // 只收「纯文件名」：不含路径分隔符（`/` 与平台分隔符），且非绝对路径。
        if (name.isEmpty()) return null
        if (name.contains('/') || name.contains('\\')) return null
        if (!name.endsWith(SUFFIX)) return null
        if (name == "." || name == "..") return null
        val candidate = root.resolve(name).toAbsolutePath().normalize()
        // 规范化后再验一次是否仍在根内（防 `..` 与符号链接之外的路径形态绕过）。
        if (!candidate.startsWith(root)) return null
        return candidate
    }

    public companion object {
        /** 只认 SQLite 库后缀（SqliteLineageStore 的产物；`*.db` 亦在 .gitignore）。 */
        public const val SUFFIX: String = ".db"

        /**
         * 由 CLI 参数构造白名单根；未给返回 null（**能力关闭**）。
         * 根路径在此**规范化一次**，后续 [resolve] 的 `startsWith` 才可靠。
         */
        @JvmStatic
        fun ofOrNull(dir: Path?): GraphRoot? =
            dir?.let { GraphRoot(it.toAbsolutePath().normalize()) }
    }
}
