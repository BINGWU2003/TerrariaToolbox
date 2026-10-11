package com.example.terrariatoolbox.domain

const val GAME_PACKAGE = "com.and.games505.TerrariaPaid"
const val TARGET_DOCUMENT_ID = "primary:Android/data/$GAME_PACKAGE/config.json"
const val TARGET_PATH = "/storage/emulated/0/Android/data/$GAME_PACKAGE/config.json"
const val SHIZUKU_URI = "shizuku://shell-user0/terraria-config"
const val SHIZUKU_IDENTITY = "shizuku|shell:2000|user:0|$TARGET_PATH"
enum class AccessMethod { SAF, SHIZUKU }

data class SourceInfo(
    val uri: String,
    val grantUri: String,
    val identity: String,
    val authority: String,
    val documentId: String,
    val displayName: String,
    val selection: String,
    val writable: Boolean,
    val persisted: Boolean,
    val knownTarget: Boolean,
    val confirmed: Boolean = false,
    val lastVerifiedAt: Long? = null,
    val accessMethod: AccessMethod = AccessMethod.SAF,
)

data class BackupRecord(
    val id: String,
    val createdAt: Long,
    val source: SourceInfo,
    val size: Long,
    val hash: String,
    val language: String?,
    val gameVersion: String?,
    val reason: String,
    val tool: String = "chinese-language",
    val fileType: String = "terraria-config-json",
)

data class PendingOperation(
    val source: SourceInfo,
    val backupId: String,
    val beforeHash: String,
    val expectedHash: String,
    val kind: String,
    val startedAt: Long,
)

interface DocumentAccess {
    fun read(uri: String): ByteArray
    fun overwrite(uri: String, bytes: ByteArray, expectedBeforeHash: String? = null)
    fun identity(uri: String): String
}

interface BackupRepository {
    fun save(source: SourceInfo, bytes: ByteArray, reason: String, config: ConfigDocument?): BackupRecord
    fun read(record: BackupRecord): ByteArray
    fun list(): List<BackupRecord>
}

interface OperationJournal {
    fun load(): PendingOperation?
    fun save(operation: PendingOperation)
    fun clear()
}

data class WriteResult(val changed: Boolean, val backup: BackupRecord?, val verifiedAt: Long?)

/** Synchronous on purpose: one caller holds a mutex on IO for the whole transaction. */
class WriteCoordinator(
    private val documents: DocumentAccess,
    private val backups: BackupRepository,
    private val journal: OperationJournal,
    private val processor: ConfigProcessor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun switchToChinese(source: SourceInfo): WriteResult {
        requireTarget(source)
        if (journal.load() != null) throw ToolException("存在未完成的写入，请先检查或恢复备份。", true)
        val original = documents.read(source.uri)
        val config = processor.requireSupported(original)
        if (config.language == Language.CHINESE) return WriteResult(false, null, null)
        val backup = backups.save(source, original, "切换中文前", config)
        // Even alternative repositories must prove the backup is readable before any write.
        if (contentHash(backups.read(backup)) != contentHash(original)) {
            throw ToolException("备份校验失败，已取消写入。")
        }
        val candidate = processor.chineseCandidate(original)
        return write(source, original, candidate, backup, "switch") {
            if (contentHash(it) != contentHash(candidate)) throw ToolException("写回内容与候选配置不一致。")
            processor.verifyChinese(original, it)
        }
    }

    fun restore(source: SourceInfo, record: BackupRecord): WriteResult {
        requireTarget(source)
        if (record.source.identity != source.identity || record.source.accessMethod != source.accessMethod || record.tool != "chinese-language" ||
            record.fileType != "terraria-config-json") {
            throw ToolException("备份来源或文件类型与目标不同，只能导出备份。")
        }
        val pending = journal.load()
        if (pending != null && (pending.source.identity != source.identity || pending.source.accessMethod != source.accessMethod)) {
            throw ToolException("请先处理另一个目标的未完成写入。", true)
        }
        val candidate = backups.read(record)
        processor.requireSupported(candidate)
        val original = documents.read(source.uri)
        // A damaged target can still be preserved byte-for-byte before recovering it.
        val backup = backups.save(source, original, "完整恢复前", runCatching { processor.inspect(original) }.getOrNull())
        if (contentHash(backups.read(backup)) != contentHash(original)) throw ToolException("当前文件备份校验失败，已取消恢复。")
        return write(source, original, candidate, backup, "restore") {
            if (contentHash(it) != record.hash) throw ToolException("恢复后的文件与原始备份不一致。")
        }
    }

    fun verifyPending(source: SourceInfo): WriteResult {
        requireTarget(source, needWrite = false)
        val pending = journal.load() ?: throw ToolException("没有待校验的操作。")
        if (pending.source.identity != source.identity || pending.source.accessMethod != source.accessMethod) throw ToolException("请重新授权未完成操作的原目标。", true)
        val bytes = documents.read(source.uri)
        if (contentHash(bytes) != pending.expectedHash) throw ToolException("目标与预期结果不一致，请恢复或导出原始备份。", true)
        processor.requireSupported(bytes)
        journal.clear()
        return WriteResult(true, null, clock())
    }

    fun export(destination: String, bytes: ByteArray, protectedSources: List<String>) {
        val outputId = documents.identity(destination)
        if (protectedSources.any { it == destination || documents.identity(it) == outputId }) {
            throw ToolException("不能导出覆盖源文件，请选择新的保存位置。")
        }
        try {
            documents.overwrite(destination, bytes)
            if (contentHash(documents.read(destination)) != contentHash(bytes)) {
                throw ToolException("导出回读校验失败，保存结果未确认，请换位置重试。")
            }
        } catch (error: ToolException) { throw error }
        catch (_: Exception) { throw ToolException("导出失败或无法回读，保存结果未确认，请换位置重试。") }
    }

    private fun write(
        source: SourceInfo, original: ByteArray, candidate: ByteArray, backup: BackupRecord,
        kind: String, verify: (ByteArray) -> Unit,
    ): WriteResult {
        val previousPending = journal.load()
        val pending = PendingOperation(source, backup.id, contentHash(original), contentHash(candidate), kind, clock())
        journal.save(pending)
        var attempted = false
        try {
            if (contentHash(documents.read(source.uri)) != pending.beforeHash) {
                throw ToolException("源文件在操作期间发生变化，已取消写入。请退出游戏并重新加载。")
            }
            attempted = true
            documents.overwrite(source.uri, candidate, pending.beforeHash)
            verify(documents.read(source.uri))
            journal.clear()
            return WriteResult(true, backup, clock())
        } catch (error: Exception) {
            if (!attempted || error is WriteNotStartedException) {
                // A failed recovery must not erase the previous interruption record.
                if (previousPending == null) journal.clear() else journal.save(previousPending)
                if (error is ToolException) throw error
                throw ToolException("写入前无法重新读取目标，已取消操作，原始备份已保留。")
            }
            throw ToolException("写入或回读校验未完成，暂勿启动游戏。原始备份已保留，请恢复或导出备份。", true)
        }
    }

    private fun requireTarget(source: SourceInfo, needWrite: Boolean = true) {
        if (!source.knownTarget || !source.confirmed) throw ToolException("来源尚未确认为游戏原配置，只允许读取或导出备份。")
        if (needWrite && !source.writable) throw ToolException("当前授权仅可读取，无法直接写回，请重新授权。")
    }
}
