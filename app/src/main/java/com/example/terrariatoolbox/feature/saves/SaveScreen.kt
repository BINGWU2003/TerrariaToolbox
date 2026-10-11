package com.example.terrariatoolbox.feature.saves

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.terrariatoolbox.feature.chinese.formatTime
import com.example.terrariatoolbox.domain.*
import java.util.Locale
import kotlinx.coroutines.flow.first

@Composable
fun SaveScreen(manager: SaveManager, connected: Boolean, goHome: () -> Unit, entry: String = "auto", otherBusy: Boolean = false, consumeEntry: () -> Unit = {}) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val phone = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), manager::preparePhone)
    val output = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        manager.exported(if (it.resultCode == Activity.RESULT_OK) it.data?.data else null)
    }
    var observedConnection by remember { mutableStateOf(false) }
    LaunchedEffect(connected, state.builtins.size, state.edition, state.importStep) {
        val reconnected = connected && !observedConnection
        if (!connected) observedConnection = false
        if (connected && state.builtins.isNotEmpty()) {
            manager.state.first { !it.busy }
            if (reconnected || !manager.state.value.loaded) manager.refresh()
            observedConnection = true
        }
    }
    SaveContent(state, connected, SaveActions(
        edition = manager::edition, account = manager::account, refresh = manager::refresh,
        toggle = manager::toggle, selectAll = manager::selectAll, builtins = manager::prepareBuiltins,
        phone = { try { phone.launch(arrayOf("*/*")) } catch (_: Exception) { manager.pickerUnavailable() } },
        export = { manager.prepareExport { spec ->
            try { output.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = spec.mime; putExtra(Intent.EXTRA_TITLE, spec.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }) } catch (_: Exception) { manager.pickerUnavailable() }
        } }, confirm = manager::confirmImport, cancel = manager::cancelImport, goHome = goHome,
        begin = manager::beginImport, review = manager::reviewImport, changeTarget = manager::changeImportTarget,
        declareSource = manager::declareSource,
        openExport = { state.exportResult?.let {
            try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it.uri,
                if (it.name.endsWith(".zip", true)) "application/zip" else "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            catch (_: Exception) { manager.openExportUnavailable() }
        } },
    ), entry, otherBusy, consumeEntry)
}

data class SaveActions(
    val edition: (GameEdition) -> Unit = {}, val account: (String) -> Unit = {}, val refresh: () -> Unit = {},
    val toggle: (String) -> Unit = {}, val selectAll: (Set<String>) -> Unit = {},
    val builtins: (Set<String>) -> Unit = {}, val phone: () -> Unit = {}, val export: () -> Unit = {},
    val confirm: () -> Unit = {}, val cancel: () -> Unit = {}, val goHome: () -> Unit = {},
    val begin: () -> Unit = {}, val review: () -> Unit = {}, val changeTarget: () -> Unit = {}, val openExport: () -> Unit = {},
    val declareSource: (GameEdition) -> Unit = {},
)

@Composable
fun SaveContent(state: SaveState, connected: Boolean, actions: SaveActions, entry: String = "auto", otherBusy: Boolean = false, consumeEntry: () -> Unit = {}) {
    var page by rememberSaveable { mutableStateOf(if (state.importStep == ImportStep.SOURCE) "list" else "import") }
    var observedStep by remember { mutableStateOf(state.importStep) }
    var kind by rememberSaveable { mutableStateOf("Players") }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("time") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var builtinsExpanded by rememberSaveable { mutableStateOf(false) }
    var chosenBuiltins by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var builtinEdition by rememberSaveable { mutableStateOf(state.edition.name) }
    var builtinKind by rememberSaveable { mutableStateOf("Players") }
    val locked = state.busy || state.awaitingExport || otherBusy
    val ready = connected && !locked && state.hasTarget && state.loaded
    LaunchedEffect(entry) {
        if (entry == "import") { page = "import"; if (state.importStep in listOf(ImportStep.SOURCE, ImportStep.RESULT)) {
            actions.begin(); builtinEdition = state.edition.name; chosenBuiltins = emptyList(); builtinsExpanded = false
        } }
        if (entry == "list") page = "list"
        if (entry == "export") { page = "list"; selecting = true }
        if (entry != "auto") consumeEntry()
    }
    LaunchedEffect(state.importStep) {
        if (state.importStep != observedStep && state.importStep != ImportStep.SOURCE) page = "import"
        observedStep = state.importStep
    }
    fun startImport() { actions.begin(); chosenBuiltins = emptyList(); builtinEdition = state.edition.name; builtinsExpanded = false; page = "import" }
    fun backImport() {
        when (state.importStep) {
            ImportStep.REVIEW -> actions.changeTarget()
            ImportStep.TARGET -> { actions.cancel(); builtinsExpanded = false }
            else -> { actions.cancel(); page = "list" }
        }
    }
    BackHandler(page == "import") { if (!locked) backImport() }
    val visible = state.saves.filter { it.kind.directory == kind && it.name.contains(query.trim(), true) }.let { saves ->
        when (sort) {
            "name" -> saves.sortedBy { it.name.lowercase(Locale.ROOT) }
            "size" -> saves.sortedByDescending { it.size }
            else -> saves.sortedWith(compareByDescending<GameSave> { it.modified }.thenBy { it.name.lowercase(Locale.ROOT) })
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (page == "import") Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(::backImport, enabled = !locked, modifier = Modifier.testTag("import-back")) {
                Text(if (state.importStep == ImportStep.REVIEW) "上一步" else if (state.importStep == ImportStep.TARGET) "重新选文件" else "返回存档")
            }
            Text("导入存档", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn(Modifier.weight(1f).testTag("saves-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (page == "list") {
                item { TargetCard(state, connected, !locked, actions) }
                if (state.pending.isNotEmpty()) item {
                    OutlinedButton({ page = "import" }, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("resume-import")) { Text("继续导入 ${state.pending.size} 个已选文件") }
                } else if (state.results.isNotEmpty()) item {
                    TextButton({ page = "import" }, modifier = Modifier.testTag("view-last-import")) { Text("查看上次导入结果") }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(::startImport, enabled = !locked, modifier = Modifier.testTag("start-import")) { Text("导入存档") }
                        TextButton({ selecting = !selecting; if (!selecting) actions.selectAll(emptySet()) }, enabled = !locked,
                            modifier = Modifier.testTag("select-mode")) { Text(if (selecting) "取消选择" else "选择并导出") }
                    }
                }
                item {
                    PrimaryTabRow(selectedTabIndex = if (kind == "Players") 0 else 1) {
                        SaveKind.entries.forEach { tab ->
                            Tab(selected = kind == tab.directory, onClick = { kind = tab.directory },
                                modifier = Modifier.testTag("kind-${tab.directory}"), text = { Text("${tab.label}（${state.saves.count { it.kind == tab }}）") })
                        }
                    }
                }
                item {
                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("搜索文件名") },
                        trailingIcon = { if (query.isNotEmpty()) TextButton({ query = "" }) { Text("清除") } },
                        modifier = Modifier.fillMaxWidth().testTag("save-search"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("time" to "最近修改", "name" to "名称", "size" to "大小").forEach { (value, label) ->
                            FilterChip(sort == value, { sort = value }, label = { Text(label) }, modifier = Modifier.testTag("sort-$value"))
                        }
                    }
                }
                if (selecting) item {
                    Text("已选 ${state.selected.size} 个（可跨角色和世界）", Modifier.testTag("selection-count"), fontWeight = FontWeight.SemiBold)
                    TextButton({
                        val names = visible.map { it.name }.toSet()
                        actions.selectAll(if (state.selected.containsAll(names)) state.selected - names else state.selected + names)
                    }, enabled = ready && visible.isNotEmpty(), modifier = Modifier.testTag("select-all-saves")) {
                        Text(if (visible.isNotEmpty() && state.selected.containsAll(visible.map { it.name })) "取消当前结果全选" else "全选当前结果")
                    }
                }
                if (state.exportResult != null) item {
                    SaveCard("导出完成") {
                        Text(state.exportResult.name)
                        Text("已保存并检查内容。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(actions.openExport, modifier = Modifier.testTag("open-export")) { Text("查看导出文件") }
                    }
                }
                if (visible.isEmpty()) item {
                    SaveCard(if (query.isNotBlank()) "没有匹配的存档" else "存档列表") {
                        Text(when { !connected -> "连接 Shizuku 后查看游戏中的存档。"
                            !state.loaded || !state.hasTarget -> "先确认游戏版本和账号，再刷新存档。"
                            query.isNotBlank() -> "换一个文件名搜索，或清除搜索条件。"
                            state.edition == GameEdition.CHINESE && kind == "Players" -> "当前账号还没有角色存档。可导入国服手机文件；工具箱暂无国服内置角色。"
                            else -> "还没有${if (kind == "Players") "角色" else "世界"}存档，可以导入同服手机文件或内置存档。" })
                    }
                }
                items(visible, key = { it.name }) { save ->
                    OutlinedCard(onClick = { if (ready) { selecting = true; actions.toggle(save.name) } }, modifier = Modifier.fillMaxWidth().testTag("save-row-${save.name}")) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selecting) Checkbox(save.name in state.selected, { actions.toggle(save.name) }, enabled = ready, modifier = Modifier.testTag("save-${save.name}"))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(save.name, fontWeight = FontWeight.SemiBold)
                                Text("${formatSaveSize(save.size)} · ${formatTime(save.modified)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item { Text("文件名可能与游戏内名称不同。只管理 .plr / .wld 主文件，不含地图探索和自动备份。", style = MaterialTheme.typography.bodySmall) }
            } else {
                item {
                    val step = when (state.importStep) { ImportStep.SOURCE -> 1; ImportStep.CHECKING -> 2; ImportStep.TARGET -> 3; ImportStep.REVIEW -> 4; else -> 5 }
                    Text("第 $step / 5 步", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("选文件 → 检查 → 所属服与目标 → 确认 → 结果", style = MaterialTheme.typography.bodySmall)
                }
                when (state.importStep) {
                    ImportStep.SOURCE -> {
                        item {
                            SaveCard("选择存档来源") {
                                Button(actions.phone, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("import-phone")) { Text("从手机选择文件") }
                                Text("支持多选 .plr 角色、.wld 世界，或工具箱导出的 ZIP。", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton({
                                    if (!builtinsExpanded) {
                                        if (builtinEdition != state.edition.name) chosenBuiltins = emptyList()
                                        builtinEdition = state.edition.name
                                    }
                                    builtinsExpanded = !builtinsExpanded
                                }, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("show-builtins")) { Text(if (builtinsExpanded) "收起内置存档" else "浏览内置存档") }
                                Text("国际服与国服存档不通用。选文件后只可导入同服目标。")
                            }
                        }
                        if (builtinsExpanded) {
                            val edition = GameEdition.valueOf(builtinEdition)
                            val catalog = state.builtins.filter { it.edition == edition }
                            item { SaveCard("内置存档所属服") {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    GameEdition.entries.forEach { item -> FilterChip(edition == item, {
                                        builtinEdition = item.name; chosenBuiltins = emptyList()
                                    }, enabled = !locked, label = { Text(item.label) }, modifier = Modifier.testTag("builtin-edition-${item.code}")) }
                                }
                                Text("仅可导入${edition.label}；切换所属服会清空已选内置存档。", style = MaterialTheme.typography.bodySmall)
                                PrimaryTabRow(selectedTabIndex = if (builtinKind == "Players") 0 else 1) {
                                    SaveKind.entries.forEach { tab -> Tab(builtinKind == tab.directory, { builtinKind = tab.directory },
                                        modifier = Modifier.testTag("builtin-kind-${tab.directory}"),
                                        text = { Text("${tab.label}（${catalog.count { it.kind == tab }}）") }) }
                                }
                            } }
                            if (catalog.none { it.kind.directory == builtinKind }) item { SaveCard("暂无${edition.label}内置${if (builtinKind == "Players") "角色" else "世界"}") {
                                Text("可以从手机选择${edition.label}存档。已有游戏存档仍可在存档列表中管理。")
                                OutlinedButton(actions.phone, enabled = !locked, modifier = Modifier.testTag("builtin-empty-phone")) { Text("选择同服手机文件") }
                            } }
                            items(catalog.filter { it.kind.directory == builtinKind }, key = { it.asset }) { save ->
                                OutlinedCard(Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(12.dp)) {
                                        Checkbox(save.asset in chosenBuiltins, { chosenBuiltins = if (save.asset in chosenBuiltins) chosenBuiltins - save.asset else chosenBuiltins + save.asset },
                                            enabled = !locked, modifier = Modifier.testTag("builtin-${save.name}"))
                                        Column(Modifier.weight(1f).padding(top = 8.dp)) {
                                            Text(save.name, fontWeight = FontWeight.SemiBold)
                                            Text("${save.edition.label} · ${save.kind.label} · ${formatSaveSize(save.size)}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                            item { Button({ actions.builtins(chosenBuiltins.toSet()) }, enabled = !locked && chosenBuiltins.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("import-builtins")) { Text("检查选中的 ${chosenBuiltins.size} 个${edition.label}存档") } }
                        }
                    }
                    ImportStep.CHECKING, ImportStep.WRITING -> item {
                        SaveCard(if (state.importStep == ImportStep.CHECKING) "正在检查文件" else "正在导入") {
                            Text(state.message)
                            if (state.total > 0 && state.importStep == ImportStep.WRITING) {
                                LinearProgressIndicator(progress = { state.completed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                                Text("${state.completed} / ${state.total} 个")
                            } else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("请保持游戏关闭，完成后会显示逐项结果。")
                        }
                    }
                    ImportStep.TARGET -> {
                        item { Text("已检查 ${state.pending.size} 个存档。请确认所属服和导入位置。", fontWeight = FontWeight.SemiBold) }
                        item { SaveCard("存档所属服") {
                            Text(state.sourceEdition?.let { "来源：${it.label} · 仅可导入同服" } ?: "部分文件无法自动识别所属服", Modifier.testTag("import-source-edition"))
                            if (state.unknownSources > 0) {
                                Text("${state.unknownSources} 个文件需要声明来源。请按下载说明或原游戏确认；同一批只可属于一个服。")
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    GameEdition.entries.forEach { edition -> FilterChip(state.declaredEdition == edition,
                                        { actions.declareSource(edition) }, enabled = !locked && state.pending.none { it.edition != null && it.edition != edition },
                                        label = { Text("这是${edition.label}存档") }, modifier = Modifier.fillMaxWidth().testTag("declare-edition-${edition.code}")) }
                                }
                            }
                            Text("所属服一致仍需游戏版本支持。工具箱复制原文件，不转换格式。", style = MaterialTheme.typography.bodySmall)
                        } }
                        item { TargetCard(state, connected, !locked, actions, importing = true) }
                        item { Button(actions.review, enabled = ready && state.compatibleTarget, modifier = Modifier.fillMaxWidth().testTag("review-import")) { Text("下一步：确认清单") } }
                    }
                    ImportStep.REVIEW -> {
                        item { SaveCard("确认导入") {
                            Text("导入到：${state.pendingTarget?.label}", Modifier.testTag("import-target"), fontWeight = FontWeight.Bold)
                            Text("来源：${state.sourceEdition?.label} · 已限制同服目标", Modifier.testTag("review-source-edition"))
                            Text("同名自动编号，保留已有存档；游戏内名称可能仍相同。")
                            Text("检查不代表游戏版本兼容。工具箱复制原文件，不转换格式。", style = MaterialTheme.typography.bodySmall)
                            if (!connected) OutlinedButton(actions.goHome, modifier = Modifier.testTag("saves-go-connect")) { Text("修复 Shizuku 连接") }
                        } }
                        items(state.pending.withIndex().toList(), key = { "pending-${it.index}" }) { (_, save) -> Text("${save.kind.label} · ${save.name}\n${(save.edition ?: state.declaredEdition)?.label} · ${if (save.edition == null) SaveOrigin.USER.label else save.origin.label}") }
                        if (state.results.isNotEmpty()) item { Text("${state.results.size} 个文件检查失败，将跳过。返回重新选文件可重试。", color = MaterialTheme.colorScheme.error) }
                        item { Button(actions.confirm, enabled = ready && state.compatibleTarget && state.pendingTarget == state.target, modifier = Modifier.fillMaxWidth().testTag("confirm-save-import")) { Text("已退出游戏，开始导入") } }
                        item { TextButton({ actions.cancel(); page = "list" }, enabled = !locked) { Text("取消导入") } }
                    }
                    ImportStep.RESULT -> {
                        item { SaveCard("导入结果") {
                            Text(state.importSummary.ifEmpty { state.message }, Modifier.testTag("import-summary"))
                            Button({ page = "list"; query = ""; kind = if (state.results.any { it.savedAs?.endsWith(".plr", true) == true }) "Players" else "Worlds" }, modifier = Modifier.fillMaxWidth().testTag("view-imported-saves")) { Text("查看存档") }
                            OutlinedButton(::startImport, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("continue-import")) { Text("继续导入") }
                            if (!connected) TextButton(actions.goHome) { Text("修复连接后重试") }
                        } }
                        items(state.results.withIndex().toList(), key = { "result-${it.index}" }) { (_, result) -> SaveCard(result.original) {
                            Text(if (result.savedAs == null) "未完成：${result.error}" else if (result.original == result.savedAs) "已导入：${result.savedAs}" else "已自动重命名并导入：${result.savedAs}",
                                color = if (result.savedAs == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        } }
                    }
                }
            }
        }
        if (page == "list" && selecting) Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(actions.export, enabled = ready && state.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("export-saves")) { Text("导出已选 ${state.selected.size} 个存档") }
                if (state.exportMessage.isNotEmpty()) Text(state.exportMessage, Modifier.testTag("export-status"), style = MaterialTheme.typography.bodySmall)
                if (state.exportResult != null) TextButton(actions.openExport) { Text("查看导出文件") }
                Text("单个导出原文件，多个导出 ZIP；取消或失败保留选择。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TargetCard(state: SaveState, connected: Boolean, enabled: Boolean, actions: SaveActions, importing: Boolean = false) {
    SaveCard(if (importing) "同服导入目标与账号" else "游戏版本与账号") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GameEdition.entries.forEach { edition -> FilterChip(state.edition == edition, { actions.edition(edition) }, enabled = enabled && connected && (!importing || state.sourceEdition == edition),
                label = { Text(edition.label) }, modifier = Modifier.testTag("edition-${edition.code}")) }
        }
        if (importing) Text(if (state.sourceEdition == null) "先声明无法识别文件的所属服，再确认目标。" else "目标已绑定${state.sourceEdition?.label}；另一服不可用于本次导入。", style = MaterialTheme.typography.bodySmall)
        if (state.edition == GameEdition.CHINESE) {
            if (state.accounts.size == 1) Text("账号 ID：${state.account}（已自动选择）", Modifier.testTag("save-account"))
            else if (state.accounts.size > 1) {
                Text("选择要管理的账号；存档分别保存。")
                state.accounts.forEach { account -> OutlinedButton({ actions.account(account) }, enabled = enabled && connected,
                    modifier = Modifier.fillMaxWidth().testTag("account-$account")) { Text(if (state.account == account) "✓ 账号 $account" else "账号 $account") } }
            } else Text("没有找到账号时，先登录国服，再退出游戏并刷新。")
        }
        Text(state.message, Modifier.testTag("save-message"))
        if (state.busy || state.awaitingExport) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!connected) OutlinedButton(actions.goHome, modifier = Modifier.fillMaxWidth().testTag("saves-go-connect")) { Text("连接 Shizuku") }
        else TextButton(actions.refresh, enabled = enabled, modifier = Modifier.testTag("refresh-saves")) { Text("刷新存档") }
    }
}

@Composable
private fun SaveCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    } }
}
fun formatSaveSize(bytes: Long): String = if (bytes < 1024 * 1024) String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0) else String.format(Locale.CHINA, "%.2f MB", bytes / (1024.0 * 1024))
