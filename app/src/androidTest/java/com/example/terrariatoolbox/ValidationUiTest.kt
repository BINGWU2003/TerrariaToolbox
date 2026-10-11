package com.example.terrariatoolbox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Ordinary smoke checks never connect to Shizuku or touch a game configuration. */
class ValidationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun saveEntryOpensBuiltinsAndShowsConnectionGuideWithoutGameConfiguration() {
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("open-saves"))
        compose.onNodeWithTag("open-saves").performClick()
        compose.onNodeWithTag("saves-go-connect").assertExists()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("show-builtins"))
        compose.onNodeWithTag("show-builtins").performClick()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("builtin-Galactus.plr"))
        compose.onNodeWithTag("builtin-Galactus.plr").assertExists()
        compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("import-builtins"))
        compose.onNodeWithTag("import-builtins").assertIsNotEnabled()
    }

    @Test fun homeHasConnectionStepsAndLanguageEntryWithoutFileOrCopyModes() {
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("connect-shizuku"))
        compose.onNodeWithTag("connect-shizuku").assertExists()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("shizuku-guide"))
        compose.onNodeWithTag("shizuku-guide").assertTextContains("无线调试", substring = true)
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("open-chinese"))
        compose.onNodeWithTag("open-chinese").performClick()
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("switch-chinese"))
        compose.onNodeWithTag("switch-chinese").assertExists()
        compose.onNodeWithTag("switch-english").assertExists()
        listOf("mode-copy", "select-file", "select-directory", "import-copy", "connect-shizuku").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("connect-shizuku"))
        compose.onNodeWithTag("connect-shizuku").assertExists()
    }
}
