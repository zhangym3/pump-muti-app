package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class MasterUiStateTest {
    private val hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE")
    private val ready = MasterUiState(connection = MasterConnectionState.Connected, hello = hello, statusFresh = true,
        slaves = listOf(MasterSlave(1, 2, true, true)), inventoryFresh = true)
    private val task = MasterTask("a1", 1, 7, 1, 500, 60_000)

    @Test fun bluetoothConnectionAloneDoesNotAuthorizeAStart() {
        assertFalse(MasterUiState().canStart)
        assertFalse(ready.copy(hello = null).canStart)
        assertFalse(ready.copy(connection = MasterConnectionState.Disconnected).canStart)
        assertTrue(ready.canStart)
    }

    @Test fun staleStatusOrUnhealthyStoragePreventsNewRequests() {
        assertFalse(ready.copy(statusFresh = false).canStart)
        assertFalse(ready.copy(storageHealthy = false).canStart)
        assertFalse(ready.copy(task = task).canStart)
        assertTrue(ready.copy(task = task.copy(outcome = MasterOutcome.REJECTED)).canStart)
    }

    @Test fun slaveWrongMasterAndUncommissionedOrLockedHardwareCannotStart() {
        listOf(hello.copy(role = "VALVE_SLAVE"), hello.copy(address = 2), hello.copy(mesh = false),
            hello.copy(commissioned = false), hello.copy(fault = "BOOT_LOCK"), hello.copy(epoch = 0)).forEach {
            assertFalse(ready.copy(hello = it).canStart)
        }
    }

    @Test fun stopRequiresAKnownUnfinishedJobButRemainsAvailableWithStaleStatusOrStorageFailure() {
        assertFalse(ready.copy(task = task).canStop)
        val accepted = task.copy(outcome = MasterOutcome.ACCEPTED, job = MasterJobKey(7, 1))
        assertTrue(ready.copy(task = accepted, statusFresh = false, storageHealthy = false).canStop)
        assertFalse(ready.copy(task = accepted.copy(outcome = MasterOutcome.FINISHED)).canStop)
        assertFalse(ready.copy(task = accepted, connection = MasterConnectionState.Disconnected).canStop)
    }

    @Test fun originalRetryRequiresFreshReadyMasterAndUnchangedBootEpoch() {
        val pending = ready.copy(task = task)
        assertTrue(pending.canRetry)
        assertFalse(pending.copy(statusFresh = false).canRetry)
        assertFalse(pending.copy(hello = hello.copy(epoch = 8)).canRetry)
        assertFalse(pending.copy(task = task.copy(outcome = MasterOutcome.REJECTED)).canRetry)
        assertFalse(pending.copy(storageHealthy = false).canRetry)
    }

    @Test fun outputIsNotShownAsCurrentAfterDisconnectOrExpiryAndKnownLocalFaultBlocksStart() {
        val snapshot = ready.copy(local = MasterMessage.Local(true, 2, "NONE"), localFresh = true)
        assertEquals("水泵输出已开启", snapshot.pumpOutputLabel)
        assertEquals("水泵输出待同步", snapshot.copy(localFresh = false).pumpOutputLabel)
        assertEquals("水泵输出待同步", snapshot.copy(connection = MasterConnectionState.Disconnected).pumpOutputLabel)
        assertFalse(snapshot.copy(local = MasterMessage.Local(false, 4, "BOOT_LOCK")).canStart)
    }

    @Test fun unknownTerminalResultBlocksNewTaskIncludingRestoredHistory() {
        val finished = task.copy(outcome = MasterOutcome.FINISHED, job = MasterJobKey(7, 1))
        val report = MasterMessage.Report(task.client, task.seq, "OK", finished.job, 6, 1, 1, 0, 1)
        assertFalse(ready.copy(task = finished, report = report).canStart)
        val saved = MasterHistoryEntry("AA:BB:CC:DD:EE:01", "主机", finished, 1, report)
        assertFalse(ready.copy(task = finished, history = listOf(saved)).canStart)
        assertFalse(ready.copy(task = finished, report = report.copy(unknown = 0), history = listOf(saved)).canStart)
        assertFalse(ready.copy(historyHealthy = false).canStart)
        assertTrue(ready.copy(task = finished.copy(outcome = MasterOutcome.ACCEPTED), historyHealthy = false).canStop)
    }

    @Test fun finishedTaskNeedsVolumeAndEverySelectedNodeTerminalMeasurement() {
        val finished = task.copy(outcome = MasterOutcome.FINISHED, job = MasterJobKey(7, 1))
        val report = MasterMessage.Report(task.client, task.seq, "OK", finished.job, 6, 1, 1, 0, 0)
        val volume = MasterMessage.Volume(finished.job!!, 500, 1)
        val flow = MasterMessage.Flow(finished.job, 2, 500, "DONE", 500, 4, true, true, 1000)
        val reconciled = ready.copy(task = finished, report = report, volume = volume, flows = mapOf(2L to flow))
        assertTrue(reconciled.taskResultsComplete)
        assertTrue(reconciled.canStart)
        assertFalse(reconciled.copy(volume = null).canStart)
        assertFalse(reconciled.copy(flows = emptyMap()).canStart)
        assertFalse(reconciled.copy(inventoryFresh = false).canStart)
        assertFalse(reconciled.copy(flows = mapOf(2L to flow.copy(measured = false))).canStart)
    }

    @Test fun reconciliationDetailsIdentifyMissingNodeFlow() {
        val finished = task.copy(outcome = MasterOutcome.FINISHED, job = MasterJobKey(7, 1))
        val report = MasterMessage.Report(task.client, task.seq, "OK", finished.job, 6, 1, 1, 0, 0)
        val volume = MasterMessage.Volume(finished.job!!, 500, 1)
        val state = ready.copy(task = finished, report = report, volume = volume)

        assertTrue(state.taskNeedsReconciliation)
        assertFalse(state.canStart)
        assertEquals(listOf("阀门 1 缺少该任务的终态计量记录（FLOW）。"), state.taskReconciliationDetails)
    }
}
