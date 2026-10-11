package io.github.bingwu2003.terrariatoolbox.feature.chinese

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bingwu2003.terrariatoolbox.data.*
import io.github.bingwu2003.terrariatoolbox.domain.*
import io.github.bingwu2003.terrariatoolbox.feature.saves.SaveManager
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
    val message: String = "连接 Shizuku 后，即可切换游戏语言。",
    val shizuku: ShizukuState = ShizukuState(),
)

data class ExportSpec(val name: String, val mime: String)
private data class ExportRequest(val spec: ExportSpec, val bytes: ByteArray, val success: String, val protectedUris: List<String>)

class ValidationViewModel(application: Application) : AndroidViewModel(application) {
    private val root = File(application.noBackupFilesDir, "validation")
    private val writer = AndroidAtomicWriter()
    private val session = SessionStore(root, writer)
    private val saf = SafDocumentAccess(application)
    private val connection = createShizukuConnection(application)
    val saves = SaveManager(application, { connection.files().saves() }, viewModelScope)
    private val shizuku = ShizukuDocumentAccess(connection)
    private val documents = RoutingDocumentAccess(saf, shizuku)
    private val processor = ConfigProcessor()
    private val backups = FileBackupRepository(File(root, "backups"), writer)
    private val journal = FileOperationJournal(File(root, "pending.json"), writer)
    private val coordinator = WriteCoordinator(documents, backups, journal, processor)
    private val workspaceStore = CopyWorkspaceStore(File(application.noBackupFilesDir, "copy-workspace"), writer)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ValidationState())
    val state: StateFlow<ValidationState> = mutableState
    private var exportRequest: ExportRequest? = null
    private var pendingShizukuSelection = false

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
        task("检查连接", after = { if (state.value.source?.accessMethod == AccessMethod.SHIZUKU) connection.connect(false) }) {
            val selected = session.source()
            if (selected == null) {
                "连接 Shizuku 后，即可切换游戏语言。"
            }
            else if (selected.accessMethod == AccessMethod.SHIZUKU) "已找到上次使用的游戏设置，请查看首页的 Shizuku 连接状态。"
            else "请连接 Shizuku 后使用工具箱。"
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
        task("读取游戏设置") {
            val source = try { shizuku.select() } catch (error: ToolException) {
                return@task "Shizuku 已连接，可以进入「存档管理」。游戏语言暂时无法读取：${error.userMessage}"
            }
            ensureSelectionAllowed(source)
            val previous = session.source()
            session.saveSource(if (previous?.identity == source.identity && previous.accessMethod == source.accessMethod)
                source.copy(lastVerifiedAt = previous.lastVerifiedAt) else source)
            val parsed = processor.inspect(documents.read(source.uri))
            "已连接，当前游戏语言：${parsed.languageLabel}。可以进入「游戏语言」进行切换。"
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

    fun refresh() = task("刷新游戏设置") {
        if (session.source()?.accessMethod != AccessMethod.SHIZUKU) "请先在首页连接 Shizuku。"
        else {
            val source = requireShizukuSource()
            val parsed = processor.inspect(documents.read(source.uri))
            "已刷新，当前游戏语言：${parsed.languageLabel}。"
        }
    }

    fun switchToChinese() = switchLanguage(Language.CHINESE)
    fun switchToEnglish() = switchLanguage(Language.ENGLISH)

    private fun switchLanguage(target: Language) = task("切换到${if (target == Language.CHINESE) "中文" else "英文"}") {
        val source = requireShizukuSource()
        val result = coordinator.switchLanguage(source, target)
        val label = if (target == Language.CHINESE) "中文" else "英文"
        if (!result.changed) "当前已经是$label，无需再次切换。"
        else {
            session.saveSource(source.copy(lastVerifiedAt = result.verifiedAt))
            "已切换为$label，重新打开游戏即可生效。"
        }
    }

    fun restore(record: BackupRecord) = task("恢复备份") {
        val source = requireShizukuSource()
        val result = coordinator.restore(source, record)
        session.saveSource(source.copy(lastVerifiedAt = result.verifiedAt))
        "已恢复备份，语言、音量和按键等设置已一起还原。现在可以重新打开游戏。"
    }

    fun verifyPending() = task("检查上次操作") {
        val source = requireShizukuSource()
        val result = coordinator.verifyPending(source)
        session.saveSource(source.copy(lastVerifiedAt = result.verifiedAt))
        "上次操作已完成，游戏设置检查正常。现在可以重新打开游戏。"
    }

    fun prepareBackupExport(record: BackupRecord, ready: (ExportSpec) -> Unit) = task("准备备份导出", after = {
        exportRequest?.spec?.let(ready)
    }) {
        val bytes = backups.read(record)
        val spec = ExportSpec("config-original-${record.id.take(8)}.json", "application/json")
        exportRequest = ExportRequest(spec, bytes, "备份已导出。", protectedUris())
        "请选择新的备份保存位置。"
    }

    fun prepareReport(notes: EnvironmentNotes, ready: (ExportSpec) -> Unit) = task("准备问题记录", after = {
        exportRequest?.spec?.let(ready)
    }) {
        session.saveNotes(notes)
        val source = session.source()
        val report = buildString {
            appendLine("泰拉瑞亚工具箱 · 本机诊断记录")
            appendLine("记录时间：${formatTime(System.currentTimeMillis())}")
            appendLine(environment)
            appendLine("游戏发行版本：${notes.gameVersion.ifBlank { "待填写" }}")
            appendLine("授权入口：${source?.selection ?: "尚未授权"}")
            appendLine("文件提供方：${source?.authority ?: "未知"}")
            appendLine("访问方式：${source?.accessMethod ?: "尚未选择"}")
            val status = connection.state.value
            appendLine("Shizuku 版本：${status.installedVersion ?: "未安装"}")
            appendLine("Shizuku API：${status.apiVersion ?: "未知"}；服务 UID：${status.uid ?: "未知"}")
            appendLine("Shizuku 授权及连接：${status.phase}；${status.message}")
            appendLine("来源核验：${if (source?.knownTarget == true) TARGET_PATH else "无法确认游戏原文件位置"}")
            appendLine("自动识别游戏配置：${source?.knownTarget == true && source.confirmed}")
            appendLine(if (source?.accessMethod == AccessMethod.SHIZUKU) "Shizuku 重启要求：设备重启后需重新启动服务并检查授权。"
                else "读取授权持久保存：${source?.persisted == true}")
            appendLine("历史写回回读通过时间：${source?.lastVerifiedAt?.let(::formatTime) ?: "尚未验证"}")
            appendLine("当前游戏语言：${state.value.config?.languageLabel ?: "未读取"}")
            appendLine("应用/设备重启后授权复用：待真机验证")
            appendLine("支持范围：历史用户实测已记录；其他设备及版本需独立验收。")
            appendLine("\n操作记录（不包含配置内容或配置中的用户标识）：")
            session.events().forEach { appendLine(it) }
        }
        val spec = ExportSpec("TerrariaToolbox-validation.txt", "text/plain")
        exportRequest = ExportRequest(spec, report.toByteArray(Charsets.UTF_8), "问题记录已导出。", protectedUris())
        "请选择问题记录的保存位置。"
    }

    fun exported(uri: Uri?) {
        val request = exportRequest
        exportRequest = null
        if (uri == null) {
            task("取消导出") { "已取消导出，备份仍保存在工具箱中。" }
            return
        }
        task("导出并回读校验") {
            if (request == null) throw ToolException("导出任务已失效，未写入文件，请重新导出。")
            coordinator.export(uri.toString(), request.bytes, (request.protectedUris + protectedUris()).distinct())
            val name = runCatching { saf.describe(uri).name }.getOrDefault("提供方未返回名称")
            request.success + "\n文件名：$name"
        }
    }

    fun cancelled(operation: String) {
        exportRequest = null
        task("取消$operation") { "已取消$operation，未修改文件。" }
    }

    fun pickerUnavailable() = task("无法打开文件选择器") {
        "系统未提供此文件选择入口，请检查系统文件应用或尝试另一入口。"
    }

    private fun requireShizukuSource(): SourceInfo {
        val source = session.source() ?: throw ToolException("请先在首页连接 Shizuku。")
        if (source.accessMethod != AccessMethod.SHIZUKU) throw ToolException("请先在首页连接 Shizuku。")
        return shizuku.refresh(source)
    }
    private fun protectedUris(): List<String> = backups.list().map { it.source.uri } +
        listOfNotNull(session.source()?.uri, runCatching { workspaceStore.load()?.source?.uri }.getOrNull()) + SHIZUKU_URI

    private fun snapshot(): ValidationState {
        val source = runCatching { session.source() }.getOrNull()
        var configError: String? = null
        var refreshed = source
        val bytes = source?.takeIf { it.accessMethod == AccessMethod.SHIZUKU }?.let {
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
        return ValidationState(refreshed, config, configError, pending, pendingError, backups.list(),
            runCatching { session.notes() }.getOrDefault(EnvironmentNotes()), runCatching { session.events() }.getOrDefault(emptyList()),
            shizuku = connection.state.value)
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
