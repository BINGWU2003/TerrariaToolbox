package io.github.bingwu2003.terrariatoolbox

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

/** Run after the copy UI fixture, am force-stop, and an injected external pending record. */
class CopyProcessRestartIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @DedicatedFixture
    @Test fun processRestartLoadsPreviouslyGeneratedCopyAndKeepsExternalRecoveryAcrossModes() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("copyRestart") == "true")
        val context = compose.activity
        val store = CopyWorkspaceStore(File(context.noBackupFilesDir, "copy-workspace"), AndroidAtomicWriter())
        val workspace = store.load()!!
        assertEquals(arguments.getString("workspaceId"), workspace.id)
        assertEquals(arguments.getString("workspaceHash"), workspace.hash)
        assertEquals(Language.CHINESE, ConfigProcessor().requireSupported(store.read(workspace)).language)
        val root = File(context.noBackupFilesDir, "validation")
        val pending = FileOperationJournal(File(root, "pending.json"), AndroidAtomicWriter()).load()!!
        val backups = FileBackupRepository(File(root, "backups"), AndroidAtomicWriter())
        val original = backups.list().first { it.workspaceId == workspace.id && it.language == "1" }
        assertEquals(arguments.getString("originalHash"), contentHash(backups.read(original)))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("recovery-warning").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("open-chinese"))
        compose.onNodeWithTag("open-chinese").performClick()
        compose.onNodeWithTag("mode-copy").assertDoesNotExist()
        compose.onNodeWithTag("export-copy").assertDoesNotExist()
        scroll(hasTestTag("switch-chinese")); compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        compose.onNodeWithTag("switch-english").assertIsNotEnabled()
        assertEquals(pending, FileOperationJournal(File(root, "pending.json"), AndroidAtomicWriter()).load())
    }
    private fun scroll(matcher: SemanticsMatcher) { compose.onNodeWithTag("validation-list").performScrollToNode(matcher) }
}
