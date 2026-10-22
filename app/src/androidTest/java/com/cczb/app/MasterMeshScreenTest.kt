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
                TaskComposer(ui, mask, "500", "60", false, List(32) { "500" }, { "阀门 $it" },
                    { mask = it }, {}, {}, { _, _ -> }, {})
            } }
        }
        compose.onNodeWithText("全选在线").performScrollTo().performClick()
        compose.onNodeWithText("已选 2 / 3 路").assertExists()
        compose.onNodeWithContentDescription("阀门 9 · ID 09 · 离线").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("已选 2 / 3 路").assertExists()
        compose.onNodeWithText("检查并启动").performScrollTo().assertIsEnabled()
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
    @Test fun offlineDeleteTargetsTheAddressAndRequiresConfirmation() {
        var selected = 0L; var removed = 0L
        compose.setContent { var address by remember { mutableStateOf<Long?>(null) }
            val current = ui.copy(mesh = ui.mesh!!.copy(supportsRemoval = true))
            PumpmutiappTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DevicesScreen(current, { "阀门 $it" }, {}, {}, {}, false, {}, {}, {}, {},
                        onRemove = { selected = it; address = it })
                }
                address?.let { SlaveRemovalConfirmation(current, it, { "阀门 $it" },
                    { address = null }, { target -> removed = target; address = null }) }
            }
        }
        compose.onNodeWithContentDescription("删除从机 ${addressLabel(10)}").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(10L, selected); assertEquals(0L, removed) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0L, removed) }
        compose.onNodeWithContentDescription("删除从机 ${addressLabel(10)}").performScrollTo().performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle { assertEquals(10L, removed) }
    }
    @Test fun deletionConfirmationIsDisabledWhenTheInventoryBecomesStale() {
        compose.setContent { PumpmutiappTheme {
            SlaveRemovalConfirmation(ui.copy(mesh = ui.mesh!!.copy(supportsRemoval = true), inventoryFresh = false),
                10, { "阀门 $it" }, {}, { error("Stale inventory must not allow deletion") })
        } }
        compose.onNodeWithText("确认删除").assertIsNotEnabled()
        compose.onNodeWithText("从机清单已过期，请刷新并等待同步完成。", substring = true).assertExists()
    }

    @Test fun unsupportedFirmwareStillOpensRemovalDialogAndExplainsWhyItCannotProceed() {
        compose.setContent { var address by remember { mutableLongStateOf(0L) }
            PumpmutiappTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DevicesScreen(ui, { "阀门 $it" }, {}, {}, {}, false, {}, onRemove = { address = it })
                }
                if (address != 0L) SlaveRemovalConfirmation(ui, address, { "阀门 $it" }, {},
                    { error("Unsupported firmware must not allow deletion") })
            }
        }
        compose.onNodeWithContentDescription("删除从机 ${addressLabel(10)}").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("确认删除").assertIsNotEnabled()
        compose.onNodeWithText("当前主机固件不支持单台删除，请先更新主机固件。", substring = true).assertExists()
    }
}
