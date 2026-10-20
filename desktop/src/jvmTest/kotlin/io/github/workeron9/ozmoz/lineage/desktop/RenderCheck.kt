package io.github.workeron9.ozmoz.lineage.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.workeron9.ozmoz.lineage.desktop.model.Activity
import io.github.workeron9.ozmoz.lineage.desktop.model.ActivityKind
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionKind
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionProfile
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionStatus
import io.github.workeron9.ozmoz.lineage.desktop.model.DesktopStoreState
import io.github.workeron9.ozmoz.lineage.desktop.model.ThemeMode
import io.github.workeron9.ozmoz.lineage.desktop.ui.AppContent
import io.github.workeron9.ozmoz.lineage.desktop.ui.DesktopTheme
import io.github.workeron9.ozmoz.lineage.desktop.ui.Page
import io.github.workeron9.ozmoz.lineage.desktop.ui.WorkbenchState
import io.github.workeron9.ozmoz.lineage.desktop.ui.WorkspaceTab
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * 离屏渲染检查（手动工具，**不进 CI**）：把整窗渲染成 PNG，供人眼确认布局。
 * 本机没有可用的窗口截图手段（辅助访问权限），这是唯一稳定的目视通道。
 *
 * 跑法：`./gradlew :desktop:jvmTest --tests "*RenderCheck*" -PozmlManualChecks=true`
 * 产物：`$TMPDIR/ozml-desktop-*.png`。
 */
class RenderCheck {

    private val enabled = System.getProperty("ozml.manual.checks") == "true"

    private val sql = """
        CREATE TABLE orders (id INT, customer_id INT, amount DECIMAL(10, 2));
        CREATE TABLE customers (id INT, name VARCHAR(64));
        SELECT c.name, SUM(o.amount) AS total
        FROM orders o JOIN customers c ON c.id = o.customer_id
        WHERE o.amount > 0
        GROUP BY c.name
        ORDER BY total DESC;
    """.trimIndent()

    @Test
    fun renderWorkspaceDark() {
        if (!enabled) return
        val state = WorkbenchState(OpenedFile("demo.sql", sql), DesktopStoreState(theme = ThemeMode.DARK))
        state.runAnalysis()
        render("workspace-dark", ThemeMode.DARK, state)
    }

    @Test
    fun renderWorkspaceLight() {
        if (!enabled) return
        val state = WorkbenchState(OpenedFile("demo.sql", sql), DesktopStoreState(theme = ThemeMode.LIGHT))
        state.runAnalysis()
        render("workspace-light", ThemeMode.LIGHT, state)
    }

    @Test
    fun renderWorkspaceAgentTab() {
        if (!enabled) return
        val state = WorkbenchState(OpenedFile("demo.sql", sql), DesktopStoreState())
        state.runAnalysis()
        state.workspaceTab = WorkspaceTab.AGENT
        state.activities = listOf(
            Activity(1, ActivityKind.USER_MESSAGE, "这段 SQL 的 total 从哪来？"),
            Activity(2, ActivityKind.THINKING, "先看 SELECT 列表里的聚合，再追到 orders.amount。"),
            Activity(3, ActivityKind.TOOL_CALL, """{"engine":"jsqlparser"}""", title = "ozml_lineage"),
            Activity(
                4,
                ActivityKind.AGENT_MESSAGE,
                "total 来自 orders.amount 的 SUM 聚合：\n" +
                    "orders.amount --[OUTPUT · AGGREGATE]--> total\n" +
                    "过滤条件 WHERE o.amount > 0 是 PREDICATE 边，不改变输出值。",
            ),
        )
        render("workspace-agent", ThemeMode.DARK, state)
    }

    /** 窄窗：侧栏仍是用户可展开的，顶栏进入 compact。 */
    @Test
    fun renderWorkspaceNarrow() {
        if (!enabled) return
        val state = WorkbenchState(OpenedFile("demo.sql", sql), DesktopStoreState(theme = ThemeMode.LIGHT))
        state.runAnalysis()
        render("workspace-narrow", ThemeMode.LIGHT, state, width = 900, height = 760)
    }

    /** 收起态：顶部开关要留在（与图标同一条竖线），不能消失。 */
    @Test
    fun renderWorkspaceRailCollapsed() {
        if (!enabled) return
        val state = WorkbenchState(OpenedFile("demo.sql", sql), DesktopStoreState(theme = ThemeMode.LIGHT))
        state.runAnalysis()
        render("workspace-collapsed", ThemeMode.LIGHT, state, width = 1280, height = 820, railExpanded = false)
    }

    /** 最小窗口（960×640）：侧栏收起后三栏仍要能看。 */
    @Test
    fun renderWorkspaceMinimum() {
        if (!enabled) return
        val state = WorkbenchState(OpenedFile("demo.sql", sql), DesktopStoreState(theme = ThemeMode.DARK))
        state.runAnalysis()
        render("workspace-minimum", ThemeMode.DARK, state, width = 960, height = 640)
    }

    @Test
    fun renderSettingsComfortable() {
        if (!enabled) return
        val state = WorkbenchState(
            OpenedFile("demo.sql", sql),
            DesktopStoreState(theme = ThemeMode.LIGHT, density = io.github.workeron9.ozmoz.lineage.desktop.model.Density.COMFORTABLE),
        )
        state.agents = listOf(
            io.github.workeron9.ozmoz.lineage.desktop.model.DiscoveredAgent(
                id = "pi",
                name = "pi",
                executable = "/usr/local/bin/pi",
                version = "1.3.0",
                model = "example/model-b",
                skills = List(12) { "skill-$it" },
            ),
        )
        state.scanning = false
        state.page = Page.SETTINGS
        render("settings-comfortable", ThemeMode.LIGHT, state)
    }

    @Test
    fun renderConnectionPage() {
        if (!enabled) return
        val dir = Files.createTempDirectory("ozml-render-db")
        val profile = ConnectionProfile(
            id = "demo",
            name = "本地示例库",
            kind = ConnectionKind.SQLITE,
            database = dir.resolve("demo.db").toString(),
        )
        java.sql.DriverManager.getConnection(profile.resolvedUrl()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL, amount NUMERIC)")
                statement.execute("CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT)")
            }
        }
        val state = WorkbenchState(OpenedFile("demo.sql", sql), DesktopStoreState())
        state.saveConnection(profile)
        state.connect(profile)
        waitUntil { state.connectionRuntime(profile.id).status != ConnectionStatus.CONNECTING }
        state.connectionRuntime(profile.id).tables.firstOrNull { it.name == "orders" }?.let {
            state.selectTable(profile, it)
        }
        state.page = Page.CONNECTION
        render("connection", ThemeMode.LIGHT, state)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun renderAgentPage() {
        if (!enabled) return
        val state = WorkbenchState(OpenedFile("demo.sql", sql), DesktopStoreState())
        state.agents = listOf(
            io.github.workeron9.ozmoz.lineage.desktop.model.DiscoveredAgent(
                id = "opencode",
                name = "OpenCode",
                executable = "/usr/local/bin/opencode",
                version = "2.0.29",
                model = "example/model-a",
                skills = List(8) { "skill-$it" },
            ),
            io.github.workeron9.ozmoz.lineage.desktop.model.DiscoveredAgent(
                id = "pi",
                name = "pi",
                executable = "/usr/local/bin/pi",
                version = "1.3.0",
                model = "example/model-b",
                skills = List(12) { "skill-$it" },
            ),
        )
        state.scanning = false
        state.page = Page.AGENT
        render("agent-onboarding", ThemeMode.LIGHT, state)
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(100)
        }
    }

    private fun render(
        name: String,
        mode: ThemeMode,
        state: WorkbenchState,
        width: Int = 1280,
        height: Int = 820,
        railExpanded: Boolean = true,
    ) {
        val out = File(System.getProperty("java.io.tmpdir"), "ozml-desktop-$name.png")
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            DesktopTheme(mode = mode) { AppContent(state, initialRailExpanded = railExpanded) }
        }
        try {
            scene.render().encodeToData()?.bytes?.let { out.writeBytes(it) }
        } finally {
            scene.close()
        }
        println("rendered: ${out.absolutePath} (${out.length()} bytes)")
    }
}
