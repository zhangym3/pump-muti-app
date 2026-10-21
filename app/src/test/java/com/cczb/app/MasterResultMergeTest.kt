package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class MasterResultMergeTest {
    @Test fun failedDoesNotProveMeasurementAndLateTargetCannotEraseMasterAbort() {
        val stopped = MasterMessage.Report("a1", 42, "OK", MasterJobKey(7, 1), 7, 1, 0, 1, 1, "TRANSPORT")
        val stillMissing = stopped.copy(unknown = 0, reason = "NONE")
        assertEquals(1L, MasterResultMerge.report(stopped, stillMissing).unknown)
        val terminal = stopped.copy(done = 1, unknown = 0, reason = "NONE")
        val reconciled = MasterResultMerge.report(stopped, terminal)
        assertEquals(0L, reconciled.unknown)
        assertEquals(1L, reconciled.failed)
        assertEquals("TRANSPORT", reconciled.reason)
        // Reports have no revision. Ambiguous stale unknown evidence stays conservative
        // until a subsequent query supplies the current device result.
        assertEquals(1L, MasterResultMerge.report(reconciled, stopped).unknown)
        val flow = MasterMessage.Flow(stopped.job!!, 2, 500, "DONE", 2940, 8, true, true, reason = "TARGET")
        assertEquals("TARGET", MasterResultMerge.flow(flow, flow.copy(reason = "UNKNOWN")).reason)
        assertEquals("TRANSPORT", reconciled.reason)
        assertEquals("TARGET", flow.reason)
    }

    private val job = MasterJobKey(7, 1)
    private val client = "fedcba9876543210"

    @Test fun delayedActiveReportCannotRegressTerminalStateAndLateDoneClearsUnknown() {
        val terminal = MasterMessage.Report(client, 42, "OK", job, 6, 1, 0, 0, 1)
        val delayed = MasterMessage.Report(client, 42, "OK", job, 3, 1, 0, 0, 1)
        assertEquals(terminal, MasterResultMerge.report(terminal, delayed))
        val recovered = MasterMessage.Report(client, 42, "OK", job, 6, 1, 1, 0, 0)
        val merged = MasterResultMerge.report(terminal, recovered)
        assertEquals(6, merged.phase)
        assertEquals(1L, merged.done)
        assertEquals(0L, merged.unknown)
    }

    @Test fun volumeCoverageAndTotalNeverRegressForSameJob() {
        val old = MasterMessage.Volume(job, 900, 1)
        val delayed = MasterMessage.Volume(job, 500, 0)
        assertEquals(old, MasterResultMerge.volume(old, delayed))
        assertNull(MasterResultMerge.volume(null, delayed))
        assertNull(MasterResultMerge.volume(old, MasterMessage.Volume(MasterJobKey(7, 2), 0, 0)))
        assertEquals(MasterMessage.Volume(job, 1100, 3),
            MasterResultMerge.volume(old, MasterMessage.Volume(job, 1100, 2)))
    }

    @Test fun volumeRequiresSelectedNodeCoverageButCanReportMeasuredZero() {
        val mask = 0x5L
        assertFalse(MasterResultMerge.hasVolumeData(MasterMessage.Volume(job, 0, 0), job, mask))
        assertTrue(MasterResultMerge.hasVolumeData(MasterMessage.Volume(job, 0, 1), job, mask))
        assertTrue(MasterResultMerge.hasVolumeData(MasterMessage.Volume(job, 500, mask), job, mask))
        assertFalse(MasterResultMerge.hasVolumeData(MasterMessage.Volume(job, 500, 2), job, mask))
    }

    @Test fun flowRevisionRejectsOldSnapshotsAndRebootInvalidatesMeasurement() {
        val done = MasterMessage.Flow(job, 2, 100, "DONE", 1000, 8, true, true)
        val old = MasterMessage.Flow(job, 2, 1, "RUNNING", 10, 7, false, true)
        assertEquals(done, MasterResultMerge.flow(done, old))
        val newerProgress = MasterMessage.Flow(job, 2, 110, "RUNNING", 1100, 9, false, true)
        val stillTerminal = MasterResultMerge.flow(done, newerProgress)
        assertTrue(stillTerminal.terminal)
        assertEquals("DONE", stillTerminal.phase)
        assertEquals(1000L, stillTerminal.pulses)
        val reboot = MasterMessage.Flow(job, 2, 0, "FAULT", 0, 10, true, false)
        val invalidated = MasterResultMerge.flow(done, reboot)
        assertTrue(invalidated.terminal)
        assertFalse(invalidated.measured)
        assertEquals(0L, invalidated.pulses)
        assertEquals(0L, invalidated.ml)
    }
}
