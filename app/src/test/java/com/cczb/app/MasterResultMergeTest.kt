package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class MasterResultMergeTest {
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
        assertEquals(MasterMessage.Volume(job, 1100, 3),
            MasterResultMerge.volume(old, MasterMessage.Volume(job, 1100, 2)))
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
