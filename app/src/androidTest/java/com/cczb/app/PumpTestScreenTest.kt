package com.cczb.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cczb.app.ui.theme.PumpmutiappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic state only: no BLE manager, hardware connection, or pump command. */
@RunWith(AndroidJUnit4::class)
class PumpTestScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ready = MasterUiState(connection = MasterConnectionState.Connected,
        hello = MasterMessage.Hello("PUMP_MASTER", 0, 1, 7, false, true, "NONE"), statusFresh = true,
        local = MasterMessage.Local(false, 0, "NONE", testSupported = true, pumpReady = true), localFresh = true)

    @Test fun preparedMasterCanStartIndependentTestBeforeMeshProvisioning() {
        var starts = 0
        compose.setContent {
            PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                PumpTestScreen(ready, 3, {}, {}, {}, { starts++ }, {})
            } }
        }
        compose.onNodeWithText("启动测试").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
        compose.onNodeWithText("停止测试").assertIsNotEnabled()
    }

    @Test fun pendingStartCanBeStoppedEvenWhenReadinessIsStale() {
        var stops = 0
        val pending = ready.copy(pumpTest = MasterPumpTest("a1", 8, 7, 3000),
            statusFresh = false, localFresh = false, storageHealthy = false)
        compose.setContent {
            PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                PumpTestScreen(pending, 3, {}, {}, {}, {}, { stops++ })
            } }
        }
        compose.onNodeWithText("启动测试").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("停止测试").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithText("3 秒").performScrollTo().assertIsNotEnabled()
    }

    @Test fun speedChipsSendSelectionWithoutStartingPump() {
        var level = 0
        var starts = 0
        val pwm = ready.copy(pwmFresh = true, pwm = MasterPumpPwm().receive(
            MasterMessage.PumpPwm(7, 0, 1, 50, 20000, false, "OK"), 7))
        compose.setContent {
            PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                PumpTestScreen(pwm, 3, {}, {}, {}, { starts++ }, {}, { level = it })
            } }
        }
        compose.onNodeWithText("中档 75%").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, level); assertEquals(0, starts) }
        compose.onNodeWithText("高档 100%").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(3, level); assertEquals(0, starts) }
    }

    @Test fun stalePwmDisablesSpeedAndStartButKeepsStopAvailable() {
        var stops = 0
        val pwm = MasterPumpPwm().receive(MasterMessage.PumpPwm(7, 0, 1, 50, 20000, true, "OK"), 7).request(2)
        val pending = ready.copy(pwm = pwm, pwmFresh = false, pumpTest = MasterPumpTest("a1", 8, 7, 3000))
        compose.setContent {
            PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                PumpTestScreen(pending, 3, {}, {}, {}, {}, { stops++ })
            } }
        }
        compose.onNodeWithText("中档 75%").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("启动测试").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("停止测试").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
    @Test fun onlyWaterTestIsShownAndStartNeedsNoConfirmation() {
        var starts = 0
        val locked = ready.copy(hello = ready.hello!!.copy(fault = "BOOT_LOCK"),
            local = ready.local!!.copy(fault = "BOOT_LOCK", phase = 4), pwmFresh = true,
            pwm = MasterPumpPwm(MasterMessage.PumpPwm(7, 0, 3, 100, 20000, false, "OK", true, 3000)))
        compose.setContent { PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            PumpTestScreen(locked, 10, {}, {}, {}, { starts++ }, {})
        } } }
        compose.onNodeWithText("通水测试").assertExists()
        compose.onNodeWithText("快速测试").assertDoesNotExist()
        compose.onNodeWithText("已确认设备允许短时运行").assertDoesNotExist()
        compose.onNodeWithText("启动测试").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }
    @Test fun meshManagementBlocksTestButNeverBlocksKnownStop() {
        var stops = 0
        val pending = ready.copy(pumpTest = MasterPumpTest("a1", 8, 7, 3000),
            mesh = MasterMeshState("JOINING", true, true, "NONE", 1, 1, 1, 0))
        compose.setContent { PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            PumpTestScreen(pending, 3, {}, {}, {}, {}, { stops++ })
        } } }
        compose.onNodeWithText("启动测试").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("停止测试").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
}
