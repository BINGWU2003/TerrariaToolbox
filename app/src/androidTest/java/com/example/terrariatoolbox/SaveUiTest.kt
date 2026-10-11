package com.example.terrariatoolbox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.terrariatoolbox.domain.*
import com.example.terrariatoolbox.feature.saves.*
import com.example.terrariatoolbox.ui.theme.TerrariaToolboxTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry

class SaveUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun connectionIsRequiredAndPhoneAndExportActionsAreDisabled() {
        show(SaveState(), false, SaveActions())
        compose.onNodeWithTag("saves-go-connect").assertExists()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("import-phone"))
        compose.onNodeWithTag("import-phone").assertIsNotEnabled()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("export-saves"))
        compose.onNodeWithTag("export-saves").assertIsNotEnabled()
    }
    @Test fun multipleChineseAccountsMustBeSelectedBeforeImport() {
        var selected = ""
        val state = SaveState(edition = GameEdition.CHINESE, accounts = listOf("53399", "98765"), loaded = true)
        show(state, true, SaveActions(account = { selected = it }))
        compose.onNodeWithTag("account-98765").performClick()
        assertEquals("98765", selected)
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("import-phone"))
        compose.onNodeWithTag("import-phone").assertIsNotEnabled()
    }
    @Test fun importConfirmationShowsExactTargetAndRequiresExplicitGameExitAction() {
        var imports = 0
        val state = SaveState(edition = GameEdition.CHINESE, accounts = listOf("53399"), account = "53399", loaded = true, message = "已检查 1 个存档，请确认导入位置。",
            pending = listOf(StagedSave("Vor.plr", File("unused"))), pendingTarget = SaveTarget(GameEdition.CHINESE, "53399"))
        show(state, true, SaveActions(confirm = { imports++ }))
        assertEquals(0, imports)
        compose.onNodeWithTag("import-target").assertTextEquals("导入到：国服 · 账号 53399")
        capture("save-import-confirm.png")
        compose.onNodeWithTag("confirm-save-import").assertTextEquals("已退出游戏，开始导入").performClick()
        assertEquals(1, imports)
    }
    @Test fun filenameSizeTimeAndWorldTabAreAvailableAndResultNamesAreVisible() {
        val state = SaveState(loaded = true, message = "已读取 2 个存档。", saves = listOf(GameSave("Vor.plr", 3008, 1000), GameSave("Items.wld", 3337307, 2000)),
            results = listOf(SaveImportResult("Vor.plr", "Vor (1).plr")))
        show(state, true, SaveActions())
        compose.onNodeWithTag("saves-list").performScrollToNode(hasText("已自动重命名并导入：Vor (1).plr"))
        compose.onNodeWithText("已自动重命名并导入：Vor (1).plr").assertExists()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("save-Vor.plr"))
        compose.onNodeWithText("2.9 KB ·", substring = true).assertExists()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("kind-Worlds"))
        compose.onNodeWithTag("kind-Worlds").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("save-Items.wld"))
        compose.onNodeWithTag("save-Items.wld").assertExists()
        capture("save-world-list.png")
    }
    private fun show(state: SaveState, connected: Boolean, actions: SaveActions) {
        compose.setContent { TerrariaToolboxTheme { Scaffold { padding ->
            Box(Modifier.padding(padding)) { SaveContent(state, connected, actions) }
        } } }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        val folder = File(context.getExternalFilesDir(null), "screenshots-v1.4").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(folder, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
