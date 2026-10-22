package com.cczb.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cczb.app.ui.theme.PumpmutiappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses memory fixtures and inert callbacks; never connects BLE or touches the user's files. */
@RunWith(AndroidJUnit4::class)
class TaskReconciliationScreenTest {
    @get:Rule val compose = createComposeRule()
    private val job = MasterJobKey(15, 6)
    private val task = MasterTask("d82ef12190ff6359", 1, 15, 1, 500, 60_000, MasterOutcome.FINISHED, job)
    private val saved = MasterHistoryEntry("DC:DA:0C:6B:BB:C6", "PUMP_MASTER_BBC6", task, 1792630468007,
        MasterMessage.Report(task.client, task.seq, "OK", job, 6, 1, 1, 0, 0, "NONE"),
        MasterMessage.Volume(job, 502, 1),
        mapOf(3L to MasterMessage.Flow(job, 3, 502, "DONE", 2957, 229, true, true, reason = "TARGET")),
        mapOf(1 to 3L))

    private fun show(entry: MasterHistoryEntry): () -> Int {
        var confirmations = 0
        val ui = MasterUiState(masterAddress = saved.address, task = task, history = listOf(entry),
            connection = MasterConnectionState.Connected, statusFresh = true,
            hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 23, true, true, "NONE"),
            slaves = listOf(MasterSlave(1, 3, true, true)), inventoryFresh = true).restoreTaskObservation()
        compose.setContent {
            PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                TaskComposer(ui, 1, "500", "60", false, List(32) { "500" }, { "阀门 $it" },
                    {}, {}, {}, { _, _ -> }, { confirmations++ })
                CurrentTaskSection(ui, { "阀门 $it" }, {}, {})
            } }
        }
        return { confirmations }
    }

    @Test fun completeRestorationEnablesReviewAndHidesTheFinishedTask() {
        val confirmations = show(saved)
        compose.onNodeWithText("检查并启动").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirmations()) }
        compose.onNodeWithText("最近一次任务").assertDoesNotExist()
        compose.onNodeWithText("待核实任务").assertDoesNotExist()
        compose.onNodeWithText("展开结果").assertDoesNotExist()
        compose.onNodeWithTag("task-total-progress").assertDoesNotExist()
    }

    @Test fun missingFlowKeepsReviewDisabledAndExplainsMissingResult() {
        show(saved.copy(flows = emptyMap()))
        compose.onNodeWithText("待核实任务").assertExists()
        compose.onNodeWithText("检查并启动").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("阀门 1 缺少该任务的终态计量记录（FLOW）。")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun wrongJobHistoryKeepsReviewDisabledAndReportsMissingMatchingReport() {
        show(saved.copy(task = task.copy(job = MasterJobKey(15, 7))))
        compose.onNodeWithText("检查并启动").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("缺少该任务匹配的主机报告（R）。").performScrollTo().assertIsDisplayed()
    }
}
