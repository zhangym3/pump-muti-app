package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class MasterMeshTest {
    @Test fun slaveCandidateUsesTheFirmwareIdentityLabel() {
        assertEquals("PUMP_SLAVE_02CE", slaveDisplayName("dddd504d1463939202ce020000000000"))
        assertEquals("从机 BEEF", slaveDisplayName("0000000000000000000000000000beef"))
    }

    private fun begin(count: Int, epoch: Long = 7, req: Long = 11) = MasterMessage.InventoryBegin(epoch, req, count)
    private fun end(count: Int, epoch: Long = 7, req: Long = 11) = MasterMessage.InventoryEnd(epoch, req, count)
    private fun node(slot: Int = 1, addr: Long = 2, epoch: Long = 7, req: Long = 11) =
        MasterMessage.InventoryNode(epoch, req, MasterSlave(slot, addr, true, true))
    @Test fun inventoryIsCommittedOnlyWhenCompleteAndUsesRealSparseSlots() {
        val collector = MasterInventoryCollector(7, 11)
        assertNull(collector.accept(begin(2)))
        assertNull(collector.accept(node(32, 33)))
        assertNull(collector.accept(node()))
        assertEquals(listOf(1, 32), collector.accept(end(2))!!.map { it.slot })
    }
    @Test fun explicitEmptySnapshotIsDifferentFromMissingOrIncompleteData() {
        assertNull(MasterInventoryCollector(7, 11).accept(end(0)))
        val empty = MasterInventoryCollector(7, 11)
        empty.accept(begin(0)); assertEquals(emptyList<MasterSlave>(), empty.accept(end(0)))
        val missing = MasterInventoryCollector(7, 11)
        missing.accept(begin(2)); missing.accept(node()); assertNull(missing.accept(end(2)))
    }
    @Test fun duplicateAddressesSlotsOrBoundariesAndMismatchedCountInvalidateSnapshot() {
        listOf(listOf(begin(2), node(), node(2, 2), end(2)),
            listOf(begin(2), node(), node(1, 3), end(2)),
            listOf(begin(1), begin(1), node(), end(1)),
            listOf(node(), begin(1), end(1)),
            listOf(begin(1), node(), end(0))).forEach { stream ->
            val collector = MasterInventoryCollector(7, 11)
            stream.forEach { assertNull(collector.accept(it)) }
        }
    }
    @Test fun repliesFromAnotherRequestOrBootAreIgnored() {
        val collector = MasterInventoryCollector(7, 11)
        collector.accept(begin(1)); collector.accept(node(epoch = 6)); collector.accept(node(req = 12))
        assertNull(collector.accept(end(1)))
        val other = MasterInventoryCollector(7, 11)
        assertNull(other.accept(begin(0, req = 12))); assertNull(other.accept(end(0, req = 12)))
    }
    @Test fun pendingConfigurationNodesCanShareSlotZeroButCannotBeSelected() {
        val collector = MasterInventoryCollector(7, 11)
        collector.accept(begin(2)); collector.accept(node(0, 2)); collector.accept(node(0, 3))
        assertEquals(2, collector.accept(end(2))!!.size)
        assertFalse(MasterSlave(0, 2, true, true).selectable)
    }
    @Test fun meshAndInventoryProtocolValidateBoundsAndCommands() {
        assertTrue(MasterProtocol.parse("INV_BEGIN epoch=7 req=11 count=32") is MasterMessage.InventoryBegin)
        assertNull(MasterProtocol.parse("INV_BEGIN epoch=7 req=11 count=33"))
        assertNull(MasterProtocol.parse("INV_NODE epoch=7 req=11 slot=33 addr=0002 online=1 commissioned=1"))
        assertNull(MasterProtocol.parse("INV_NODE epoch=7 req=11 slot=1 addr=8000 online=1 commissioned=1"))
        assertNull(MasterProtocol.parse("INV_BEGIN epoch=7 req=11 req=12 count=0"))
        val mesh = MasterProtocol.parse("MESH epoch=7 req=11 op=10 scan=10 phase=SCANNING busy=1 capable=1 reason=NONE found=1 target=0000") as MasterMessage.Mesh
        assertTrue(mesh.state.busy); assertEquals(10L, mesh.state.scan)
        val uuid = "dddd504d3076f5ba02ce020000000000"
        assertTrue(MasterProtocol.parse("CAND epoch=7 scan=10 uuid=$uuid") is MasterMessage.Candidate)
        assertNull(MasterProtocol.parse("CAND epoch=7 scan=10 uuid=bad"))
        listOf("inventory 7 11", "mesh-state 7 12", "mesh-scan 7 13", "mesh-join 7 14 $uuid", "mesh-retry 7 15 0002").forEach {
            assertTrue(it, MasterProtocol.isAllowedCommand(it))
        }
        listOf("mesh-scan 0 11", "inventory 7 0", "mesh-join 7 14 bad", "mesh-retry 7 15 8000", "mesh-scan 7 11 extra").forEach {
            assertFalse(it, MasterProtocol.isAllowedCommand(it))
        }
        assertFalse(MasterProtocol.isAllowedCommand("mesh-migrate CONFIRM"))
        assertTrue(MasterProtocol.isAllowedCommand("mesh-reset CONFIRM"))
        listOf("mesh-reset", "mesh-reset confirm", "mesh-reset CONFIRM extra").forEach {
            assertFalse(MasterProtocol.isAllowedCommand(it))
        }
    }
    @Test fun onlyFreshOnlineCommissionedInventoryIsSelectable() {
        val ui = MasterUiState(slaves = listOf(MasterSlave(1, 2, true, true), MasterSlave(9, 10, false, true),
            MasterSlave(17, 18, true, false), MasterSlave(32, 33, true, true)), inventoryFresh = true)
        assertEquals(0x8000_0001L, ui.selectableMask)
        assertEquals(0L, ui.copy(inventoryFresh = false).selectableMask)
        assertEquals(0L, MasterUiState().selectableMask)
    }

    @Test fun commissionReplyAndCommandAreBoundedAndValidated() {
        val parsed = MasterProtocol.parse(
            "COMMISSION epoch=7 req=19 slot=32 addr=7fff reply=OK commissioned=1"
        ) as MasterMessage.Commission
        assertEquals(19L, parsed.request)
        assertEquals(32, parsed.slot)
        assertTrue(parsed.commissioned)
        assertNull(MasterProtocol.parse("COMMISSION epoch=7 req=19 slot=33 addr=0002 reply=OK commissioned=1"))
        assertNull(MasterProtocol.parse("COMMISSION epoch=7 req=19 slot=1 addr=0002 reply=OK commissioned=2"))
        assertNull(MasterProtocol.parse("COMMISSION epoch=7 req=19 slot=1 addr=8000 reply=OK commissioned=1"))
        assertTrue(MasterProtocol.isAllowedCommand("commission 7 19 32 CONFIRM"))
        listOf("commission 0 19 1 CONFIRM", "commission 7 0 1 CONFIRM", "commission 7 19 0 CONFIRM",
            "commission 7 19 33 CONFIRM", "commission 7 19 1 confirm", "commission 7 19 1 CONFIRM extra").forEach {
            assertFalse(it, MasterProtocol.isAllowedCommand(it))
        }
    }

    @Test fun commissioningRequiresFreshIdleOnlineUncommissionedNode() {
        val hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE")
        val base = MasterUiState(connection = MasterConnectionState.Connected, hello = hello, statusFresh = true,
            local = MasterMessage.Local(false, 0, "NONE", true, pumpReady = true), localFresh = true,
            slaves = listOf(MasterSlave(1, 2, true, false)), inventoryFresh = true,
            mesh = MasterMeshState("IDLE", false, true, "NONE", 0, 0, 0, 0), meshFresh = true)
        val node = base.slaves!!.single()
        assertTrue(base.canCommission(node))
        assertFalse(base.copy(inventoryFresh = false).canCommission(node))
        assertFalse(base.copy(mesh = base.mesh!!.copy(busy = true)).canCommission(node))
        assertFalse(base.copy(local = base.local!!.copy(pumpCommand = true)).canCommission(node))
        assertFalse(base.copy(historyHealthy = false).canCommission(node))
        val offline = node.copy(online = false)
        val configured = node.copy(commissioned = true)
        assertFalse(base.canCommission(offline))
        assertFalse(base.canCommission(configured))
    }

    @Test fun busyMeshBlocksNewControlAndKeepsStopsAvailable() {
        val hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE")
        val task = MasterTask("a1", 1, 7, 1, 500, 60000, MasterOutcome.ACCEPTED, MasterJobKey(7, 1))
        val ui = MasterUiState(connection = MasterConnectionState.Connected, hello = hello, statusFresh = true,
            local = MasterMessage.Local(false, 0, "NONE", true, pumpReady = true), localFresh = true,
            slaves = listOf(MasterSlave(1, 2, true, true)), inventoryFresh = true,
            mesh = MasterMeshState("JOINING", true, true, "NONE", 1, 1, 1, 0), meshFresh = true)
        assertFalse(ui.canStart); assertFalse(ui.canStartTest); assertFalse(ui.canSetSpeed); assertFalse(ui.canManageMesh)
        assertTrue(ui.copy(task = task).canStop)
        assertTrue(ui.copy(pumpTest = MasterPumpTest("a1", 2, 7, 3000)).canStopTest)
    }

    @Test fun legacyMeshIsVisibleAsUnavailableWithoutMigrationPrompt() {
        val hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE")
        val ui = MasterUiState(connection = MasterConnectionState.Connected, hello = hello, statusFresh = true,
            local = MasterMessage.Local(false, 0, "NONE", true, pumpReady = true), localFresh = true,
            mesh = MasterMeshState("LEGACY_NETWORK", false, false, "MIGRATION_REQUIRED", 0, 0, 0, 0), meshFresh = true)
        assertTrue(ui.canViewMesh)
        assertFalse(ui.mesh!!.label.contains("迁移"))
        assertTrue(ui.canRequestMeshScan)
        assertFalse(ui.canManageMesh)
        assertFalse(ui.canScanMesh)
        assertFalse(ui.canJoinMesh)
        assertFalse(ui.copy(meshPending = true).canRequestMeshScan)
    }

    @Test fun scanAndJoinPermissionsFollowMeshPhase() {
        val hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE")
        val base = MasterUiState(connection = MasterConnectionState.Connected, hello = hello, statusFresh = true,
            local = MasterMessage.Local(false, 0, "NONE", true, pumpReady = true), localFresh = true,
            mesh = MasterMeshState("IDLE", false, true, "NONE", 0, 0, 0, 0), meshFresh = true)
        assertTrue(base.canScanMesh)
        assertFalse(base.canJoinMesh)
        val uuid = "dddd504d3076f5ba02ce020000000000"
        val done = base.copy(mesh = base.mesh!!.copy(phase = "SCAN_DONE", found = 1), candidates = listOf(uuid))
        assertTrue(done.canScanMesh)
        assertTrue(done.canJoinMesh)
    }

    @Test fun scanActionIsNotBlockedByAnUnrelatedPendingPumpTask() {
        val hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE")
        val base = MasterUiState(connection = MasterConnectionState.Connected, hello = hello, statusFresh = true,
            local = MasterMessage.Local(false, 0, "NONE", true, pumpReady = true), localFresh = true,
            meshFresh = true, mesh = MasterMeshState("IDLE", false, true, "NONE", 0, 0, 0, 0),
            task = MasterTask("a1", 1, 7, 1, 500, 60_000))
        assertTrue(base.canScanMesh)
        // A stale local snapshot can recover by tapping the button; it must
        // not look like an unresponsive control while the refresh is pending.
        assertTrue(base.copy(localFresh = false).canRequestMeshScan)
    }

    @Test fun connectedMasterCanEnterScanFlowBeforeInitialSnapshotsArrive() {
        val connected = MasterUiState(connection = MasterConnectionState.Connected)
        assertTrue(connected.canRequestMeshScan)
        assertFalse(connected.copy(hello = MasterMessage.Hello("VALVE_SLAVE", 2, 1, 7, true, true, "NONE")).canRequestMeshScan)
        assertFalse(connected.copy(mesh = MasterMeshState("SCANNING", true, true, "NONE", 1, 1, 0, 0)).canRequestMeshScan)
    }

    @Test fun resetPermissionRequiresIdleFreshMasterState() {
        val hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE")
        val ready = MasterUiState(connection = MasterConnectionState.Connected, hello = hello, statusFresh = true,
            local = MasterMessage.Local(false, 0, "NONE", true, pumpReady = true), localFresh = true,
            mesh = MasterMeshState("IDLE", false, true, "NONE", 0, 0, 0, 0), meshFresh = true)
        assertTrue(ready.canResetMesh)
        assertFalse(ready.copy(mesh = ready.mesh!!.copy(phase = "RESETTING", busy = true)).canResetMesh)
        assertFalse(ready.copy(local = ready.local!!.copy(pumpCommand = true)).canResetMesh)
    }
}
