package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class TaskProgressTest {
    private val job = MasterJobKey(328, 102)
    private val task = MasterTask("a1", 1, 328, 0b1011, 100, 60_000, MasterOutcome.ACCEPTED, job)
    private val base = MasterUiState(connection = MasterConnectionState.Connected, task = task,
        taskAddresses = mapOf(1 to 2L, 2 to 3L, 4 to 5L), flowClockAt = 1_000,
        liveFlows = mapOf(
            2L to MasterMessage.Live(job, 2, 52, 312, 2_000, 358, 1_000),
            3L to MasterMessage.Live(job, 3, 41, 246, 2_000, 296, 1_000),
            5L to MasterMessage.Live(job, 5, 45, 268, 2_000, 322, 1_000)))

    @Test fun totalCombinesEachSelectedValveAndItsOwnTarget() {
        val progress = base.taskProgress()!!
        assertEquals(300L, progress.targetMl)
        assertEquals(138L, progress.reportedMl)
        assertEquals(46, progress.percent)
        assertEquals(listOf(1, 2, 4), progress.valves.map { it.slot })
        assertEquals(3, progress.measuredCount)
        val amounts = ValveAmounts.encode(task.mask, List(32) { when (it) { 1 -> "200"; 3 -> "400"; else -> "100" } })!!
        val individual = base.copy(task = task.copy(amounts = amounts)).taskProgress()!!
        assertEquals(700L, individual.targetMl)
        assertEquals(400L, individual.valves.last().targetMl)
    }

    @Test fun unmeasuredFinalInvalidatesTheOldLiveMeasurement() {
        val final = MasterMessage.Flow(job, 2, 0, "FAULT", terminal = true, measured = false, reason = "REBOOT")
        val progress = base.copy(flows = mapOf(2L to final)).taskProgress()!!
        assertNull(progress.valves.first().ml)
        assertNull(progress.valves.first().pulses)
        assertEquals(ValveProgressState.FAULT, progress.valves.first().state)
        assertEquals(86L, progress.reportedMl)
        assertEquals(2, progress.measuredCount)
        assertEquals(1, progress.endedCount)
    }

    @Test fun finalOverridesLiveAndOverTargetRetainsActualVolumeWithoutOverfillingRing() {
        val final = MasterMessage.Flow(job, 2, 108, "DONE", 640, terminal = true, reason = "TARGET")
        val valve = base.copy(flows = mapOf(2L to final)).taskProgress()!!.valves.first()
        assertEquals(108L, valve.ml)
        assertEquals(100, valve.percent)
        assertEquals(1f, valve.fraction)
        assertEquals(ValveProgressState.COMPLETE, valve.state)
        assertNull(valve.rateMlMin)
        val stopped = base.copy(flows = mapOf(2L to final.copy(reason = "MANUAL"))).taskProgress()!!.valves.first()
        assertEquals(ValveProgressState.STOPPED, stopped.state)
    }

    @Test fun originalTaskBindingsSurviveInventoryChangesAndPartialBindingsStayPartial() {
        val changed = base.copy(slaves = listOf(MasterSlave(1, 9, true, true)))
        assertEquals(52L, changed.taskProgress()!!.valves.first().ml)
        val partial = changed.copy(taskAddresses = mapOf(2 to 3L))
        assertNull(partial.taskProgress()!!.valves.first().ml)
    }

    @Test fun missingOrWrongJobDataNeverBecomesZeroPercent() {
        val progress = base.copy(liveFlows = mapOf(2L to base.liveFlows.getValue(2).copy(job = MasterJobKey(328, 103))))
            .taskProgress()!!
        assertNull(progress.reportedMl)
        assertNull(progress.percent)
        assertTrue(progress.valves.all { it.percent == null })
        assertEquals(0f, progress.fraction)
    }

    @Test fun staleDisconnectedAndStoppingSnapshotsNeverShowAnActiveRate() {
        listOf(
            base.copy(flowClockAt = 7_001) to ValveProgressState.STALE,
            base.copy(connection = MasterConnectionState.Disconnected) to ValveProgressState.DISCONNECTED,
            base.copy(stopPending = true) to ValveProgressState.STOPPING,
            base.copy(task = task.copy(outcome = MasterOutcome.FINISHED)) to ValveProgressState.RESULT_PENDING
        ).forEach { (state, expected) ->
            val valve = state.taskProgress()!!.valves.first()
            assertEquals(expected, valve.state)
            assertEquals(52L, valve.ml)
            assertNull(valve.rateMlMin)
        }
    }

    @Test fun savedBindingsAndFinalMeasurementsRemainAvailableWithoutInventory() {
        val final = MasterMessage.Flow(job, 2, 101, "DONE", 608, terminal = true, reason = "TARGET")
        val saved = MasterHistoryEntry("DC:DA:0C:6B:BB:C6", "Master", task, 1,
            flows = mapOf(2L to final), nodes = base.taskAddresses)
        val progress = MasterUiState(masterAddress = saved.address, task = task, history = listOf(saved))
            .taskProgress()!!
        assertEquals(101L, progress.reportedMl)
        assertEquals(ValveProgressState.COMPLETE, progress.valves.first().state)
        assertEquals(1, progress.measuredCount)
    }
}
