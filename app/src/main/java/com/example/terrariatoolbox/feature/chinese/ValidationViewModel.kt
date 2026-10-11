package com.example.terrariatoolbox.feature.chinese

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.terrariatoolbox.data.*
import com.example.terrariatoolbox.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ValidationState(
    val source: SourceInfo? = null,
    val config: ConfigDocument? = null,
    val configError: String? = null,
    val pending: PendingOperation? = null,
    val pendingError: String? = null,
    val backups: List<BackupRecord> = emptyList(),
    val notes: EnvironmentNotes = EnvironmentNotes(),
    val events: List<String> = emptyList(),
    val busy: Boolean = false,
    val message: String = "请选择直接切换或导入配置副本。",
    val shizuku: ShizukuState = ShizukuState(),
    val workspace: CopyWorkspace? = null,
    val copyConfig: ConfigDocument? = null,
    val copyError: String? = null,
    val importAwaiting: SourceInfo? = null,
)

data class ExportSpec(val name: String, val mime: String)
private data class ExportRequest(val spec: ExportSpec, val bytes: ByteArray, val success: String, val protectedUris: List<String>, val copy: Boolean = false)

class ValidationViewModel(application: Application) : AndroidViewModel(application) {
    private val root = File(application.noBackupFilesDir, "validation")
    private val writer = AndroidAtomicWriter()
    private val session = SessionStore(root, writer)
    private val saf = SafDocumentAccess(application)
    private val connection = createShizukuConnection(application)
    private val shizuku = ShizukuDocumentAccess(connection)
    private val documents = RoutingDocumentAccess(saf, shizuku)
    private val processor = ConfigProcessor()
    private val backups = FileBackupRepository(File(root, "backups"), writer)
    private val journal = FileOperationJournal(File(root, "pending.json"), writer)
    private val coordinator = WriteCoordinator(documents, backups, journal, processor)
    private val workspaceStore = CopyWorkspaceStore(File(application.noBackupFilesDir, "copy-workspace"), writer)
    private val copies = CopyCoordinator(workspaceStore, documents, backups, coordinator, processor)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ValidationState())
    val state: StateFlow<ValidationState> = mutableState
    private var exportRequest: ExportRequest? = null
    private var pendingShizukuSelection = false
    private var preparedImport: PreparedCopyImport? = null

    val environment: String = "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}" +
        "\n安全补丁：${Build.VERSION.SECURITY_PATCH}\n应用版本：${application.packageManager.getPackageInfo(application.packageName, 0).versionName}" +
        " · targetSdk ${application.applicationInfo.targetSdkVersion}\n目标包名：$GAME_PACKAGE"

    init {
        viewModelScope.launch {
            connection.state.collect { status ->
                val current = mutableState.value
                val invalid = current.source?.accessMethod == AccessMethod.SHIZUKU && status.phase != ShizukuPhase.CONNECTED
                mutableState.value = current.copy(shizuku = status,
                    source = if (invalid) current.source?.copy(writable = false) else current.source,
                    config = if (invalid) null else current.config,
                    configError = if (invalid) status.message else current.configError)
                // Status changes must not wait for a long write holding the file-operation mutex.
                viewModelScope.launch(Dispatchers.IO) {
                    mutex.withLock { runCatching { session.event("Shizuku 连接：${status.message}") } }
                }
                if (status.phase == ShizukuPhase.CONNECTED) {
                    if (pendingShizukuSelection) readShizukuSelection()
                    else if (mutableState.value.source?.accessMethod == AccessMethod.SHIZUKU) refresh()
                }
            }
        }
        task("重新检查授权", after = { if (state.value.source?.accessMethod == AccessMethod.SHIZUKU) connection.connect(false) }) {
            val selected = session.source()
            if (selected == null) {
                if (workspaceStore.load() != null) "已加载上次编辑副本。导入导出无需启动 Shizuku，仍需手动替换游戏配置。"
                else "请连接 Shizuku 读取游戏配置，或导入可访问的配置副本。"
            }
            else "已重新检查保存的授权和目标内容。历史验证不代表本次游戏已生效。"
        }
    }

    fun connectShizuku() {
        if (state.value.busy) return
        pendingShizukuSelection = true
        connection.connect()
        if (connection.state.value.phase == ShizukuPhase.CONNECTED) readShizukuSelection()
    }
    private fun readShizukuSelection() {
        if (state.value.busy || !pendingShizukuSelection) return
        pendingShizukuSelection = false
        task("Shizuku 读取配置") {
            val source = shizuku.select()
            ensureSelectionAllowed(source)
            val previous = session.source()
            session.saveSource(if (previous?.identity == source.identity && previous.accessMethod == source.accessMethod)
                source.copy(confirmed = previous.confirmed, lastVerifiedAt = previous.lastVerifiedAt) else source)
            val parsed = processor.inspect(documents.read(source.uri))
            "Shizuku 读取成功：${parsed.languageLabel}。请核对实际路径并确认来源；读取成功不代表写回已验证。"
        }
    }
    fun onForeground() {
        connection.check()
        if (state.value.source?.accessMethod == AccessMethod.SHIZUKU) connection.connect(false)
        if (!state.value.busy && state.value.source != null) refresh()
    }
    override fun onCleared() { connection.close(); super.onCleared() }
    fun externalUnavailable() = task("打开外部应用失败") { "无法打开外部应用，请手动打开 Shizuku 或访问官方启动说明。" }
    private fun ensureSelectionAllowed(source: SourceInfo) {
        val pending = journal.load()
        if (pending != null && (pending.source.identity != source.identity || pending.source.accessMethod != source.accessMethod))
            throw ToolException("存在其他来源的未完成写入，请先连接原来源检查或导出备份。", true)
    }
    private fun refreshSource(source: SourceInfo): SourceInfo = when (source.accessMethod) {
        AccessMethod.SAF -> saf.refresh(source)
        AccessMethod.SHIZUKU -> shizuku.refresh(source)
    }

    fun selected(uri: Uri?, flags: Int, directory: Boolean) {
        if (uri == null) { cancelled("授权"); return }
        task("${if (directory) "目录" else "文件"}授权") {
            val source = saf.select(uri, flags, directory)
            ensureSelectionAllowed(source)
            session.saveSource(source)
            val parsed = processor.inspect(saf.read(source.uri))
            "读取成功：${parsed.languageLabel}。${if (source.persisted) "已取得持久读取授权。" else "授权未持久保存，重启后可能需重新授权。"}" +
                if (!source.knownTarget) " 此来源无法确认为游戏原配置，只开放读取和原始备份导出。" else " 请核对实际来源并确认。"
        }
    }

    fun confirmSource() = task("确认实际来源") {
        val source = requireSource()
        if (!source.knownTarget) throw ToolException("提供方无法证明这是目标目录中的原配置，只允许读取。")
        session.saveSource(source.copy(confirmed = true))
        "已确认这是游戏原配置。写权限仍需实际写回验证。"
    }

    fun refresh() = task("刷新目标") { "已重新读取目标。请查看最新语言和授权状态。" }

    fun switchToChinese() = task("验证并切换中文") {
        val source = requireSource()
        val result = coordinator.switchToChinese(source)
        if (!result.changed) "配置已设为中文，未备份、未写入。本次不构成写回能力验证。"
        else {
            session.saveSource(source.copy(lastVerifiedAt = result.verifiedAt))
            "配置已保存并回读校验，请启动游戏确认中文显示。游戏实际生效尚待验证。"
        }
    }

    fun restore(record: BackupRecord) = task("完整恢复配置") {
        val source = requireSource()
        val result = coordinator.restore(source, record)
        session.saveSource(source.copy(lastVerifiedAt = result.verifiedAt))
        "已恢复游戏配置并逐字节校验，请启动游戏确认。"
    }

    fun verifyPending() = task("校验未完成操作") {
        val source = requireSource()
        val result = coordinator.verifyPending(source)
        session.saveSource(source.copy(lastVerifiedAt = result.verifiedAt))
        "目标与上次预期结果一致，回读校验已完成。请启动游戏确认。"
    }

    fun saveOriginalBackup() = task("保存原始备份") {
        val source = requireSource()
        val bytes = documents.read(source.uri)
        backups.save(source, bytes, "用户主动备份", runCatching { processor.inspect(bytes) }.getOrNull())
        "原始字节备份已保存并回读校验。卸载应用或清除数据会丢失内部备份，请及时导出。"
    }

    fun importSelected(uri: Uri?, flags: Int) {
        if (uri == null) { task("取消导入") { "已取消导入，当前副本未改变。" }; return }
        task("读取导入副本") { stageImport(saf.importSource(uri, flags)) }
    }

    fun importLegacySource() = task("导入为副本") {
        val source = session.source() ?: throw ToolException("请先选择可读取的配置。")
        stageImport(source)
    }

    private fun stageImport(source: SourceInfo): String {
        preparedImport = null
        val prepared = copies.prepareImport(source)
        return if (prepared.previousId == null) {
            copies.import(prepared)
            "配置副本已导入，源文件未修改。生成和恢复仅操作应用内副本。"
        } else {
            preparedImport = prepared
            "配置有效，请确认是否替换当前编辑副本。"
        }
    }

    fun confirmImport() = task("替换编辑副本") {
        val prepared = preparedImport ?: throw ToolException("导入任务已失效，请重新选择。")
        copies.import(prepared)
        preparedImport = null
        "新配置副本已导入，原编辑副本已备份；导入源文件未修改。"
    }

    fun cancelImport() = task("取消替换副本") {
        preparedImport = null
        "已取消导入，当前副本未改变。"
    }

    fun saveCopyBackup() = task("备份编辑副本") {
        copies.saveBackup()
        "副本原始字节备份已保存并回读校验。"
    }

    fun restoreCopy(record: BackupRecord) = task("恢复编辑副本") {
        copies.restore(record)
        "已恢复编辑副本并逐字节校验，尚未替换游戏配置。"
    }

    fun prepareCopyExport(ready: (ExportSpec) -> Unit) = task("生成并准备导出副本", after = {
        exportRequest?.spec?.let(ready)
    }) {
        val workspace = copies.generateChinese()
        val bytes = workspaceStore.read(workspace)
        val spec = ExportSpec("config.json", "application/json")
        exportRequest = ExportRequest(spec, bytes, "中文配置已导出，尚未替换游戏配置。请完全退出游戏，再用 MT 替换原文件。",
            protectedUris() + workspace.source.uri, copy = true)
        "中文编辑副本已准备好，尚未导出。请选择新的保存位置。"
    }

    fun prepareBackupExport(record: BackupRecord, ready: (ExportSpec) -> Unit) = task("准备备份导出", after = {
        exportRequest?.spec?.let(ready)
    }) {
        val bytes = backups.read(record)
        val spec = ExportSpec("config-original-${record.id.take(8)}.json", "application/json")
        exportRequest = ExportRequest(spec, bytes, "原始备份已导出，尚未替换游戏配置。", protectedUris())
        "请选择新的备份保存位置。"
    }

    fun prepareReport(notes: EnvironmentNotes, ready: (ExportSpec) -> Unit) = task("准备验证记录", after = {
        exportRequest?.spec?.let(ready)
    }) {
        session.saveNotes(notes)
        val source = session.source()
        val report = buildString {
            appendLine("泰拉瑞亚工具箱 · 本机诊断记录")
            appendLine("记录时间：${formatTime(System.currentTimeMillis())}")
            appendLine(environment)
            appendLine("游戏发行版本：${notes.gameVersion.ifBlank { "待填写" }}")
            appendLine("MT 版本：${notes.mtVersion.ifBlank { "待填写" }}")
            appendLine("授权入口：${source?.selection ?: "尚未授权"}")
            appendLine("文件提供方：${source?.authority ?: "未知"}")
            appendLine("访问方式：${source?.accessMethod ?: "尚未选择"}")
            val status = connection.state.value
            appendLine("Shizuku 版本：${status.installedVersion ?: "未安装"}")
            appendLine("Shizuku API：${status.apiVersion ?: "未知"}；服务 UID：${status.uid ?: "未知"}")
            appendLine("Shizuku 授权及连接：${status.phase}；${status.message}")
            appendLine("来源核验：${if (source?.knownTarget == true) TARGET_PATH else "无法确认游戏原文件位置"}")
            appendLine("用户确认来源：${source?.confirmed == true}")
            appendLine(if (source?.accessMethod == AccessMethod.SHIZUKU) "Shizuku 重启要求：设备重启后需重新启动服务并检查授权。"
                else "读取授权持久保存：${source?.persisted == true}")
            appendLine("历史写回回读通过时间：${source?.lastVerifiedAt?.let(::formatTime) ?: "尚未验证"}")
            appendLine("游戏中文显示：需在当前设备人工确认，工具箱不自动检测")
            appendLine("应用/设备重启后授权复用：待真机验证")
            appendLine("MT 授权页面及实际范围：待人工核对")
            appendLine("支持范围：历史用户实测已记录；其他设备及版本需独立验收。")
            val copy = runCatching { workspaceStore.load() }.getOrNull()
            appendLine("编辑副本：${if (copy == null) "尚未导入" else "仅在应用内部保存，未自动替换游戏配置"}")
            appendLine("\n操作记录（不包含配置内容或配置中的用户标识）：")
            session.events().forEach { appendLine(it) }
        }
        val spec = ExportSpec("TerrariaToolbox-validation.txt", "text/plain")
        exportRequest = ExportRequest(spec, report.toByteArray(Charsets.UTF_8), "验证记录已导出。", protectedUris())
        "请选择验证记录的保存位置。"
    }

    fun exported(uri: Uri?) {
        val request = exportRequest
        exportRequest = null
        if (uri == null) {
            task("取消导出") { if (request?.copy == true) "已取消导出，已生成的编辑副本和原始备份已保留，尚未替换游戏配置。"
                else "已取消导出，未修改文件。" }
            return
        }
        task("导出并回读校验") {
            if (request == null) throw ToolException("导出任务已失效，未写入文件，请重新导出。")
            coordinator.export(uri.toString(), request.bytes, (request.protectedUris + protectedUris()).distinct())
            val name = runCatching { saf.describe(uri).name }.getOrDefault("提供方未返回名称")
            request.success + "\n实际名称：$name\n保存位置：$uri" +
                if (request.spec.mime == "application/json" && name != "config.json") "\n手动替换游戏配置时，最终文件名必须为 config.json。" else ""
        }
    }

    fun cancelled(operation: String) {
        exportRequest = null
        task("取消$operation") { "已取消$operation，未修改文件。" }
    }

    fun pickerUnavailable() = task("无法打开文件选择器") {
        "系统未提供此文件选择入口，请检查系统文件应用或尝试另一入口。"
    }

    private fun requireSource(): SourceInfo = session.source()?.let { refreshSource(it) }
        ?: throw ToolException("请先授权或选择配置文件。")
    private fun protectedUris(): List<String> = backups.list().map { it.source.uri } +
        listOfNotNull(session.source()?.uri, runCatching { workspaceStore.load()?.source?.uri }.getOrNull()) + SHIZUKU_URI

    private fun snapshot(): ValidationState {
        val source = runCatching { session.source() }.getOrNull()
        var configError: String? = null
        var refreshed = source
        val bytes = source?.let {
            try {
                refreshed = refreshSource(it)
                documents.read(it.uri)
            } catch (error: Exception) {
                configError = friendly(error)
                refreshed = it.copy(writable = false)
                null
            }
        }
        val config = bytes?.let {
            try { processor.inspect(it) } catch (error: Exception) { configError = friendly(error); null }
        }
        var pendingError: String? = null
        val pending = try { journal.load() } catch (error: Exception) { pendingError = friendly(error); null }
        var copyError: String? = null
        val workspace = try { workspaceStore.load() } catch (error: Exception) { copyError = friendly(error); null }
        val copyConfig = workspace?.let {
            try { processor.inspect(workspaceStore.read(it)) } catch (error: Exception) { copyError = friendly(error); null }
        }
        return ValidationState(refreshed, config, configError, pending, pendingError, backups.list(),
            runCatching { session.notes() }.getOrDefault(EnvironmentNotes()), runCatching { session.events() }.getOrDefault(emptyList()),
            shizuku = connection.state.value, workspace = workspace, copyConfig = copyConfig, copyError = copyError,
            importAwaiting = preparedImport?.source)
    }

    private fun task(label: String, after: (() -> Unit)? = null, work: () -> String) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, message = "$label…")
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                mutex.withLock {
                    var success = false
                    val message = try { work().also { success = true } }
                        catch (error: CancellationException) { throw error }
                        catch (error: Exception) { friendly(error) }
                    // Diagnostic failures never cause a successful external write to be repeated.
                    val logged = runCatching { session.event("$label：$message".substringBefore("\n")) }.isSuccess
                    val current = runCatching { snapshot() }.getOrElse {
                        ValidationState(pendingError = "内部状态读取失败，请先导出原始备份，暂勿写入。")
                    }
                    Triple(current.copy(message = message + if (!logged) "\n本地诊断记录保存失败，请检查空间。" else ""), success, logged)
                }
            }
            val latest = connection.state.value
            val invalid = outcome.first.source?.accessMethod == AccessMethod.SHIZUKU && latest.phase != ShizukuPhase.CONNECTED
            mutableState.value = outcome.first.copy(shizuku = latest,
                source = if (invalid) outcome.first.source?.copy(writable = false) else outcome.first.source,
                config = if (invalid) null else outcome.first.config)
            if (outcome.second) after?.invoke()
            if (pendingShizukuSelection && connection.state.value.phase == ShizukuPhase.CONNECTED) readShizukuSelection()
        }
    }

    private fun friendly(error: Exception): String = when (error) {
        is ToolException -> error.userMessage
        is SecurityException -> "授权已失效，请重新授权。"
        else -> "操作未完成，请检查文件权限和剩余空间。结果未确认，请查看备份和诊断记录。"
    }
}

fun formatTime(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(time))
