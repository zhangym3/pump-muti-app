package com.cczb.app

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline regression: no BLE connection or physical control. */
@RunWith(AndroidJUnit4::class)
class MasterControlScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun noPlaceholderNodesAreShownWithoutInventory() {
        compose.onNodeWithText("请连接主机并等待阀门清单同步。").assertExists()
        compose.onNodeWithText("单路最长时间（1–240 秒）").assertDoesNotExist()
        compose.onNodeWithText("01").assertDoesNotExist()
        compose.onNodeWithText("32").assertDoesNotExist()
        compose.onNodeWithText("检查并启动").assertIsNotEnabled()
    }
    @Test fun devicePageHasMasterScanActionAndNoFakeSlaveRows() {
        compose.onNodeWithText("设备").performClick()
        compose.onNodeWithText("添加设备").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("01").assertDoesNotExist()
        compose.onNodeWithText("32").assertDoesNotExist()
    }
    @Test fun disconnectedWaterTestIsBlockedWithoutConfirmationUi() {
        compose.onNodeWithText("设备").performClick()
        compose.onNodeWithText("设备维护").performScrollTo().performClick()
        compose.onNodeWithText("水泵测试与调速").performScrollTo().performClick()
        compose.onNodeWithText("通水测试").assertExists()
        compose.onNodeWithText("快速测试").assertDoesNotExist()
        compose.onNodeWithText("供水和出水通路已准备").assertDoesNotExist()
        compose.onNodeWithText("启动测试").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("停止测试").assertIsNotEnabled()
    }
    @Test fun amountDraftSurvivesNavigationAndActivityRecreation() {
        compose.onNodeWithContentDescription("出水量 mL").performScrollTo().performTextReplacement("250")
        compose.onNodeWithText("记录").performClick()
        compose.onNodeWithText("暂无任务记录").assertExists()
        compose.onNodeWithText("控制").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("出水量 mL").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("出水量 mL").assertTextContains("250")
        compose.onNodeWithText("检查并启动").performScrollTo().assertIsNotEnabled()
    }
    @Test fun globalTimeIsOnlyEditedInSettingsAndSurvivesRecreation() {
        compose.onNodeWithText("单路最长时间（1–240 秒）").assertDoesNotExist()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("单路最长时间（1–240 秒）").performScrollTo().performTextReplacement("90")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("90").assertExists()
        compose.onNodeWithText("控制").performClick()
        compose.onNodeWithText("单路最长时间（1–240 秒）").assertDoesNotExist()
    }
}
