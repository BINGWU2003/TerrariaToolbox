package com.example.terrariatoolbox

import com.example.terrariatoolbox.data.*
import com.example.terrariatoolbox.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CopyCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()
    private val processor = ConfigProcessor()
    private val source = testSource("ordinary-copy").copy(displayName = "my-english-copy.txt", knownTarget = false)
    private val english = "\uFEFF{\r\n \"Language\":1,\"Volume\":0.1234567890123456789012345,\"Items\":[2,1,null],\"Big\":123456789012345678901234567890\r\n}\r\n".toByteArray()
    private var failBackup = false
    private var corruptBackup = false
    private var failPointer = false
    private var failBlob = false
    private val atomic = AtomicWriter { file, bytes ->
        if (failPointer && file.name == "current.json" || failBlob && file.parentFile?.name == "copy" && file.extension == "bin") throw IOException()
        if (failBackup && file.parentFile?.name == "backups") throw IOException()
        file.parentFile!!.mkdirs()
        file.writeBytes(if (corruptBackup && file.parentFile?.name == "backups" && file.extension == "bin") byteArrayOf(0) else bytes)
    }
    private val documents = object : DocumentAccess {
        val files = mutableMapOf(source.uri to english.copyOf())
        var writes = 0
        var badReadback = false
        override fun read(uri: String) = files.getValue(uri).copyOf().let { if (badReadback && uri == "export") byteArrayOf(0) else it }
        override fun overwrite(uri: String, bytes: ByteArray, expectedBeforeHash: String?) { writes++; files[uri] = bytes.copyOf() }
        override fun identity(uri: String) = uri.removePrefix("alias:")
    }
    private val journal = object : OperationJournal {
        var value: PendingOperation? = null
        override fun load() = value
        override fun save(operation: PendingOperation) { value = operation }
        override fun clear() { value = null }
    }
    private val root by lazy { temp.newFolder() }
    private val store by lazy { CopyWorkspaceStore(File(root, "copy"), atomic) }
    private val backups by lazy { FileBackupRepository(File(root, "backups"), atomic) }
    private val writer by lazy { WriteCoordinator(documents, backups, journal, processor) }
    private val coordinator by lazy { CopyCoordinator(store, documents, backups, writer, processor) }
    private fun imported() = coordinator.import(coordinator.prepareImport(source))

    @Test fun generationPreservesPrecisionUnknownFieldsOrderAndExactOriginalBackupWithoutWritingImportSource() {
        val current = imported()
        val changed = coordinator.generateChinese()
        assertEquals(current.id, changed.id)
        processor.verifyChinese(english, store.read(changed))
        assertTrue(store.read(changed).toString(Charsets.UTF_8).contains("123456789012345678901234567890"))
        assertEquals(current.id, backups.list().single().workspaceId)
        assertArrayEquals(english, backups.read(backups.list().single()))
        assertArrayEquals(english, documents.read(source.uri))
        assertEquals(0, documents.writes)
        assertFalse(changed.source.writable); assertFalse(changed.source.confirmed); assertFalse(changed.source.knownTarget)
    }

    @Test fun realSampleAndEnglishSampleCopyRoundTripWithOriginalBytes() {
        val original = javaClass.classLoader!!.getResourceAsStream("config.json")!!.use { it.readBytes() }
        val sample = Json.parseToJsonElement(original.toString(Charsets.UTF_8)).jsonObject
        val input = JsonObject(sample + ("Language" to JsonPrimitive(1))).toString().toByteArray()
        documents.files[source.uri] = input
        val workspace = imported()
        coordinator.generateChinese()
        processor.verifyChinese(input, store.read(store.load()!!))
        val record = backups.list().single()
        assertArrayEquals(input, backups.read(record))
        assertEquals(workspace.id, coordinator.restore(record).id)
        assertArrayEquals(input, store.read(store.load()!!))
        assertArrayEquals(original, javaClass.classLoader!!.getResourceAsStream("config.json")!!.use { it.readBytes() })
    }

    @Test fun chineseExportPreservesBytesAndCreatesNoAutomaticBackup() {
        val chinese = " {\r\n\"Language\":7,\"x\":[2,1]} \n".toByteArray()
        documents.files[source.uri] = chinese
        val current = imported()
        assertEquals(current, coordinator.generateChinese())
        coordinator.export("export", emptyList())
        assertArrayEquals(chinese, documents.read("export"))
        assertTrue(backups.list().isEmpty())
    }

    @Test fun canceledReplacementAndInvalidInputsPreserveExistingCopyAndBackups() {
        val old = imported()
        documents.files[source.uri] = "{\"Language\":7}".toByteArray()
        coordinator.prepareImport(source) // user cancels: no commit or backup
        assertEquals(old, store.load()); assertTrue(backups.list().isEmpty())
        listOf("{", "{}", "{\"Language\":\"1\"}", "{\"Language\":1.0}", "{\"Language\":2}", "{\"Language\":1,\"Language\":7}").forEach {
            documents.files[source.uri] = it.toByteArray()
            assertThrows(ToolException::class.java) { coordinator.prepareImport(source) }
            assertEquals(old, store.load()); assertArrayEquals(english, store.read(old))
        }
    }

    @Test fun replacingCopySavesOldBytesAndGivesNewWorkspaceId() {
        val old = imported()
        val newBytes = "{\"Language\":7,\"Other\":true}".toByteArray()
        documents.files[source.uri] = newBytes
        val newer = coordinator.import(coordinator.prepareImport(source))
        assertNotEquals(old.id, newer.id)
        assertArrayEquals(newBytes, store.read(newer))
        assertArrayEquals(english, backups.read(backups.list().single()))
        assertEquals(old.id, backups.list().single().workspaceId)
        assertThrows(ToolException::class.java) { coordinator.restore(backups.list().single()) }
    }

    @Test fun failedOrCorruptBackupPreventsGenerationReplacementAndRestore() {
        val old = imported()
        listOf(false, true).forEach { corrupt ->
            failBackup = !corrupt; corruptBackup = corrupt
            assertThrows(ToolException::class.java) { coordinator.generateChinese() }
            assertThrows(ToolException::class.java) { coordinator.import(coordinator.prepareImport(source)) }
            assertEquals(old, store.load()); assertArrayEquals(english, store.read(old))
        }
        failBackup = false; corruptBackup = false
        coordinator.generateChinese()
        val original = backups.list().first { it.workspaceId == old.id && it.language == "1" }
        val changed = store.load()
        failBackup = true
        assertThrows(ToolException::class.java) { coordinator.restore(original) }
        assertEquals(changed, store.load()); assertEquals(0, documents.writes)
    }

    @Test fun interruptedContentOrPointerCommitLeavesOldCopyAfterReconstruction() {
        val old = imported()
        failBlob = true
        assertThrows(ToolException::class.java) { coordinator.generateChinese() }
        failBlob = false; failPointer = true
        assertThrows(ToolException::class.java) { coordinator.generateChinese() }
        val reopened = CopyWorkspaceStore(File(root, "copy"), atomic)
        assertEquals(old, reopened.load()); assertArrayEquals(english, reopened.read(old))
        assertTrue(File(root, "copy").listFiles()!!.count { it.extension == "bin" } > 1)
        failPointer = false
        coordinator.generateChinese()
        assertEquals(Language.CHINESE, processor.requireSupported(reopened.read(reopened.load()!!)).language)
    }

    @Test fun interruptedFirstImportDoesNotLoadOrphanContentAsCurrentCopy() {
        failPointer = true
        assertThrows(ToolException::class.java) { imported() }
        assertNull(CopyWorkspaceStore(File(root, "copy"), atomic).load())
    }

    @Test fun restoreUsesExactBytesAndBacksUpChangedCopy() {
        val current = imported()
        coordinator.generateChinese()
        val changed = store.read(store.load()!!)
        val original = backups.list().single()
        val restored = coordinator.restore(original)
        assertEquals(current.id, restored.id)
        assertArrayEquals(english, store.read(restored))
        assertTrue(backups.list().any { backups.read(it).contentEquals(changed) })
        assertEquals(0, documents.writes)
    }

    @Test fun externalWrongWorkspaceAndBackendBackupsCannotRestoreCopyOrDirectTarget() {
        val current = imported()
        coordinator.generateChinese()
        val own = backups.list().single()
        listOf(own.copy(workspaceId = null), own.copy(workspaceId = "different"), own.copy(source = source.copy(identity = "other")),
            own.copy(source = own.source.copy(accessMethod = AccessMethod.SHIZUKU))).forEach {
            assertThrows(ToolException::class.java) { coordinator.restore(it) }
        }
        assertThrows(ToolException::class.java) { writer.restore(testSource(), own.copy(source = testSource())) }
        assertEquals(current.id, store.load()!!.id); assertEquals(0, documents.writes)
    }

    @Test fun exportRejectsImportAliasesAndGameTargetAndDoesNotReportBadReadbackAsSuccess() {
        imported(); coordinator.generateChinese()
        listOf(source.uri, "alias:${source.uri}", SHIZUKU_URI, "alias:$SHIZUKU_URI").forEach {
            assertThrows(ToolException::class.java) { coordinator.export(it, emptyList()) }
        }
        assertEquals(0, documents.writes)
        documents.badReadback = true
        assertThrows(ToolException::class.java) { coordinator.export("export", emptyList()) }
        assertArrayEquals(english, documents.read(source.uri))
        assertEquals(Language.CHINESE, processor.requireSupported(store.read(store.load()!!)).language)
    }

    @Test fun copyActionsNeverClearExternalPendingJournal() {
        journal.value = PendingOperation(testSource(), "old-backup", "before", "expected", "switch", 1)
        val pending = journal.value
        imported(); coordinator.generateChinese(); coordinator.restore(backups.list().single())
        assertEquals(pending, journal.value)
    }
}
