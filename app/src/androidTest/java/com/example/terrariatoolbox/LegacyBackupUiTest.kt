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
import java.util.UUID

/** Historical copy backups remain exportable without reintroducing a file/import entry. */
class LegacyBackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun legacyCopyBackupCanExportExactBytesButCannotRestoreToGame() {
        Intents.init()
        try {
            lateinit var model: ValidationViewModel
            compose.runOnIdle { model = ViewModelProvider(compose.activity)[ValidationViewModel::class.java] }
            compose.waitUntil(15_000) { !model.state.value.busy }
            val context = compose.activity
            context.contentResolver.call(TEST_CONTROL_AUTHORITY, "test.reset", null, Bundle())
            val original = "\uFEFF{\r\n\"Language\":1,\"volume\":0.5,\"keys\":[2,1]}\n".toByteArray()
            val uri = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "config").toString()
            val source = SourceInfo(uri, uri, "legacy|config", TEST_AUTHORITY, "config", "config.json",
                "旧版本导入", false, false, false)
            val repository = FileBackupRepository(File(context.noBackupFilesDir, "validation/backups"), AndroidAtomicWriter())
            val record = repository.save(source, original, "旧版本副本备份", ConfigProcessor().inspect(original), UUID.randomUUID().toString())
            compose.runOnIdle { model.refresh() }
            compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.backups.any { it.id == record.id } }
            compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("open-chinese"))
            compose.onNodeWithTag("open-chinese").performClick()
            scroll(hasTestTag("more-actions")); compose.onNodeWithTag("more-actions").performClick()
            scroll(hasTestTag("open-backups")); compose.onNodeWithTag("open-backups").performClick()
            scroll(hasTestTag("backup-restore-${record.id}"))
            compose.onNodeWithTag("backup-restore-${record.id}").assertIsNotEnabled()
            val output = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "export")
            Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK,
                Intent().setData(output).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)))
            scroll(hasTestTag("backup-export-${record.id}")); compose.onNodeWithTag("backup-export-${record.id}").performClick()
            compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.message.contains("备份已导出") }
            assertArrayEquals(original, SafDocumentAccess(context).read(output.toString()))
            assertArrayEquals(original, repository.read(record))
        } finally { Intents.release() }
    }

    private fun scroll(matcher: SemanticsMatcher) { compose.onNodeWithTag("validation-list").performScrollToNode(matcher) }
}
