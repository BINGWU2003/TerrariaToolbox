package com.example.terrariatoolbox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Uses only the opt-in dedicated emulator fixture, with Shizuku already started and authorized. */
class ShizukuUiIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @DedicatedFixture
    @Test fun genuineShizukuPageConfirmsSourceSwitchesAndRestores() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuFixture") == "true")
        compose.onNodeWithTag("open-chinese").performClick()
        scroll(hasTestTag("connect-shizuku"))
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("connect-shizuku") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("connect-shizuku").performClick()
        waitResult("Shizuku 读取成功")
        scroll(hasText("确认这是游戏原配置"))
        compose.onNodeWithText("确认这是游戏原配置").performClick()
        compose.onNodeWithText("确认来源").performClick()
        waitResult("已确认这是游戏原配置")
        scroll(hasTestTag("switch-chinese"))
        compose.onNodeWithTag("switch-chinese").assertIsEnabled().performClick()
        compose.onNodeWithText("已退出，开始验证").performClick()
        waitResult("配置已保存并回读校验")
        scroll(hasTestTag("switch-chinese"))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        scroll(hasTestTag("open-backups")); compose.onNodeWithTag("open-backups").performClick()
        scroll(hasText("完整恢复"))
        compose.onAllNodesWithText("完整恢复").filter(isEnabled()).onFirst().performClick()
        compose.onNodeWithText("已退出，完整恢复").performClick()
        waitResult("已恢复游戏配置并逐字节校验")
        compose.onNodeWithTag("navigate-back").performClick()
        scroll(hasTestTag("switch-chinese")); compose.onNodeWithTag("switch-chinese").assertIsEnabled()
    }
    private fun scroll(matcher: SemanticsMatcher) { compose.onNodeWithTag("validation-list").performScrollToNode(matcher) }
    private fun waitResult(text: String) {
        scroll(hasTestTag("operation-result"))
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("operation-result") and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
