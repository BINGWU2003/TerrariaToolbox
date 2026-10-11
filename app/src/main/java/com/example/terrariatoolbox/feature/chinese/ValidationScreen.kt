package com.example.terrariatoolbox.feature.chinese

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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

@Composable
fun ValidationScreen(model: ValidationViewModel) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    fun guide() { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_GUIDE))) } catch (_: Exception) { model.externalUnavailable() } }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.selected(if (it.resultCode == Activity.RESULT_OK) it.data?.data else null, it.data?.flags ?: 0, true)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.selected(if (it.resultCode == Activity.RESULT_OK) it.data?.data else null, it.data?.flags ?: 0, false)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.importSelected(if (it.resultCode == Activity.RESULT_OK) it.data?.data else null, it.data?.flags ?: 0)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.exported(if (it.resultCode == Activity.RESULT_OK) it.data?.data else null)
    }
    fun export(spec: ExportSpec) {
        try {
            exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = spec.mime
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
        directory = { try { directoryPicker.launch(selectionIntent(true)) } catch (_: Exception) { model.pickerUnavailable() } },
        file = { try { filePicker.launch(selectionIntent(false)) } catch (_: Exception) { model.pickerUnavailable() } },
        importCopy = {
            try { importPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }) } catch (_: Exception) { model.pickerUnavailable() }
        },
        confirm = model::confirmSource, refresh = model::refresh, switch = model::switchToChinese,
        saveBackup = model::saveOriginalBackup, restore = model::restore,
        exportBackup = { model.prepareBackupExport(it, ::export) },
        report = { model.prepareReport(it, ::export) }, verifyPending = model::verifyPending,
        importLegacy = model::importLegacySource, confirmImport = model::confirmImport, cancelImport = model::cancelImport,
        exportCopy = { model.prepareCopyExport(::export) }, saveCopyBackup = model::saveCopyBackup, restoreCopy = model::restoreCopy,
    ), initialPage = "home")
}

private fun selectionIntent(directory: Boolean) = Intent(
    if (directory) Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_OPEN_DOCUMENT
).apply {
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    if (!directory) { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
    putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
        if (directory) TARGET_DOCUMENT_ID.substringBeforeLast('/') else TARGET_DOCUMENT_ID))
}

data class ValidationActions(
    val connectShizuku: () -> Unit = {}, val openShizuku: () -> Unit = {}, val guide: () -> Unit = {},
    val directory: () -> Unit = {}, val file: () -> Unit = {}, val confirm: () -> Unit = {},
    val refresh: () -> Unit = {}, val switch: () -> Unit = {}, val saveBackup: () -> Unit = {},
    val restore: (BackupRecord) -> Unit = {}, val exportBackup: (BackupRecord) -> Unit = {},
    val report: (EnvironmentNotes) -> Unit = {}, val verifyPending: () -> Unit = {},
    val importCopy: () -> Unit = {}, val importLegacy: () -> Unit = {},
    val confirmImport: () -> Unit = {}, val cancelImport: () -> Unit = {},
    val exportCopy: () -> Unit = {}, val saveCopyBackup: () -> Unit = {}, val restoreCopy: (BackupRecord) -> Unit = {},
)

@Composable
fun ValidationContent(state: ValidationState, environment: String, actions: ValidationActions,
    initialPage: String = "chinese") {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var mode by rememberSaveable { mutableStateOf("direct") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var restoreBackup by remember { mutableStateOf<BackupRecord?>(null) }
    var gameVersion by rememberSaveable { mutableStateOf("") }
    var mtVersion by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.notes) { gameVersion = state.notes.gameVersion; mtVersion = state.notes.mtVersion }
    val source = state.source
    val recovery = state.pending != null || state.pendingError != null
    val enabled = !state.busy
    val accessReady = source?.accessMethod != AccessMethod.SHIZUKU || state.shizuku.phase == ShizukuPhase.CONNECTED
    val direct = source?.knownTarget == true && source.confirmed && source.writable && accessReady
    fun back() { page = if (page == "chinese") "home" else "chinese" }
    BackHandler(page != "home") { back() }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (page != "home") Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(::back, Modifier.testTag("navigate-back")) { Text(if (page == "chinese") "返回首页" else "返回工具") }
                TextButton({ page = "home" }, Modifier.testTag("navigate-home")) { Text("首页") }
            }
            key(page, mode) {
                LazyColumn(Modifier.fillMaxSize().testTag(if (page == "home") "home-list" else "validation-list"),
                    contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text(when (page) { "home" -> "泰拉瑞亚工具箱"; "backups" -> "备份历史"; "help" -> "使用说明";
                            "diagnostics" -> "诊断与记录"; else -> "中文切换" }, style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                        if (page == "home") Text("1.2 · 轻量离线工具", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    if (recovery) item {
                        RecoveryPanel(state, enabled && accessReady && source?.confirmed == true && source.knownTarget,
                            actions.verifyPending, { page = "backups" })
                    }
                    if (page == "home") {
                        item {
                            Card(onClick = { page = "chinese" }, modifier = Modifier.fillMaxWidth().testTag("open-chinese")) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("中文切换", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("备份游戏配置后切换中文，或导入副本生成并导出。")
                                    Text("进入工具 →", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        item { Text("直接切换可使用 Shizuku；导入导出无需启动服务。") }
                        if (state.busy) item { ResultPanel(state) }
                    } else {
                        item { ResultPanel(state) }
                        when (page) {
                            "chinese" -> {
                                item {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(mode == "direct", { mode = "direct" }, { Text("直接切换") }, Modifier.testTag("mode-direct"))
                                        FilterChip(mode == "copy", { mode = "copy" }, { Text("导入副本") }, Modifier.testTag("mode-copy"))
                                    }
                                }
                                if (mode == "direct") {
                                    item {
                                        Section("游戏原配置") {
                                            Text(TARGET_PATH, style = MaterialTheme.typography.bodySmall)
                                            if (source != null) {
                                                Text(state.config?.languageLabel ?: state.configError ?: "尚未识别语言", fontWeight = FontWeight.Bold)
                                                Text("访问方式：${source.accessMethod}")
                                                Text("提供方：${source.authority}", style = MaterialTheme.typography.bodySmall)
                                                Text(if (source.accessMethod == AccessMethod.SHIZUKU) "实际路径：${source.documentId}"
                                                    else "实际 URI：${source.uri}", style = MaterialTheme.typography.bodySmall)
                                                Text(if (source.accessMethod == AccessMethod.SHIZUKU) "手机重启后需重新启动 Shizuku 并检查授权。"
                                                    else if (source.persisted) "已持久保存读取授权；实际访问仍需检查"
                                                    else "授权未持久保存，重启后可能需要重新授权")
                                                Text(if (direct) "当前可尝试写入，操作会自动备份并回读验证。" else "当前无法直接写回，请检查授权或导入副本。")
                                                if (!source.knownTarget) Text("来源无法确认为游戏原配置，仅可读取、备份或导入副本。")
                                                if (source.knownTarget && !source.confirmed) OutlinedButton({ dialog = "source" }, enabled = enabled) { Text("确认这是游戏原配置") }
                                                source.lastVerifiedAt?.let { Text("历史写回回读通过：${formatTime(it)}；游戏生效需实际确认。", style = MaterialTheme.typography.bodySmall) }
                                                state.configError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                            } else Text("先连接并授权游戏配置，或导入可访问的副本。")
                                            Button({ dialog = "switch" }, enabled = enabled && direct && !recovery && state.config?.language == Language.ENGLISH,
                                                modifier = Modifier.fillMaxWidth().testTag("switch-chinese")) {
                                                Text(if (state.config?.language == Language.CHINESE) "配置已设为中文" else "切换中文")
                                            }
                                            if (source != null) {
                                                OutlinedButton(actions.refresh, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("刷新配置") }
                                                OutlinedButton(actions.saveBackup, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("保存原始备份") }
                                                if (!source.knownTarget || !source.writable) OutlinedButton({ mode = "copy"; actions.importLegacy() }, enabled = enabled,
                                                    modifier = Modifier.fillMaxWidth().testTag("import-legacy")) { Text("导入为副本") }
                                            }
                                        }
                                    }
                                    item {
                                        Section("连接与授权") {
                                            Text(state.shizuku.message, Modifier.testTag("shizuku-status"))
                                            Button(actions.connectShizuku, enabled = enabled && state.shizuku.phase != ShizukuPhase.CONNECTING,
                                                modifier = Modifier.fillMaxWidth().testTag("connect-shizuku")) { Text("连接 Shizuku 并读取配置") }
                                            OutlinedButton(actions.openShizuku, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("打开 Shizuku") }
                                            OutlinedButton({ page = "help" }, modifier = Modifier.fillMaxWidth()) { Text("安装与启动说明") }
                                            Text("其他授权方式：系统可能限制 Android/data，MT 的权限不会授予工具箱。", style = MaterialTheme.typography.bodySmall)
                                            OutlinedButton(actions.directory, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("select-directory")) { Text("授权配置目录") }
                                            OutlinedButton(actions.file, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("select-file")) { Text("选择配置文件") }
                                        }
                                    }
                                } else {
                                    item {
                                        Section("编辑副本") {
                                            Text("只在应用内编辑，导入源文件不会被修改。导出后需手动替换游戏配置。")
                                            state.workspace?.let { copy ->
                                                Text(state.copyConfig?.languageLabel ?: state.copyError ?: "副本读取未完成", fontWeight = FontWeight.Bold)
                                                Text("导入名称：${copy.source.displayName}")
                                                Text("导入来源：${copy.source.uri}", style = MaterialTheme.typography.bodySmall)
                                                Text("${copy.size} 字节 · ${formatTime(copy.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                                            }
                                            state.copyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                            Button(actions.exportCopy, enabled = enabled && state.copyConfig?.language in listOf(Language.ENGLISH, Language.CHINESE),
                                                modifier = Modifier.fillMaxWidth().testTag("export-copy")) {
                                                Text(if (state.copyConfig?.language == Language.CHINESE) "导出当前中文副本" else "生成中文并导出")
                                            }
                                            OutlinedButton(actions.importCopy, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("import-copy")) {
                                                Text(if (state.workspace == null) "导入配置副本" else "重新导入配置")
                                            }
                                            if (state.workspace != null) OutlinedButton(actions.saveCopyBackup, enabled = enabled && state.copyConfig != null,
                                                modifier = Modifier.fillMaxWidth()) { Text("备份编辑副本") }
                                        }
                                    }
                                }
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton({ page = "backups" }, modifier = Modifier.fillMaxWidth().testTag("open-backups")) { Text("备份历史") }
                                        TextButton({ page = "help" }, Modifier.fillMaxWidth()) { Text("使用说明") }
                                        TextButton({ page = "diagnostics" }, Modifier.fillMaxWidth().testTag("open-diagnostics")) { Text("诊断与记录") }
                                    }
                                }
                            }
                            "backups" -> {
                                item { Text("完整恢复会还原音量、按键等全部设置。卸载或清除数据会丢失内部备份，请及时导出。") }
                                if (state.backups.isEmpty()) item { Text("尚无备份") }
                                items(state.backups, key = { it.id }) { backup ->
                                    Section("${formatTime(backup.createdAt)} · ${backup.reason}") {
                                        Text(if (backup.workspaceId != null) "编辑副本备份" else "外部配置备份", fontWeight = FontWeight.Bold)
                                        Text("${backup.id.take(8)} · ${backup.size} 字节 · 原始语言 ${backup.language ?: "无法识别"}")
                                        Text("来源：${backup.source.authority}", style = MaterialTheme.typography.bodySmall)
                                        Text("来源 URI：${backup.source.uri}", style = MaterialTheme.typography.bodySmall)
                                        Text("SHA-256：${backup.hash}", style = MaterialTheme.typography.bodySmall)
                                        OutlinedButton({ actions.exportBackup(backup) }, enabled = enabled,
                                            modifier = Modifier.fillMaxWidth().testTag("backup-export-${backup.id}")) { Text("导出原始备份") }
                                        val canRestore = if (backup.workspaceId != null) backup.workspaceId == state.workspace?.id
                                            else direct && state.pendingError == null && backup.source.identity == source?.identity &&
                                                backup.source.accessMethod == source.accessMethod &&
                                                (state.pending == null || (state.pending.source.identity == source.identity && state.pending.source.accessMethod == source.accessMethod))
                                        OutlinedButton({ restoreBackup = backup }, enabled = enabled && canRestore,
                                            modifier = Modifier.fillMaxWidth().testTag("backup-restore-${backup.id}")) {
                                            Text(if (backup.workspaceId != null) "恢复编辑副本" else "完整恢复")
                                        }
                                        if (!canRestore) Text("当前来源不匹配或无法写回，可导出备份。", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            "help" -> {
                                item { Section("直接切换") {
                                    Text("通过 Shizuku 的无线调试模式授权工具箱，不需要 Root 或连接电脑；仍依赖调试服务。仅支持主用户、shell UID 2000、API 13+ 和固定游戏包。")
                                    OutlinedButton(actions.guide, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("查看 Shizuku 官方说明") }
                                    Text("先运行游戏生成配置，再完全退出。连接并确认来源后切换；工具箱自动备份并回读，完成后启动游戏确认中文。手机重启后需重新启动 Shizuku。")
                                } }
                                item { Section("导入与手动替换") {
                                    Text("1. 用 MT 将 config.json 复制到可访问位置。\n2. 在导入副本模式选择文件，生成中文并导出。\n3. 完全退出游戏，用 MT 将导出文件替换原配置。\n4. 最终名称必须为 config.json，再启动游戏确认中文。")
                                    Text("导出成功仅表示文件保存并回读通过，尚未替换游戏配置。需恢复时导出原始备份，用 MT 放回原位置。")
                                } }
                                item { Section("出现未完成写入时") {
                                    Text("暂勿启动游戏。重新连接原来源，校验上次结果；不一致时进入备份历史完整恢复，或导出原始备份手动恢复。副本恢复只影响编辑副本。")
                                } }
                            }
                            "diagnostics" -> {
                                item { Section("环境与记录") {
                                    Text(environment, style = MaterialTheme.typography.bodySmall)
                                    Text("Shizuku ${state.shizuku.installedVersion ?: "未安装"} · API ${state.shizuku.apiVersion ?: "未知"} · UID ${state.shizuku.uid ?: "未知"}")
                                    Text(state.shizuku.message)
                                    OutlinedTextField(gameVersion, { gameVersion = it.take(100) }, enabled = enabled,
                                        label = { Text("游戏发行版本") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(mtVersion, { mtVersion = it.take(100) }, enabled = enabled,
                                        label = { Text("MT 版本") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedButton({ actions.report(EnvironmentNotes(gameVersion, mtVersion)) }, enabled = enabled,
                                        modifier = Modifier.fillMaxWidth()) { Text("导出诊断记录") }
                                    Text("记录保存在本机，不包含配置内容。游戏中文显示和设备重启需人工验收。")
                                } }
                                item { Section("最近操作") { state.events.takeLast(10).reversed().forEach { Text(it.substringAfter(" | "), style = MaterialTheme.typography.bodySmall) } } }
                            }
                        }
                    }
                }
            }
        }
    }
    if (dialog == "source" && source != null) ConfirmDialog("确认实际来源",
        "目标：$TARGET_PATH\n\n实际来源：${if (source.accessMethod == AccessMethod.SHIZUKU) source.documentId else source.uri}\n\n确认这就是当前游戏使用的原配置。",
        "确认来源", { dialog = null }, enabled) { dialog = null; actions.confirm() }
    if (dialog == "switch") ConfirmDialog("先完全退出游戏",
        "工具箱会先核验原始备份，再写回配置。请确认游戏已完全退出；写入失败时先恢复备份，再启动游戏。",
        "已退出，开始验证", { dialog = null }, enabled && direct && !recovery) { dialog = null; actions.switch() }
    state.importAwaiting?.let { imported -> ConfirmDialog("替换当前编辑副本",
        "新文件：${imported.displayName}\n会先备份当前编辑副本，再导入新文件。历史备份继续保留，导入源文件不会修改。",
        "备份并替换", actions.cancelImport, enabled, actions.confirmImport) }
    restoreBackup?.let { backup ->
        val copy = backup.workspaceId != null
        ConfirmDialog(if (copy) "恢复编辑副本" else "完整恢复配置",
            "备份时间：${formatTime(backup.createdAt)}\n来源：${backup.source.uri}\n\n会先备份当前内容，再完整恢复按键、音量等所有设置。" +
                if (copy) "仅恢复应用内副本，尚未替换游戏配置。" else "请确认游戏已完全退出。",
            if (copy) "恢复副本" else "已退出，完整恢复", { restoreBackup = null }, enabled) {
            restoreBackup = null; if (copy) actions.restoreCopy(backup) else actions.restore(backup)
        }
    }
}

@Composable
private fun ResultPanel(state: ValidationState) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("操作结果", fontWeight = FontWeight.Bold)
            Text(state.message, Modifier.testTag("operation-result"))
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RecoveryPanel(state: ValidationState, verifyEnabled: Boolean, verify: () -> Unit, backups: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("recovery-warning"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("需要检查或恢复", fontWeight = FontWeight.Bold)
            Text("暂勿启动游戏。存在未完成或无法读取的外部操作记录，请连接原来源检查或恢复备份。")
            state.pendingError?.let { Text(it) }
            state.pending?.let { Text("${formatTime(it.startedAt)} · 原始备份 ${it.backupId.take(8)}") }
            OutlinedButton(verify, enabled = verifyEnabled && state.pending != null, modifier = Modifier.fillMaxWidth()) { Text("重新校验上次结果") }
            OutlinedButton(backups, modifier = Modifier.fillMaxWidth()) { Text("查看恢复备份") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); content()
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, label: String, dismiss: () -> Unit, enabled: Boolean, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (enabled) dismiss() }, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(confirm, enabled = enabled) { Text(label) } },
        dismissButton = { TextButton(dismiss, enabled = enabled) { Text("取消") } })
}
