package io.github.bingwu2003.terrariatoolbox

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
    @Test fun genuineShizukuHomeConnectsAndToolSwitchesBothDirectionsThenRestores() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuFixture") == "true")
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("connect-shizuku"))
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("connect-shizuku") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("connect-shizuku").performClick()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("operation-result"))
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("operation-result") and hasText("已连接，当前游戏语言", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("open-chinese"))
        compose.onNodeWithTag("open-chinese").performClick()
        scroll(hasTestTag("switch-chinese"))
        compose.onNodeWithTag("switch-chinese").assertIsEnabled().performClick()
        compose.onNodeWithText("已退出游戏，切换到中文").performClick()
        waitResult("已切换为中文")
        scroll(hasTestTag("switch-chinese"))
        compose.onNodeWithTag("switch-chinese").assertIsNotEnabled()
        scroll(hasTestTag("switch-english")); compose.onNodeWithTag("switch-english").performClick()
        compose.onNodeWithText("已退出游戏，切换到英文").performClick()
        waitResult("已切换为英文")
        scroll(hasTestTag("switch-english")); compose.onNodeWithTag("switch-english").assertIsNotEnabled()
        scroll(hasTestTag("more-actions")); compose.onNodeWithTag("more-actions").performClick()
        scroll(hasTestTag("open-backups")); compose.onNodeWithTag("open-backups").performClick()
        scroll(hasText("恢复这份备份"))
        compose.onAllNodesWithText("恢复这份备份").filter(isEnabled()).onFirst().performClick()
        compose.onNodeWithText("已退出游戏，恢复备份").performClick()
        waitResult("已恢复备份")
        compose.onNodeWithTag("navigate-back").performClick()
        scroll(hasTestTag("switch-english")); compose.onNodeWithTag("switch-english").assertIsEnabled()
    }
    private fun scroll(matcher: SemanticsMatcher) { compose.onNodeWithTag("validation-list").performScrollToNode(matcher) }
    private fun waitResult(text: String) {
        scroll(hasTestTag("operation-result"))
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("operation-result") and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
