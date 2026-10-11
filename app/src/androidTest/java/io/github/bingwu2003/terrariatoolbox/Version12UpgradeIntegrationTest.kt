package io.github.bingwu2003.terrariatoolbox

import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bingwu2003.terrariatoolbox.data.*
import io.github.bingwu2003.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in after the actual 1.1 APK creates backups, then an install -r of 1.2. */
class Version12UpgradeIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @DedicatedFixture
    @Test fun oldShellSessionBackupsAndPendingSurviveUpgradeAndCanExportWithoutClearingRecovery() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("version12Upgrade") == "true")
        val context = compose.activity
        assertEquals("1.3", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val root = File(context.noBackupFilesDir, "validation")
        val atomic = AndroidAtomicWriter()
        val source = SessionStore(root, atomic).source()!!
        assertEquals(AccessMethod.SHIZUKU, source.accessMethod)
        assertEquals(SHIZUKU_IDENTITY, source.identity)
        val backups = FileBackupRepository(File(root, "backups"), atomic)
        assertTrue(backups.list().isNotEmpty())
        backups.list().forEach {
            assertNull(it.workspaceId)
            assertFalse(File(root, "backups/${it.id}.json").readText().contains("workspaceId"))
            assertEquals(it.hash, contentHash(backups.read(it)))
        }
        val journal = FileOperationJournal(File(root, "pending.json"), atomic)
        val pending = journal.load()!!
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("recovery-warning").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("open-chinese"))
        compose.onNodeWithTag("open-chinese").performClick()
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("switch-chinese"))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        context.contentResolver.call(TEST_CONTROL_AUTHORITY, "test.reset", null, Bundle())
        val saf = SafDocumentAccess(context)
        val noShell = object : DocumentAccess {
            override fun read(uri: String): ByteArray = error("Export must not read the game target")
            override fun overwrite(uri: String, bytes: ByteArray, expectedBeforeHash: String?) = error("Export must not overwrite the game target")
            override fun identity(uri: String) = SHIZUKU_IDENTITY
        }
        val record = backups.list().first { it.language == "1" }
        val original = backups.read(record)
        val output = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "export").toString()
        WriteCoordinator(RoutingDocumentAccess(saf, noShell), backups, journal, ConfigProcessor())
            .export(output, original, listOf(SHIZUKU_URI))
        assertArrayEquals(original, saf.read(output))
        assertEquals(pending, journal.load())
    }
}
