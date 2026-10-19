package io.github.workeron9.ozmoz.lineage.desktop

import java.nio.file.Path

/**
 * 本地数据目录：缺省 `~/.ozml-lineage`（设置、驱动目录）。
 * 测试用 `-Dozml.storage.root=` 指到临时目录，避免写用户的真实目录。
 */
internal object Storage {
    fun root(): Path = System.getProperty("ozml.storage.root")
        ?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".ozml-lineage")
}
