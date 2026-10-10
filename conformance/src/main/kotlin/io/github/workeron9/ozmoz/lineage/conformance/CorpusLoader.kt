package io.github.workeron9.ozmoz.lineage.conformance

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo

/**
 * 语料加载器：一个目录（递归）下的全部 `*.json` → [CorpusEntry] 列表。
 *
 * Never-wrong：解析失败 / 字段缺失 / id 重复**不静默丢弃**，全部计入
 * [LoadedCorpus.problems]（带文件定位），由调用方决定 WARN 还是整体失败。
 * 文件按相对路径排序，保证 [LoadedCorpus.entries] 顺序确定 → 矩阵 JSON 可复现。
 */
public object CorpusLoader {

    /**
     * 加载一个语料目录。目录不存在 / 为空目录 → 返回空 [LoadedCorpus]（带 problem），
     * 不抛异常——CLI 层据此走统一的「语料损坏」报错路径。
     */
    public fun load(root: Path): LoadedCorpus {
        val problems = ArrayList<String>()
        if (!Files.isDirectory(root)) {
            return LoadedCorpus(emptyList(), listOf("语料目录不存在或不是目录：$root"))
        }
        val files = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .sorted()
                .toList()
        }
        if (files.isEmpty()) return LoadedCorpus(emptyList(), listOf("语料目录下没有 *.json 文件：$root"))

        val entries = ArrayList<CorpusEntry>()
        val seenIds = HashMap<String, String>()
        for (file in files) {
            val relative = file.relativeTo(root).toString().replace('\\', '/')
            val text = try {
                Files.readString(file)
            } catch (e: Exception) {
                problems += "$relative: 读文件失败（${e.message}）"
                continue
            }
            val case = try {
                jsonBuilder().decodeFromString<CorpusCase>(text)
            } catch (e: Exception) {
                problems += "$relative: JSON 结构不合法（${firstLine(e.message)}）"
                continue
            }
            val duplicated = seenIds[case.id]
            if (duplicated != null) {
                problems += "$relative: id 重复「${case.id}」（与 $duplicated 冲突；条目仍保留，矩阵里 id 不再用作唯一键溯源）"
                continue
            }
            seenIds[case.id] = relative
            entries += CorpusEntry(case, relative)
        }
        return LoadedCorpus(entries, problems)
    }

    private fun firstLine(message: String?): String = message?.lineSequence()?.firstOrNull() ?: "未知错误"

    /**
     * 矩阵 JSON 的输出与语料加载共用一个编码器（语料 JSON 是矩阵 JSON 消费者的镜像）。
     * `prettyPrint` 保证 `--out` 落盘文件 diff 友好。
     */
    @JvmStatic
    public fun jsonBuilder(): Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
}
