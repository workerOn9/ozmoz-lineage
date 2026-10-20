package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.workeron9.ozmoz.lineage.desktop.AgentGateway
import io.github.workeron9.ozmoz.lineage.desktop.ConnectionGateway
import io.github.workeron9.ozmoz.lineage.desktop.AgentRun
import io.github.workeron9.ozmoz.lineage.desktop.EngineGateway
import io.github.workeron9.ozmoz.lineage.desktop.OpenedFile
import io.github.workeron9.ozmoz.lineage.desktop.StoreGateway
import io.github.workeron9.ozmoz.lineage.desktop.model.Activity
import io.github.workeron9.ozmoz.lineage.desktop.model.ActivityKind
import io.github.workeron9.ozmoz.lineage.desktop.model.AgentEvent
import io.github.workeron9.ozmoz.lineage.desktop.model.Analysis
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionProfile
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionResult
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionRuntime
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionStatus
import io.github.workeron9.ozmoz.lineage.desktop.model.DbColumn
import io.github.workeron9.ozmoz.lineage.desktop.model.DbTable
import io.github.workeron9.ozmoz.lineage.desktop.model.Density
import io.github.workeron9.ozmoz.lineage.desktop.model.DesktopStoreState
import io.github.workeron9.ozmoz.lineage.desktop.model.DiscoveredAgent
import io.github.workeron9.ozmoz.lineage.desktop.model.InputMode
import io.github.workeron9.ozmoz.lineage.desktop.model.ProviderConfig
import io.github.workeron9.ozmoz.lineage.desktop.model.ProviderKind
import io.github.workeron9.ozmoz.lineage.desktop.model.SchemaSnapshot
import io.github.workeron9.ozmoz.lineage.desktop.model.ThemeMode
import io.github.workeron9.ozmoz.lineage.desktop.model.newAgentSessionId

/** 新建文档的显示名（不落盘，纯展示）。 */
internal const val DRAFT_NAME = "未命名.sql"

/** 四个目的地。侧栏项数控制在 4–5 个以内（超过就该换 NavigationDrawer 了）。 */
internal enum class Page(val label: String, val icon: ImageVector) {
    WORKSPACE("工作区", Icons.Workspace),
    CONNECTION("连接", Icons.Database),
    AGENT("Agent", Icons.Agent),
    SETTINGS("设置", Icons.Settings),
}

/** 工作区内的三个视图。切换而不是并排：1200px 宽下并排会把图和树都压成废件。 */
internal enum class WorkspaceTab(val label: String) {
    GRAPH("血缘图"),
    TREE("归一化树"),
    AGENT("Agent 对话"),
}

/**
 * D2 工作台的**呈现状态**（commonMain，纯 Compose 状态）。
 *
 * 平台副作用全部经三个门面走：[EngineGateway]（解析）、[ConnectionGateway]（JDBC）、
 * [AgentGateway]（本地 agent 进程）；本类只做状态聚合与消息编排。回调都在后台线程触发，
 * 写 Compose 快照状态是安全的。
 */
@Suppress("TooManyFunctions")
internal class WorkbenchState(
    initialFile: OpenedFile?,
    initialStore: DesktopStoreState,
) {
    // —— 壳层 ——
    var store by mutableStateOf(initialStore)
        private set
    var page by mutableStateOf(Page.WORKSPACE)

    // —— 工作区 ——
    var opened by mutableStateOf(initialFile)
        private set
    var analysis by mutableStateOf<Analysis?>(null)
        private set

    /** 源面板是否处于编辑态（新建 / 粘一段 SQL 时直接进来）。 */
    var editing by mutableStateOf(false)

    /** 编辑一次 +1：[SourcePanel] 用它做「停手 350ms 再分析」的 debounce 键。 */
    var sqlRevision by mutableStateOf(0)
        private set

    var workspaceTab by mutableStateOf(WorkspaceTab.GRAPH)
    var collapsedNodes by mutableStateOf(emptySet<String>())
    var selectedColumn by mutableStateOf<String?>(null)
    var selectedFlow by mutableStateOf<Int?>(null)
    var focusLine by mutableStateOf<Int?>(null)
    var engineId by mutableStateOf(EngineGateway.engineIds.first())
    var tolerant by mutableStateOf(true)

    /** 血缘分析用哪条连接的 schema（null = 只用 SQL 文本里的 DDL）。 */
    var activeConnectionId by mutableStateOf<String?>(null)

    // —— 连接 ——
    var runtimes by mutableStateOf<Map<String, ConnectionRuntime>>(emptyMap())
        private set

    /** 密码只在内存里活到本次连接（不落盘；凭据存储是 D3 的事）。 */
    val passwords: MutableMap<String, String> = mutableMapOf()

    // —— Agent ——
    var agents by mutableStateOf<List<DiscoveredAgent>>(emptyList())
    var scanning by mutableStateOf(true)
    var agentProviderId by mutableStateOf<String?>(null)
    var activities by mutableStateOf<List<Activity>>(emptyList())
    var agentBusy by mutableStateOf(false)
        private set
    var usageLine by mutableStateOf<String?>(null)
        private set
    var agentError by mutableStateOf<String?>(null)
        private set

    private var run: AgentRun? = null
    private var activitySeq = 0L
    private var providerSessionId: String? = null
    private val textActivityIds = mutableMapOf<String, Long>()

    // ————————————————————————————— 工作区 —————————————————————————————

    fun openFile(file: OpenedFile?) {
        opened = file
        editing = false
        runAnalysis()
    }

    /** 新建空白文档：不要求先有 .sql 文件，直接粘 / 打字。 */
    fun newDocument() {
        opened = OpenedFile(DRAFT_NAME, "")
        editing = true
        runAnalysis()
    }

    fun editSql(sql: String) {
        val current = opened ?: return
        opened = current.copy(sql = sql)
        sqlRevision++
    }

    fun pickFile() {
        openFile(EngineGateway.pickSqlFile())
    }

    /** 重跑分析。UI 在引擎 / 容错 / 连接变化时调；编辑态由 [sqlRevision] 防抖后调。 */
    fun runAnalysis() {
        val file = opened
        analysis = if (file == null || file.sql.isBlank()) {
            null
        } else {
            EngineGateway.analyze(file.sql, engineId, tolerant, activeConnectionId)
        }
        collapsedNodes = emptySet()
        selectedColumn = null
        selectedFlow = null
        focusLine = null
    }

    // ————————————————————————————— 连接 —————————————————————————————

    fun connectionRuntime(profileId: String): ConnectionRuntime = runtimes[profileId] ?: ConnectionRuntime()

    /** 这条连接缺驱动且可自动补时，给 UI 一个下载入口；否则 null。 */
    fun driverPlan(profile: ConnectionProfile) = ConnectionGateway.driverPlan(profile)

    fun saveConnection(profile: ConnectionProfile) {
        updateStore { state -> state.copy(connections = state.connections.filterNot { it.id == profile.id } + profile) }
    }

    fun deleteConnection(profile: ConnectionProfile) {
        ConnectionGateway.close(profile.id)
        passwords.remove(profile.id)
        runtimes = runtimes - profile.id
        if (activeConnectionId == profile.id) activeConnectionId = null
        updateStore { state -> state.copy(connections = state.connections.filterNot { it.id == profile.id }) }
    }

    fun connect(profile: ConnectionProfile) {
        runtimes = runtimes + (profile.id to ConnectionRuntime(status = ConnectionStatus.CONNECTING))
        ConnectionGateway.open(profile, passwords[profile.id].orEmpty()) { result, snapshot ->
            applyConnection(profile.id, result, snapshot)
        }
    }

    /** 缺驱动时的自动补：下载到 ~/.ozml-lineage/drivers 后自动重连。 */
    fun installDriver(profile: ConnectionProfile) {
        val plan = ConnectionGateway.driverPlan(profile) ?: return
        runtimes = runtimes + (
            profile.id to ConnectionRuntime(status = ConnectionStatus.CONNECTING, message = "正在下载 ${plan.name} 驱动（${plan.sizeLabel}）…")
            )
        ConnectionGateway.installDriver(profile) { ok, message ->
            if (ok) {
                connect(profile)
            } else {
                runtimes = runtimes + (profile.id to ConnectionRuntime(status = ConnectionStatus.ERROR, message = message))
            }
        }
    }

    private fun applyConnection(profileId: String, result: ConnectionResult, snapshot: SchemaSnapshot?) {
        runtimes = runtimes + (
            profileId to ConnectionRuntime(
                status = if (result.ok) ConnectionStatus.ONLINE else ConnectionStatus.ERROR,
                message = result.message,
                tables = snapshot?.tables.orEmpty(),
                schemaColumns = snapshot?.columns.orEmpty(),
            )
            )
        if (result.ok) activeConnectionId = profileId
    }

    fun disconnect(profile: ConnectionProfile) {
        ConnectionGateway.close(profile.id)
        runtimes = runtimes + (profile.id to ConnectionRuntime())
        if (activeConnectionId == profile.id) activeConnectionId = null
    }

    /** 选中一张表：列从已加载的快照里取（不再触库）。 */
    fun selectTable(profile: ConnectionProfile, table: DbTable) {
        val current = connectionRuntime(profile.id)
        runtimes = runtimes + (profile.id to current.copy(selectedTable = table))
    }

    /** 一键跳转血缘：SQL 由列清单生成，schema 由这条连接的元数据提供。 */
    fun openInWorkspace(profile: ConnectionProfile, table: DbTable, columns: List<DbColumn>) {
        val columnList = columns.joinToString(", ") { it.name }
        val sql = if (columnList.isBlank()) {
            "SELECT * FROM ${table.qualifiedName};"
        } else {
            "SELECT $columnList\nFROM ${table.qualifiedName};"
        }
        activeConnectionId = profile.id
        opened = OpenedFile(name = "${table.name}.sql", sql = sql)
        page = Page.WORKSPACE
        workspaceTab = WorkspaceTab.GRAPH
        runAnalysis()
    }

    // ————————————————————————————— Agent —————————————————————————————

    /** 启动时扫描一次；结果经回调回来（后台线程）。已保存的 Provider 路径也当候选——
     *  从 Finder 启动的 .app 只有 launchd 的极简 PATH，扫不到 /opt/homebrew/bin。 */
    fun scanAgents() {
        scanning = true
        AgentGateway.discover(store.providers.map { it.endpoint }) { found ->
            agents = found
            if (agentProviderId == null) agentProviderId = enabledProviders().firstOrNull()?.id
            scanning = false
        }
    }

    /** 已启用的 Provider（用户点过「启用」的本地 agent）。 */
    fun enabledProviders(): List<ProviderConfig> = store.providers.filter { it.enabled }

    fun enableProvider(agent: DiscoveredAgent) {
        val config = ProviderConfig(
            id = agent.id,
            name = agent.name,
            kind = ProviderKind.NATIVE,
            enabled = true,
            endpoint = agent.executable,
            model = agent.model.orEmpty(),
        )
        updateStore { state -> state.copy(providers = state.providers.filterNot { it.id == config.id } + config) }
        if (agentProviderId == null) agentProviderId = config.id
    }

    fun disableProvider(config: ProviderConfig) {
        updateStore { state ->
            state.copy(providers = state.providers.map { if (it.id == config.id) it.copy(enabled = false) else it })
        }
        if (agentProviderId == config.id) {
            agentProviderId = store.providers.firstOrNull { it.enabled && it.id != config.id }?.id
        }
    }

    fun newAgentSession() {
        run?.cancel()
        run = null
        activities = emptyList()
        agentError = null
        usageLine = null
        agentBusy = false
        providerSessionId = null
        textActivityIds.clear()
    }

    /**
     * 发一条消息。[context] 非空时把当前 SQL 作为上下文附在 prompt 前面
     * （工作区的 Agent Tab 用）；AGENT 目的地不带。
     */
    fun sendAgent(prompt: String, context: OpenedFile?) {
        val text = prompt.trim()
        if (text.isEmpty() || agentBusy) return
        val provider = enabledProviders().firstOrNull { it.id == agentProviderId }
        if (provider == null) {
            agentError = "还没有启用任何本地 Agent —— 先在卡片里启用一个。"
            return
        }
        val session = providerSessionId ?: newAgentSessionId(provider.id).also { providerSessionId = it }
        agentError = null
        agentBusy = true
        addActivity(ActivityKind.USER_MESSAGE, text)
        run = AgentGateway.start(provider, session, composePrompt(text, context)) { event -> applyEvent(event) }
    }

    fun cancelAgent() {
        run?.cancel()
        run = null
        agentBusy = false
    }

    private fun composePrompt(text: String, context: OpenedFile?): String {
        if (context == null) return text
        val sql = context.sql
        val clipped = if (sql.length > 12_000) sql.take(12_000) + "\n-- …（上下文过长，已截断）" else sql
        return buildString {
            appendLine("当前工作区打开的文件：${context.name}（${sql.lineSequence().count()} 行）")
            appendLine("\u0060\u0060\u0060sql")
            appendLine(clipped)
            appendLine("\u0060\u0060\u0060")
            appendLine()
            append(text)
        }
    }

    private fun applyEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Text -> {
                val id = textActivityIds[event.partId]
                if (id == null) {
                    textActivityIds[event.partId] = addActivity(ActivityKind.AGENT_MESSAGE, event.text)
                } else {
                    activities = activities.map {
                        when {
                            it.id != id -> it
                            event.delta -> it.copy(text = it.text + event.text)
                            else -> it.copy(text = event.text)
                        }
                    }
                }
            }

            is AgentEvent.Tool -> {
                val kind = if (event.output == null) ActivityKind.TOOL_CALL else ActivityKind.TOOL_RESULT
                addActivity(kind, event.output ?: event.input, title = event.name, isError = event.isError)
            }

            is AgentEvent.Thinking -> addActivity(ActivityKind.THINKING, event.text)

            is AgentEvent.Usage -> usageLine = buildString {
                event.tokens?.let { append("上下文 $it tokens") }
                event.costUsd?.takeIf { it > 0 }?.let {
                    if (isNotEmpty()) append(" · ")
                    append("花费 $").append(it)
                }
            }.ifBlank { null }

            is AgentEvent.Failure -> {
                agentError = event.message
                addActivity(ActivityKind.ERROR, event.message, isError = true)
                agentBusy = false
                run = null
            }

            AgentEvent.Completed -> {
                agentBusy = false
                run = null
            }
        }
    }

    private fun addActivity(kind: ActivityKind, text: String, title: String = "", isError: Boolean = false): Long {
        val id = ++activitySeq
        activities = activities + Activity(id = id, kind = kind, text = text, title = title, isError = isError)
        return id
    }

    fun toggleActivity(id: Long) {
        activities = activities.map { if (it.id == id) it.copy(expanded = !it.expanded) else it }
    }

    fun modeInstruction(mode: InputMode): String = when (mode) {
        InputMode.QUERY -> "请解释这段 SQL 的列级血缘：每列从哪来、经过什么变换。"
        InputMode.GENERATE -> "请根据下面的需求写出 SQL。"
        InputMode.REVIEW -> "请审阅这段 SQL：找出语义错误、性能与可读性问题。"
    }

    // ————————————————————————————— 设置 —————————————————————————————

    private fun updateStore(transform: (DesktopStoreState) -> DesktopStoreState) {
        val next = transform(store)
        store = next
        StoreGateway.save(next)
    }

    fun setTheme(mode: ThemeMode) = updateStore { it.copy(theme = mode) }

    fun setDensity(density: Density) = updateStore { it.copy(density = density) }
}
