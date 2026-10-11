package com.example.terrariatoolbox.feature.saves

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.example.terrariatoolbox.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class BuiltinSave(val asset: String, val name: String, val size: Long)
data class SaveState(
    val edition: GameEdition = GameEdition.INTERNATIONAL,
    val accounts: List<String> = emptyList(), val account: String = "",
    val saves: List<GameSave> = emptyList(), val selected: Set<String> = emptySet(),
    val builtins: List<BuiltinSave> = emptyList(),
    val busy: Boolean = false, val loaded: Boolean = false,
    val message: String = "连接 Shizuku 后，选择游戏版本查看存档。",
    val pending: List<StagedSave> = emptyList(), val pendingTarget: SaveTarget? = null,
    val results: List<SaveImportResult> = emptyList(),
) {
    val target get() = SaveTarget(edition, account)
    val hasTarget get() = edition == GameEdition.INTERNATIONAL || account.isNotEmpty()
}
data class SaveExportSpec(val name: String, val mime: String)
private data class PreparedExport(val file: File, val spec: SaveExportSpec)

class SaveManager(private val app: Application, private val remote: () -> RemoteSaveFiles, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(SaveState())
    val state: StateFlow<SaveState> = mutable
    private val cache = File(app.cacheDir, "save-transfer").apply { mkdirs() }
    private var export: PreparedExport? = null
    init {
        // Only this manager's disposable cache is cleaned; supplied assets and game files are untouched.
        cache.listFiles()?.forEach { it.deleteRecursively() }
        scope.launch {
            val builtins = withContext(Dispatchers.IO) {
                listOf("players", "worlds").flatMap { dir ->
                    app.assets.list(dir).orEmpty().filter { runCatching { SaveNames.directory(it) }.isSuccess }.map { name ->
                        val asset = "$dir/$name"
                        BuiltinSave(asset, name, app.assets.open(asset).use { it.available().toLong() })
                    }
                }
            }
            mutable.value = mutable.value.copy(builtins = builtins)
        }
    }
    fun edition(edition: GameEdition) {
        if (state.value.busy) return
        cancelImport(); export?.file?.delete(); export = null
        mutable.value = state.value.copy(edition = edition, accounts = emptyList(), account = "", saves = emptyList(), selected = emptySet(), loaded = false, results = emptyList())
        refresh()
    }
    fun account(account: String) {
        if (state.value.busy || account !in state.value.accounts) return
        cancelImport()
        mutable.value = state.value.copy(account = account, saves = emptyList(), selected = emptySet(), results = emptyList())
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
        update { it.copy(accounts = accounts, account = account, saves = saves, selected = it.selected.intersect(saves.map { s -> s.name }.toSet()), loaded = true,
            message = when { accounts.isEmpty() && current.edition == GameEdition.CHINESE -> "没有找到国服账号。请先登录游戏，再完全退出后刷新。"
                account.isEmpty() && current.edition == GameEdition.CHINESE -> "找到多个国服账号，请选择要管理的账号。"
                saves.isEmpty() -> "当前没有角色或世界存档，可以从内置存档或手机文件导入。"
                else -> "已读取 ${saves.size} 个存档。" }) }
    }
    fun prepareBuiltins(assets: Set<String>) = prepare {
        assets.map { asset ->
            val builtin = state.value.builtins.single { it.asset == asset }
            builtin.name to { app.assets.open(asset) }
        }
    }
    fun preparePhone(uris: List<Uri>) { if (uris.isNotEmpty()) prepare {
        uris.distinct().mapIndexed { index, uri ->
            val name = runCatching { app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } }.getOrNull()
            (name ?: "所选文件 ${index + 1}") to {
                require(name != null) { "文件应用没有提供文件名，请换一个文件入口。" }
                app.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("无法打开所选文件。")
            }
        }
    } }
    private fun prepare(sources: () -> List<Pair<String, () -> java.io.InputStream>>) = task("正在检查导入文件…") {
        val target = state.value.target
        require(state.value.hasTarget) { "请先选择国服账号。" }
        remote().list(target)
        val root = File(cache, UUID.randomUUID().toString()).apply { mkdirs() }
        val stager = SaveStager(root)
        val prepared = mutableListOf<StagedSave>()
        val errors = mutableListOf<SaveImportResult>()
        try {
            val input = sources()
            require(input.size <= MAX_SAVE_COUNT) { "一次最多选择 $MAX_SAVE_COUNT 个文件。" }
            input.forEach { (name, open) ->
                try { open().use { prepared += stager.stage(name, it) } }
                catch (e: Exception) { errors += SaveImportResult(name, error = friendly(e)) }
            }
            update { it.copy(pending = prepared, pendingTarget = if (prepared.isEmpty()) null else target, results = errors,
                message = if (prepared.isEmpty()) "没有可导入的存档，请查看逐项结果。" else "已检查 ${prepared.size} 个存档，请确认导入位置。") }
        } catch (e: Exception) { root.deleteRecursively(); throw e }
    }
    fun cancelImport() {
        if (state.value.busy) return
        state.value.pending.forEach { it.file.delete(); it.file.parentFile?.delete() }
        mutable.value = state.value.copy(pending = emptyList(), pendingTarget = null)
    }
    fun confirmImport() = task("正在导入，请保持游戏关闭…") {
        val current = state.value
        val target = current.pendingTarget ?: throw IllegalArgumentException("导入任务已失效，请重新选择文件。")
        val results = current.results.toMutableList()
        var files: RemoteSaveFiles? = null
        current.pending.forEach { save ->
            try {
                val access = files ?: remote().also { files = it }
                results += SaveImportResult(save.name, savedAs = access.import(target, save))
            } catch (e: Exception) { results += SaveImportResult(save.name, error = friendly(e)) }
            finally { save.file.delete(); save.file.parentFile?.delete() }
        }
        val refreshed = runCatching { remote().list(target) }
        update { it.copy(pending = emptyList(), pendingTarget = null, results = results, selected = emptySet(),
            saves = refreshed.getOrDefault(it.saves),
            message = "导入完成：${results.count { r -> r.savedAs != null }} 个成功，${results.count { r -> r.error != null }} 个未完成。" +
                if (refreshed.isFailure) " 列表刷新失败，请重新连接后检查。" else "") }
    }
    fun prepareExport(launch: (SaveExportSpec) -> Unit) {
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
            update { it.copy(message = "请选择存档的保存位置。") }
        }
    }
    fun exported(uri: Uri?) {
        val request = export; export = null
        if (uri == null) { request?.file?.delete(); mutable.value = state.value.copy(message = "已取消导出。"); return }
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
                update { it.copy(message = "已导出并检查文件：${request.spec.name}") }
            } finally { request.file.delete() }
        }
    }
    fun pickerUnavailable() { export?.file?.delete(); export = null; mutable.value = state.value.copy(message = "无法打开系统文件应用，请确认手机提供文件选择功能。") }
    private suspend fun update(change: (SaveState) -> SaveState) = withContext(Dispatchers.Main) { mutable.value = change(mutable.value) }
    private fun task(message: String, after: (() -> Unit)? = null, work: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.value = state.value.copy(busy = true, message = message)
        scope.launch {
            try { withContext(Dispatchers.IO) { work() }; after?.invoke() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = state.value.copy(message = friendly(e)) }
            finally { mutable.value = state.value.copy(busy = false) }
        }
    }
    private fun friendly(e: Exception): String = when (e) {
        is ToolException -> e.userMessage
        is IllegalArgumentException -> e.message ?: "文件格式不受支持，请选择 .plr、.wld 或 ZIP。"
        else -> "无法完成存档操作，请检查文件权限和剩余空间后重试。"
    }
}
