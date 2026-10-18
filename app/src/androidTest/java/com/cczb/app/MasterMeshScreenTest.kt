package com.cczb.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cczb.app.ui.theme.PumpmutiappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MasterMeshScreenTest {
    @get:Rule val compose = createComposeRule()
    private val nodes = listOf(MasterSlave(1, 2, true, true), MasterSlave(9, 10, false, true), MasterSlave(32, 33, true, true))
    private val ui = MasterUiState(connection = MasterConnectionState.Connected,
        hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE"), statusFresh = true,
        local = MasterMessage.Local(false, 0, "NONE"), localFresh = true,
        slaves = nodes, inventoryFresh = true, meshFresh = true,
        mesh = MasterMeshState("SCAN_DONE", false, true, "NONE", 1, 1, 1, 0))

    @Test fun sparseValveSelectionKeepsOfflineNodesOutOfTheTask() {
        compose.setContent { var mask by remember { mutableLongStateOf(0) }
            PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                ValveSelectionScreen(ui, mask, { "阀门 $it" }, { mask = it }, {}, {})
            } }
        }
        compose.onNodeWithText("全选在线").performScrollTo().performClick()
        compose.onNodeWithText("已选 2 / 3 路").assertExists()
        compose.onNodeWithText("阀门 9").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("已选 2 / 3 路").assertExists()
        compose.onNodeWithText("确认选择（2）").performScrollTo().assertIsEnabled()
    }
    @Test fun scanAndJoinUseTheirOwnCallbacks() {
        var scans = 0; var joined = ""
        val uuid = "dddd504d3076f5ba02ce020000000000"
        compose.setContent { var open by remember { mutableStateOf(false) }
            PumpmutiappTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                DevicesScreen(ui.copy(slaves = emptyList(), candidates = listOf(uuid)), { "阀门 $it" }, {}, {}, {}, open,
                    { scans++; open = true }, { joined = it }, {}, {})
        } } }
        compose.onNodeWithText("添加设备").performScrollTo().assertIsEnabled().performClick()
        compose.onNode(hasText("PUMP_SLAVE_02CE", substring = true)).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, scans); assertEquals(uuid, joined) }
    }
    @Test fun incompleteInventoryDoesNotDisplayZeroPairedDevices() {
        compose.setContent { PumpmutiappTheme { Column { MasterSummary(MasterUiState(), {}) } } }
        compose.onNodeWithText("主机状态待同步").assertExists()
        compose.onNodeWithText("0/0 台从机在线", substring = true).assertDoesNotExist()
    }
}
