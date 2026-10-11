package com.example.terrariatoolbox.feature.chinese

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.terrariatoolbox.data.*
import com.example.terrariatoolbox.domain.*
import com.example.terrariatoolbox.feature.saves.*
import com.example.terrariatoolbox.ui.ToolboxIcons

@Composable
fun ValidationScreen(model: ValidationViewModel) {
    val state by model.state.collectAsState()
    val saves by model.saves.state.collectAsState()
    val context = LocalContext.current
    fun guide() {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_GUIDE))) }
        catch (_: Exception) { model.externalUnavailable() }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.exported(if (it.resultCode == Activity.RESULT_OK) it.data?.data else null)
    }
    fun export(spec: ExportSpec) {
        try { exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = spec.mime; putExtra(Intent.EXTRA_TITLE, spec.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }) } catch (_: Exception) { model.pickerUnavailable() }
    }
    ValidationContent(state, model.environment, ValidationActions(
        connectShizuku = model::connectShizuku, guide = ::guide,
        openShizuku = {
            try { context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let(context::startActivity) ?: guide() }
            catch (_: Exception) { model.externalUnavailable() }
        },
        refresh = model::refresh, switch = model::switchToChinese, switchEnglish = model::switchToEnglish,
        restore = model::restore, exportBackup = { model.prepareBackupExport(it, ::export) },
        report = { model.prepareReport(it, ::export) }, verifyPending = model::verifyPending,
    ), initialPage = "home", saveBusy = saves.busy || saves.awaitingExport, saveState = saves,
        saveContent = { entry, consume, connect -> SaveScreen(model.saves, state.shizuku.phase == ShizukuPhase.CONNECTED, connect, entry, state.busy, consume) })
}

data class ValidationActions(
    val connectShizuku: () -> Unit = {}, val openShizuku: () -> Unit = {}, val guide: () -> Unit = {},
    val refresh: () -> Unit = {}, val switch: () -> Unit = {}, val switchEnglish: () -> Unit = {},
    val restore: (BackupRecord) -> Unit = {}, val exportBackup: (BackupRecord) -> Unit = {},
    val report: (EnvironmentNotes) -> Unit = {}, val verifyPending: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValidationContent(state: ValidationState, environment: String, actions: ValidationActions,
    initialPage: String = "chinese", saveBusy: Boolean = false, saveState: SaveState = SaveState(),
    saveContent: @Composable ((String, () -> Unit, () -> Unit) -> Unit)? = null) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var parent by rememberSaveable { mutableStateOf("home") }
    var backStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var saveEntry by rememberSaveable { mutableStateOf("list") }
    var guideExpanded by rememberSaveable { mutableStateOf(false) }
    var target by remember { mutableStateOf<Language?>(null) }
    var restoreBackup by remember { mutableStateOf<BackupRecord?>(null) }
    var gameVersion by rememberSaveable { mutableStateOf("") }
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(state.notes) { gameVersion = state.notes.gameVersion }
    val source = state.source
    val recovery = state.pending != null || state.pendingError != null
    val enabled = !state.busy && !saveBusy
    val connected = state.shizuku.phase == ShizukuPhase.CONNECTED
    val direct = connected && source?.accessMethod == AccessMethod.SHIZUKU && source.knownTarget && source.confirmed && source.writable
    val supported = state.config?.language in listOf(Language.ENGLISH, Language.CHINESE)
    fun canRestore(backup: BackupRecord): Boolean = recovery && direct && backup.workspaceId == null &&
        backup.source.identity == source?.identity && backup.source.accessMethod == AccessMethod.SHIZUKU &&
        state.pendingError == null && (state.pending == null ||
        (state.pending.source.identity == source?.identity && state.pending.source.accessMethod == AccessMethod.SHIZUKU))
    fun auxiliary(destination: String) {
        if (page in listOf("home", "saves", "chinese")) parent = page
        backStack = backStack + page
        page = destination
    }
    fun back() {
        if (backStack.isNotEmpty()) { page = backStack.last(); backStack = backStack.dropLast(1) }
        else page = "home"
    }
    BackHandler(page != "home") { if (!saveBusy) back() }
    val section = if (page in listOf("home", "saves", "chinese")) page else parent
    Scaffold(
        topBar = { TopAppBar(title = { Text(when (page) {
            "home" -> "泰拉瑞亚工具箱"; "saves" -> "存档"; "settings" -> "设置与帮助"
            "help" -> "使用说明"; "diagnostics" -> "问题排查"; "backups" -> "异常恢复"; else -> "游戏语言"
        }) }, navigationIcon = {
            if (page !in listOf("home", "saves")) TextButton(::back, enabled = !saveBusy, modifier = Modifier.testTag("navigate-back")) { Text("返回") }
        }, actions = {
            if (page in listOf("home", "saves", "chinese")) TextButton({ auxiliary("settings") }, enabled = !saveBusy, modifier = Modifier.testTag("open-settings")) { Text("帮助") }
        }) },
        bottomBar = { NavigationBar {
            listOf(Triple("home", "首页", ToolboxIcons.Home), Triple("saves", "存档", ToolboxIcons.Saves), Triple("chinese", "工具", ToolboxIcons.Tools)).forEach { (route, label, icon) ->
                NavigationBarItem(selected = section == route, enabled = !saveBusy, onClick = {
                    if (route == "saves") saveEntry = "list"
                    backStack = emptyList()
                    page = route
                }, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) }, modifier = Modifier.testTag("nav-$route"))
            }
        } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (recovery) RecoveryPanel(state, enabled && direct && state.pending?.source?.identity == source?.identity &&
                state.pending?.source?.accessMethod == AccessMethod.SHIZUKU, actions.verifyPending, { auxiliary("backups") })
            holder.SaveableStateProvider(page) {
                if (page == "saves") saveContent?.invoke(saveEntry, { saveEntry = "auto" }, { auxiliary("settings") })
                else LazyColumn(Modifier.fillMaxSize().testTag(if (page == "home") "home-list" else "validation-list"),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (page) {
                        "home" -> {
                            item { Text("管理存档，轻松调整游戏设置", style = MaterialTheme.typography.bodyLarge) }
                            item { ConnectionCard(state, enabled, actions, guideExpanded) { guideExpanded = !guideExpanded } }
                            item { Section("常用操作") {
                                Button({ saveEntry = "import"; page = "saves" }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("home-import")) { Text("导入存档 · 文件 → 游戏") }
                                OutlinedButton({ saveEntry = "export"; page = "saves" }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("home-export")) { Text("导出存档 · 游戏 → 文件") }
                                TextButton({ saveEntry = "list"; page = "saves" }, modifier = Modifier.fillMaxWidth().testTag("open-saves")) { Text("查看角色与世界") }
                            } }
                            item { Card(onClick = { page = "chinese" }, modifier = Modifier.fillMaxWidth().testTag("open-chinese")) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("游戏语言", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("国际服 · ${state.config?.languageLabel ?: "尚未读取"}")
                                    Text("修改中文 / 英文 →", color = MaterialTheme.colorScheme.primary)
                                }
                            } }
                            if (state.busy || source != null || state.message != "连接 Shizuku 后，即可切换游戏语言。") item { ResultPanel(state) }
                            item { Section("最近操作") {
                                val entries = (saveState.recent.map { it.time to it.message } + state.events.mapNotNull {
                                    val time = it.substringBefore(" | ").toLongOrNull()
                                    time?.let { stamp -> stamp to it.substringAfter(" | ") }
                                }).sortedByDescending { it.first }.take(3)
                                if (entries.isEmpty()) Text("完成导入、导出或语言切换后，这里会显示实际记录。")
                                entries.forEach { (time, message) -> Text(message); Text(formatTime(time), style = MaterialTheme.typography.bodySmall) }
                                if (saveState.message.isNotBlank() && saveState.recent.isNotEmpty()) Text(saveState.message, style = MaterialTheme.typography.bodySmall)
                            } }
                        }
                        "chinese" -> {
                            item { Section("游戏语言 · 仅国际服") {
                                Text(if (source?.accessMethod == AccessMethod.SHIZUKU && connected) "当前语言：${state.config?.languageLabel ?: "暂时无法读取"}" else "当前语言：尚未读取",
                                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("current-language"))
                                Text("仅修改国际服语言，音量和按键保持原样。切换完成后重新打开游戏。")
                                if (!direct) {
                                    Text(state.configError ?: if (connected) "没有读取到可写的国际服设置。先打开一次国际服，退出后重新读取。" else "先连接 Shizuku，再选择语言。")
                                    OutlinedButton({ auxiliary("settings") }, modifier = Modifier.fillMaxWidth().testTag("go-connect")) { Text("连接与问题排查") }
                                } else state.configError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Button({ target = Language.CHINESE }, enabled = enabled && direct && !recovery && supported && state.config?.language != Language.CHINESE,
                                    modifier = Modifier.fillMaxWidth().testTag("switch-chinese")) { Text(if (state.config?.language == Language.CHINESE) "当前为中文" else "切换到中文") }
                                OutlinedButton({ target = Language.ENGLISH }, enabled = enabled && direct && !recovery && supported && state.config?.language != Language.ENGLISH,
                                    modifier = Modifier.fillMaxWidth().testTag("switch-english")) { Text(if (state.config?.language == Language.ENGLISH) "当前为英文" else "切换到英文") }
                                TextButton(actions.refresh, enabled = enabled && connected, modifier = Modifier.fillMaxWidth()) { Text("刷新当前语言") }
                            } }
                            item { ResultPanel(state) }
                        }
                        "settings" -> {
                            item { ConnectionCard(state, enabled, actions, true) {} }
                            item { Section("帮助与排查") {
                                OutlinedButton({ auxiliary("help") }, modifier = Modifier.fillMaxWidth().testTag("open-help")) { Text("使用说明") }
                                OutlinedButton({ auxiliary("diagnostics") }, modifier = Modifier.fillMaxWidth().testTag("open-diagnostics")) { Text("问题排查") }
                                Text("工具箱会记住上次的存档版本与账号；连接后重新核实。", style = MaterialTheme.typography.bodySmall)
                            } }
                        }
                        "backups" -> {
                            if (!recovery) item { Text("当前没有需要恢复的异常操作。") }
                            else {
                                item { Text("仅用于处理未完成的设置写入。恢复会还原语言、音量和按键，请先退出游戏。") }
                                items(state.backups.filter { backup ->
                                    backup.id == state.pending?.backupId || (state.pending == null && backup.source.identity == source?.identity)
                                }, key = { it.id }) { backup -> Section(formatTime(backup.createdAt)) {
                                    Text(backup.reason)
                                    OutlinedButton({ restoreBackup = backup }, enabled = enabled && canRestore(backup), modifier = Modifier.fillMaxWidth().testTag("backup-restore-${backup.id}")) { Text("恢复此次操作前的设置") }
                                    // Old interrupted SAF operations still need an export route to recover outside the app.
                                    if (backup.source.accessMethod != AccessMethod.SHIZUKU || !direct || state.pendingError != null) OutlinedButton({ actions.exportBackup(backup) }, enabled = enabled,
                                        modifier = Modifier.testTag("backup-export-${backup.id}")) { Text("导出用于异常恢复") }
                                } }
                            }
                        }
                        "help" -> {
                            item { Section("导入存档") { Text("选择手机文件或内置存档 → 检查 → 确认游戏版本与账号 → 确认退出游戏 → 导入结果。重名时自动编号，保留已有存档。检查不保证游戏版本兼容。") } }
                            item { Section("导出存档") { Text("在存档页点击「选择并导出」，可跨角色、世界勾选。单个保存原文件，多个保存 ZIP。取消或失败后选择会保留。") } }
                            item { Section("切换语言") { Text("先连接 Shizuku，完全退出国际服，在「工具」中切换中文或英文。看到完成提示后重新打开游戏。国服不使用此语言工具。") } }
                            item { Section("操作中断") { Text("设置写入异常时会出现检查与恢复入口。存档导入若结果未确认，请重新连接并刷新检查后再试。") } }
                        }
                        "diagnostics" -> {
                            item { ResultPanel(state) }
                            item { Section("遇到问题时") {
                                Text("先确认 Shizuku 已启动，并已允许工具箱使用。没有游戏设置时，先打开一次国际服，再退出并重新读取。")
                                OutlinedTextField(gameVersion, { gameVersion = it.take(100) }, enabled = enabled, label = { Text("游戏版本（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedButton({ actions.report(EnvironmentNotes(gameVersion, state.notes.mtVersion)) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("导出问题记录") }
                                Text("记录不包含游戏设置内容。", style = MaterialTheme.typography.bodySmall)
                            } }
                            item { Section("技术信息") {
                                Text(environment, style = MaterialTheme.typography.bodySmall)
                                Text("Shizuku ${state.shizuku.installedVersion ?: "未安装"} · API ${state.shizuku.apiVersion ?: "未知"} · UID ${state.shizuku.uid ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                                Text("国际服配置：$TARGET_PATH", style = MaterialTheme.typography.bodySmall)
                                source?.let { Text("来源：${it.accessMethod} · ${it.authority}\n${it.uri}", style = MaterialTheme.typography.bodySmall) }
                            } }
                            item { Section("最近操作") {
                                state.events.takeLast(10).reversed().forEach { Text(it.substringAfter(" | "), style = MaterialTheme.typography.bodySmall) }
                                saveState.recent.asReversed().take(10).forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                            } }
                        }
                    }
                }
            }
        }
    }
    target?.let { language ->
        val label = if (language == Language.CHINESE) "中文" else "英文"
        ConfirmDialog("切换到$label", "请先完全退出国际服泰拉瑞亚。工具箱只修改语言，完成后重新打开游戏。",
            "已退出游戏，切换到$label", { target = null }, enabled && direct && !recovery && supported && state.config?.language != language) {
            target = null
            if (language == Language.CHINESE) actions.switch() else actions.switchEnglish()
        }
    }
    restoreBackup?.let { backup ->
        ConfirmDialog("恢复此次操作前的设置？", "语言、音量和按键等设置都会还原到 ${formatTime(backup.createdAt)} 的状态。请先完全退出游戏。",
            "已退出游戏，恢复备份", { restoreBackup = null }, enabled && canRestore(backup)) { restoreBackup = null; actions.restore(backup) }
    }
}

@Composable
private fun ConnectionCard(state: ValidationState, enabled: Boolean, actions: ValidationActions, expanded: Boolean, toggle: () -> Unit) {
    Section("存档访问 · Shizuku") {
        Text(state.shizuku.message, Modifier.testTag("shizuku-status"))
        val needsStart = state.shizuku.phase in listOf(ShizukuPhase.NOT_INSTALLED, ShizukuPhase.NOT_RUNNING)
        Button(if (needsStart) actions.openShizuku else actions.connectShizuku,
            enabled = enabled && state.shizuku.phase != ShizukuPhase.CONNECTING, modifier = Modifier.fillMaxWidth().testTag("connect-shizuku")) {
            Text(when (state.shizuku.phase) { ShizukuPhase.NOT_INSTALLED -> "安装 Shizuku"; ShizukuPhase.NOT_RUNNING -> "打开 Shizuku"; ShizukuPhase.CONNECTED -> "重新读取游戏设置"; else -> "连接 Shizuku" })
        }
        TextButton(toggle, modifier = Modifier.fillMaxWidth().testTag("toggle-shizuku-guide")) { Text(if (expanded) "连接步骤" else "查看连接步骤") }
        if (expanded) {
            Text("1. 安装并打开 Shizuku。\n2. 通过「无线调试」完成配对并启动。\n3. 回到工具箱连接，并在弹窗中允许授权。", Modifier.testTag("shizuku-guide"))
            Text("手机重启后需重新启动 Shizuku。首次使用前，先打开一次对应游戏，再完全退出。", style = MaterialTheme.typography.bodySmall)
            TextButton(actions.guide, modifier = Modifier.fillMaxWidth()) { Text("详细安装与启动教程") }
        }
    }
}

@Composable
private fun ResultPanel(state: ValidationState) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.busy) "正在处理" else "操作提示", fontWeight = FontWeight.Bold)
            Text(state.message, Modifier.testTag("operation-result"))
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RecoveryPanel(state: ValidationState, verifyEnabled: Boolean, verify: () -> Unit, backups: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp).testTag("recovery-warning"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("上次设置操作需要检查", fontWeight = FontWeight.Bold)
            Text("先不要打开游戏。重新连接后检查；检查未通过时恢复此次操作前的设置。")
            state.pendingError?.let { Text(it) }
            Row {
                TextButton(verify, enabled = verifyEnabled && state.pending != null && state.pendingError == null) { Text("检查上次操作") }
                TextButton(backups, modifier = Modifier.testTag("open-recovery")) { Text("异常恢复") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    } }
}

@Composable
private fun ConfirmDialog(title: String, text: String, label: String, dismiss: () -> Unit, enabled: Boolean, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(confirm, enabled = enabled) { Text(label) } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}
