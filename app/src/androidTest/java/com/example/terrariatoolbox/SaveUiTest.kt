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
import android.app.Application
import com.example.terrariatoolbox.data.SavePreferences
import kotlinx.coroutines.*
import java.io.OutputStream
import java.util.UUID

class SaveUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun reconnectRefreshesAnAlreadyLoadedNationalAccount() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val preferences = File(app.cacheDir, "ui-target-${UUID.randomUUID()}.json")
        var accounts = listOf("53399")
        val remote = object : RemoteSaveFiles {
            override fun accounts() = accounts
            override fun list(target: SaveTarget) = emptyList<GameSave>()
            override fun read(target: SaveTarget, name: String, output: OutputStream) = error("unused")
            override fun import(target: SaveTarget, save: StagedSave): String = error("unused")
        }
        val connected = androidx.compose.runtime.mutableStateOf(true)
        val manager = SaveManager(app, { remote }, scope, SavePreferences(preferences))
        try {
            compose.setContent { TerrariaToolboxTheme { Scaffold { padding -> Box(Modifier.padding(padding)) {
                SaveScreen(manager, connected.value, {})
            } } } }
            compose.waitUntil(10000) { manager.state.value.loaded && !manager.state.value.busy }
            compose.runOnIdle { manager.edition(GameEdition.CHINESE) }
            compose.waitUntil(10000) { manager.state.value.account == "53399" && !manager.state.value.busy }
            compose.runOnIdle { connected.value = false }
            compose.waitForIdle()
            compose.runOnIdle { accounts = listOf("98765"); connected.value = true }
            compose.waitUntil(10000) { manager.state.value.account == "98765" && !manager.state.value.busy }
            assertEquals(listOf("98765"), manager.state.value.accounts)
        } finally { scope.cancel(); preferences.delete() }
    }
    @Test fun filesCanBeChosenBeforeConnectingButExportRequiresConnection() {
        show(SaveState(), false, SaveActions())
        compose.onNodeWithTag("saves-go-connect").assertExists()
        compose.onNodeWithTag("select-mode").performClick()
        compose.onNodeWithTag("export-saves").assertIsNotEnabled()
        compose.onNodeWithTag("start-import").performClick()
        compose.onNodeWithTag("import-phone").assertIsEnabled()
    }
    @Test fun multipleChineseAccountsMustBeSelectedBeforeImport() {
        var selected = ""
        val state = SaveState(edition = GameEdition.CHINESE, accounts = listOf("53399", "98765"), loaded = true,
            importStep = ImportStep.TARGET, pending = listOf(StagedSave("National.plr", File("unused"), GameEdition.CHINESE, SaveOrigin.BUNDLE)))
        show(state, true, SaveActions(account = { selected = it }))
        compose.onNodeWithTag("account-98765").performClick()
        assertEquals("98765", selected)
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("review-import"))
        compose.onNodeWithTag("review-import").assertIsNotEnabled()
    }
    @Test fun importConfirmationShowsExactTargetAndRequiresExplicitGameExitAction() {
        var imports = 0
        val state = SaveState(edition = GameEdition.CHINESE, accounts = listOf("53399"), account = "53399", loaded = true, message = "已检查 1 个存档，请确认导入位置。",
            pending = listOf(StagedSave("National.plr", File("unused"), GameEdition.CHINESE, SaveOrigin.BUNDLE)), pendingTarget = SaveTarget(GameEdition.CHINESE, "53399"), importStep = ImportStep.REVIEW)
        show(state, true, SaveActions(confirm = { imports++ }))
        assertEquals(0, imports)
        compose.onNodeWithTag("import-target").assertTextEquals("导入到：国服 · 账号 53399")
        capture("save-import-confirm.png")
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("confirm-save-import"))
        compose.onNodeWithTag("confirm-save-import").assertTextEquals("已退出游戏，开始导入").performClick()
        assertEquals(1, imports)
    }
    @Test fun builtinEditionTabsClearSelectionAndChinesePlayerCategoryShowsExplicitEmptyState() {
        val assets = listOf(BuiltinSave("players/Vor.plr", "Vor.plr", 3008, GameEdition.INTERNATIONAL),
            BuiltinSave("xd-worlds/National.wld", "National.wld", 500, GameEdition.CHINESE))
        var selected = emptySet<String>()
        show(SaveState(builtins = assets), false, SaveActions(builtins = { selected = it }))
        compose.onNodeWithTag("start-import").performClick()
        compose.onNodeWithTag("show-builtins").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-Vor.plr"))
        compose.onNodeWithTag("builtin-Vor.plr").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-edition-1"))
        compose.onNodeWithTag("builtin-edition-1").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasText("暂无国服内置角色"))
        compose.onNodeWithText("暂无国服内置角色").assertExists()
        compose.onNodeWithTag("builtin-empty-phone").assertIsEnabled()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("import-builtins"))
        compose.onNodeWithTag("import-builtins").assertIsNotEnabled()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-kind-Worlds"))
        compose.onNodeWithTag("builtin-kind-Worlds").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-National.wld"))
        compose.onNodeWithTag("builtin-National.wld").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("import-builtins"))
        compose.onNodeWithTag("import-builtins").performClick()
        assertEquals(setOf("xd-worlds/National.wld"), selected)
    }
    @Test fun unknownPhoneFileRequiresDeclarationAndIdentifiedWorldDisablesOppositeDeclaration() {
        val current = androidx.compose.runtime.mutableStateOf(SaveState(loaded = true, importStep = ImportStep.TARGET,
            pending = listOf(StagedSave("Unknown.plr", File("unused")))))
        compose.setContent { TerrariaToolboxTheme { Scaffold { padding -> Box(Modifier.padding(padding)) {
            SaveContent(current.value, true, SaveActions(declareSource = { current.value = current.value.copy(declaredEdition = it, edition = it) }))
        } } } }
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("review-import"))
        compose.onNodeWithTag("review-import").assertIsNotEnabled()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("declare-edition-0"))
        compose.onNodeWithTag("declare-edition-0").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("review-import"))
        compose.onNodeWithTag("review-import").assertIsEnabled()
        compose.runOnIdle { current.value = current.value.copy(pending = current.value.pending +
            StagedSave("Known.wld", File("unused"), GameEdition.INTERNATIONAL, SaveOrigin.WORLD_HEADER)) }
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("declare-edition-1"))
        compose.onNodeWithTag("declare-edition-1").assertIsNotEnabled()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("edition-1"))
        compose.onNodeWithTag("edition-1").assertIsNotEnabled()
    }
    @Test fun wrongEditionCannotReachWriteActionEvenIfReviewStateIsForged() {
        show(SaveState(edition = GameEdition.CHINESE, account = "53399", accounts = listOf("53399"), loaded = true,
            pending = listOf(StagedSave("Vor.plr", File("unused"), GameEdition.INTERNATIONAL, SaveOrigin.BUILTIN)),
            pendingTarget = SaveTarget(GameEdition.CHINESE, "53399"), importStep = ImportStep.REVIEW), true, SaveActions())
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("confirm-save-import"))
        compose.onNodeWithTag("confirm-save-import").assertIsNotEnabled()
    }
    @Test fun filenameSizeTimeAndWorldTabAreAvailableAndResultNamesAreVisible() {
        val state = SaveState(loaded = true, message = "已读取 2 个存档。", saves = listOf(GameSave("Vor.plr", 3008, 1000), GameSave("Items.wld", 3337307, 2000)),
            results = listOf(SaveImportResult("Vor.plr", "Vor (1).plr")), importStep = ImportStep.RESULT)
        show(state, true, SaveActions())
        compose.onNodeWithTag("saves-list").performScrollToNode(hasText("已自动重命名并导入：Vor (1).plr"))
        compose.onNodeWithText("已自动重命名并导入：Vor (1).plr").assertExists()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("view-imported-saves"))
        compose.onNodeWithTag("view-imported-saves").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("save-row-Vor.plr"))
        compose.onNodeWithText("2.9 KB ·", substring = true).assertExists()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("kind-Worlds"))
        compose.onNodeWithTag("kind-Worlds").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("save-row-Items.wld"))
        compose.onNodeWithTag("save-row-Items.wld").assertExists()
        capture("save-world-list.png")
    }
    @Test fun searchAndCrossCategorySelectionKeepTotalAndExportChoice() {
        val current = androidx.compose.runtime.mutableStateOf(SaveState(loaded = true,
            saves = listOf(GameSave("Vor.plr", 3008, 1000), GameSave("Items.wld", 3337307, 2000))))
        compose.setContent { TerrariaToolboxTheme { Scaffold { padding -> Box(Modifier.padding(padding)) {
            SaveContent(current.value, true, SaveActions(toggle = { name -> current.value = current.value.copy(selected = current.value.selected + name) }))
        } } } }
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("save-row-Vor.plr"))
        compose.onNodeWithTag("save-row-Vor.plr").performClick()
        compose.onNodeWithTag("export-saves").assertTextContains("1", substring = true)
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("kind-Worlds"))
        compose.onNodeWithTag("kind-Worlds").performClick()
        compose.onNodeWithTag("save-search").performTextInput("ITEM")
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("save-row-Items.wld"))
        compose.onNodeWithTag("save-row-Items.wld").performClick()
        compose.onNodeWithTag("export-saves").assertTextContains("2", substring = true)
        compose.runOnIdle { assertEquals(setOf("Vor.plr", "Items.wld"), current.value.selected) }
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
