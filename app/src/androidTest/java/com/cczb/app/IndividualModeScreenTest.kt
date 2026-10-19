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
            TaskComposer(state.value, selected.longValue, "500", "60", true, true, List(32) { "2000" },
                { "阀门 $it" }, {}, { selected.longValue = it }, {}, {}, { _, _ -> }, {})
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
                TaskComposer(ready.copy(slaves = ready.slaves!!.take(2)), 1, "500", "90", true, true, amounts,
                    { "阀门 $it" }, {}, {}, {}, {}, { slot, value ->
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

    @Test fun reviewShowsActualPerValveTargetsAndSum() {
        compose.setContent { PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            TaskReviewScreen(ready, 0x80000001, "500", "90", true,
                List(32) { if (it == 0) "100" else "2000" }, true, { "阀门 $it" }, {}, {})
        } } }
        compose.onNodeWithText("2100 mL").assertExists()
        compose.onNodeWithText("90 秒").assertExists()
        compose.onNodeWithText("确认选择并启动").performScrollTo().assertIsEnabled()
    }
}
