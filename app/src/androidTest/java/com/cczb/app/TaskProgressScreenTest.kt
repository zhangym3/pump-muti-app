package com.cczb.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cczb.app.ui.theme.PumpmutiappTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Inert observations only; this screen never opens BLE or starts a pump. */
@RunWith(AndroidJUnit4::class)
class TaskProgressScreenTest {
    @get:Rule val compose = createComposeRule()
    private val job = MasterJobKey(328, 102)
    private val task = MasterTask("a1", 1, 328, 0b1011, 100, 60_000, MasterOutcome.ACCEPTED, job)
    private val state = MasterUiState(connection = MasterConnectionState.Connected, task = task,
        taskAddresses = mapOf(1 to 2L, 2 to 3L, 4 to 5L), flowClockAt = 1_000,
        report = MasterMessage.Report(task.client, task.seq, "OK", job, 3, task.mask, 0, 0, 0),
        liveFlows = mapOf(
            2L to MasterMessage.Live(job, 2, 52, 312, 2_000, 358, 1_000),
            3L to MasterMessage.Live(job, 3, 41, 246, 2_000, 296, 1_000),
            5L to MasterMessage.Live(job, 5, 45, 268, 2_000, 322, 1_000)))

    private fun fiveValveState() = state.copy(task = task.copy(mask = 31, ml = 2000),
        report = state.report!!.copy(mask = 31), taskAddresses = (1..5).associateWith { it + 1L },
        liveFlows = (1..5).associate { slot ->
            val address = slot + 1L
            address to MasterMessage.Live(job, address, 1989 + slot.toLong(), 11950 + slot.toLong(), 2_000, 358, 1_000)
        })

    private fun show(ui: MasterUiState = state, width: Int = 380, dark: Boolean = false, fontScale: Float = 1f,
        alias: (Int) -> String = { "阀门 $it" }) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PumpmutiappTheme(darkTheme = dark) {
                    androidx.compose.material3.Surface {
                        Column(Modifier.width(width.dp).verticalScroll(rememberScrollState()).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CurrentTaskSection(ui, alias, {}, {})
                        }
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun totalHasHorizontalProgressAndAnOddCardIsCenteredAtTheSameWidth() {
        show()
        compose.onNodeWithTag("task-total-progress").assertExists()
        compose.onNodeWithText("138 mL").assertExists()
        compose.onNodeWithText("46%").assertExists()
        compose.onNodeWithText("300 mL").assertExists()
        val first = compose.onNodeWithTag("valve-progress-1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("valve-progress-2").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("valve-progress-4").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left > first.right)
        assertTrue(third.top > first.bottom)
        assertEquals(first.width, third.width, 1f)
        assertEquals((first.left + second.right) / 2, third.center.x, 1f)
        screenshot("task-progress.png")
    }

    @Test fun narrowScreenAndLargeTextKeepTwoColumnsAndUnknownVolumeIsNotZeroPercent() {
        show(state.copy(liveFlows = emptyMap()), width = 320, fontScale = 1.3f)
        compose.onNodeWithText("待确认").assertExists()
        compose.onAllNodesWithText("0%").assertCountEquals(0)
        val first = compose.onNodeWithTag("valve-progress-1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("valve-progress-2").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left > first.right)
        screenshot("task-progress-narrow.png")
    }

    @Test fun darkThemeShowsFinalFaultAndKeepsUnmeasuredFinalUnknown() {
        show(state.copy(flows = mapOf(
            2L to MasterMessage.Flow(job, 2, 108, "DONE", 640, terminal = true, reason = "TARGET"),
            3L to MasterMessage.Flow(job, 3, 42, "FAULT", 250, terminal = true, reason = "NO_FLOW"),
            5L to MasterMessage.Flow(job, 5, 0, "DONE", terminal = true, measured = false, reason = "REBOOT")
        )), dark = true)
        compose.onNodeWithText("已完成").assertExists()
        compose.onNodeWithText("异常停止").assertExists()
        compose.onNodeWithText("待核实").assertExists()
        compose.onNodeWithText("45 mL").assertDoesNotExist()
        compose.onNodeWithText("150 mL").assertExists()
        screenshot("task-progress-dark.png")
    }

    @Test fun fiveValvesKeepTheirOrderAndLargeAmountsFitOnNarrowScreens() {
        show(fiveValveState(), width = 320, fontScale = 1.3f, alias = { "温室东区超长名称阀门 $it" })
        compose.onNodeWithText("10000 mL").assertExists()
        val nodes = (1..5).map { compose.onNodeWithTag("valve-progress-$it").fetchSemanticsNode() }
        val bounds = nodes.map { it.boundsInRoot }
        assertEquals(bounds[0].top, bounds[1].top, 1f)
        assertTrue(bounds[2].top > bounds[0].bottom)
        assertTrue(bounds[3].top > bounds[2].bottom)
        assertEquals(bounds[3].top, bounds[4].top, 1f)
        assertEquals((bounds[0].left + bounds[1].right) / 2, bounds[2].center.x, 1f)
        assertEquals(bounds[0].left, bounds[3].left, 1f)
        assertEquals(bounds[1].left, bounds[4].left, 1f)
        nodes.forEach { assertEquals(nodes[0].size.width, it.size.width) }
        nodes.forEachIndexed { index, card ->
            val metrics = compose.onNodeWithTag("valve-metrics-${index + 1}").fetchSemanticsNode()
            assertTrue("Metrics must fit inside the card", metrics.positionInRoot.y + metrics.size.height <=
                card.positionInRoot.y + card.size.height)
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("1990 mL").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertFalse("Volume must remain readable", layouts.single().hasVisualOverflow)
        screenshot("task-progress-five-narrow.png")
        compose.onNodeWithTag("valve-progress-5").performScrollTo().assertIsDisplayed()
    }

    @Test fun fiveValveProgressFitsInTheNormalPhoneViewport() {
        show(fiveValveState())
        (1..5).forEach { slot -> compose.onNodeWithTag("valve-progress-$slot").assertIsDisplayed() }
        val bounds = (1..5).map { compose.onNodeWithTag("valve-progress-$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].top, bounds[1].top, 1f)
        assertTrue(bounds[2].top > bounds[0].bottom)
        assertEquals((bounds[0].left + bounds[1].right) / 2, bounds[2].center.x, 1f)
        assertTrue(bounds[3].top > bounds[2].bottom)
        assertEquals(bounds[3].top, bounds[4].top, 1f)
        bounds.forEach { assertEquals(bounds[0].width, it.width, 1f) }
        val last = compose.onNodeWithTag("valve-progress-5").fetchSemanticsNode()
        assertTrue(last.positionInRoot.y + last.size.height <= compose.onRoot().fetchSemanticsNode().size.height)
        screenshot("task-progress-five.png")
    }
}
