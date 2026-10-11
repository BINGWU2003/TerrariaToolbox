package com.example.terrariatoolbox.feature.saves

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.terrariatoolbox.domain.*
import com.example.terrariatoolbox.feature.chinese.formatTime
import java.util.Locale

@Composable
fun SaveScreen(manager: SaveManager, connected: Boolean, goHome: () -> Unit) {
    val state by manager.state.collectAsState()
    val phone = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), manager::preparePhone)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        manager.exported(if (it.resultCode == Activity.RESULT_OK) it.data?.data else null)
    }
    LaunchedEffect(connected) { if (connected) manager.refresh() }
    SaveContent(state, connected, SaveActions(
        edition = manager::edition, account = manager::account, refresh = manager::refresh,
        toggle = manager::toggle, selectAll = manager::selectAll, builtins = manager::prepareBuiltins,
        phone = { try { phone.launch(arrayOf("*/*")) } catch (_: Exception) { manager.pickerUnavailable() } },
        export = { manager.prepareExport { spec ->
            try { export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = spec.mime
                putExtra(Intent.EXTRA_TITLE, spec.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }) } catch (_: Exception) { manager.pickerUnavailable() }
        } }, confirm = manager::confirmImport, cancel = manager::cancelImport, goHome = goHome,
    ))
}

data class SaveActions(
    val edition: (GameEdition) -> Unit = {}, val account: (String) -> Unit = {}, val refresh: () -> Unit = {},
    val toggle: (String) -> Unit = {}, val selectAll: (Set<String>) -> Unit = {},
    val builtins: (Set<String>) -> Unit = {}, val phone: () -> Unit = {}, val export: () -> Unit = {},
    val confirm: () -> Unit = {}, val cancel: () -> Unit = {}, val goHome: () -> Unit = {},
)

@Composable
fun SaveContent(state: SaveState, connected: Boolean, actions: SaveActions) {
    var kind by rememberSaveable { mutableStateOf("Players") }
    var builtinsExpanded by rememberSaveable { mutableStateOf(false) }
    var chosenBuiltins by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val enabled = connected && !state.busy
    val ready = enabled && state.hasTarget && state.loaded
    val visible = state.saves.filter { it.kind.directory == kind }
    LazyColumn(Modifier.fillMaxSize().testTag("saves-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("存档管理", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("查看、导入和导出你的角色与世界。")
        }
        item {
            SaveCard("游戏版本") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GameEdition.entries.forEach { edition ->
                        FilterChip(selected = state.edition == edition, onClick = { actions.edition(edition) }, enabled = !state.busy,
                            label = { Text(edition.label) }, modifier = Modifier.testTag("edition-${edition.code}"))
                    }
                }
                if (state.edition == GameEdition.CHINESE) {
                    if (state.accounts.size == 1) Text("账号 ID：${state.account}（已自动选择）", Modifier.testTag("save-account"))
                    else if (state.accounts.size > 1) {
                        Text("选择账号 ID。登录多个账号时，每个账号的存档单独保存。")
                        state.accounts.forEach { account ->
                            OutlinedButton({ actions.account(account) }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().testTag("account-$account")) {
                                Text(if (state.account == account) "✓ 账号 $account" else "账号 $account")
                            }
                        }
                    } else Text("先登录国服游戏，再完全退出后刷新，即可找到账号。")
                }
                Text(state.message, Modifier.testTag("save-message"))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!connected) OutlinedButton(actions.goHome, modifier = Modifier.fillMaxWidth().testTag("saves-go-connect")) { Text("去首页连接 Shizuku") }
                else TextButton(actions.refresh, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("refresh-saves")) { Text("刷新存档") }
            }
        }
        item {
            SaveCard("导入存档") {
                Text("同名时会自动添加编号，不覆盖已有存档。只调整文件名，游戏里的名称可能仍然相同。")
                OutlinedButton({ builtinsExpanded = !builtinsExpanded }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("show-builtins")) { Text(if (builtinsExpanded) "收起内置存档" else "选择内置存档（${state.builtins.size} 个）") }
                Button(actions.phone, enabled = ready, modifier = Modifier.fillMaxWidth().testTag("import-phone")) { Text("从手机选择文件") }
                Text("支持多选 .plr 角色、.wld 世界，或导入工具箱导出的 ZIP。", style = MaterialTheme.typography.bodySmall)
                Text("存档能否在游戏中读取取决于游戏版本，工具箱不转换存档格式。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (builtinsExpanded) {
            item { Text("内置存档", style = MaterialTheme.typography.titleLarge) }
            items(state.builtins, key = { "builtin-${it.asset}" }) { save ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        Checkbox(save.asset in chosenBuiltins, onCheckedChange = {
                            chosenBuiltins = if (save.asset in chosenBuiltins) chosenBuiltins - save.asset else chosenBuiltins + save.asset
                        }, enabled = !state.busy, modifier = Modifier.testTag("builtin-${save.name}"))
                        Column(Modifier.weight(1f).padding(top = 8.dp)) {
                            Text(save.name, fontWeight = FontWeight.SemiBold)
                            Text("${if (SaveNames.directory(save.name) == "Players") "角色" else "世界"} · ${formatSaveSize(save.size)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Button({ actions.builtins(chosenBuiltins.toSet()) }, enabled = ready && chosenBuiltins.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("import-builtins")) { Text("导入选中的 ${chosenBuiltins.size} 个内置存档") }
            }
        }
        if (state.results.isNotEmpty()) {
            item { Text("本次导入结果", style = MaterialTheme.typography.titleLarge) }
            items(state.results.withIndex().toList(), key = { "result-${it.index}" }) { (_, result) ->
                SaveCard(result.original) {
                    Text(if (result.savedAs == null) "未完成：${result.error}" else if (result.original == result.savedAs) "已导入：${result.savedAs}" else "已自动重命名并导入：${result.savedAs}",
                        color = if (result.savedAs == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Text("游戏里的存档", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SaveKind.entries.forEach { tab ->
                    FilterChip(kind == tab.directory, { kind = tab.directory }, label = { Text("${tab.label}（${state.saves.count { it.kind == tab }}）") }, modifier = Modifier.testTag("kind-${tab.directory}"))
                }
            }
            Text("只管理角色和世界的主文件，不包含自动备份和地图探索文件。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedButton(actions.export, enabled = ready && state.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("export-saves")) {
                Text("导出选中的 ${state.selected.size} 个存档")
            }
            Text("选一个导出原文件，选多个导出 ZIP，可再次导入。", style = MaterialTheme.typography.bodySmall)
            TextButton({
                val names = visible.map { it.name }.toSet()
                actions.selectAll(if (state.selected.containsAll(names)) state.selected - names else state.selected + names)
            }, enabled = ready && visible.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("select-all-saves")) {
                Text(if (visible.isNotEmpty() && state.selected.containsAll(visible.map { it.name })) "取消本页全选" else "全选${if (kind == "Players") "角色" else "世界"}")
            }
        }
        if (visible.isEmpty()) item { Text(if (state.loaded && state.hasTarget) "还没有${if (kind == "Players") "角色" else "世界"}存档。" else "选择游戏版本和账号后，即可查看存档。") }
        items(visible, key = { "game-${it.name}" }) { save ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp)) {
                    Checkbox(save.name in state.selected, onCheckedChange = { actions.toggle(save.name) }, enabled = ready,
                        modifier = Modifier.testTag("save-${save.name}"))
                    Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(save.name, fontWeight = FontWeight.SemiBold)
                        Text("${formatSaveSize(save.size)} · ${formatTime(save.modified)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (state.pending.isNotEmpty()) AlertDialog(
        onDismissRequest = { if (!state.busy) actions.cancel() }, title = { Text("导入 ${state.pending.size} 个存档？") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("导入到：${state.pendingTarget?.label}", Modifier.testTag("import-target"), fontWeight = FontWeight.Bold)
                Text("请先完全退出泰拉瑞亚，再开始导入。同名文件会自动添加编号，已有存档会保留。")
                state.pending.forEach { Text("${it.kind.label} · ${it.name}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(actions.confirm, enabled = enabled, modifier = Modifier.testTag("confirm-save-import")) { Text(if (state.busy) "正在导入…" else "已退出游戏，开始导入") } },
        dismissButton = { TextButton(actions.cancel, enabled = !state.busy) { Text("取消") } },
    )
}

@Composable
private fun SaveCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
fun formatSaveSize(bytes: Long): String = if (bytes < 1024 * 1024) String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0) else String.format(Locale.CHINA, "%.2f MB", bytes / (1024.0 * 1024))
