package com.example.terrariatoolbox

import com.example.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.Test

internal fun testSource(id: String = TARGET_DOCUMENT_ID) = SourceInfo("content://test/$id", "content://test/$id",
    "test|$id", "test", id, "config.json", "文件授权", true, true, true, true)

private class MemoryDocuments : DocumentAccess {
    var bytes = "{\"Language\":1,\"value\":12345678901234567890}".toByteArray()
    var reads = 0
    var writes = 0
    var changeAtRead: Int? = null
    var failAtRead: Int? = null
    var shortWrite = false
    var failWrite = false
    var rejectBeforeWrite = false
    var lastExpectedHash: String? = null
    override fun read(uri: String): ByteArray {
        reads++
        if (reads == failAtRead) throw java.io.IOException()
        if (reads == changeAtRead) bytes = "{\"Language\":1,\"value\":false}".toByteArray()
        return bytes.copyOf()
    }
    override fun overwrite(uri: String, bytes: ByteArray, expectedBeforeHash: String?) {
        lastExpectedHash = expectedBeforeHash
        if (rejectBeforeWrite) throw WriteNotStartedException("源文件发生变化，已取消写入。")
        writes++
        this.bytes = if (shortWrite || failWrite) bytes.take(5).toByteArray() else bytes.copyOf()
        if (failWrite) throw java.io.IOException()
    }
    override fun identity(uri: String): String = uri.removePrefix("alias:")
}

private class MemoryBackups : BackupRepository {
    val entries = mutableListOf<Pair<BackupRecord, ByteArray>>()
    var fail = false
    var corrupt = false
    override fun save(source: SourceInfo, bytes: ByteArray, reason: String, config: ConfigDocument?, workspaceId: String?): BackupRecord {
        if (fail) throw ToolException("备份失败")
        val record = BackupRecord("backup-${entries.size}", 1, source, bytes.size.toLong(), contentHash(bytes), config?.languageNumber, config?.version, reason, workspaceId = workspaceId)
        entries.add(record to bytes.copyOf())
        return record
    }
    override fun read(record: BackupRecord) = if (corrupt) byteArrayOf(0) else entries.single { it.first.id == record.id }.second.copyOf()
    override fun list() = entries.map { it.first }
}

private class MemoryJournal : OperationJournal {
    var pending: PendingOperation? = null
    var failSave = false
    override fun load() = pending
    override fun save(operation: PendingOperation) { if (failSave) throw ToolException("记录失败"); pending = operation }
    override fun clear() { pending = null }
}

class WriteCoordinatorTest {
    private val source = testSource()
    private val documents = MemoryDocuments()
    private val backups = MemoryBackups()
    private val journal = MemoryJournal()
    private val processor = ConfigProcessor()
    private val coordinator = WriteCoordinator(documents, backups, journal, processor) { 99 }

    @Test fun successfulSwitchBacksUpOriginalAndVerifiesSameTarget() {
        val original = documents.bytes.copyOf()
        val result = coordinator.switchToChinese(source)
        assertTrue(result.changed)
        assertEquals(1, documents.writes)
        assertEquals(3, documents.reads)
        assertArrayEquals(original, backups.read(result.backup!!))
        processor.verifyChinese(original, documents.bytes)
        assertNull(journal.pending)
        assertEquals(contentHash(original), documents.lastExpectedHash)
    }
    @Test fun backendHashConflictBeforeTruncationKeepsOriginalAndDoesNotRequireRecovery() {
        val original = documents.bytes.copyOf()
        documents.rejectBeforeWrite = true
        assertFalse(assertThrows(ToolException::class.java) { coordinator.switchToChinese(source) }.needsRecovery)
        assertArrayEquals(original, documents.bytes)
        assertEquals(0, documents.writes); assertNull(journal.pending)
        assertEquals(1, backups.entries.size)
    }
    @Test fun backendMismatchPreventsRestoreEvenIfStoredIdentityWasCopied() {
        val record = backups.save(source.copy(accessMethod = AccessMethod.SHIZUKU), documents.bytes, "test", processor.inspect(documents.bytes))
        assertThrows(ToolException::class.java) { coordinator.restore(source, record) }
        assertEquals(0, documents.writes)
    }
    @Test fun chineseNoOpDoesNotBackupOrWrite() {
        documents.bytes = "{\"Language\":7}".toByteArray()
        assertFalse(coordinator.switchToChinese(source).changed)
        assertEquals(0, documents.writes)
        assertTrue(backups.entries.isEmpty())
    }
    @Test fun backupFailureAndCorruptionPreventWrites() {
        backups.fail = true
        assertThrows(ToolException::class.java) { coordinator.switchToChinese(source) }
        backups.fail = false; backups.corrupt = true
        assertThrows(ToolException::class.java) { coordinator.switchToChinese(source) }
        assertEquals(0, documents.writes)
    }
    @Test fun sourceChangeCancelsWriteAndKeepsBackup() {
        documents.changeAtRead = 2
        assertThrows(ToolException::class.java) { coordinator.switchToChinese(source) }
        assertEquals(0, documents.writes)
        assertEquals(1, backups.entries.size)
        assertNull(journal.pending)
    }
    @Test fun sourceRevocationBeforeWritePreventsMutation() {
        documents.failAtRead = 2
        assertThrows(ToolException::class.java) { coordinator.switchToChinese(source) }
        assertEquals(0, documents.writes)
        assertNull(journal.pending)
    }
    @Test fun interruptedWriteRetainsJournalAndCanRestoreDamagedTarget() {
        val original = documents.bytes.copyOf()
        documents.failWrite = true
        assertTrue(assertThrows(ToolException::class.java) { coordinator.switchToChinese(source) }.needsRecovery)
        val pending = journal.pending!!
        assertArrayEquals(original, backups.read(backups.entries.first().first))
        val damaged = documents.bytes.copyOf()
        documents.failWrite = false
        coordinator.restore(source, backups.entries.first().first)
        assertArrayEquals(original, documents.bytes)
        assertArrayEquals(damaged, backups.entries.last().second)
        assertEquals("backup-0", pending.backupId)
        assertNull(journal.pending)
    }
    @Test fun shortWriteAndReadbackFailureNeverReportSuccess() {
        documents.shortWrite = true
        assertTrue(assertThrows(ToolException::class.java) { coordinator.switchToChinese(source) }.needsRecovery)
        assertNotNull(journal.pending)
    }
    @Test fun readbackRevocationKeepsRecoveryRecord() {
        documents.failAtRead = 3
        assertTrue(assertThrows(ToolException::class.java) { coordinator.switchToChinese(source) }.needsRecovery)
        assertNotNull(journal.pending)
    }
    @Test fun pendingOperationBlocksFurtherSwitchesAndCanBeRecheckedAfterRestart() {
        val original = documents.bytes.copyOf()
        val candidate = processor.chineseCandidate(original)
        val backup = backups.save(source, original, "test", processor.inspect(original))
        journal.save(PendingOperation(source, backup.id, contentHash(original), contentHash(candidate), "switch", 1))
        assertThrows(ToolException::class.java) { coordinator.switchToChinese(source) }
        documents.bytes = candidate
        val restarted = WriteCoordinator(documents, backups, journal, processor)
        restarted.verifyPending(source)
        assertNull(journal.pending)
        assertEquals(0, documents.writes)
    }
    @Test fun invalidOriginReadOnlyAndDifferentBackupSourcePreventWrites() {
        assertThrows(ToolException::class.java) { coordinator.switchToChinese(source.copy(confirmed = false)) }
        assertThrows(ToolException::class.java) { coordinator.switchToChinese(source.copy(knownTarget = false)) }
        assertThrows(ToolException::class.java) { coordinator.switchToChinese(source.copy(writable = false)) }
        val backup = backups.save(testSource("another"), documents.bytes, "test", processor.inspect(documents.bytes))
        assertThrows(ToolException::class.java) { coordinator.restore(source, backup) }
        assertEquals(0, documents.writes)
    }
    @Test fun journalFailurePreventsExternalWrites() {
        journal.failSave = true
        assertThrows(ToolException::class.java) { coordinator.switchToChinese(source) }
        assertEquals(0, documents.writes)
    }
    @Test fun restoreConflictPreservesPreviousPendingRecord() {
        val backup = backups.save(source, documents.bytes, "test", processor.inspect(documents.bytes))
        val pending = PendingOperation(source, backup.id, "old", "expected", "switch", 1)
        journal.save(pending)
        documents.changeAtRead = 2
        assertThrows(ToolException::class.java) { coordinator.restore(source, backup) }
        assertEquals(pending, journal.pending)
        assertEquals(0, documents.writes)
    }
    @Test fun exportRejectsSourceAliasesBeforeOpeningAndVerifiesOutput() {
        assertThrows(ToolException::class.java) { coordinator.export("alias:${source.uri}", byteArrayOf(1), listOf(source.uri)) }
        assertEquals(0, documents.writes)
        coordinator.export("new-document", byteArrayOf(1, 2, 3), listOf(source.uri))
        assertArrayEquals(byteArrayOf(1, 2, 3), documents.bytes)
        documents.shortWrite = true
        assertThrows(ToolException::class.java) { coordinator.export("another", ByteArray(20), listOf(source.uri)) }
    }
}
