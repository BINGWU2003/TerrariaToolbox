package com.example.terrariatoolbox

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import com.example.terrariatoolbox.data.*
import com.example.terrariatoolbox.domain.*
import com.example.terrariatoolbox.feature.chinese.ValidationViewModel
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Ordinary documents only; never uses the game directory or Shizuku. */
class CopyUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val configUri = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "config")
    private val exportUri = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "export")
    private val english = "\uFEFF{\r\n\"Language\":1,\"x\":1234567890123456789012345,\"items\":[2,1]}\n".toByteArray()
    private val atomic = AndroidAtomicWriter()
    private val store get() = CopyWorkspaceStore(File(compose.activity.noBackupFilesDir, "copy-workspace"), atomic)
    private val backups get() = FileBackupRepository(File(compose.activity.noBackupFilesDir, "validation/backups"), atomic)
    private lateinit var model: ValidationViewModel

    @Before fun setup() {
        Intents.init()
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[ValidationViewModel::class.java] }
        compose.waitUntil(15_000) { !model.state.value.busy }
        // Isolate the current copy in the test APK's target sandbox; leave historical backups intact.
        File(compose.activity.noBackupFilesDir, "copy-workspace").deleteRecursively()
        reset(english)
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.workspace == null }
        compose.onNodeWithTag("open-chinese").performClick()
        scroll(hasTestTag("mode-copy")); compose.onNodeWithTag("mode-copy").performClick()
        val result = Intent().setData(configUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, result))
    }
    @After fun cleanup() { Intents.release() }

    @Test fun readonlyArbitraryNameImportGenerateCancelAndActivityRestartKeepCopyAndExactBackup() {
        importCopy(); waitResult("配置副本已导入")
        assertEquals("english-original.txt", store.load()!!.source.displayName)
        assertFalse(store.load()!!.source.writable)
        Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
        exportCopy(); waitResult("已取消导出，已生成的编辑副本和原始备份已保留")
        val workspace = store.load()!!
        assertEquals(Language.CHINESE, ConfigProcessor().requireSupported(store.read(workspace)).language)
        assertArrayEquals(english, backups.read(backups.list().single { it.workspaceId == workspace.id }))
        assertArrayEquals(english, SafDocumentAccess(compose.activity).read(configUri.toString()))
        compose.activityRule.scenario.recreate()
        scroll(hasTestTag("export-copy"))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("配置已设为中文", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(workspace, store.load())
        assertArrayEquals(english, SafDocumentAccess(compose.activity).read(configUri.toString()))
    }

    @Test fun invalidReimportAndReplacementCancelKeepOldCopyThenAcceptedReplaceBacksItUp() {
        importCopy(); waitResult("配置副本已导入")
        val old = store.load()!!
        val count = backups.list().size
        reset("{\"Language\":\"1\"}".toByteArray())
        importCopy(); waitResult("必须是整数")
        assertEquals(old, store.load()); assertEquals(count, backups.list().size)
        val chinese = "{\"Language\":7,\"new\":true}".toByteArray()
        reset(chinese); importCopy()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("备份并替换").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("取消").performClick(); waitResult("已取消导入，当前副本未改变")
        assertEquals(old, store.load()); assertEquals(count, backups.list().size)
        importCopy()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("备份并替换").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("备份并替换").performClick(); waitResult("原编辑副本已备份")
        assertNotEquals(old.id, store.load()!!.id)
        assertArrayEquals(chinese, store.read(store.load()!!))
        assertArrayEquals(english, backups.read(backups.list().single { it.workspaceId == old.id }))
    }

    @Test fun englishExportVerifiesResultAndSameWorkspaceRestoreKeepsImportSource() {
        importCopy(); waitResult("配置副本已导入")
        Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK,
            Intent().setData(exportUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)))
        exportCopy(); waitResult("中文配置已导出，尚未替换游戏配置")
        compose.onNodeWithTag("operation-result").assertTextContains("实际名称：export.json", substring = true)
        val access = SafDocumentAccess(compose.activity)
        ConfigProcessor().verifyChinese(english, access.read(exportUri.toString()))
        assertArrayEquals(english, access.read(configUri.toString()))
        val record = backups.list().single { it.workspaceId == store.load()!!.id }
        scroll(hasTestTag("open-backups")); compose.onNodeWithTag("open-backups").performClick()
        scroll(hasTestTag("backup-restore-${record.id}")); compose.onNodeWithTag("backup-restore-${record.id}").performClick()
        compose.onNodeWithText("恢复副本").performClick(); waitResult("已恢复编辑副本并逐字节校验")
        assertArrayEquals(english, store.read(store.load()!!))
        assertArrayEquals(english, access.read(configUri.toString()))
        compose.onNodeWithTag("navigate-home").performClick(); compose.onNodeWithTag("open-chinese").assertIsDisplayed()
    }

    @Test fun chineseCopyExportsOriginalBytesWithoutAutomaticBackup() {
        val chinese = " {\r\n\"Language\":7,\"values\":[2,1]}\n".toByteArray()
        reset(chinese); importCopy(); waitResult("配置副本已导入")
        val id = store.load()!!.id
        Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(exportUri)))
        exportCopy(); waitResult("中文配置已导出")
        assertArrayEquals(chinese, SafDocumentAccess(compose.activity).read(exportUri.toString()))
        assertTrue(backups.list().none { it.workspaceId == id })
    }

    @Test fun exportRejectsImportSourceTreeAliasBeforeTruncation() {
        importCopy(); waitResult("配置副本已导入")
        val alias = DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(TEST_AUTHORITY, "root"), "config")
        Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(alias)))
        exportCopy(); waitResult("不能导出覆盖源文件")
        assertArrayEquals(english, SafDocumentAccess(compose.activity).read(configUri.toString()))
    }

    @Test fun failedExportReadbackKeepsGeneratedCopyAndShowsUnconfirmedResult() {
        reset(english, denyExportRead = true); importCopy(); waitResult("配置副本已导入")
        Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(exportUri)))
        exportCopy(); waitResult("保存结果未确认")
        assertFalse(model.state.value.message.contains("中文配置已导出"))
        assertEquals(Language.CHINESE, ConfigProcessor().requireSupported(store.read(store.load()!!)).language)
        assertArrayEquals(english, backups.read(backups.list().single { it.workspaceId == store.load()!!.id }))
        assertArrayEquals(english, SafDocumentAccess(compose.activity).read(configUri.toString()))
    }

    private fun reset(bytes: ByteArray, denyExportRead: Boolean = false) {
        compose.activity.contentResolver.call(TEST_CONTROL_AUTHORITY, "test.reset", null, Bundle().apply {
            putByteArray("bytes", bytes); putBoolean("readOnly", true); putString("name", "english-original.txt")
            putBoolean("denyExportRead", denyExportRead)
        })
    }
    private fun importCopy() { scroll(hasTestTag("import-copy")); compose.onNodeWithTag("import-copy").performClick() }
    private fun exportCopy() { scroll(hasTestTag("export-copy")); compose.onNodeWithTag("export-copy").performClick() }
    private fun scroll(matcher: SemanticsMatcher) { compose.onNodeWithTag("validation-list").performScrollToNode(matcher) }
    private fun waitResult(text: String) {
        scroll(hasTestTag("operation-result"))
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("operation-result") and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Exception) { throw AssertionError("Expected: $text; actual: ${model.state.value.message}", error) }
    }
}
