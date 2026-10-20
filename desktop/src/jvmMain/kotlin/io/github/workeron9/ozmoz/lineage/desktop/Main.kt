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
    // 无标题栏（macOS）：内容延伸到标题栏区域、标题文字隐藏，红绿灯保留。
    // 其它平台仍是原生标题栏（Windows / Linux 的等价做法要各自的窗口管理器，D4 再分平台处理）。
    val macOS = System.getProperty("os.name").lowercase().contains("mac")
    val initial = args.firstOrNull()?.let { readFile(it) }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
            title = "Lineage",
        ) {
            LaunchedEffect(Unit) {
                // 工作台是四目的地 + 三栏布局；再窄侧栏与检查器就会互相挤压。给一个下限，
                // 比让用户把窗口拉到手机比例再看到坏布局好。
                window.minimumSize = Dimension(960, 640)
                if (macOS) {
                    // JDK 的 macOS 实现从 **JRootPane 的 client property** 读这几项
                    // （sun.lwawt.macosx.CPlatformWindow 的 Property 表），不是 system property。
                    // 写晚了也没关系：ClientPropertyApplicator 监听 rootPane 的属性变更并实时生效。
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    window.rootPane.putClientProperty("apple.awt.draggableWindowBackground", true)
                }
            }
            App(initial = initial, hiddenTitleBar = macOS)
        }
    }
}
