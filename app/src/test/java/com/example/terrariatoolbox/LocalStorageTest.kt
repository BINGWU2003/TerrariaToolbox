package com.example.terrariatoolbox

import com.example.terrariatoolbox.data.*
import com.example.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private val writer = AtomicWriter { file, bytes -> file.parentFile!!.mkdirs(); file.writeBytes(bytes) }
    private val processor = ConfigProcessor()

    @Test fun backupRoundTripKeepsBytesHashesSourceAndUniqueIds() {
        val root = temp.newFolder()
        val repository = FileBackupRepository(root, writer)
        val original = "\uFEFF{\r\n  \"Language\": 1, \"LastLaunchedVersion\": 326\r\n}\r\n".toByteArray()
        val source = testSource()
        val first = repository.save(source, original, "test", processor.inspect(original))
        val second = repository.save(source, original, "test", processor.inspect(original))
        assertNotEquals(first.id, second.id)
        val reopened = FileBackupRepository(root, writer)
        assertArrayEquals(original, reopened.read(first))
        assertArrayEquals(original, reopened.read(second))
        assertEquals(contentHash(original), first.hash)
        assertEquals(source, first.source)
        assertEquals("326", first.gameVersion)
        assertEquals(2, reopened.list().size)
    }
    @Test fun damagedBackupCannotBeReadEvenWithValidMetadata() {
        val root = temp.newFolder()
        val repository = FileBackupRepository(root, writer)
        val bytes = "{\"Language\":1}".toByteArray()
        val record = repository.save(testSource(), bytes, "test", processor.inspect(bytes))
        File(root, record.id + ".bin").writeBytes(bytes.dropLast(1).toByteArray())
        assertThrows(ToolException::class.java) { repository.read(record) }
    }
    @Test fun incompleteBackupIsNotListedAfterMetadataCommitFailure() {
        val root = temp.newFolder()
        val failingWriter = AtomicWriter { file, bytes ->
            if (file.extension == "json") throw java.io.IOException("out of space")
            writer.write(file, bytes)
        }
        val repository = FileBackupRepository(root, failingWriter)
        assertThrows(ToolException::class.java) { repository.save(testSource(), byteArrayOf(1), "test", null) }
        assertTrue(FileBackupRepository(root, writer).list().isEmpty())
    }
    @Test fun pendingJournalSurvivesReconstructionAndCompletionIsCommitted() {
        val file = File(temp.newFolder(), "pending.json")
        val pending = PendingOperation(testSource(), "backup", "before", "after", "switch", 2)
        FileOperationJournal(file, writer).save(pending)
        assertEquals(pending, FileOperationJournal(file, writer).load())
        FileOperationJournal(file, writer).clear()
        assertNull(FileOperationJournal(file, writer).load())
    }
    @Test fun damagedPendingJournalFailsClosed() {
        val file = temp.newFile("pending.json")
        file.writeText("{")
        assertTrue(assertThrows(ToolException::class.java) { FileOperationJournal(file, writer).load() }.needsRecovery)
    }
    @Test fun sessionKeepsConfirmationAndOnlyControlledDiagnosticText() {
        val root = temp.newFolder()
        val store = SessionStore(root, writer)
        val source = testSource().copy(lastVerifiedAt = 10)
        store.saveSource(source)
        store.saveNotes(EnvironmentNotes("1.4.x", "2.x"))
        repeat(65) { store.event("备份回读通过 $it") }
        val reopened = SessionStore(root, writer)
        assertEquals(source, reopened.source())
        assertEquals(EnvironmentNotes("1.4.x", "2.x"), reopened.notes())
        assertEquals(60, reopened.events().size)
    }
    @Test fun legacySessionBackupAndPendingDefaultToSafWhileNewBackendSurvivesReload() {
        val root = temp.newFolder()
        val store = SessionStore(root, writer)
        val shell = testSource().copy(accessMethod = AccessMethod.SHIZUKU)
        store.saveSource(shell)
        assertEquals(shell, SessionStore(root, writer).source())
        store.saveSource(testSource())
        val file = File(root, "source.json")
        file.writeText(file.readText().replace(",\"accessMethod\":\"SAF\"", ""))
        assertEquals(testSource(), SessionStore(root, writer).source())
        val repository = FileBackupRepository(File(root, "backups"), writer)
        val bytes = "{\"Language\":1}".toByteArray()
        val record = repository.save(testSource(), bytes, "legacy", processor.inspect(bytes))
        val meta = File(root, "backups/${record.id}.json")
        meta.writeText(meta.readText().replace(",\"accessMethod\":\"SAF\"", ""))
        assertArrayEquals(bytes, repository.read(repository.list().single()))
        val pendingFile = File(root, "pending.json")
        val pending = PendingOperation(testSource(), record.id, "before", "after", "switch", 1)
        FileOperationJournal(pendingFile, writer).save(pending)
        pendingFile.writeText(pendingFile.readText().replace(",\"accessMethod\":\"SAF\"", ""))
        assertEquals(pending, FileOperationJournal(pendingFile, writer).load())
    }
}
