package io.github.bingwu2003.terrariatoolbox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.bingwu2003.terrariatoolbox.data.*
import io.github.bingwu2003.terrariatoolbox.domain.*
import io.github.bingwu2003.terrariatoolbox.feature.chinese.ValidationViewModel
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Daily backup UI was removed; existing recovery bytes must remain intact during upgrades. */
class LegacyBackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun legacyBackupIsPreservedButDailyExportAndRestoreAreAbsent() {
        lateinit var model: ValidationViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[ValidationViewModel::class.java] }
        compose.waitUntil(15_000) { !model.state.value.busy }
        val context = compose.activity
        val original = "{\"Language\":1,\"volume\":0.5}".toByteArray()
        val source = SourceInfo("content://legacy/config", "content://legacy/config", "legacy|config", TEST_AUTHORITY,
            "config", "config.json", "旧版本导入", false, false, false)
        val repository = FileBackupRepository(File(context.noBackupFilesDir, "validation/backups"), AndroidAtomicWriter())
        val record = repository.save(source, original, "旧版本副本备份", ConfigProcessor().inspect(original), UUID.randomUUID().toString())
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.backups.any { it.id == record.id } }
        compose.onNodeWithTag("nav-chinese").performClick()
        compose.onNodeWithTag("open-backups").assertDoesNotExist()
        compose.onNodeWithTag("backup-export-${record.id}").assertDoesNotExist()
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("backup-restore-${record.id}").assertDoesNotExist()
        assertArrayEquals(original, repository.read(record))
    }
}
