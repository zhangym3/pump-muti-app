package com.cczb.app

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
        compose.onNodeWithText("请选择在线阀门").assertExists()
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
        compose.onNodeWithText("自定义 mL").performScrollTo().performTextReplacement("250")
        compose.onNodeWithText("记录").performClick()
        compose.onNodeWithText("暂无任务记录").assertExists()
        compose.onNodeWithText("控制").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("250 mL").performScrollTo().assertExists()
        compose.onNodeWithText("检查并启动").performScrollTo().assertIsNotEnabled()
    }
}
