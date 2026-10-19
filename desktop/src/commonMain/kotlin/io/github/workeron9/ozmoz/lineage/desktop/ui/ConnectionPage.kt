package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionKind
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionProfile
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionStatus
import io.github.workeron9.ozmoz.lineage.desktop.model.DbTable
import kotlin.random.Random

/**
 * CONNECTION 目的地：连接列表 + schema 树 + 对象检查器。
 *
 * **只读**：连上以后只读元数据（表 / 列 / 可空），不执行用户 SQL、不写库。
 * 密码只活在内存（`state.passwords`），设置文件里没有凭据——加密存储是 D3 的事项。
 */
@Composable
internal fun ConnectionPage(state: WorkbenchState) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    var selectedId by remember { mutableStateOf<String?>(state.store.connections.firstOrNull()?.id) }
    var editing by remember { mutableStateOf<ConnectionProfile?>(null) }
    val connections = state.store.connections
    val selected = connections.firstOrNull { it.id == selectedId } ?: connections.firstOrNull()

    Row(Modifier.fillMaxSize()) {
        // 左：连接列表
        Column(Modifier.width(240.dp).fillMaxHeight().background(tokens.surface)) {
            PanelHeader("连接")
            LazyColumn(Modifier.weight(1f)) {
                items(connections, key = { it.id }) { profile ->
                    ConnectionRow(
                        profile = profile,
                        selected = profile.id == selected?.id,
                        status = state.connectionRuntime(profile.id).status,
                        onClick = {
                            selectedId = profile.id
                            editing = null
                        },
                    )
                }
            }
            TextButton(
                onClick = { editing = blankConnection() },
                modifier = Modifier.fillMaxWidth().padding(6.dp),
            ) { Text("+ 新建连接") }
        }
        VerticalDivider()

        if (editing != null) {
            ConnectionForm(
                initial = editing!!,
                onCancel = { editing = null },
                onSave = { profile ->
                    state.saveConnection(profile)
                    selectedId = profile.id
                    editing = null
                },
            )
        } else if (selected == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("还没有连接", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "左侧新建一个；SQLite 驱动随包，其他数据库把官方 JDBC 驱动 jar 放进 ~/.ozml-lineage/drivers 即可。",
                        color = tokens.textMuted,
                        fontSize = density.bodySize,
                    )
                }
            }
        } else {
            val runtime = state.connectionRuntime(selected.id)
            SchemaTree(
                profile = selected,
                runtime = runtime,
                onConnect = { state.connect(selected) },
                onDisconnect = { state.disconnect(selected) },
                onSelectTable = { table -> state.selectTable(selected, table) },
                modifier = Modifier.weight(1f),
            )
            VerticalDivider()
            ObjectInspector(
                state = state,
                profile = selected,
                modifier = Modifier.width(320.dp).fillMaxHeight(),
            )
        }
    }
}

private fun blankConnection(): ConnectionProfile = ConnectionProfile(
    id = "conn-" + Random.nextLong().toString(16).take(12),
    name = "新连接",
    kind = ConnectionKind.SQLITE,
    database = "",
)

@Composable
private fun ConnectionRow(
    profile: ConnectionProfile,
    selected: Boolean,
    status: ConnectionStatus,
    onClick: () -> Unit,
) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    val dotColor = when (status) {
        ConnectionStatus.ONLINE -> tokens.flowColor(io.github.workeron9.ozmoz.lineage.desktop.model.FlowKind.GROUP_BY)
        ConnectionStatus.CONNECTING -> tokens.warning
        ConnectionStatus.ERROR -> tokens.danger
        ConnectionStatus.UNKNOWN -> tokens.textFaint
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dotColor, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name, fontSize = density.titleSize, maxLines = 1)
            Text(
                profile.kind.label,
                color = tokens.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = density.bodySize,
            )
        }
    }
}

/** 新建 / 编辑表单。JDBC URL 是高级项，非空时覆盖 host/port/database。 */
@Composable
private fun ConnectionForm(
    initial: ConnectionProfile,
    onCancel: () -> Unit,
    onSave: (ConnectionProfile) -> Unit,
) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var kindMenu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (initial.name == "新连接") "新建连接" else "编辑连接",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { kindMenu = true }) { Text("类型：${draft.kind.label}  ▾") }
                DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                    for (kind in ConnectionKind.entries) {
                        DropdownMenuItem(
                            text = { Text(kind.label) },
                            onClick = {
                                draft = draft.copy(kind = kind)
                                kindMenu = false
                            },
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.width(360.dp),
        )
        if (draft.kind != ConnectionKind.SQLITE && draft.kind != ConnectionKind.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = { draft = draft.copy(host = it) },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.width(200.dp),
                )
                OutlinedTextField(
                    value = draft.port,
                    onValueChange = { draft = draft.copy(port = it) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp),
                )
            }
        }
        OutlinedTextField(
            value = draft.database,
            onValueChange = { draft = draft.copy(database = it) },
            label = {
                Text(if (draft.kind == ConnectionKind.SQLITE) "数据库文件路径" else "数据库名")
            },
            singleLine = true,
            modifier = Modifier.width(360.dp),
        )
        OutlinedTextField(
            value = draft.user,
            onValueChange = { draft = draft.copy(user = it) },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.width(360.dp),
        )
        OutlinedTextField(
            value = draft.jdbcUrl,
            onValueChange = { draft = draft.copy(jdbcUrl = it) },
            label = { Text("JDBC URL（高级，非空覆盖以上字段）") },
            singleLine = true,
            modifier = Modifier.width(560.dp),
        )
        Text(
            "生成的 URL：${draft.resolvedUrl()}",
            color = tokens.textFaint,
            fontFamily = FontFamily.Monospace,
            fontSize = density.bodySize,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(draft) }) { Text("保存") }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

/** 中间：schema 树（按 schema 分组，点表名在右侧检查器看列）。 */
@Composable
private fun SchemaTree(
    profile: ConnectionProfile,
    runtime: io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionRuntime,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectTable: (DbTable) -> Unit,
    modifier: Modifier,
) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(profile.name, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(10.dp))
            Text(
                when (runtime.status) {
                    ConnectionStatus.ONLINE -> "已连接 · ${runtime.tables.size} 张表"
                    ConnectionStatus.CONNECTING -> "连接中…"
                    ConnectionStatus.ERROR -> runtime.message ?: "连接失败"
                    ConnectionStatus.UNKNOWN -> "未连接"
                },
                color = when (runtime.status) {
                    ConnectionStatus.ONLINE -> tokens.textMuted
                    ConnectionStatus.ERROR -> tokens.danger
                    else -> tokens.textFaint
                },
                fontSize = density.bodySize,
            )
            Spacer(Modifier.weight(1f))
            when (runtime.status) {
                ConnectionStatus.ONLINE -> TextButton(onClick = onDisconnect) { Text("断开") }
                ConnectionStatus.CONNECTING -> Unit
                else -> Button(onClick = onConnect) { Text("连接") }
            }
        }
        HorizontalDivider()
        if (runtime.tables.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when (runtime.status) {
                        ConnectionStatus.ERROR -> runtime.message ?: "连接失败"
                        ConnectionStatus.ONLINE -> "这个库里没有表"
                        else -> "连接后在这里浏览 schema（只读元数据）"
                    },
                    color = tokens.textMuted,
                    fontSize = density.bodySize,
                )
            }
        } else {
            var collapsedSchemas by remember(profile.id) { mutableStateOf(emptySet<String>()) }
            val grouped = remember(runtime.tables) { runtime.tables.groupBy { it.schema ?: "(默认)" } }
            LazyColumn(Modifier.fillMaxSize()) {
                for ((schema, tables) in grouped) {
                    item(key = "schema:$schema") {
                        Text(
                            (if (schema in collapsedSchemas) "▸ " else "▾ ") + schema,
                            color = tokens.textMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = density.bodySize,
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    collapsedSchemas = if (schema in collapsedSchemas) {
                                        collapsedSchemas - schema
                                    } else {
                                        collapsedSchemas + schema
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    if (schema !in collapsedSchemas) {
                        items(tables, key = { it.qualifiedName }) { table ->
                            val selected = runtime.selectedTable?.qualifiedName == table.qualifiedName
                            Text(
                                "   " + table.name + if (table.kind == "VIEW") "  (视图)" else "",
                                color = if (selected) tokens.accent else tokens.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = density.bodySize,
                                modifier = Modifier.fillMaxWidth()
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            androidx.compose.ui.graphics.Color.Transparent
                                        },
                                    )
                                    .clickable { onSelectTable(table) }
                                    .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 右：对象检查器——列清单 + 一键跳转血缘。 */
@Composable
private fun ObjectInspector(
    state: WorkbenchState,
    profile: ConnectionProfile,
    modifier: Modifier,
) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    val runtime = state.connectionRuntime(profile.id)
    val table = runtime.selectedTable
    Column(modifier.background(tokens.surface)) {
        PanelHeader("对象详情")
        if (table == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("选中一张表看列", color = tokens.textFaint, fontSize = density.bodySize)
            }
            return@Column
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(table.qualifiedName, color = tokens.accent, fontFamily = FontFamily.Monospace, fontSize = density.titleSize)
            Text(
                "${table.kind} · ${runtime.columns.size} 列",
                color = tokens.textMuted,
                fontSize = density.bodySize,
            )
            Button(onClick = { state.openInWorkspace(profile, table, runtime.columns) }) {
                Text("在血缘图中打开")
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(runtime.columns, key = { it.ordinal }) { column ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(column.name, fontFamily = FontFamily.Monospace, fontSize = density.bodySize)
                        Text(
                            column.type,
                            color = tokens.textMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = density.bodySize,
                        )
                    }
                    Text(
                        when (column.nullable) {
                            true -> "可空"
                            false -> "非空"
                            null -> "?"
                        },
                        color = tokens.textFaint,
                        fontSize = density.bodySize,
                    )
                }
            }
        }
    }
}
