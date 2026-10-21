package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class MasterLiveTest {
    private val job = MasterJobKey(7, 1)
    private val baseTask = MasterTask("a1", 1, 7, 1, 500, 60_000,
        MasterOutcome.ACCEPTED, job)
    private val base = MasterUiState(
        connection = MasterConnectionState.Connected,
        hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE"),
        task = baseTask,
        inventoryFresh = true,
        slaves = listOf(MasterSlave(1, 2, true, true))
    )

    private fun live(pulses: Long = 120, ml: Long = 20, runtime: Long = 2_000,
                     epoch: Long = 7, id: Long = 1, address: Long = 2) =
        MasterMessage.Live(MasterJobKey(epoch, id), address, ml, pulses, runtime, 600)

    @Test fun liveLineRequiresAllBoundedFieldsAndRejectsDuplicates() {
        val parsed = MasterProtocol.parse(
            "LIVE addr=0002 job=7:1 pulses=120 ml=20 runtime_ms=2000 rate_ml_min=600"
        ) as MasterMessage.Live
        assertEquals(20, parsed.ml)
        assertEquals(120, parsed.pulses)
        assertEquals(600, parsed.rateMlMin)
        listOf(
            "LIVE addr=0002 job=7:1 pulses=120 ml=20 runtime_ms=2000",
            "LIVE addr=0002 addr=0003 job=7:1 pulses=120 ml=20 runtime_ms=2000 rate_ml_min=600",
            "LIVE addr=0000 job=7:1 pulses=120 ml=20 runtime_ms=2000 rate_ml_min=600",
            "LIVE addr=0002 job=7:0 pulses=120 ml=20 runtime_ms=2000 rate_ml_min=600",
            "LIVE addr=0002 job=7:1 pulses=4294967296 ml=20 runtime_ms=2000 rate_ml_min=600",
            "LIVE addr=0002 job=7:1 pulses=120 ml=20 runtime_ms=0 rate_ml_min=600",
            "LIVE addr=0002 job=7:1 pulses=120 ml=20 runtime_ms=240001 rate_ml_min=600"
        ).forEach { assertNull(it, MasterProtocol.parse(it)) }
    }

    @Test fun currentLiveSnapshotIsSessionOnlyAndMonotonic() {
        val first = base.receiveLive(live(), 1_000)
        assertEquals(20, first.liveFlows.getValue(2).ml)
        assertTrue(first.flows.isEmpty())
        assertNull(first.volume)
        assertTrue(first.history.isEmpty())

        val newer = first.receiveLive(live(pulses = 180, ml = 30, runtime = 4_000), 3_000)
        assertEquals(180, newer.liveFlows.getValue(2).pulses)
        assertSame(newer, newer.receiveLive(live(pulses = 179, ml = 31, runtime = 6_000), 4_000))
        assertSame(newer, newer.receiveLive(live(pulses = 181, ml = 31, runtime = 4_000), 4_000))
        assertSame(newer, newer.receiveLive(live(runtime = 60_001), 4_000))
    }

    @Test fun liveSnapshotMustMatchConnectedCurrentTaskAndSelectedNonterminalNode() {
        listOf(
            base.copy(connection = MasterConnectionState.Disconnected),
            base.copy(hello = base.hello!!.copy(epoch = 8)),
            base.copy(task = baseTask.copy(outcome = MasterOutcome.FINISHED)),
            base.copy(task = baseTask.copy(job = MasterJobKey(7, 2))),
            base.copy(slaves = listOf(MasterSlave(1, 3, true, true))),
            base.copy(inventoryFresh = false, slaves = null),
            base.copy(flows = mapOf(2L to MasterMessage.Flow(job, 2, 20, "DONE", terminal = true)))
        ).forEach { state -> assertSame(state, state.receiveLive(live(), 1_000)) }

        val staleInventory = base.copy(inventoryFresh = false)
        assertNull(staleInventory.expectedFlowAddresses)
        assertEquals(20, staleInventory.receiveLive(live(), 1_000).liveFlows.getValue(2).ml)

        val nodeDone = MasterMessage.Report("a1", 1, "OK", job, 3, 1, 1, 0, 0)
        val nodeDoneState = base.copy(report = nodeDone)
        assertSame(nodeDoneState, nodeDoneState.receiveLive(live(), 1_000))
        assertTrue(base.copy(liveFlows = mapOf(2L to live(1, 1))).copy(
            connection = MasterConnectionState.Disconnected).pruneLiveFlows().liveFlows.isEmpty())
    }

    @Test fun freshnessUsesPhoneArrivalTimeAndStopsAtSixSeconds() {
        val sample = base.receiveLive(live(), 1_000).liveFlows.getValue(2)
        assertTrue(sample.isFresh(7_000))
        assertFalse(sample.isFresh(7_001))
        assertFalse(sample.isFresh(999))
    }
}
