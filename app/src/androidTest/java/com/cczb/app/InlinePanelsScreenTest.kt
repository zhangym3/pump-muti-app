package com.cczb.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cczb.app.ui.theme.PumpmutiappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic state and callbacks only; no BLE manager or hardware operations. */
@RunWith(AndroidJUnit4::class)
class InlinePanelsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val task = MasterTask("a1", 1, 7, 1, 500, 60_000,
        outcome = MasterOutcome.FINISHED, job = MasterJobKey(7, 1))
    private val entry = MasterHistoryEntry("AA:BB:CC:DD:EE:01", "测试主机", task, 100,
        MasterMessage.Report("a1", 1, "OK", task.job, 6, 1, 1, 0, 0),
        MasterMessage.Volume(task.job!!, 500, 1),
        mapOf(2L to MasterMessage.Flow(task.job!!, 2, 500, "DONE", 2940, 8, true, true, reason = "TARGET")))

    @Test fun recordExpandsInTheListAndOnlyCopiesDraftOnExplicitAction() {
        var copied: MasterTask? = null
        compose.setContent { PumpmutiappTheme {
            var key by remember { mutableStateOf<String?>(null) }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RecordsScreen(MasterUiState(history = listOf(entry, entry.copy(task = task.copy(seq = 2)))),
                    { "阀门 $it" }, key, { key = it.takeUnless { next -> next == key } }, { copied = it })
            }
        } }
        compose.onAllNodesWithText("查看结果与脉冲 ↓")[0].performClick()
        compose.onNodeWithText("地址 0002 · 500 mL · 2940 脉冲 · DONE").assertExists()
        compose.onNodeWithText("查看结果与脉冲 ↓").assertExists()
        compose.runOnIdle { assertEquals(null, copied) }
        compose.onNodeWithText("填入控制页草稿").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(task, copied) }
        compose.onNodeWithText("返回记录列表").assertDoesNotExist()
    }

    @Test fun unresolvedCurrentTaskPreventsHistoryDraftReplacement() {
        compose.setContent { PumpmutiappTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RecordsScreen(MasterUiState(task = task.copy(outcome = MasterOutcome.PENDING), history = listOf(entry)),
                    { "阀门 $it" }, entry.key, {}, {})
            }
        } }
        compose.onNodeWithText("填入控制页草稿").performScrollTo().assertIsNotEnabled()
    }

    @Test fun unfinishedCalibrationSurvivesTabSwitchAndStateRestoration() {
        val snapshot = MasterMessage.Calibration(7, 1, 1, 2, 0, 5880, 5880, 5880, "OK", 5880, 1)
        val state = MasterUiState(connection = MasterConnectionState.Connected,
            hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE", calibration = true),
            statusFresh = true, inventoryFresh = true, slaves = listOf(MasterSlave(1, 2, true, true)),
            localFresh = true, local = MasterMessage.Local(false, 0, "NONE"),
            calibration = MasterCalibration(1, 2, snapshot))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { PumpmutiappTheme {
            var tab by rememberSaveable { mutableIntStateOf(1) }
            val pages = rememberSaveableStateHolder()
            Column {
                Row {
                    TextButton(onClick = { tab = 0 }) { Text("控制页") }
                    TextButton(onClick = { tab = 1 }) { Text("调校页") }
                }
                pages.SaveableStateProvider(tab) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (tab == 1) CalibrationSettings(state, { "阀门 $it" }, {}, {},
                            { _, _ -> }, {}, {}, {}, {})
                        else Text("控制草稿")
                    }
                }
            }
        } }
        compose.onNodeWithText("低档 50% · 脉冲/mL").performScrollTo().performTextReplacement("6.123")
        compose.onNodeWithText("测试总脉冲数").performScrollTo().performTextReplacement("3000")
        compose.onNodeWithText("控制页").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("调校页").performClick()
        compose.onNodeWithText("低档 50% · 脉冲/mL").performScrollTo().assertTextContains("6.123")
        compose.onNodeWithText("测试总脉冲数").performScrollTo().assertTextContains("3000")
    }
}
