package com.example.terrariatoolbox.feature.chinese

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import com.example.terrariatoolbox.data.SHIZUKU_PACKAGE
import com.example.terrariatoolbox.data.SHIZUKU_GUIDE
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.terrariatoolbox.data.EnvironmentNotes
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
        directory = { try { directoryPicker.launch(selectionIntent(true)) } catch (_: Exception) { model.pickerUnavailable() } },
        file = { try { filePicker.launch(selectionIntent(false)) } catch (_: Exception) { model.pickerUnavailable() } },
        confirm = model::confirmSource, refresh = model::refresh, switch = model::switchToChinese,
        saveBackup = model::saveOriginalBackup, restore = model::restore,
        exportBackup = { model.prepareBackupExport(it, ::export) },
        report = { model.prepareReport(it, ::export) }, verifyPending = model::verifyPending,
    ))
}

private fun selectionIntent(directory: Boolean): Intent = Intent(
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
)

@Composable
fun ValidationContent(state: ValidationState, environment: String, actions: ValidationActions) {
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

    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("validation-list"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("泰拉瑞亚工具箱", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("文件授权与安全写回验证", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Shizuku 优先验证版 1.1 · 游戏实际生效需要真机确认", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Section("目标配置") {
                    Text(TARGET_PATH, style = MaterialTheme.typography.bodySmall)
                    Text("通过 Shizuku 的无线调试模式访问固定配置。请先启动服务，再授权工具箱。")
                    Text(state.shizuku.message, Modifier.testTag("shizuku-status"))
                    state.shizuku.installedVersion?.let { Text("Shizuku $it · API ${state.shizuku.apiVersion ?: "未知"} · UID ${state.shizuku.uid ?: "未知"}", style = MaterialTheme.typography.bodySmall) }
                    Button(actions.connectShizuku, enabled = enabled && state.shizuku.phase != ShizukuPhase.CONNECTING,
                        modifier = Modifier.fillMaxWidth().testTag("connect-shizuku")) { Text("连接 Shizuku 并读取配置") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(actions.openShizuku, enabled = enabled) { Text("打开 Shizuku") }
                        OutlinedButton(actions.guide, enabled = enabled) { Text("安装与启动说明") }
                    }
                    Text("无需 Root 或连接电脑；依赖无线调试。手机重启后需要重新启动 Shizuku。", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Section("其他授权方式") {
                    Text("系统入口可能限制 Android/data。目录选择失败时可尝试单文件；MT 的权限不会授予本应用。")
                    Button(actions.directory, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("select-directory")) { Text("授权配置目录") }
                    OutlinedButton(actions.file, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("select-file")) { Text("选择配置文件") }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = if (recovery)
                    MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (recovery) "需要检查或恢复" else "操作结果", fontWeight = FontWeight.Bold)
                        Text(state.message, Modifier.testTag("operation-result"))
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.pendingError?.let { Text(it) }
                        state.pending?.let { pending ->
                            Text("检测到未完成的${if (pending.kind == "restore") "恢复" else "写入"}：${formatTime(pending.startedAt)}")
                            Text("暂勿启动游戏。请重新授权原目标，再校验结果或恢复下方原始备份。")
                            Text("恢复备份标识：${pending.backupId.take(8)}")
                            OutlinedButton(actions.verifyPending, enabled = enabled && source?.confirmed == true && source.knownTarget) { Text("重新校验上次结果") }
                        }
                    }
                }
            }
            if (source != null) item {
                Section("实际来源与授权") {
                    Text("文件：${source.displayName}")
                    Text("提供方：${source.authority}", style = MaterialTheme.typography.bodySmall)
                    Text(if (source.accessMethod == AccessMethod.SHIZUKU) "实际路径：${source.documentId}" else "实际 URI：${source.uri}", style = MaterialTheme.typography.bodySmall)
                    Text("入口：${source.selection}")
                    Text(if (source.accessMethod == AccessMethod.SHIZUKU) "Shizuku 授权与服务每次均需检查；设备重启后重新启动服务"
                        else if (source.persisted) "已持久保存读取授权；实际访问仍需检查" else "授权未持久保存，重启后可能需要重新授权")
                    Text(if (source.writable && accessReady) "当前可尝试写入，需实际写回验证" else "当前无法直接写回，请检查授权和服务")
                    Text(if (!source.knownTarget) "来源无法确认为游戏原配置，仅可读取和备份导出" else
                        if (source.confirmed) "已由你确认游戏原配置来源" else "路径匹配目标，请核对并确认来源")
                    source.lastVerifiedAt?.let { Text("历史写回回读通过：${formatTime(it)}；不代表游戏已生效。") }
                    Text(state.config?.languageLabel ?: state.configError ?: "尚未识别语言", fontWeight = FontWeight.Bold)
                    if (source.knownTarget && !source.confirmed) OutlinedButton({ dialog = "source" }, enabled = enabled) { Text("确认这是游戏原配置") }
                    if (state.config?.language == Language.CHINESE) Text("无需重复写入；读取中文配置不能证明写回能力。")
                    Button({ dialog = "switch" }, enabled = enabled && direct && !recovery && state.config?.language == Language.ENGLISH,
                        modifier = Modifier.fillMaxWidth().testTag("switch-chinese")) { Text("验证并切换中文") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(actions.refresh, enabled = enabled) { Text("刷新配置") }
                        OutlinedButton(actions.saveBackup, enabled = enabled) { Text("保存原始备份") }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("原始备份", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("完整恢复会同时还原音量、按键等其他设置。内部备份在卸载或清除数据后丢失，请及时导出。", style = MaterialTheme.typography.bodySmall)
                    if (state.backups.isEmpty()) Text("尚无备份")
                }
            }
            items(state.backups, key = { it.id }) { backup ->
                Section("${formatTime(backup.createdAt)} · ${backup.reason}") {
                    Text("${backup.id.take(8)} · ${backup.size} 字节 · 原始语言 ${backup.language ?: "无法识别"}")
                    Text("来源：${if (backup.source.knownTarget) "游戏原配置" else "未确认来源"} · ${backup.source.authority}", style = MaterialTheme.typography.bodySmall)
                    Text("来源 URI：${backup.source.uri}", style = MaterialTheme.typography.bodySmall)
                    Text("SHA-256：${backup.hash}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ actions.exportBackup(backup) }, enabled = enabled) { Text("导出原始备份") }
                        OutlinedButton({ restoreBackup = backup }, enabled = enabled && direct && state.pendingError == null && backup.source.accessMethod == source?.accessMethod &&
                            backup.source.identity == source?.identity && (state.pending == null || state.pending.source.identity == source?.identity)) { Text("完整恢复") }
                    }
                }
            }
            item {
                Section("设备与验收记录") {
                    Text(environment, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(gameVersion, { gameVersion = it.take(100) }, enabled = enabled,
                        label = { Text("游戏发行版本（手动填写）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(mtVersion, { mtVersion = it.take(100) }, enabled = enabled,
                        label = { Text("MT 版本（手动填写）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton({ actions.report(EnvironmentNotes(gameVersion, mtVersion)) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth()) { Text("导出验证记录") }
                    Text("记录仅保存在本机，不包含配置内容。游戏中文显示、设备重启后的授权复用需人工验收。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.events.isNotEmpty()) item {
                Section("最近操作") { state.events.takeLast(6).reversed().forEach { Text(it.substringAfter(" | "), style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
    if (dialog == "source" && source != null) ConfirmDialog("确认实际来源",
        "目标：$TARGET_PATH\n\n实际来源：${source.uri}\n\n请确认这就是当前游戏使用的原配置。", "确认来源", { dialog = null }) {
        dialog = null; actions.confirm()
    }
    if (dialog == "switch") ConfirmDialog("先完全退出游戏",
        "请确认游戏已完全退出。应用会先校验原始备份，再覆盖写回原文件。提供方写入可能中断；失败时请先恢复备份，再启动游戏。",
        "已退出，开始验证", { dialog = null }) { dialog = null; actions.switch() }
    restoreBackup?.let { backup ->
        ConfirmDialog("完整恢复配置", "备份时间：${formatTime(backup.createdAt)}\n来源：${backup.source.uri}\n\n音量、按键等设置也会恢复。请完全退出游戏；应用会先备份当前内容再恢复。",
            "已退出，完整恢复", { restoreBackup = null }) { restoreBackup = null; actions.restore(backup) }
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
private fun ConfirmDialog(title: String, text: String, confirmLabel: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(confirm) { Text(confirmLabel) } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}
