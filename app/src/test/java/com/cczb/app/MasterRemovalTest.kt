package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class MasterRemovalTest {
    private val node = MasterSlave(9, 10, false, true)
    private val mesh = MasterMeshState("IDLE", false, true, "NONE", 0, 0, 0, 0, supportsRemoval = true)
    private val ui = MasterUiState(connection = MasterConnectionState.Connected,
        hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE"), statusFresh = true,
        local = MasterMessage.Local(false, 0, "NONE", true, pumpReady = true), localFresh = true,
        mesh = mesh, meshFresh = true, slaves = listOf(node), inventoryFresh = true)

    @Test fun removalRequiresCurrentInventoryCapabilityAndIdleStateButAllowsOfflineAndPendingNodes() {
        assertTrue(ui.canRemoveSlave(node))
        assertTrue(ui.canOpenSlaveRemoval(node))
        val pending = MasterSlave(0, 12, false, false)
        assertTrue(ui.copy(slaves = listOf(pending)).canRemoveSlave(pending))
        assertFalse(ui.copy(inventoryFresh = false).canRemoveSlave(node))
        assertFalse(ui.copy(meshFresh = false).canRemoveSlave(node))
        assertFalse(ui.copy(mesh = mesh.copy(supportsRemoval = false)).canRemoveSlave(node))
        assertFalse(ui.copy(mesh = mesh.copy(busy = true)).canRemoveSlave(node))
        assertFalse(ui.copy(local = ui.local!!.copy(pumpCommand = true)).canRemoveSlave(node))
        assertFalse(ui.copy(local = ui.local!!.copy(testActive = true)).canRemoveSlave(node))
        assertFalse(ui.copy(commissionRequest = 11).canRemoveSlave(node))
        assertFalse(ui.copy(removal = MasterRemoval(7, 11, 10, 9)).canRemoveSlave(node))
        assertFalse(ui.canRemoveSlave(node.copy(address = 11)))
        assertFalse(ui.canRemoveSlave(node.copy(slot = 8)))
        assertFalse(ui.copy(connection = MasterConnectionState.Disconnected).canRemoveSlave(node))
    }

    @Test fun unavailableRemovalExplainsTheBlockingPrerequisiteWhileKeepingTheEntryActionable() {
        val legacy = ui.copy(mesh = mesh.copy(supportsRemoval = false))
        assertTrue(legacy.canOpenSlaveRemoval(node))
        assertFalse(legacy.canRemoveSlave(node))
        assertTrue(legacy.slaveRemovalBlockReason(node)!!.contains("更新主机固件"))

        val stale = ui.copy(inventoryFresh = false)
        assertTrue(stale.canOpenSlaveRemoval(node))
        assertFalse(stale.canRemoveSlave(node))
        assertTrue(stale.slaveRemovalBlockReason(node)!!.contains("清单已过期"))

        assertFalse(ui.copy(slaves = emptyList()).canOpenSlaveRemoval(node))
    }

    @Test fun deleteCommandRequiresBoundedIdentityUnicastAndExplicitConfirmation() {
        assertTrue(MasterProtocol.isAllowedCommand("mesh-remove 7 11 000a CONFIRM"))
        assertTrue(MasterProtocol.isAllowedCommand("mesh-remove 4294967295 4294967295 7fff CONFIRM"))
        listOf("mesh-remove 0 11 000a CONFIRM", "mesh-remove 7 0 000a CONFIRM",
            "mesh-remove 4294967296 11 000a CONFIRM", "mesh-remove 7 4294967296 000a CONFIRM",
            "mesh-remove 7 11 0000 CONFIRM", "mesh-remove 7 11 8000 CONFIRM",
            "mesh-remove 7 11 000a", "mesh-remove 7 11 000a confirm",
            "mesh-remove 7 11 000a CONFIRM extra").forEach { assertFalse(it, MasterProtocol.isAllowedCommand(it)) }
    }

    @Test fun oldFirmwareHasNoRemovalCapabilityAndMalformedFlagsAreRejected() {
        val line = "MESH epoch=7 req=11 op=11 scan=0 phase=REMOVE_DONE busy=0 capable=1 reason=LOCAL_ONLY found=0 target=000a"
        assertFalse((MasterProtocol.parse(line) as MasterMessage.Mesh).state.supportsRemoval)
        assertTrue((MasterProtocol.parse("$line remove=1") as MasterMessage.Mesh).state.supportsRemoval)
        assertNull(MasterProtocol.parse("$line remove=2"))
        assertNull(MasterProtocol.parse("$line remove=1 remove=0"))
        val rejection = MasterProtocol.parse("REMOVE epoch=7 req=11 addr=000a reply=BUSY") as MasterMessage.Removal
        assertEquals(10L, rejection.address)
        assertEquals("BUSY", rejection.reply)
        assertNull(MasterProtocol.parse("REMOVE epoch=7 req=11 addr=8000 reply=BUSY"))
        assertNull(MasterProtocol.parse("REMOVE epoch=7 req=0 addr=000a reply=BUSY"))
    }

    @Test fun completionMustMatchBootOperationAndExactAddressIncludingSlotZero() {
        val removal = MasterRemoval(7, 11, 10, 0)
        val done = MasterMessage.Mesh(7, 19, mesh.copy(phase = "REMOVE_DONE", reason = "REMOTE_RESET", operation = 11, target = 10))
        assertEquals(removal, removal.receive(done.copy(epoch = 8)))
        assertEquals(removal, removal.receive(done.copy(state = done.state.copy(operation = 12))))
        assertEquals(removal, removal.receive(done.copy(state = done.state.copy(target = 12))))
        assertEquals(removal, removal.receive(done.copy(state = done.state.copy(phase = "READY"))))
        assertTrue(removal.receive(done).succeeded)
        assertFalse(removal.receive(done).pending)
        val completed = removal.receive(done)
        assertEquals(completed, completed.receive(done.copy(state = done.state.copy(phase = "FAILED", reason = "REMOVE_STORAGE"))))
        assertEquals(completed, completed.receive(done.copy(state = done.state.copy(phase = "REMOVING"))))
        val offline = removal.receive(done.copy(state = done.state.copy(reason = "LOCAL_ONLY")))
        assertTrue(offline.message.contains("未确认从机退网"))
        val failed = removal.receive(done.copy(state = done.state.copy(phase = "FAILED", reason = "REMOVE_STORAGE")))
        assertFalse(failed.succeeded); assertFalse(failed.pending)
    }

    @Test fun deletionCompletionAndConfigurationFailureAllowAnotherScanWhenIdle() {
        listOf("REMOVE_DONE", "READY", "FAILED").forEach {
            assertTrue(ui.copy(mesh = mesh.copy(phase = it)).canScanMesh)
            assertFalse(ui.copy(mesh = mesh.copy(phase = it, busy = true)).canScanMesh)
        }
    }

    @Test fun unconfirmedResetKeepsTheDeviceAndAllowsAnExplicitRetry() {
        val removal = MasterRemoval(7, 11, 10, 9)
        val update = MasterMessage.Mesh(7, 19, mesh.copy(phase = "REMOVE_PENDING",
            reason = "REMOTE_UNCONFIRMED", operation = 11, target = 10))
        val pending = removal.receive(update)
        assertFalse(pending.succeeded)
        assertFalse(pending.pending)
        assertTrue(pending.message.contains("已保留"))
        assertTrue(ui.copy(mesh = update.state, removal = pending).canRemoveSlave(node))
        val waiting = ui.copy(mesh = update.state, removal = pending)
        assertFalse(waiting.canScanMesh)
        assertFalse(waiting.canResetMesh)
        assertFalse(waiting.canStart)
        assertFalse(waiting.canStartTest)
        assertFalse(waiting.canSetSpeed)
        val other = node.copy(slot = 8, address = 11)
        assertFalse(waiting.copy(slaves = listOf(node, other)).canRemoveSlave(other))
        assertEquals(removal, removal.receive(update.copy(state = update.state.copy(target = 12))))
    }

    @Test fun removingOrReusingATaskSlotDoesNotReopenCompletedResultReconciliation() {
        val job = MasterJobKey(7, 1)
        val task = MasterTask("a1", 42, 7, 0x100, 500, 60_000, MasterOutcome.FINISHED, job)
        val finished = ui.copy(task = task, taskAddresses = taskNodeAddresses(task.mask, ui.slaves.orEmpty()),
            report = MasterMessage.Report("a1", 42, "OK", job, 6, task.mask, task.mask, 0, 0),
            volume = MasterMessage.Volume(job, 500, task.mask),
            flows = mapOf(10L to MasterMessage.Flow(job, 10, 500, "DONE", 2940, 8, true, true)))
        assertTrue(finished.taskResultsComplete)
        val removed = finished.copy(slaves = emptyList())
        assertTrue(removed.taskResultsComplete)
        assertFalse(removed.taskNeedsReconciliation)
        val reused = removed.copy(slaves = listOf(node.copy(address = 22)))
        assertTrue(reused.taskResultsComplete)
        assertEquals(setOf(10L), reused.expectedFlowAddresses)
        assertFalse(reused.copy(flows = emptyMap()).taskResultsComplete)
        assertFalse(finished.copy(taskAddresses = emptyMap(), slaves = emptyList()).taskResultsComplete)
    }
}
