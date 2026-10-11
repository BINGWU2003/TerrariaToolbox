package com.example.terrariatoolbox

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import com.example.terrariatoolbox.domain.contentHash
import com.example.terrariatoolbox.data.SafDocumentAccess
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ValidationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val configUri = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "config")
    private val exportUri = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "export")
    private val english = "{\"Language\":1,\"unknown\":[1,true,null]}".toByteArray()

    @Before fun setup() {
        Intents.init()
        compose.activity.contentResolver.call(TEST_CONTROL_AUTHORITY, "test.reset", null, Bundle().apply { putByteArray("bytes", english) })
        compose.onNodeWithTag("open-chinese").performClick()
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("select-file"))
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("select-file") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    @After fun cleanup() { Intents.release() }

    @Test fun directoryAndFileCancellationAreNotModificationFailures() {
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
        compose.onNodeWithTag("select-directory").performClick()
        waitForResult("已取消授权")
        Intents.intended(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE))
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("select-file"))
        compose.onNodeWithTag("select-file").performClick()
        waitForResult("已取消授权")
        assertArrayEquals(english, SafDocumentAccess(compose.activity).read(configUri.toString()))
    }

    @Test fun sameNamedOrdinaryDocumentStaysReadOnlyAndBackupExportRoundTrips() {
        selectConfig()
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("switch-chinese"))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        compose.onNodeWithTag("validation-list").performScrollToNode(hasText("保存原始备份"))
        compose.onNodeWithText("保存原始备份").assertIsDisplayed().assertIsEnabled().performClick()
        waitForResult("原始字节备份已保存")
        val result = Intent().setData(exportUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, result))
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("open-backups"))
        compose.onNodeWithTag("open-backups").performClick()
        compose.onNodeWithTag("validation-list").performScrollToNode(hasText("导出原始备份"))
        compose.onAllNodesWithText("导出原始备份").onFirst().performClick()
        waitForResult("原始备份已导出，尚未替换游戏配置")
        val access = SafDocumentAccess(compose.activity)
        assertEquals(contentHash(english), contentHash(access.read(exportUri.toString())))
        assertArrayEquals(english, access.read(configUri.toString()))
    }

    @Test fun invalidConfigDisablesModificationAndDoesNotExposeItsContents() {
        compose.activity.contentResolver.call(TEST_CONTROL_AUTHORITY, "test.reset", null, Bundle().apply {
            putByteArray("bytes", "{\"Language\":1,\"Language\":7,\"ClientUUID\":\"private-test-id\"}".toByteArray())
        })
        selectConfig("重复键")
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("switch-chinese"))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        compose.onAllNodesWithText("private-test-id", substring = true).assertCountEquals(0)
    }

    private fun selectConfig(expected: String = "读取成功") {
        val returned = Intent().setData(configUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, returned))
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("select-file"))
        compose.onNodeWithTag("select-file").performClick()
        waitForResult(expected)
    }
    private fun waitForResult(text: String) {
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("operation-result"))
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("operation-result") and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Exception) {
            val actual = compose.onNodeWithTag("operation-result").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            throw AssertionError("Expected result: $text; actual result: $actual", error)
        }
        compose.onNodeWithTag("operation-result").assertTextContains(text, substring = true)
    }
}
