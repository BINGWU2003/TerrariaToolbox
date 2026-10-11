package com.example.terrariatoolbox

import com.example.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.Test

class RoutingDocumentAccessTest {
    private class Access(private val id: String) : DocumentAccess {
        var bytes = byteArrayOf(1)
        var expected: String? = null
        var writes = 0
        override fun read(uri: String) = bytes
        override fun overwrite(uri: String, bytes: ByteArray, expectedBeforeHash: String?) { this.bytes = bytes; expected = expectedBeforeHash; writes++ }
        override fun identity(uri: String) = if (uri.contains("target")) "com.android.externalstorage.documents|$TARGET_DOCUMENT_ID" else id
    }
    private val saf = Access("ordinary")
    private val shell = Access(SHIZUKU_IDENTITY)
    private val router = RoutingDocumentAccess(saf, shell)
    @Test fun routesReadsWritesAndExpectedHashToChosenBackend() {
        router.overwrite(SHIZUKU_URI, byteArrayOf(2), "before")
        assertEquals("before", shell.expected); assertEquals(0, saf.writes)
        assertArrayEquals(byteArrayOf(2), router.read(SHIZUKU_URI))
        router.overwrite("content://provider/file", byteArrayOf(3))
        assertNull(saf.expected); assertEquals(1, saf.writes)
        assertThrows(ToolException::class.java) { router.read("file://$TARGET_PATH") }
        assertThrows(ToolException::class.java) { router.overwrite(SHIZUKU_URI + "?path=other", byteArrayOf()) }
    }
    @Test fun targetDocumentAliasIsRejectedForExportBeforeOpeningDestination() {
        val backups = object : BackupRepository {
            override fun save(source: SourceInfo, bytes: ByteArray, reason: String, config: ConfigDocument?): BackupRecord = error("unused")
            override fun read(record: BackupRecord): ByteArray = error("unused")
            override fun list() = emptyList<BackupRecord>()
        }
        val journal = object : OperationJournal {
            override fun load(): PendingOperation? = null
            override fun save(operation: PendingOperation) = Unit
            override fun clear() = Unit
        }
        val coordinator = WriteCoordinator(router, backups, journal, ConfigProcessor())
        assertThrows(ToolException::class.java) { coordinator.export("content://target", byteArrayOf(1), listOf(SHIZUKU_URI)) }
        assertEquals(0, saf.writes); assertEquals(0, shell.writes)
    }
}
