package io.github.bingwu2003.terrariatoolbox

import androidx.test.platform.app.InstrumentationRegistry
import io.github.bingwu2003.terrariatoolbox.data.*
import io.github.bingwu2003.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in only on the dedicated emulator fixture, never automatically on a user's game data. */
class ShizukuIntegrationTest {
    @DedicatedFixture
    @Test fun genuineShellServiceLargeConfigBackupSwitchRestoreAndConflict() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuFixture") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        var connection: ShizukuConnection? = null
        instrument.runOnMainSync { connection = createShizukuConnection(context); connection!!.connect(false) }
        val client = connection!!
        try {
            val deadline = System.currentTimeMillis() + 12_000
            while (client.state.value.phase == ShizukuPhase.CONNECTING && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals(client.state.value.message, ShizukuPhase.CONNECTED, client.state.value.phase)
            val access = ShizukuDocumentAccess(client)
            val source = access.select().copy(confirmed = true)
            assertEquals(2000, client.files().probe().uid)
            val original = access.read(source.uri)
            assertTrue("Fixture must exercise descriptor transfer over Binder's usual payload limit", original.size > 1024 * 1024)
            val processor = ConfigProcessor()
            assertEquals(Language.ENGLISH, processor.inspect(original).language)
            assertEquals("controlled-shizuku-fixture", processor.inspect(original).root["FixtureMarker"]?.toString()?.trim('"'))
            val root = File(context.noBackupFilesDir, "real-shizuku-test-${UUID.randomUUID()}")
            val backups = FileBackupRepository(File(root, "backups"), AndroidAtomicWriter())
            val journal = FileOperationJournal(File(root, "pending.json"), AndroidAtomicWriter())
            val coordinator = WriteCoordinator(access, backups, journal, processor)
            val result = coordinator.switchToChinese(source)
            assertArrayEquals(original, backups.read(result.backup!!))
            val chinese = access.read(source.uri)
            processor.verifyChinese(original, chinese)
            assertFalse(coordinator.switchToChinese(source).changed)
            coordinator.restore(source, result.backup)
            assertArrayEquals(original, access.read(source.uri))
            assertFalse(assertThrows(ToolException::class.java) { access.overwrite(source.uri, chinese, contentHash(chinese)) }.needsRecovery)
            assertArrayEquals(original, access.read(source.uri))
            journal.save(PendingOperation(source, result.backup.id, contentHash(original), contentHash(original), "restore", 1))
            coordinator.verifyPending(source); assertNull(journal.load())
        } finally { instrument.runOnMainSync { client.close() } }
    }
}
