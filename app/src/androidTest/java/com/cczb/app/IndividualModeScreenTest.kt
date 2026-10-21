package com.cczb.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cczb.app.ui.theme.PumpmutiappTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic inventory: no BLE manager and no commands to physical hardware. */
@RunWith(AndroidJUnit4::class)
class IndividualModeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ready = MasterUiState(connection = MasterConnectionState.Connected,
        hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE", individual = true),
        statusFresh = true, localFresh = true, local = MasterMessage.Local(false, 0, "NONE"),
        inventoryFresh = true, slaves = listOf(1, 2, 3, 32).map { MasterSlave(it, it + 1L, true, true) })

    @Test fun pollingAndIncomingOrderNeverMoveValveCards() {
        val state = mutableStateOf(ready)
        val selected = mutableLongStateOf(1)
        compose.setContent { PumpmutiappTheme { Column(Modifier.width(380.dp).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TaskComposer(state.value, selected.longValue, "500", "60", true, List(32) { "2000" },
                { "阀门 $it" }, { selected.longValue = it }, {}, {}, { _, _ -> }, {})
        } } }
        val names = listOf(1, 2, 3, 32).map { "阀门 $it · ID ${it.toString().padStart(2, '0')} · 在线" }
        compose.onNodeWithContentDescription(names[0]).performScrollTo()
        val before = names.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot }
        repeat(4) { pass ->
            compose.runOnIdle { state.value = ready.copy(inventoryLoading = pass % 2 == 0,
                slaves = ready.slaves!!.reversed()); selected.longValue = if (pass % 2 == 0) 0x80000002L else 1 }
            compose.waitForIdle()
            assertEquals(before, names.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot })
        }
        compose.onNodeWithText("单路最长时间（1–240 秒）").assertDoesNotExist()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.filesDir, "individual-mode.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun perValveControlsKeepTargetsIndependentAndRespectBounds() {
        var values = List(32) { "2000" }
        compose.setContent { var amounts by remember { mutableStateOf(values) }
            PumpmutiappTheme { Column(Modifier.width(380.dp).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TaskComposer(ready.copy(slaves = ready.slaves!!.take(2)), 1, "500", "90", true, amounts,
                    { "阀门 $it" }, {}, {}, {}, { slot, value ->
                        amounts = amounts.toMutableList().also { it[slot - 1] = value }; values = amounts
                    }, {})
            } }
        }
        compose.onAllNodesWithText("+")[0].performScrollTo().assertIsNotEnabled()
        compose.onAllNodesWithText("−")[0].performClick()
        compose.runOnIdle { assertEquals("1950", values[0]); assertEquals("2000", values[1]) }
        compose.onAllNodesWithContentDescription("出水量 mL")[1].assertIsNotEnabled()
        compose.onAllNodesWithText("100")[0].performClick()
        compose.runOnIdle { assertEquals("100", values[0]); assertEquals("2000", values[1]) }
        compose.onAllNodesWithContentDescription("出水量 mL")[0].performTextReplacement("2001")
        compose.onNodeWithText("检查并启动").performScrollTo().assertIsNotEnabled()
    }

    @Test fun singleConfirmationShowsPerValveTargetsSumAndSpeed() {
        compose.setContent { PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            TaskStartConfirmation(ready, 0x80000001, "500", "90", true,
                List(32) { if (it == 0) "100" else "2000" }, true, { "阀门 $it" }, {}, {})
        } } }
        compose.onNodeWithText("2100 mL").assertExists()
        compose.onNodeWithText("90 秒").assertExists()
        compose.onNodeWithText("水泵速度").assertExists()
        compose.onNodeWithText("确认启动").assertIsEnabled()
    }

    @Test fun emptySelectionExplainsWhyReviewIsDisabled() {
        compose.setContent { PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            TaskComposer(ready, 0, "500", "60", false, List(32) { "500" },
                { "阀门 $it" }, {}, {}, {}, { _, _ -> }, {})
        } } }
        compose.onNodeWithText("检查并启动").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("请至少选择一个在线且已启用的阀门。").assertExists()
    }

    @Test fun blockedMasterExplainsReasonWithoutOpeningAnotherPage() {
        var confirmations = 0
        compose.setContent {
            PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                TaskComposer(ready.copy(statusFresh = false), 1, "500", "60", false, List(32) { "500" },
                    { "阀门 $it" }, {}, {}, {}, { _, _ -> }, { confirmations++ })
            } }
        }
        compose.onNodeWithText("检查并启动").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("暂不可启动：主机状态已过期，请刷新或重新连接").assertExists()
        compose.onNodeWithText("确认本次出水").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirmations) }
    }

    @Test fun confirmationDisablesStartWhenMasterBecomesStale() {
        val state = mutableStateOf(ready)
        var starts = 0
        compose.setContent { PumpmutiappTheme {
            TaskStartConfirmation(state.value, 1, "500", "60", false, List(32) { "500" }, true,
                { "阀门 $it" }, {}, { starts++ })
        } }
        compose.onNodeWithText("确认启动").assertIsEnabled()
        compose.runOnIdle { state.value = ready.copy(statusFresh = false) }
        compose.onNodeWithText("确认启动").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test fun blockedReviewCanQueryOldTaskWithoutEnablingStart() {
        val task = MasterTask("a1", 1, 7, 1, 500, 60_000,
            outcome = MasterOutcome.FINISHED, job = MasterJobKey(7, 1))
        val report = MasterMessage.Report(task.client, task.seq, "OK", task.job, 6, 1, 1, 0, 0)
        val blocked = ready.copy(task = task, report = report,
            volume = MasterMessage.Volume(task.job!!, 500, 1))
        var queried = false
        compose.setContent { PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            TaskStartConfirmation(blocked, 1, "500", "60", false, List(32) { "500" }, true,
                { "阀门 $it" }, {}, {}, { queried = true })
        } } }

        compose.onNodeWithText("阀门 1 缺少该任务的终态计量记录（FLOW）。").assertExists()
        compose.onNodeWithText("确认启动").assertIsNotEnabled()
        compose.onNodeWithText("查询原任务").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(queried) }
    }

    @Test fun taskComposerSpeedPresetsSendOnlySpeedSelection() {
        var selectedLevel = 0
        var reviews = 0
        val pwm = ready.copy(local = MasterMessage.Local(false, 0, "NONE", testSupported = true, pumpReady = true),
            pwm = MasterPumpPwm().receive(MasterMessage.PumpPwm(7, 0, 1, 50, 20000, false, "OK"), 7), pwmFresh = true)
        compose.setContent { PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            TaskComposer(pwm, 1, "500", "60", false, List(32) { "500" },
                { "阀门 $it" }, {}, {}, {}, { _, _ -> }, { reviews++ }, { selectedLevel = it })
        } } }

        compose.onNodeWithText("低档 50%").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("中档 75%").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("高档 100%").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(3, selectedLevel); assertEquals(0, reviews) }
        compose.onNodeWithText("检查并启动").performScrollTo().assertIsEnabled()
    }
}
