package com.example.terrariatoolbox

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.terrariatoolbox.domain.*
import com.example.terrariatoolbox.feature.chinese.*
import com.example.terrariatoolbox.ui.theme.TerrariaToolboxTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ValidationStateUiTest {
    @get:Rule val compose = createComposeRule()
    private val source = SourceInfo(SHIZUKU_URI, SHIZUKU_URI, SHIZUKU_IDENTITY, "Shizuku shell UID 2000", TARGET_PATH,
        "config.json", "Shizuku 授权", true, false, true, true, accessMethod = AccessMethod.SHIZUKU)
    private fun state(language: Int = 1) = ValidationState(source,
        ConfigProcessor().inspect("{\"Language\":$language,\"volume\":0.5}".toByteArray()),
        message = "已连接，可以切换游戏语言。",
        shizuku = ShizukuState(phase = ShizukuPhase.CONNECTED, message = "Shizuku 已连接。"))

    @Test fun chineseSwitchRequiresExitConfirmationAndCancellationDoesNotWrite() {
        var writes = 0
        show(state(), ValidationActions(switch = { writes++ }))
        compose.onNodeWithTag("switch-chinese").assertIsEnabled().performClick()
        assertEquals(0, writes)
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, writes)
        compose.onNodeWithTag("switch-chinese").performClick()
        compose.onNodeWithText("已退出游戏，切换到中文").performClick()
        assertEquals(1, writes)
    }

    @Test fun englishSwitchDispatchesEnglishOnlyAfterExitConfirmation() {
        var english = 0
        var chinese = 0
        show(state(7), ValidationActions(switch = { chinese++ }, switchEnglish = { english++ }))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        capture("language-chinese.png")
        compose.onNodeWithTag("switch-english").assertIsEnabled().performClick()
        capture("confirm-english.png")
        assertEquals(0, english)
        compose.onNodeWithText("已退出游戏，切换到英文").performClick()
        assertEquals(1, english)
        assertEquals(0, chinese)
    }

    @Test fun englishCurrentStateDisablesOnlyEnglishAndHidesTechnicalDetails() {
        show(state())
        compose.onNodeWithTag("switch-chinese").assertIsEnabled()
        compose.onNodeWithTag("switch-english").assertIsNotEnabled()
        capture("language-english.png")
        compose.onAllNodesWithText(TARGET_PATH, substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("确认这是游戏原配置").assertCountEquals(0)
        compose.onAllNodesWithText("shell UID", substring = true).assertCountEquals(0)
    }

    @Test fun legacySafSourceCannotEnableEitherLanguage() {
        show(state().copy(source = source.copy(accessMethod = AccessMethod.SAF)))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        compose.onNodeWithTag("switch-english").assertIsNotEnabled()
        compose.onNodeWithTag("go-connect").assertExists()
    }

    @Test fun disconnectedShizukuDisablesBothDirections() {
        show(state(7).copy(shizuku = ShizukuState(phase = ShizukuPhase.DISCONNECTED)))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        compose.onNodeWithTag("switch-english").assertIsNotEnabled()
    }

    @Test fun disconnectWhileConfirmingDisablesWriteAndStillAllowsCancellation() {
        val current = mutableStateOf(state(7))
        var writes = 0
        compose.setContent { TerrariaToolboxTheme { ValidationContent(current.value, "测试设备", ValidationActions(switchEnglish = { writes++ })) } }
        scroll(hasTestTag("switch-english")); compose.onNodeWithTag("switch-english").performClick()
        compose.runOnIdle { current.value = current.value.copy(shizuku = ShizukuState(phase = ShizukuPhase.DISCONNECTED)) }
        compose.onNodeWithText("已退出游戏，切换到英文").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, writes)
    }

    @Test fun recoveryWarningSurvivesNavigationAndBlocksBothLanguages() {
        val current = state().copy(pending = PendingOperation(source, "backup", "before", "after", "switch", 1))
        compose.setContent { TerrariaToolboxTheme { ValidationContent(current, "测试设备", ValidationActions(), initialPage = "home") } }
        compose.onNodeWithTag("recovery-warning").assertExists()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("open-chinese"))
        compose.onNodeWithTag("open-chinese").performClick()
        scroll(hasTestTag("switch-chinese")); compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        compose.onNodeWithTag("switch-english").assertIsNotEnabled()
        scroll(hasTestTag("more-actions")); compose.onNodeWithTag("more-actions").performClick()
        scroll(hasTestTag("open-diagnostics")); compose.onNodeWithTag("open-diagnostics").performClick()
        compose.onNodeWithTag("recovery-warning").assertExists()
        compose.onNodeWithTag("navigate-home").performClick()
        compose.onNodeWithTag("recovery-warning").assertExists()
    }

    @Test fun restoreExplainsAllSettingsAndRequiresSeparateExitConfirmation() {
        val backup = BackupRecord("test", 1, source, 20, "hash", "1", null, "切换中文前")
        var restores = 0
        show(state(7).copy(backups = listOf(backup)), ValidationActions(restore = { restores++ }))
        scroll(hasTestTag("more-actions")); compose.onNodeWithTag("more-actions").performClick()
        scroll(hasTestTag("open-backups")); compose.onNodeWithTag("open-backups").performClick()
        scroll(hasTestTag("backup-restore-test")); compose.onNodeWithTag("backup-restore-test").performClick()
        compose.onNodeWithText("语言、音量和按键等设置都会还原", substring = true).assertExists()
        assertEquals(0, restores)
        compose.onNodeWithText("已退出游戏，恢复备份").performClick()
        assertEquals(1, restores)
    }

    @Test fun returningHomeDoesNotCancelBusyOperation() {
        show(state().copy(busy = true))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("operation-result"))
        compose.onNodeWithTag("operation-result").assertExists()
    }

    private fun show(state: ValidationState, actions: ValidationActions = ValidationActions()) {
        compose.setContent { TerrariaToolboxTheme { ValidationContent(state, "测试设备", actions) } }
        scroll(hasTestTag("switch-chinese"))
    }
    private fun scroll(matcher: SemanticsMatcher) { compose.onNodeWithTag("validation-list").performScrollToNode(matcher) }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        instrument.uiAutomation.waitForIdle(500, 5_000)
        File(context.filesDir, name).outputStream().use {
            instrument.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
