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
    val workspaceId: String? = null,
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
    fun save(source: SourceInfo, bytes: ByteArray, reason: String, config: ConfigDocument?, workspaceId: String? = null): BackupRecord
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
    fun switchToChinese(source: SourceInfo): WriteResult = switchLanguage(source, Language.CHINESE)

    fun switchLanguage(source: SourceInfo, target: Language): WriteResult {
        require(target != Language.UNSUPPORTED)
        requireTarget(source)
        if (journal.load() != null) throw ToolException("上次操作还未确认完成，请先检查上次操作或恢复备份。", true)
        val original = documents.read(source.uri)
        val config = processor.requireSupported(original)
        if (config.language == target) return WriteResult(false, null, null)
        val backup = backups.save(source, original, if (target == Language.CHINESE) "切换中文前" else "切换英文前", config)
        // Even alternative repositories must prove the backup is readable before any write.
        if (contentHash(backups.read(backup)) != contentHash(original)) {
            throw ToolException("备份未能安全保存，已取消切换。请检查手机剩余空间。")
        }
        val candidate = processor.languageCandidate(original, target)
        return write(source, original, candidate, backup, "switch") {
            if (contentHash(it) != contentHash(candidate)) throw ToolException("保存后的设置检查未通过。")
            processor.verifyLanguage(original, it, target)
        }
    }

    fun restore(source: SourceInfo, record: BackupRecord): WriteResult {
        requireTarget(source)
        if (record.workspaceId != null || record.source.identity != source.identity || record.source.accessMethod != source.accessMethod || record.tool != "chinese-language" ||
            record.fileType != "terraria-config-json") {
            throw ToolException("这份备份不属于当前游戏设置，可以导出保存，但不能直接恢复。")
        }
        val pending = journal.load()
        if (pending != null && (pending.source.identity != source.identity || pending.source.accessMethod != source.accessMethod)) {
            throw ToolException("旧操作还未完成，请先导出对应备份进行恢复。", true)
        }
        val candidate = backups.read(record)
        processor.requireSupported(candidate)
        val original = documents.read(source.uri)
        // A damaged target can still be preserved byte-for-byte before recovering it.
        val backup = backups.save(source, original, "完整恢复前", runCatching { processor.inspect(original) }.getOrNull())
        if (contentHash(backups.read(backup)) != contentHash(original)) throw ToolException("当前设置未能安全备份，已取消恢复。请检查手机剩余空间。")
        return write(source, original, candidate, backup, "restore") {
            if (contentHash(it) != record.hash) throw ToolException("恢复后的设置检查未通过，请重新恢复备份。")
        }
    }

    fun verifyPending(source: SourceInfo): WriteResult {
        requireTarget(source, needWrite = false)
        val pending = journal.load() ?: throw ToolException("没有需要检查的操作。")
        if (pending.source.identity != source.identity || pending.source.accessMethod != source.accessMethod) throw ToolException("请在首页重新连接 Shizuku，再检查上次操作。", true)
        val bytes = documents.read(source.uri)
        if (contentHash(bytes) != pending.expectedHash) throw ToolException("上次操作没有完成，请恢复备份后再打开游戏。", true)
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
                throw ToolException("导出的文件检查未通过，请换一个保存位置重试。")
            }
        } catch (error: ToolException) {
            throw ToolException("导出的文件未能确认保存成功，请换一个位置重试。\n${error.userMessage}")
        }
        catch (_: Exception) { throw ToolException("导出的文件未能确认保存成功，请换一个位置重试。") }
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
                throw ToolException("游戏设置在操作时发生变化，已取消保存。请完全退出游戏后刷新。")
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
                throw ToolException("保存前无法读取游戏设置，已取消操作。备份已保留，请重新连接后重试。")
            }
            throw ToolException("保存未能确认完成，先不要打开游戏。备份已保留，请检查上次操作或恢复备份。", true)
        }
    }

    private fun requireTarget(source: SourceInfo, needWrite: Boolean = true) {
        if (!source.knownTarget || !source.confirmed) throw ToolException("还没有找到可修改的游戏设置，请重新连接 Shizuku。")
        if (needWrite && !source.writable) throw ToolException("当前无法保存游戏设置，请重新连接并允许 Shizuku 授权。")
    }
}
