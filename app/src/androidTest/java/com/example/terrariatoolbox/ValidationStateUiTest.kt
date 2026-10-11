package com.example.terrariatoolbox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.terrariatoolbox.domain.*
import com.example.terrariatoolbox.feature.chinese.*
import com.example.terrariatoolbox.ui.theme.TerrariaToolboxTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ValidationStateUiTest {
    @get:Rule val compose = createComposeRule()
    private val source = SourceInfo("content://com.android.externalstorage.documents/document/$TARGET_DOCUMENT_ID", "grant",
        "external|$TARGET_DOCUMENT_ID", "com.android.externalstorage.documents", TARGET_DOCUMENT_ID, "config.json", "文件授权",
        true, true, true, true)
    @Test fun switchRequiresGameExitConfirmationBeforeCallingOperation() {
        var writes = 0
        show(ValidationState(source, ConfigProcessor().inspect("{\"Language\":1}".toByteArray())), ValidationActions(switch = { writes++ }))
        compose.onNodeWithTag("switch-chinese").assertIsEnabled().performClick()
        assertEquals(0, writes)
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, writes)
        compose.onNodeWithTag("switch-chinese").performClick()
        compose.onNodeWithText("已退出，开始验证").performClick()
        assertEquals(1, writes)
    }
    @Test fun chineseStateNeverEnablesAnotherWrite() {
        show(ValidationState(source, ConfigProcessor().inspect("{\"Language\":7}".toByteArray())))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
    }
    @Test fun interruptedOperationDisablesNewSwitches() {
        val pending = PendingOperation(source, "backup", "before", "after", "switch", 1)
        show(ValidationState(source, ConfigProcessor().inspect("{\"Language\":1}".toByteArray()), pending = pending))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
    }
    @Test fun disconnectedShizukuDisablesWriteAndDoesNotShowSafPersistenceText() {
        val shell = source.copy(uri = SHIZUKU_URI, identity = SHIZUKU_IDENTITY, persisted = false, accessMethod = AccessMethod.SHIZUKU)
        show(ValidationState(shell, ConfigProcessor().inspect("{\"Language\":1}".toByteArray()),
            shizuku = ShizukuState(phase = ShizukuPhase.DISCONNECTED, message = "Shizuku 服务已断开")))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        compose.onAllNodesWithText("授权未持久保存，重启后可能需要重新授权").assertCountEquals(0)
    }
    @Test fun connectedShizukuStillRequiresGameExitConfirmation() {
        var writes = 0
        val shell = source.copy(uri = SHIZUKU_URI, identity = SHIZUKU_IDENTITY, persisted = false, accessMethod = AccessMethod.SHIZUKU)
        show(ValidationState(shell, ConfigProcessor().inspect("{\"Language\":1}".toByteArray()),
            shizuku = ShizukuState(phase = ShizukuPhase.CONNECTED)), ValidationActions(switch = { writes++ }))
        compose.onNodeWithTag("switch-chinese").assertIsEnabled().performClick()
        assertEquals(0, writes)
        compose.onNodeWithText("已退出，开始验证").performClick(); assertEquals(1, writes)
    }

    @Test fun recoveryWarningSurvivesHomeModeAndSecondaryPageNavigation() {
        val state = ValidationState(source, ConfigProcessor().inspect("{\"Language\":1}".toByteArray()),
            pending = PendingOperation(source, "backup", "before", "after", "switch", 1))
        compose.setContent { TerrariaToolboxTheme { ValidationContent(state, "测试设备", ValidationActions(), initialPage = "home") } }
        compose.onNodeWithTag("recovery-warning").assertExists()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("open-chinese"))
        compose.onNodeWithTag("open-chinese").performClick()
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("mode-copy"))
        compose.onNodeWithTag("mode-copy").performClick()
        compose.onNodeWithTag("recovery-warning").assertExists()
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("mode-direct"))
        compose.onNodeWithTag("mode-direct").performClick()
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("switch-chinese"))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        compose.onNodeWithTag("navigate-home").performClick()
        compose.onNodeWithTag("recovery-warning").assertExists()
    }

    @Test fun returningHomeDoesNotCancelBusyOperation() {
        var writes = 0
        show(ValidationState(source, ConfigProcessor().inspect("{\"Language\":1}".toByteArray()), busy = true),
            ValidationActions(switch = { writes++ }))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        compose.onNodeWithTag("navigate-home").performClick()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("operation-result"))
        compose.onNodeWithTag("operation-result").assertExists(); assertEquals(0, writes)
    }
    private fun show(state: ValidationState, actions: ValidationActions = ValidationActions()) {
        compose.setContent { TerrariaToolboxTheme { ValidationContent(state, "测试设备", actions) } }
        compose.onNodeWithTag("validation-list").performScrollToNode(hasTestTag("switch-chinese"))
    }
}
