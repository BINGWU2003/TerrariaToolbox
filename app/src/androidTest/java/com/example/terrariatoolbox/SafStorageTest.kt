package com.example.terrariatoolbox

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.terrariatoolbox.data.*
import com.example.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SafStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val uri = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "config")
    private val destination = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "export")
    private val bytes = "{\r\n \"Language\":1,\"number\":12345678901234567890,\"array\":[true,null,{}]\r\n}".toByteArray()
    private val processor = ConfigProcessor()
    private val access = SafDocumentAccess(context)
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    @Before fun reset() { resolver.call(TEST_CONTROL_AUTHORITY, "test.reset", null, Bundle().apply { putByteArray("bytes", bytes) }) }

    @Test fun selectionDoesNotMisidentifySameNamedCopyAsGameOriginal() {
        val selected = access.select(uri, flags, false)
        assertFalse(selected.knownTarget)
        assertFalse(selected.confirmed)
        assertFalse(selected.persisted)
        assertTrue(selected.writable)
        assertArrayEquals(bytes, access.read(selected.uri))
        val tree = DocumentsContract.buildTreeDocumentUri(TEST_AUTHORITY, "root")
        val directory = access.select(tree, flags, true)
        assertEquals(selected.identity, directory.identity)
    }
    @Test fun actualResolverRoundTripTruncatesWritesRestoresAndVerifiesExports() {
        // Explicit test-only origin override exercises the coordinator on controlled provider files.
        val source = access.select(uri, flags, false).copy(knownTarget = true, confirmed = true)
        val root = File(context.noBackupFilesDir, "instrumented-${UUID.randomUUID()}")
        val writer = AndroidAtomicWriter()
        val backups = FileBackupRepository(File(root, "backups"), writer)
        val journal = FileOperationJournal(File(root, "pending.json"), writer)
        val coordinator = WriteCoordinator(access, backups, journal, processor)
        val result = coordinator.switchToChinese(source)
        processor.verifyChinese(bytes, access.read(source.uri))
        assertArrayEquals(bytes, backups.read(result.backup!!))
        coordinator.restore(source, result.backup)
        assertArrayEquals(bytes, access.read(source.uri))
        coordinator.export(destination.toString(), bytes, listOf(source.uri))
        assertArrayEquals(bytes, access.read(destination.toString()))
        val alias = DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(TEST_AUTHORITY, "root"), "config")
        assertThrows(ToolException::class.java) { coordinator.export(alias.toString(), bytes, listOf(source.uri)) }
        val pending = PendingOperation(source, result.backup.id, contentHash(bytes), contentHash(bytes), "restore", 1)
        journal.save(pending)
        assertEquals(pending, FileOperationJournal(File(root, "pending.json"), writer).load())
        coordinator.verifyPending(source)
        assertNull(journal.load())
    }
    @Test fun readOnlyAndRevocationAreHandled() {
        resolver.call(TEST_CONTROL_AUTHORITY, "test.reset", null, Bundle().apply { putBoolean("readOnly", true) })
        assertFalse(access.select(uri, flags, false).writable)
        resolver.call(TEST_CONTROL_AUTHORITY, "test.reset", null, Bundle().apply { putBoolean("denyRead", true) })
        assertThrows(ToolException::class.java) { access.read(uri.toString()) }
    }
    @Test fun revokedWriteGrantOverridesSavedWritableFlag() {
        val selected = access.select(uri, flags, false)
        assertTrue(selected.writable)
        resolver.call(TEST_CONTROL_AUTHORITY, "test.reset", null, Bundle().apply {
            putBoolean("readGrantOnly", true)
            putByteArray("bytes", bytes)
        })
        assertArrayEquals(bytes, access.read(uri.toString()))
        assertFalse(access.refresh(selected).writable)
        assertFalse(access.select(uri, flags, false).writable)
    }
}
