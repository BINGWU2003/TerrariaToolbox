package io.github.bingwu2003.terrariatoolbox.feature.saves

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import io.github.bingwu2003.terrariatoolbox.domain.*
import io.github.bingwu2003.terrariatoolbox.data.SavePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class BuiltinSave(val asset: String, val name: String, val size: Long, val edition: GameEdition) {
    val kind get() = if (SaveNames.directory(name) == "Players") SaveKind.PLAYER else SaveKind.WORLD
}
enum class ImportStep { SOURCE, CHECKING, TARGET, REVIEW, WRITING, RESULT }
data class SaveActivity(val time: Long, val message: String)
data class SaveExportResult(val name: String, val uri: Uri)
data class SaveState(
    val edition: GameEdition = GameEdition.INTERNATIONAL,
    val accounts: List<String> = emptyList(), val account: String = "",
    val saves: List<GameSave> = emptyList(), val selected: Set<String> = emptySet(),
    val builtins: List<BuiltinSave> = emptyList(),
    val busy: Boolean = false, val loaded: Boolean = false,
    val message: String = "连接 Shizuku 后，选择游戏版本查看存档。",
    val pending: List<StagedSave> = emptyList(), val pendingTarget: SaveTarget? = null,
    val results: List<SaveImportResult> = emptyList(),
    val importStep: ImportStep = ImportStep.SOURCE,
    val completed: Int = 0, val total: Int = 0,
    val recent: List<SaveActivity> = emptyList(),
    val exportResult: SaveExportResult? = null,
    val awaitingExport: Boolean = false,
    val importSummary: String = "", val exportMessage: String = "",
    val declaredEdition: GameEdition? = null,
) {
    val target get() = SaveTarget(edition, account)
    val hasTarget get() = edition == GameEdition.INTERNATIONAL || account.isNotEmpty()
    val sourceEdition get() = resolvedEdition(pending, declaredEdition)
    val unknownSources get() = pending.count { it.edition == null }
    val compatibleTarget get() = pending.isNotEmpty() && sourceEdition == edition
}
data class SaveExportSpec(val name: String, val mime: String)
private data class PreparedExport(val file: File, val spec: SaveExportSpec)
private data class SaveInput(val name: String, val open: () -> java.io.InputStream, val edition: GameEdition? = null)

class SaveManager(private val app: Application, private val remote: () -> RemoteSaveFiles, private val scope: CoroutineScope,
    private val preferences: SavePreferences = SavePreferences(File(app.noBackupFilesDir, "save-target.json"))) {
    private val mutable = MutableStateFlow(SaveState(busy = true))
    val state: StateFlow<SaveState> = mutable
    private val cache = File(app.cacheDir, "save-transfer").apply { mkdirs() }
    private var export: PreparedExport? = null
    init {
        // Only this manager's disposable cache is cleaned; supplied assets and game files are untouched.
        cache.listFiles()?.forEach { it.deleteRecursively() }
        scope.launch {
            val remembered = withContext(Dispatchers.IO) { preferences.read() }
            val builtins = withContext(Dispatchers.IO) {
                listOf("players" to GameEdition.INTERNATIONAL, "worlds" to GameEdition.INTERNATIONAL,
                    "xd-players" to GameEdition.CHINESE, "xd-worlds" to GameEdition.CHINESE).flatMap { (dir, edition) ->
                    app.assets.list(dir).orEmpty().filter { runCatching { SaveNames.directory(it) }.isSuccess }.map { name ->
                        val asset = "$dir/$name"
                        BuiltinSave(asset, name, app.assets.open(asset).use { it.available().toLong() }, edition)
                    }
                }
            }
            mutable.value = mutable.value.copy(builtins = builtins, edition = remembered.edition, account = remembered.account, busy = false)
        }
    }
    fun edition(edition: GameEdition) {
        if (state.value.busy || state.value.awaitingExport) return
        val changingImportTarget = state.value.importStep in listOf(ImportStep.TARGET, ImportStep.REVIEW)
        if (changingImportTarget && state.value.edition == edition) return
        if (changingImportTarget && state.value.sourceEdition?.let { it != edition } == true) {
            mutable.value = state.value.copy(message = "所选存档属于${state.value.sourceEdition?.label}，不能导入${edition.label}。请重新选文件。")
            return
        }
        if (!changingImportTarget) cancelImport()
        export?.file?.delete(); export = null
        mutable.value = state.value.copy(edition = edition, accounts = emptyList(), account = "", saves = emptyList(), selected = emptySet(), loaded = false,
            pendingTarget = null, importStep = if (changingImportTarget) ImportStep.TARGET else ImportStep.SOURCE,
            results = if (changingImportTarget) state.value.results else emptyList(), exportResult = null)
        refresh()
    }
    fun account(account: String) {
        if (state.value.busy || state.value.awaitingExport || account !in state.value.accounts) return
        val changingImportTarget = state.value.importStep in listOf(ImportStep.TARGET, ImportStep.REVIEW)
        if (!changingImportTarget) cancelImport()
        mutable.value = state.value.copy(account = account, saves = emptyList(), selected = emptySet(), loaded = false,
            pendingTarget = null, importStep = if (changingImportTarget) ImportStep.TARGET else ImportStep.SOURCE,
            results = if (changingImportTarget) state.value.results else emptyList(), exportResult = null)
        refresh()
    }
    fun toggle(name: String) {
        if (state.value.busy || state.value.saves.none { it.name == name }) return
        mutable.value = state.value.copy(selected = state.value.selected.let { if (name in it) it - name else it + name })
    }
    fun selectAll(names: Set<String>) { if (!state.value.busy) mutable.value = state.value.copy(selected = names) }
    fun refresh() = task("正在读取存档…") {
        val current = state.value
        val files = remote()
        val accounts = if (current.edition == GameEdition.CHINESE) files.accounts() else emptyList()
        val account = when {
            current.edition == GameEdition.INTERNATIONAL -> ""
            current.account in accounts -> current.account
            accounts.size == 1 -> accounts.single()
            else -> ""
        }
        val target = SaveTarget(current.edition, account)
        val saves = if (current.edition == GameEdition.INTERNATIONAL || account.isNotEmpty()) files.list(target) else emptyList()
        runCatching { preferences.write(target) }
        update { it.copy(accounts = accounts, account = account, saves = saves, selected = it.selected.intersect(saves.map { s -> s.name }.toSet()), loaded = true,
            message = when { accounts.isEmpty() && current.edition == GameEdition.CHINESE -> "没有找到国服账号。请先登录游戏，再完全退出后刷新。"
                account.isEmpty() && current.edition == GameEdition.CHINESE -> "找到多个国服账号，请选择要管理的账号。"
                saves.isEmpty() -> "当前没有角色或世界存档，可以从内置存档或手机文件导入。"
                else -> "已读取 ${saves.size} 个存档。" }) }
    }
    fun beginImport() {
        if (state.value.busy || state.value.awaitingExport) return
        cancelImport()
        mutable.value = state.value.copy(importStep = ImportStep.SOURCE, results = emptyList(), exportResult = null,
            completed = 0, total = 0, importSummary = "", message = "先选择手机文件或内置存档，再确认导入目标。")
    }
    fun reviewImport() {
        val current = state.value
        if (current.busy || current.awaitingExport || !current.loaded || !current.hasTarget || !current.compatibleTarget) return
        mutable.value = current.copy(pendingTarget = current.target, importStep = ImportStep.REVIEW,
            message = "请确认文件清单和目标，再完全退出游戏。")
    }
    fun changeImportTarget() {
        if (!state.value.busy) mutable.value = state.value.copy(pendingTarget = null, importStep = ImportStep.TARGET)
    }
    fun declareSource(edition: GameEdition) {
        val current = state.value
        if (current.busy || current.awaitingExport || current.importStep != ImportStep.TARGET || current.unknownSources == 0) return
        if (current.pending.any { it.edition != null && it.edition != edition }) {
            mutable.value = current.copy(message = "声明与已识别存档的所属服不一致，请按所属服分批选择。")
            return
        }
        mutable.value = bindEdition(current.copy(declaredEdition = edition, pendingTarget = null), edition)
    }
    private fun bindEdition(current: SaveState, edition: GameEdition?): SaveState {
        if (edition == null || edition == current.edition) return current
        return current.copy(edition = edition, accounts = emptyList(), account = "", saves = emptyList(),
            selected = emptySet(), loaded = false, exportResult = null)
    }
    fun prepareBuiltins(assets: Set<String>) = prepare {
        assets.map { asset ->
            val builtin = state.value.builtins.single { it.asset == asset }
            SaveInput(builtin.name, { app.assets.open(asset) }, builtin.edition)
        }
    }
    fun preparePhone(uris: List<Uri>) { if (uris.isNotEmpty()) prepare {
        uris.distinct().mapIndexed { index, uri ->
            val name = runCatching { app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } }.getOrNull()
            SaveInput(name ?: "所选文件 ${index + 1}", {
                require(name != null) { "文件应用没有提供文件名，请换一个文件入口。" }
                app.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("无法打开所选文件。")
            })
        }
    } }
    private fun prepare(sources: () -> List<SaveInput>) {
        if (state.value.busy || state.value.awaitingExport) return
        cancelImport()
        mutable.value = state.value.copy(importStep = ImportStep.CHECKING, results = emptyList())
        task("正在检查导入文件…") {
        val root = File(cache, UUID.randomUUID().toString()).apply { mkdirs() }
        val stager = SaveStager(root)
        val prepared = mutableListOf<StagedSave>()
        val errors = mutableListOf<SaveImportResult>()
        try {
            val input = sources()
            require(input.isNotEmpty() && input.size <= MAX_SAVE_COUNT) { "请选择 1 至 $MAX_SAVE_COUNT 个文件。" }
            input.forEach { (name, open, edition) ->
                try { open().use { stream ->
                    val staged = stager.stage(name, stream)
                    prepared += staged
                    if (edition != null) {
                        val start = prepared.size - staged.size
                        staged.forEachIndexed { index, save -> prepared[start + index] = save.withEdition(edition, SaveOrigin.BUILTIN) }
                    }
                } }
                catch (e: SaveCompatibilityException) { throw e }
                catch (e: Exception) { errors += SaveImportResult(name, error = friendly(e)) }
            }
            ensureSingleEdition(prepared)
            if (prepared.isEmpty()) root.deleteRecursively()
            update { bindEdition(it.copy(pending = prepared, pendingTarget = null, results = errors, declaredEdition = null,
                importStep = if (prepared.isEmpty()) ImportStep.RESULT else ImportStep.TARGET,
                importSummary = if (prepared.isEmpty()) "没有可导入的存档，请查看逐项结果并重新选文件。" else "",
                message = if (prepared.isEmpty()) "没有可导入的存档，请查看逐项结果。" else "已检查 ${prepared.size} 个存档，请确认所属服和导入位置。"), resolvedEdition(prepared, null)) }
        } catch (e: Exception) {
            root.deleteRecursively()
            update { it.copy(pending = emptyList(), pendingTarget = null, declaredEdition = null, importStep = ImportStep.RESULT,
                results = listOf(SaveImportResult("本次文件检查", error = friendly(e))), importSummary = friendly(e)) }
            throw e
        }
        }
    }
    fun cancelImport() {
        if (state.value.busy) return
        state.value.pending.forEach { it.file.delete(); it.file.parentFile?.delete() }
        mutable.value = state.value.copy(pending = emptyList(), pendingTarget = null, declaredEdition = null, importStep = ImportStep.SOURCE)
    }
    fun confirmImport() {
        val ready = state.value
        if (ready.busy || ready.awaitingExport || !ready.loaded || !ready.compatibleTarget || ready.importStep != ImportStep.REVIEW || ready.pendingTarget != ready.target) return
        mutable.value = state.value.copy(importStep = ImportStep.WRITING, completed = 0, total = state.value.pending.size)
        task("正在导入，请保持游戏关闭…") {
        val current = state.value
        val target = current.pendingTarget ?: throw IllegalArgumentException("导入任务已失效，请重新选择文件。")
        val checked = try {
            current.pending.map { save ->
                val resolved = if (save.edition == null) save.withEdition(requireNotNull(current.sourceEdition), SaveOrigin.USER) else save
                checkSaveEdition(target, resolved)
                resolved
            }
        } catch (e: Exception) {
            current.pending.forEach { it.file.delete(); it.file.parentFile?.delete() }
            update { it.copy(pending = emptyList(), pendingTarget = null, importStep = ImportStep.RESULT,
                results = it.results + SaveImportResult("写入前所属服检查", error = friendly(e)), importSummary = friendly(e)) }
            throw e
        }
        val results = current.results.toMutableList()
        var files: RemoteSaveFiles? = null
        checked.forEachIndexed { index, save ->
            try {
                val access = files ?: remote().also { files = it }
                results += SaveImportResult(save.name, savedAs = access.import(target, save))
            } catch (e: Exception) { results += SaveImportResult(save.name, error = friendly(e)) }
            finally { save.file.delete(); save.file.parentFile?.delete() }
            update { it.copy(completed = index + 1, message = "正在导入 ${index + 1} / ${current.pending.size}：${save.name}") }
        }
        val refreshed = runCatching { remote().list(target) }
        val summary = "导入完成：${results.count { r -> r.savedAs != null }} 个成功，${results.count { r -> r.error != null }} 个未完成。" +
                if (refreshed.isFailure) " 列表刷新失败，请重新连接后检查。" else ""
        update { it.copy(pending = emptyList(), pendingTarget = null, results = results, selected = emptySet(), importStep = ImportStep.RESULT,
            saves = refreshed.getOrDefault(it.saves),
            loaded = refreshed.isSuccess, message = summary, importSummary = summary, recent = (it.recent + SaveActivity(System.currentTimeMillis(), "${target.label} · $summary")).takeLast(20)) }
        }
    }
    fun prepareExport(launch: (SaveExportSpec) -> Unit) {
        if (state.value.busy || state.value.awaitingExport) return
        mutable.value = state.value.copy(exportMessage = "正在准备导出…", exportResult = null)
        export?.file?.delete(); export = null
        task("正在准备导出…", after = { export?.let { launch(it.spec) } }) {
            val current = state.value
            val saves = current.saves.filter { it.name in current.selected }
            require(saves.isNotEmpty()) { "请先选择要导出的存档。" }
            val file = File(cache, "export-${UUID.randomUUID()}")
            try { exportSaves(remote(), current.target, saves, file) }
            catch (e: Exception) { file.delete(); throw e }
            val spec = SaveExportSpec(if (saves.size == 1) saves.single().name else "Terraria-${current.edition.name.lowercase()}-${System.currentTimeMillis()}.zip",
                if (saves.size == 1) "application/octet-stream" else "application/zip")
            export = PreparedExport(file, spec)
            update { it.copy(message = "请选择存档的保存位置。", exportMessage = "请选择保存位置。", awaitingExport = true, exportResult = null) }
        }
    }
    fun exported(uri: Uri?) {
        val request = export; export = null
        mutable.value = state.value.copy(awaitingExport = false)
        if (uri == null) { request?.file?.delete(); mutable.value = state.value.copy(message = "已取消导出，已选存档已保留。", exportMessage = "已取消导出，已选存档已保留。"); return }
        mutable.value = state.value.copy(exportMessage = "正在保存并检查文件…")
        task("正在保存并检查导出文件…") {
            requireNotNull(request) { "导出任务已失效，请重新选择存档。" }
            try {
                val path = Uri.decode(uri.toString()).lowercase()
                require(!path.contains("android/data/com.and.games505.terrariapaid") && !path.contains("android/data/com.xd.terraria")) { "请将导出的存档保存到下载等普通文件夹。" }
                val descriptor = app.contentResolver.openFileDescriptor(uri, "rwt") ?: throw IllegalArgumentException("无法创建导出文件。")
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    request.file.inputStream().use { it.copyTo(output) }; output.flush(); output.fd.sync(); descriptor.checkError()
                }
                val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
                app.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; size += n; require(size <= request.file.length()) { "导出文件校验未通过。" }; digest.update(buffer, 0, n) }
                } ?: throw IllegalArgumentException("无法检查导出的文件，请换一个保存位置。")
                require(size == request.file.length() && digest.digest().joinToString("") { "%02x".format(it) } == saveFileHash(request.file)) { "导出文件校验未通过，请换一个保存位置后重试。" }
                update { it.copy(message = "已导出并检查文件：${request.spec.name}", exportMessage = "已保存并检查：${request.spec.name}", exportResult = SaveExportResult(request.spec.name, uri),
                    recent = (it.recent + SaveActivity(System.currentTimeMillis(), "已导出：${request.spec.name}")).takeLast(20)) }
            } finally { request.file.delete() }
        }
    }
    fun pickerUnavailable() { export?.file?.delete(); export = null; mutable.value = state.value.copy(awaitingExport = false, message = "无法打开系统文件应用，请确认手机提供文件选择功能。", exportMessage = "无法打开系统文件应用，请确认手机提供文件选择功能。") }
    fun openExportUnavailable() { mutable.value = state.value.copy(message = "文件已保存，手机没有可打开此格式的应用。可在保存时选择的位置找到文件。",
        exportMessage = "文件已保存，手机没有可打开此格式的应用。可在保存时选择的位置找到文件。") }
    private suspend fun update(change: (SaveState) -> SaveState) = withContext(Dispatchers.Main) { mutable.value = change(mutable.value) }
    private fun task(message: String, after: (() -> Unit)? = null, work: suspend () -> Unit) {
        if (state.value.busy || state.value.awaitingExport) return
        mutable.value = state.value.copy(busy = true, message = message)
        scope.launch {
            try { withContext(Dispatchers.IO) { work() }; after?.invoke() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = state.value.copy(message = friendly(e), loaded = if (message == "正在读取存档…") false else state.value.loaded,
                exportMessage = if (message.contains("导出")) friendly(e) else state.value.exportMessage,
                importSummary = if (state.value.importStep == ImportStep.CHECKING) friendly(e) else state.value.importSummary,
                importStep = if (state.value.importStep == ImportStep.CHECKING) ImportStep.RESULT else state.value.importStep) }
            finally { mutable.value = state.value.copy(busy = false) }
        }
    }
    private fun friendly(e: Exception): String = when (e) {
        is ToolException -> e.userMessage
        is IllegalArgumentException -> e.message ?: "文件格式不受支持，请选择 .plr、.wld 或 ZIP。"
        else -> "无法完成存档操作，请检查文件权限和剩余空间后重试。"
    }
}
