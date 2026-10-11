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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.terrariatoolbox.data.*
import com.example.terrariatoolbox.domain.*
import com.example.terrariatoolbox.feature.saves.SaveScreen

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
        try {
            exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = spec.mime
                putExtra(Intent.EXTRA_TITLE, spec.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            })
        } catch (_: Exception) { model.pickerUnavailable() }
    }
    ValidationContent(state, model.environment, ValidationActions(
        connectShizuku = model::connectShizuku, guide = ::guide,
        openShizuku = {
            try { context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let(context::startActivity) ?: guide() }
            catch (_: Exception) { model.externalUnavailable() }
        },
        refresh = model::refresh, switch = model::switchToChinese, switchEnglish = model::switchToEnglish,
        saveBackup = model::saveOriginalBackup, restore = model::restore,
        exportBackup = { model.prepareBackupExport(it, ::export) },
        report = { model.prepareReport(it, ::export) }, verifyPending = model::verifyPending,
    ), initialPage = "home", saveBusy = saves.busy, saveContent = { home ->
        SaveScreen(model.saves, state.shizuku.phase == ShizukuPhase.CONNECTED, home)
    })
}

data class ValidationActions(
    val connectShizuku: () -> Unit = {}, val openShizuku: () -> Unit = {}, val guide: () -> Unit = {},
    val refresh: () -> Unit = {}, val switch: () -> Unit = {}, val switchEnglish: () -> Unit = {},
    val saveBackup: () -> Unit = {}, val restore: (BackupRecord) -> Unit = {},
    val exportBackup: (BackupRecord) -> Unit = {}, val report: (EnvironmentNotes) -> Unit = {},
    val verifyPending: () -> Unit = {},
)

@Composable
fun ValidationContent(state: ValidationState, environment: String, actions: ValidationActions,
    initialPage: String = "chinese", saveBusy: Boolean = false,
    saveContent: @Composable ((() -> Unit) -> Unit)? = null) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    var guideExpanded by rememberSaveable { mutableStateOf(state.shizuku.phase != ShizukuPhase.CONNECTED) }
    var target by remember { mutableStateOf<Language?>(null) }
    var restoreBackup by remember { mutableStateOf<BackupRecord?>(null) }
    var gameVersion by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.notes) { gameVersion = state.notes.gameVersion }
    val source = state.source
    val recovery = state.pending != null || state.pendingError != null
    val enabled = !state.busy && !saveBusy
    val connected = state.shizuku.phase == ShizukuPhase.CONNECTED
    val direct = connected && source?.accessMethod == AccessMethod.SHIZUKU && source.knownTarget && source.confirmed && source.writable
    val supported = state.config?.language in listOf(Language.ENGLISH, Language.CHINESE)
    fun canRestore(backup: BackupRecord): Boolean = direct && backup.workspaceId == null &&
        backup.source.identity == source?.identity && backup.source.accessMethod == AccessMethod.SHIZUKU &&
        state.pendingError == null && (state.pending == null ||
        (state.pending.source.identity == source?.identity && state.pending.source.accessMethod == AccessMethod.SHIZUKU))
    fun back() { page = if (page == "chinese" || page == "saves") "home" else "chinese" }
    BackHandler(page != "home") { if (!saveBusy) back() }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (page != "home") Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(::back, enabled = !saveBusy, modifier = Modifier.testTag("navigate-back")) { Text(if (page == "chinese" || page == "saves") "返回首页" else "返回工具") }
                if (page != "chinese") TextButton({ page = "home" }, enabled = !saveBusy, modifier = Modifier.testTag("navigate-home")) { Text("首页") }
            }
            if (page == "saves") {
                saveContent?.invoke { page = "home" }
            } else key(page) {
                LazyColumn(Modifier.fillMaxSize().testTag(if (page == "home") "home-list" else "validation-list"),
                    contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text(when (page) { "home" -> "泰拉瑞亚工具箱"; "backups" -> "恢复备份";
                            "help" -> "使用说明"; "diagnostics" -> "问题排查"; else -> "游戏语言" },
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        if (page == "home") Text("管理存档，轻松调整游戏设置", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    if (recovery) item {
                        RecoveryPanel(state, enabled && direct && state.pending?.source?.identity == source?.identity &&
                            state.pending?.source?.accessMethod == AccessMethod.SHIZUKU,
                            actions.verifyPending, { page = "backups" })
                    }
                    when (page) {
                        "home" -> {
                            item {
                                Section("连接 Shizuku") {
                                    Text(state.shizuku.message, Modifier.testTag("shizuku-status"))
                                    Text("Shizuku 帮助工具箱管理存档和保存游戏设置，安装后按下面的步骤启动即可。")
                                    val needsStart = state.shizuku.phase in listOf(ShizukuPhase.NOT_INSTALLED, ShizukuPhase.NOT_RUNNING)
                                    Button(if (needsStart) actions.openShizuku else actions.connectShizuku,
                                        enabled = enabled && state.shizuku.phase != ShizukuPhase.CONNECTING,
                                        modifier = Modifier.fillMaxWidth().testTag("connect-shizuku")) {
                                        Text(when (state.shizuku.phase) {
                                            ShizukuPhase.NOT_INSTALLED -> "安装 Shizuku"
                                            ShizukuPhase.NOT_RUNNING -> "打开 Shizuku"
                                            ShizukuPhase.CONNECTED -> "重新读取游戏设置"
                                            else -> "连接 Shizuku"
                                        })
                                    }
                                    if (!needsStart) OutlinedButton(actions.openShizuku, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                                        Text("打开 Shizuku")
                                    }
                                    TextButton({ guideExpanded = !guideExpanded }, modifier = Modifier.fillMaxWidth().testTag("toggle-shizuku-guide")) {
                                        Text(if (guideExpanded) "收起连接步骤" else "查看连接步骤")
                                    }
                                    if (guideExpanded) {
                                        Text("1. 安装并打开 Shizuku。\n2. 按 Shizuku 的提示，通过「无线调试」完成配对并启动。\n3. 回到工具箱，点击「连接 Shizuku」，在弹窗中允许授权。",
                                            Modifier.testTag("shizuku-guide"))
                                        Text("第一次连接前，先打开一次泰拉瑞亚，再完全退出游戏。手机重启后，需要重新启动 Shizuku。",
                                            style = MaterialTheme.typography.bodySmall)
                                        TextButton(actions.guide, modifier = Modifier.fillMaxWidth()) { Text("查看详细安装与启动教程") }
                                    }
                                }
                            }
                            item {
                                Card(onClick = { page = "saves" }, modifier = Modifier.fillMaxWidth().testTag("open-saves")) {
                                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("存档管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text("查看角色和世界，导入内置或手机存档，也可导出留存。支持国服和国际服。")
                                        Text("进入工具 →", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            item {
                                Card(onClick = { page = "chinese" }, modifier = Modifier.fillMaxWidth().testTag("open-chinese")) {
                                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("游戏语言", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text("切换到中文或英文，原来的设置会自动备份。")
                                        Text("进入工具 →", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            if (state.busy || source != null || state.message != "连接 Shizuku 后，即可切换游戏语言。") item { ResultPanel(state) }
                        }
                        "chinese" -> {
                            item { ResultPanel(state) }
                            item {
                                Section("切换游戏语言") {
                                    Text(if (source?.accessMethod == AccessMethod.SHIZUKU && connected)
                                        "当前语言：${state.config?.languageLabel ?: "暂时无法读取"}" else "还没有连接游戏",
                                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("current-language"))
                                    Text("只修改语言，音量和按键等设置会保持原样。切换完成后，重新打开游戏即可生效。")
                                    if (!direct) {
                                        Text(state.configError ?: if (connected) "无法保存游戏设置，请在首页重新连接后重试。" else "先在首页连接 Shizuku，再回来选择语言。")
                                        OutlinedButton({ page = "home" }, modifier = Modifier.fillMaxWidth().testTag("go-connect")) { Text("去首页连接") }
                                    } else state.configError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    Button({ target = Language.CHINESE }, enabled = enabled && direct && !recovery && supported && state.config?.language != Language.CHINESE,
                                        modifier = Modifier.fillMaxWidth().testTag("switch-chinese")) {
                                        Text(if (state.config?.language == Language.CHINESE) "当前为中文" else "切换到中文")
                                    }
                                    OutlinedButton({ target = Language.ENGLISH }, enabled = enabled && direct && !recovery && supported && state.config?.language != Language.ENGLISH,
                                        modifier = Modifier.fillMaxWidth().testTag("switch-english")) {
                                        Text(if (state.config?.language == Language.ENGLISH) "当前为英文" else "切换到英文")
                                    }
                                    TextButton(actions.refresh, enabled = enabled && connected, modifier = Modifier.fillMaxWidth()) { Text("刷新当前语言") }
                                }
                            }
                            item {
                                TextButton({ moreExpanded = !moreExpanded }, modifier = Modifier.fillMaxWidth().testTag("more-actions")) {
                                    Text(if (moreExpanded) "收起更多操作" else "更多操作")
                                }
                            }
                            if (moreExpanded) item {
                                Section("备份与帮助") {
                                    Text("每次切换都会自动备份。恢复备份会还原当时的全部设置，包括语言、音量和按键。")
                                    OutlinedButton({ page = "backups" }, modifier = Modifier.fillMaxWidth().testTag("open-backups")) { Text("恢复备份") }
                                    OutlinedButton(actions.saveBackup, enabled = enabled && direct, modifier = Modifier.fillMaxWidth()) { Text("备份当前设置") }
                                    TextButton({ page = "help" }, modifier = Modifier.fillMaxWidth()) { Text("使用说明") }
                                    TextButton({ page = "diagnostics" }, modifier = Modifier.fillMaxWidth().testTag("open-diagnostics")) { Text("问题排查") }
                                }
                            }
                        }
                        "backups" -> {
                            item { ResultPanel(state) }
                            item { Text("恢复会还原备份时的全部设置，包括语言、音量和按键。如果只想换语言，请返回「游戏语言」。") }
                            item { Text("备份保存在本机。卸载工具箱或清除数据会删除备份，可以先导出留存。", style = MaterialTheme.typography.bodySmall) }
                            if (state.backups.isEmpty()) item { Text("还没有备份，第一次切换语言后会自动生成。") }
                            items(state.backups, key = { it.id }) { backup ->
                                Section(formatTime(backup.createdAt)) {
                                    val language = when (backup.language) { "1" -> "英文"; "7" -> "中文"; else -> "未知" }
                                    Text("备份时的语言：$language", fontWeight = FontWeight.Bold)
                                    Text(backup.reason)
                                    if (backup.workspaceId != null || backup.source.accessMethod != AccessMethod.SHIZUKU)
                                        Text("这是旧版本的备份，可以导出保存。", style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton({ actions.exportBackup(backup) }, enabled = enabled,
                                        modifier = Modifier.fillMaxWidth().testTag("backup-export-${backup.id}")) { Text("导出备份") }
                                    OutlinedButton({ restoreBackup = backup }, enabled = enabled && canRestore(backup),
                                        modifier = Modifier.fillMaxWidth().testTag("backup-restore-${backup.id}")) { Text("恢复这份备份") }
                                    if (backup.workspaceId == null && backup.source.accessMethod == AccessMethod.SHIZUKU && !direct)
                                        Text("先回到首页连接 Shizuku，才能恢复到游戏。", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        "help" -> {
                            item { Section("如何切换语言") {
                                Text("1. 在首页连接 Shizuku。\n2. 完全退出泰拉瑞亚。\n3. 进入「游戏语言」，选择中文或英文。\n4. 看到切换完成的提示后，重新打开游戏。")
                                OutlinedButton({ page = "home" }, modifier = Modifier.fillMaxWidth()) { Text("回首页查看连接步骤") }
                            } }
                            item { Section("备份有什么用") {
                                Text("切换前会自动保存原来的设置。如果需要回到之前的状态，在「更多操作 → 恢复备份」中选择一份备份。恢复会同时还原语言、音量和按键等设置。")
                            } }
                            item { Section("操作被中断怎么办") {
                                Text("先不要打开游戏。回到首页重新连接 Shizuku，点击「检查上次操作」。如果检查未通过，可以恢复备份。")
                            } }
                        }
                        "diagnostics" -> {
                            item { ResultPanel(state) }
                            item { Section("遇到问题时") {
                                Text("先确认 Shizuku 已启动，并已允许工具箱使用。没有找到游戏设置时，先打开一次游戏，再完全退出并重新连接。")
                                OutlinedTextField(gameVersion, { gameVersion = it.take(100) }, enabled = enabled,
                                    label = { Text("游戏版本（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedButton({ actions.report(EnvironmentNotes(gameVersion, state.notes.mtVersion)) }, enabled = enabled,
                                    modifier = Modifier.fillMaxWidth()) { Text("导出问题记录") }
                                Text("记录只保存设备和操作信息，不包含游戏设置内容。", style = MaterialTheme.typography.bodySmall)
                            } }
                            item { Section("技术信息") {
                                Text(environment, style = MaterialTheme.typography.bodySmall)
                                Text("Shizuku ${state.shizuku.installedVersion ?: "未安装"} · API ${state.shizuku.apiVersion ?: "未知"} · UID ${state.shizuku.uid ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                                Text("游戏配置：$TARGET_PATH", style = MaterialTheme.typography.bodySmall)
                                source?.let {
                                    Text("来源：${it.accessMethod} · ${it.authority}\n${it.uri}", style = MaterialTheme.typography.bodySmall)
                                    Text("语言编号：${state.config?.languageNumber ?: "未读取"}", style = MaterialTheme.typography.bodySmall)
                                    it.lastVerifiedAt?.let { time -> Text("最近保存检查：${formatTime(time)}", style = MaterialTheme.typography.bodySmall) }
                                }
                            } }
                            item { Section("最近操作") {
                                if (state.events.isEmpty()) Text("还没有操作记录")
                                state.events.takeLast(10).reversed().forEach { Text(it.substringAfter(" | "), style = MaterialTheme.typography.bodySmall) }
                            } }
                        }
                    }
                }
            }
        }
    }
    target?.let { language ->
        val label = if (language == Language.CHINESE) "中文" else "英文"
        ConfirmDialog("切换到$label", "请先完全退出泰拉瑞亚。工具箱会自动备份原来的设置，再切换语言。",
            "已退出游戏，切换到$label", { target = null }, enabled && direct && !recovery && supported && state.config?.language != language) {
            target = null
            if (language == Language.CHINESE) actions.switch() else actions.switchEnglish()
        }
    }
    restoreBackup?.let { backup ->
        ConfirmDialog("恢复这份备份？", "备份时间：${formatTime(backup.createdAt)}\n\n语言、音量和按键等设置都会还原到当时的状态。请先完全退出游戏；恢复前也会备份当前设置。",
            "已退出游戏，恢复备份", { restoreBackup = null }, enabled && canRestore(backup)) {
            restoreBackup = null
            actions.restore(backup)
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
    Card(Modifier.fillMaxWidth().testTag("recovery-warning"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("上次操作需要检查", fontWeight = FontWeight.Bold)
            Text("先不要打开游戏。请在首页重新连接 Shizuku，检查上次操作；如果检查未通过，可以恢复备份。")
            state.pendingError?.let { Text(it) }
            if (state.pending?.source?.accessMethod == AccessMethod.SAF)
                Text("这是旧版本留下的操作，可先导出对应备份，再按原来的方式恢复游戏设置。")
            OutlinedButton(verify, enabled = verifyEnabled && state.pending != null && state.pendingError == null,
                modifier = Modifier.fillMaxWidth()) { Text("检查上次操作") }
            OutlinedButton(backups, modifier = Modifier.fillMaxWidth()) { Text("查看可用备份") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, label: String, dismiss: () -> Unit, enabled: Boolean, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = { dismiss() }, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(confirm, enabled = enabled) { Text(label) } },
        dismissButton = { TextButton(dismiss) { Text("取消") } })
}
