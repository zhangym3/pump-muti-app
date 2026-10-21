package com.cczb.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cczb.app.ui.theme.PumpmutiappTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveFlowScreenTest {
    @get:Rule val compose = createComposeRule()

    private val job = MasterJobKey(7, 1)
    private val task = MasterTask("a1", 1, 7, 1, 500, 60_000,
        MasterOutcome.ACCEPTED, job)

    @Test fun currentTaskShowsLiveVolumePulsesAndRateThenMarksStaleRate() {
        val state = MasterUiState(
            connection = MasterConnectionState.Connected,
            task = task,
            slaves = listOf(MasterSlave(1, 2, true, true)),
            liveFlows = mapOf(2L to MasterMessage.Live(job, 2, 20, 120, 2_000, 600, 1_000)),
            flowClockAt = 1_000
        )
        val uiState = mutableStateOf(state)
        compose.setContent { PumpmutiappTheme { Column { LiveFlowPanel(uiState.value) { "阀门 $it" } } } }
        compose.onNodeWithText("累计 20 mL · 120 脉冲").assertExists()
        compose.onNodeWithText("当前流速 600 mL/min").assertExists()

        compose.runOnIdle { uiState.value = state.copy(flowClockAt = 7_001) }
        compose.onNodeWithText("当前流速 —").assertExists()
        compose.onNodeWithText("当前流速 600 mL/min").assertDoesNotExist()
    }

    @Test fun finalFlowReplacesLiveSnapshot() {
        val state = MasterUiState(
            connection = MasterConnectionState.Connected,
            task = task,
            slaves = listOf(MasterSlave(1, 2, true, true)),
            liveFlows = mapOf(2L to MasterMessage.Live(job, 2, 20, 120, 2_000, 600, 1_000)),
            flows = mapOf(2L to MasterMessage.Flow(job, 2, 500, "DONE", 2_940, terminal = true,
                measured = true, reason = "TARGET")),
            flowClockAt = 1_000
        )
        compose.setContent { PumpmutiappTheme { Column { LiveFlowPanel(state) { "阀门 $it" } } } }
        compose.onNodeWithText("已完成").assertExists()
        compose.onNodeWithText("最终出水 500 mL · 2940 脉冲").assertExists()
        compose.onNodeWithText("当前流速 600 mL/min").assertDoesNotExist()
    }

    @Test fun measuredLeaseFaultIsNotShownAsCompleted() {
        val state = MasterUiState(
            task = task.copy(outcome = MasterOutcome.FINISHED),
            slaves = listOf(MasterSlave(1, 2, true, true)),
            flows = mapOf(2L to MasterMessage.Flow(job, 2, 62, "FAULT", 367, terminal = true,
                measured = true, reason = "LEASE"))
        )
        compose.setContent { PumpmutiappTheme { Column { LiveFlowPanel(state) { "阀门 $it" } } } }
        compose.onNodeWithText("异常停止").assertExists()
        compose.onNodeWithText("已完成").assertDoesNotExist()
        compose.onNodeWithText("最终出水 62 mL · 367 脉冲").assertExists()
    }

    @Test fun measuredManualStopIsNotShownAsCompleted() {
        val state = MasterUiState(
            task = task.copy(outcome = MasterOutcome.FINISHED),
            slaves = listOf(MasterSlave(1, 2, true, true)),
            flows = mapOf(2L to MasterMessage.Flow(job, 2, 62, "DONE", 367, terminal = true,
                measured = true, reason = "MANUAL"))
        )
        compose.setContent { PumpmutiappTheme { Column { LiveFlowPanel(state) { "阀门 $it" } } } }
        compose.onNodeWithText("已停止").assertExists()
        compose.onNodeWithText("已完成").assertDoesNotExist()
    }
}
