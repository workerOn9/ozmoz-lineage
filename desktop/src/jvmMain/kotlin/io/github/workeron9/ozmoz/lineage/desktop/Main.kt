package io.github.workeron9.ozmoz.lineage.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.workeron9.ozmoz.lineage.desktop.ui.App
import java.awt.Dimension

/**
 * 桌面入口。可以带一个 SQL 文件路径启动（`./gradlew :desktop:run --args=/path/q.sql`），
 * 不带就是空窗口等「打开 SQL 文件」。
 */
public fun main(args: Array<String>) {
    val initial = args.firstOrNull()?.let { readFile(it) }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
            title = "ozml lineage",
        ) {
            // 工作台是四目的地 + 三栏布局；再窄侧栏与检查器就会互相挤压。给一个下限，
            // 比让用户把窗口拉到手机比例再看到坏布局好。
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(960, 640)
            }
            App(initial = initial)
        }
    }
}
