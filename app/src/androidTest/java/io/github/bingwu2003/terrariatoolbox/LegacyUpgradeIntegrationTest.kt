package io.github.bingwu2003.terrariatoolbox

import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bingwu2003.terrariatoolbox.data.*
import io.github.bingwu2003.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Run after v1.0 creates a SAF backup and v1.1 is installed with adb install -r. */
class LegacyUpgradeIntegrationTest {
    @DedicatedFixture
    @Test fun realLegacySessionAndBackupRemainReadableAndExportable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("legacyUpgrade") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "validation")
        assertFalse(File(root, "source.json").readText().contains("accessMethod"))
        val session = SessionStore(root, AndroidAtomicWriter())
        assertEquals(AccessMethod.SAF, session.source()!!.accessMethod)
        val backups = FileBackupRepository(File(root, "backups"), AndroidAtomicWriter())
        val legacy = backups.list().first { record ->
            !File(root, "backups/${record.id}.json").readText().contains("accessMethod")
        }
        assertEquals(AccessMethod.SAF, legacy.source.accessMethod)
        val original = backups.read(legacy)
        assertEquals("{\"Language\":1,\"unknown\":[1,true,null]}", original.toString(Charsets.UTF_8))
        assertEquals(legacy.hash, contentHash(original))
        context.contentResolver.call(TEST_CONTROL_AUTHORITY, "test.reset", null, Bundle())
        val destination = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "export").toString()
        val saf = SafDocumentAccess(context)
        val unavailableShell = object : DocumentAccess {
            override fun identity(uri: String) = SHIZUKU_IDENTITY
            override fun read(uri: String): ByteArray = error("Shizuku must not be used for SAF export")
            override fun overwrite(uri: String, bytes: ByteArray, expectedBeforeHash: String?) =
                error("Shizuku must not be used for SAF export")
        }
        val coordinator = WriteCoordinator(RoutingDocumentAccess(saf, unavailableShell), backups,
            FileOperationJournal(File(root, "pending.json"), AndroidAtomicWriter()), ConfigProcessor())
        coordinator.export(destination, original, listOf(legacy.source.uri, SHIZUKU_URI))
        assertArrayEquals(original, saf.read(destination))
        assertArrayEquals(original, backups.read(legacy))
    }
}
