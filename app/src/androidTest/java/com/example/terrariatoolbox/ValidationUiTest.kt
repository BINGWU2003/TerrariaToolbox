package com.example.terrariatoolbox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Ordinary smoke checks never connect to Shizuku or touch a game configuration. */
class ValidationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualBuiltinCatalogSeparatesAllThreeWorldsAndKeepsChinesePlayerEmpty() {
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-import"))
        compose.onNodeWithTag("home-import").performClick()
        compose.onNodeWithTag("show-builtins").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-edition-0"))
        compose.onNodeWithTag("builtin-edition-0").performClick()
        compose.onNodeWithTag("builtin-kind-Worlds").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-（经典）言寺全物品1.4.5.8.wld"))
        compose.onNodeWithTag("builtin-（经典）言寺全物品1.4.5.8.wld").assertExists()
        capture("builtin-international-worlds.png")
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-edition-1"))
        compose.onNodeWithTag("builtin-edition-1").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-（经典）心动国服 言寺全物品1.4.5.8.wld"))
        compose.onNodeWithTag("builtin-（经典）心动国服 言寺全物品1.4.5.8.wld").assertExists()
        capture("builtin-chinese-worlds.png")
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-kind-Players"))
        compose.onNodeWithTag("builtin-kind-Players").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasText("暂无国服内置角色"))
        compose.onNodeWithText("暂无国服内置角色").assertExists()
        capture("builtin-chinese-players-empty.png")
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-kind-Worlds"))
        compose.onNodeWithTag("builtin-kind-Worlds").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-（经典）心动国服 言寺全物品1.4.5.8.wld"))
        compose.onNodeWithTag("builtin-（经典）心动国服 言寺全物品1.4.5.8.wld").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("import-builtins"))
        compose.onNodeWithTag("import-builtins").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("import-source-edition"))
        compose.onNodeWithTag("import-source-edition").assertTextContains("国服", substring = true)
        capture("builtin-chinese-target.png")
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("review-import"))
        compose.onNodeWithTag("review-import").assertIsNotEnabled()
    }

    @Test fun saveEntryOpensBuiltinsAndShowsConnectionGuideWithoutGameConfiguration() {
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("open-saves"))
        compose.onNodeWithTag("open-saves").performClick()
        compose.onNodeWithTag("saves-go-connect").assertExists()
        capture("saves.png")
        compose.onNodeWithTag("start-import").performClick()
        capture("import-source.png")
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("show-builtins"))
        compose.onNodeWithTag("show-builtins").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-Galactus.plr"))
        compose.onNodeWithTag("builtin-Galactus.plr").assertExists()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("import-builtins"))
        compose.onNodeWithTag("import-builtins").assertIsNotEnabled()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-Galactus.plr"))
        compose.onNodeWithTag("builtin-Galactus.plr").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("import-builtins"))
        compose.onNodeWithTag("import-builtins").assertIsEnabled().performClick()
        compose.onNodeWithTag("review-import").assertIsNotEnabled()
    }

    @Test fun homeHasConnectionStepsAndLanguageEntryWithoutFileOrCopyModes() {
        capture("home.png")
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("connect-shizuku"))
        compose.onNodeWithTag("connect-shizuku").assertExists()
        compose.onNodeWithTag("toggle-shizuku-guide").performClick()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("shizuku-guide"))
        compose.onNodeWithTag("shizuku-guide").assertTextContains("无线调试", substring = true)
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("open-chinese"))
        compose.onNodeWithTag("open-chinese").performClick()
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("switch-chinese"))
        compose.onNodeWithTag("switch-chinese").assertExists()
        compose.onNodeWithTag("switch-english").assertExists()
        capture("tools.png")
        listOf("mode-copy", "select-file", "select-directory", "import-copy", "connect-shizuku").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("connect-shizuku"))
        compose.onNodeWithTag("connect-shizuku").assertExists()
    }

    @Test fun bottomNavigationPreservesSaveCategoryAndSearchAndHomeImportIsDirect() {
        compose.onNodeWithTag("nav-saves").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("kind-Worlds"))
        compose.onNodeWithTag("kind-Worlds").performClick()
        compose.onNodeWithTag("save-search").performTextInput("world")
        compose.onNodeWithTag("nav-chinese").performClick()
        compose.onNodeWithTag("nav-saves").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("save-search"))
        compose.onNodeWithTag("save-search").assertTextContains("world")
        compose.onNodeWithTag("kind-Worlds").assertIsSelected()
        compose.onNodeWithTag("nav-home").performClick()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-import"))
        compose.onNodeWithTag("home-import").performClick()
        compose.onNodeWithTag("import-phone").assertIsEnabled()
    }

    @Test fun connectionHelpReturnsToCheckedImportAndNestedHelpReturnsToSettings() {
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-import"))
        compose.onNodeWithTag("home-import").performClick()
        compose.onNodeWithTag("show-builtins").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-Vor.plr"))
        compose.onNodeWithTag("builtin-Vor.plr").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("import-builtins"))
        compose.onNodeWithTag("import-builtins").performClick()
        compose.onNodeWithTag("saves-go-connect").performClick()
        capture("help-connection.png")
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("open-help"))
        compose.onNodeWithTag("open-help").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("open-help"))
        compose.onNodeWithTag("open-help").assertExists()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("review-import").assertIsNotEnabled()
        compose.onNodeWithText("已检查 1 个存档。请确认所属服和导入位置。").assertExists()
        capture("import-target.png")
        compose.onNodeWithTag("nav-home").performClick()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-export"))
        compose.onNodeWithTag("home-export").performClick()
        compose.onNodeWithTag("resume-import").assertExists()
        compose.onNodeWithTag("export-saves").assertIsNotEnabled()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.waitForIdle(500, 5_000)
        val folder = File(instrument.targetContext.getExternalFilesDir(null), "screenshots-v1.6").apply { mkdirs() }
        instrument.uiAutomation.takeScreenshot().let { bitmap ->
            File(folder, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
