package com.example.terrariatoolbox.domain

import java.util.UUID

data class CopyWorkspace(val id: String, val source: SourceInfo, val contentId: String,
    val size: Long, val hash: String, val updatedAt: Long)

interface CopyWorkspaceRepository {
    fun load(): CopyWorkspace?
    fun read(workspace: CopyWorkspace): ByteArray
    /** Commit immutable content first, then the single current-workspace pointer. */
    fun commit(id: String, source: SourceInfo, bytes: ByteArray): CopyWorkspace
}

data class PreparedCopyImport(val source: SourceInfo, val bytes: ByteArray, val previousId: String?)

class CopyCoordinator(private val store: CopyWorkspaceRepository, private val documents: DocumentAccess,
    private val backups: BackupRepository, private val writer: WriteCoordinator, private val processor: ConfigProcessor) {
    fun prepareImport(source: SourceInfo): PreparedCopyImport {
        val bytes = documents.read(source.uri)
        processor.requireSupported(bytes)
        return PreparedCopyImport(source.copy(knownTarget = false, confirmed = false, writable = false,
            selection = "导入副本", lastVerifiedAt = null), bytes, store.load()?.id)
    }

    fun import(prepared: PreparedCopyImport): CopyWorkspace {
        processor.requireSupported(prepared.bytes)
        val current = store.load()
        if (current?.id != prepared.previousId) throw ToolException("当前副本已变化，请重新导入。")
        if (current != null) backup(current, store.read(current), "替换编辑副本前")
        return store.commit(UUID.randomUUID().toString(), prepared.source, prepared.bytes)
    }

    fun generateChinese(): CopyWorkspace {
        val current = requireWorkspace()
        val original = store.read(current)
        if (processor.requireSupported(original).language == Language.CHINESE) return current
        backup(current, original, "生成中文副本前")
        val candidate = processor.chineseCandidate(original)
        return store.commit(current.id, current.source, candidate)
    }

    fun restore(record: BackupRecord): CopyWorkspace {
        val current = requireWorkspace()
        if (record.workspaceId != current.id || record.source.identity != current.source.identity ||
            record.source.accessMethod != current.source.accessMethod ||
            record.tool != "chinese-language" || record.fileType != "terraria-config-json")
            throw ToolException("备份不属于当前编辑副本，只能导出备份。")
        val candidate = backups.read(record)
        if (candidate.size.toLong() != record.size || contentHash(candidate) != record.hash)
            throw ToolException("副本原始备份损坏，禁止恢复。")
        processor.requireSupported(candidate)
        backup(current, store.read(current), "恢复编辑副本前")
        return store.commit(current.id, current.source, candidate)
    }

    fun saveBackup(): BackupRecord {
        val current = requireWorkspace()
        return backup(current, store.read(current), "用户备份编辑副本")
    }

    fun export(destination: String, protectedSources: List<String>) {
        val current = requireWorkspace()
        writer.export(destination, store.read(current), protectedSources + current.source.uri + SHIZUKU_URI)
    }

    private fun backup(current: CopyWorkspace, bytes: ByteArray, reason: String): BackupRecord {
        val record = backups.save(current.source, bytes, reason, processor.requireSupported(bytes), current.id)
        if (!backups.read(record).contentEquals(bytes)) throw ToolException("副本原始备份校验失败，已取消操作。")
        return record
    }

    private fun requireWorkspace() = store.load() ?: throw ToolException("请先导入配置副本。")
}
