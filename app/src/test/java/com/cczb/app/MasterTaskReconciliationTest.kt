package com.cczb.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class MasterTaskReconciliationTest {
    private val address = "DC:DA:0C:6B:BB:C6"
    private val job = MasterJobKey(15, 6)
    private val task = MasterTask("d82ef12190ff6359", 1, 15, 1, 500, 60_000, MasterOutcome.FINISHED, job)
    private val report = MasterMessage.Report(task.client, task.seq, "OK", job, 6, 1, 1, 0, 0, "NONE")
    private val volume = MasterMessage.Volume(job, 502, 1)
    private val flow = MasterMessage.Flow(job, 3, 502, "DONE", 2957, 229, true, true, 1234, "TARGET")
    private val entry = MasterHistoryEntry(address, "PUMP_MASTER_BBC6", task, 1792630468007,
        report, volume, mapOf(3L to flow), mapOf(1 to 3L))
    private val ready = MasterUiState(masterAddress = address, task = task,
        connection = MasterConnectionState.Connected, statusFresh = true,
        hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 15, true, true, "NONE"),
        inventoryFresh = true, slaves = listOf(MasterSlave(1, 3, true, true)),
        localFresh = true, local = MasterMessage.Local(false, 0, "NONE"))

    private fun restore(saved: MasterHistoryEntry = entry) =
        ready.copy(history = listOf(saved)).restoreTaskObservation()
    private fun unknown() = report.copy(reply = "UNKNOWN", job = null, phase = 0, mask = 0, done = 0)

    @Test fun fieldTaskSurvivesBothFilesAndRestoresCompleteResultsWithoutChangingIdentity() {
        val historyBytes = ByteArrayOutputStream().also { MasterHistory.write(listOf(entry), it) }.toByteArray()
        val ledger = MasterLedger(task.client, 1, mapOf(address to task))
        val ledgerBytes = ByteArrayOutputStream().also { ledger.write(it) }.toByteArray()
        val history = MasterHistory.read(ByteArrayInputStream(historyBytes))
        val restoredTask = MasterLedger.read(ByteArrayInputStream(ledgerBytes)).tasks.getValue(address)
        val restored = ready.copy(task = restoredTask, history = history).restoreTaskObservation()
        assertEquals(task, restored.task)
        assertEquals(report, restored.report)
        assertEquals(502L, restored.volume!!.ml)
        assertEquals(2957L, restored.flows.getValue(3).pulses)
        assertEquals("TARGET", restored.flows.getValue(3).reason)
        assertEquals(mapOf(1 to 3L), restored.taskAddresses)
        assertTrue(restored.taskResultsComplete)
        assertFalse(restored.taskNeedsReconciliation)
        assertNull(restored.startBlockReason)
        assertTrue(restored.canStart)
        assertEquals(1, restored.history.size)
    }

    @Test fun restorationClearsLiveProgressAndArrivalTimesEvenForInMemoryHistory() {
        val restored = ready.copy(history = listOf(entry), flowClockAt = 1234,
            liveFlows = mapOf(3L to MasterMessage.Live(job, 3, 100, 600, 1000, 600, 1234)))
            .restoreTaskObservation()
        assertEquals(0L, restored.flowClockAt)
        assertEquals(0L, restored.flows.getValue(3).receivedAt)
        assertFalse(restored.flows.getValue(3).isFresh(1234))
        assertTrue(restored.liveFlows.isEmpty())
    }

    @Test fun sameSequenceWithDifferentHostOrRequestCannotUnlock() {
        val candidates = listOf(
            entry.copy(address = "DC:DA:0C:6B:BB:C7"),
            entry.copy(task = task.copy(client = "b2")),
            entry.copy(task = task.copy(seq = 2)),
            entry.copy(task = task.copy(epoch = 16)),
            entry.copy(task = task.copy(job = MasterJobKey(15, 7))),
            entry.copy(task = task.copy(mask = 3)),
            entry.copy(task = task.copy(ml = 550)),
            entry.copy(task = task.copy(maxMs = 61_000)),
            entry.copy(task = task.copy(amounts = "1:500"))
        )
        candidates.forEach { candidate ->
            val restored = restore(candidate)
            assertNull(candidate.toString(), restored.savedTaskObservation)
            assertNull(restored.report)
            assertTrue(restored.taskNeedsReconciliation)
            assertFalse(restored.canStart)
        }
        val wrongEpochTask = task.copy(job = MasterJobKey(16, 6))
        val invalid = ready.copy(task = wrongEpochTask, history = listOf(entry.copy(task = wrongEpochTask)),
            report = report.copy(job = wrongEpochTask.job), volume = volume.copy(job = wrongEpochTask.job!!),
            flows = mapOf(3L to flow.copy(job = wrongEpochTask.job)), taskAddresses = entry.nodes)
        assertNull(invalid.savedTaskObservation)
        assertFalse(invalid.taskResultsComplete)
    }

    @Test fun missingEvidenceKeepsStartBlockedAndIdentifiesTheGap() {
        listOf(
            entry.copy(report = null) to "主机报告（R）",
            entry.copy(volume = null) to "计量汇总（V）",
            entry.copy(flows = emptyMap()) to "终态计量记录（FLOW）",
            entry.copy(nodes = emptyMap()) to "阀门地址映射"
        ).forEach { (saved, reason) ->
            val restored = restore(saved)
            assertFalse(reason, restored.taskResultsComplete)
            assertTrue(reason, restored.taskNeedsReconciliation)
            assertFalse(reason, restored.canStart)
            assertTrue(restored.taskReconciliationDetails.toString(),
                restored.taskReconciliationDetails.any { reason in it })
        }
    }

    @Test fun mismatchedOrUnfinishedEvidenceCannotUnlock() {
        listOf(
            entry.copy(report = report.copy(client = "b2")),
            entry.copy(report = report.copy(seq = 2)),
            entry.copy(report = report.copy(job = MasterJobKey(15, 7))),
            entry.copy(report = report.copy(mask = 3)),
            entry.copy(report = report.copy(reply = "UNKNOWN")),
            entry.copy(report = report.copy(phase = 3)),
            entry.copy(report = report.copy(unknown = 1)),
            entry.copy(report = report.copy(done = 0)),
            entry.copy(volume = volume.copy(job = MasterJobKey(15, 7))),
            entry.copy(volume = volume.copy(measured = 0)),
            entry.copy(volume = volume.copy(measured = 3)),
            entry.copy(flows = mapOf(3L to flow.copy(job = MasterJobKey(15, 7)))),
            entry.copy(flows = mapOf(3L to flow.copy(address = 4))),
            entry.copy(flows = mapOf(3L to flow.copy(terminal = false))),
            entry.copy(flows = mapOf(3L to flow.copy(measured = false))),
            entry.copy(flows = mapOf(3L to flow, 4L to flow.copy(address = 4))),
            entry.copy(nodes = mapOf(1 to 0)),
            entry.copy(nodes = mapOf(1 to 0x8000)),
            entry.copy(nodes = mapOf(2 to 3))
        ).forEach { candidate ->
            val restored = restore(candidate)
            assertFalse(candidate.toString(), restored.taskResultsComplete)
            assertTrue(candidate.toString(), restored.taskNeedsReconciliation)
            assertFalse(candidate.toString(), restored.canStart)
        }
    }

    @Test fun completeHistoryAndIncompleteHistoryRetainTheirGatesAfterUnknownQuery() {
        val complete = restore()
        val queried = complete.receiveTaskReport(unknown())!!
        assertEquals(complete.report, queried.report)
        assertEquals(complete.volume, queried.volume)
        assertEquals(complete.flows, queried.flows)
        assertEquals(complete.history, queried.history)
        assertEquals(complete.task, queried.task)
        assertTrue(queried.taskResultsComplete)
        assertTrue(queried.canStart)
        assertTrue(queried.message.contains("保留本机已完整对账"))

        val incomplete = restore(entry.copy(flows = emptyMap()))
        val missing = incomplete.receiveTaskReport(unknown())!!
        assertTrue(missing.taskNeedsReconciliation)
        assertFalse(missing.canStart)
        assertEquals(incomplete.report, missing.report)
        assertTrue(missing.flows.isEmpty())
        assertTrue(missing.message.contains("尚未完整确认"))
    }

    @Test fun noHistoryAndPendingOrAcceptedLedgerNeverBecomeFinishedByRestorationOrUnknown() {
        val absent = ready.restoreTaskObservation()
        assertTrue(absent.taskNeedsReconciliation)
        assertNull(absent.report)
        assertFalse(absent.receiveTaskReport(unknown())!!.canStart)
        listOf(MasterOutcome.PENDING, MasterOutcome.ACCEPTED).forEach { outcome ->
            val restored = ready.copy(task = task.copy(outcome = outcome), history = listOf(entry))
                .restoreTaskObservation()
            assertEquals(outcome, restored.task!!.outcome)
            assertTrue(restored.taskNeedsReconciliation)
            assertFalse(restored.canStart)
            val queried = restored.receiveTaskReport(unknown())!!
            assertEquals(outcome, queried.task!!.outcome)
            assertFalse(queried.taskResultsComplete)
        }
        val pending = ready.copy(task = task.copy(outcome = MasterOutcome.PENDING, job = null))
            .restoreTaskObservation()
        assertTrue(pending.receiveTaskReport(unknown(), directStartReply = true)!!.task!!.unresolved)
    }

    @Test fun verifiedStopReplyUsesOriginalSequenceInMemoryAndSavedHistory() {
        val stopping = restore().copy(task = task.copy(outcome = MasterOutcome.ACCEPTED),
            report = report.copy(phase = 3, done = 0), stopPending = true)
        val stop = report.copy(seq = 12, phase = 7, failed = 1, reason = "STOP")
        assertNull(stopping.receiveTaskReport(stop))
        val finished = stopping.receiveTaskReport(stop, stopSequence = 12)!!
        assertEquals(task.seq, finished.report!!.seq)
        assertEquals(MasterOutcome.FINISHED, finished.task!!.outcome)
        assertFalse(finished.stopPending)
        assertTrue(finished.taskResultsComplete)
        assertTrue(finished.canStart)
        val bytes = ByteArrayOutputStream().also {
            MasterHistory.write(listOf(entry.copy(task = finished.task, report = finished.report)), it)
        }.toByteArray()
        assertEquals(task.seq, MasterHistory.read(ByteArrayInputStream(bytes)).single().report!!.seq)
        assertNull(stopping.receiveTaskReport(stop.copy(client = "b2"), 12))
        assertNull(stopping.receiveTaskReport(stop.copy(job = MasterJobKey(15, 7)), 12))
        assertNull(stopping.receiveTaskReport(stop.copy(mask = 3), 12))
    }

    @Test fun freshMatchingTerminalReportCompletesOutstandingTaskButDelayedActiveReportCannotRegress() {
        val outstanding = restore().copy(task = task.copy(outcome = MasterOutcome.ACCEPTED), report = null)
        val completed = outstanding.receiveTaskReport(report)!!
        assertTrue(completed.taskResultsComplete)
        assertNull(completed.receiveTaskReport(report.copy(phase = 3)))
        assertNull(completed.receiveTaskReport(report.copy(client = "b2")))
        assertNull(completed.receiveTaskReport(report.copy(seq = 2)))
        assertNull(completed.receiveTaskReport(report.copy(job = MasterJobKey(16, 6))))
        assertNull(completed.receiveTaskReport(report.copy(mask = 3)))
    }

    @Test fun restoringCompleteTaskDoesNotBypassOtherSafetyGatesOrRequireCurrentEpochToMatchOldJob() {
        val complete = restore()
        listOf(complete.copy(statusFresh = false), complete.copy(inventoryFresh = false),
            complete.copy(storageHealthy = false), complete.copy(historyHealthy = false),
            complete.copy(hello = complete.hello!!.copy(fault = "BOOT_LOCK")),
            complete.copy(connection = MasterConnectionState.Disconnected)).forEach {
            assertTrue(it.taskResultsComplete)
            assertFalse(it.canStart)
        }
        assertTrue(complete.copy(hello = complete.hello!!.copy(epoch = 23)).canStart)
    }

    @Test fun everySelectedSlotNeedsItsOwnFrozenAddressAndMeasurement() {
        val multiple = task.copy(mask = 0x80000011L)
        val bindings = mapOf(1 to 3L, 5 to 4L, 32 to 5L)
        val complete = ready.copy(task = multiple, taskAddresses = bindings,
            report = report.copy(mask = multiple.mask, done = multiple.mask),
            volume = volume.copy(measured = multiple.mask),
            flows = bindings.values.associateWith { flow.copy(address = it) })
        assertTrue(complete.taskResultsComplete)
        listOf(
            complete.copy(taskAddresses = bindings - 5),
            complete.copy(taskAddresses = bindings + (5 to 3L)),
            complete.copy(flows = complete.flows - 4L),
            complete.copy(volume = volume.copy(measured = 1)),
            complete.copy(report = report.copy(mask = multiple.mask, done = 1))
        ).forEach { assertFalse(it.taskResultsComplete) }
        // Reusing a slot for a new device cannot change what the old task must reconcile.
        assertTrue(complete.copy(slaves = listOf(MasterSlave(1, 9, true, true))).taskResultsComplete)
    }
}
