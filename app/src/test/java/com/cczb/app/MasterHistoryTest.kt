package com.cczb.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class MasterHistoryTest {
    private val task = MasterTask("a1", 42, 7, 0x80000001, 500, 60_000, MasterOutcome.FINISHED, MasterJobKey(7, 1))
    private val report = MasterMessage.Report("a1", 42, "OK", task.job, 6, task.mask, task.mask, 0, 0x80000000)
    private val entry = MasterHistoryEntry("AA:BB:CC:DD:EE:01", "泵房主机", task, 100, report, MasterMessage.Volume(task.job!!, 503, 1))
    private fun bytes(entries: List<MasterHistoryEntry>) = ByteArrayOutputStream().also { MasterHistory.write(entries, it) }.toByteArray()

    @Test fun roundTripPreservesIncompleteCoverageUnknownResultsAndMissingData() {
        val pending = entry.copy(task = task.copy(seq = 43, outcome = MasterOutcome.PENDING, job = null), report = null, volume = null)
        val restored = MasterHistory.read(ByteArrayInputStream(bytes(listOf(entry, pending))))
        assertEquals(listOf(entry, pending), restored)
        assertEquals(1L, restored.first().volume!!.measured)
        assertNull(restored.last().volume)
        assertTrue(restored.last().task.unresolved)
    }

    @Test fun upsertIsolatesMastersAndBoundsLatestHistory() {
        val other = entry.copy(address = "AA:BB:CC:DD:EE:02")
        assertEquals(2, MasterHistory.upsert(listOf(entry), other).size)
        assertEquals(1, MasterHistory.upsert(listOf(entry), entry.copy(updatedAt = 200)).size)
        var history = emptyList<MasterHistoryEntry>()
        repeat(105) { history = MasterHistory.upsert(history, entry.copy(task = task.copy(seq = it + 1L), report = null, updatedAt = it + 1L)) }
        assertEquals(100, history.size)
        assertEquals(105L, history.first().task.seq)
        assertEquals(6L, history.last().task.seq)
    }

    @Test fun corruptOrCrossTaskSnapshotsCannotRestoreAsValidResults() {
        listOf("record.0.report" to "R client=a1 seq=41 reply=OK job=7:1 phase=6 mask=80000001 done=0 failed=0 unknown=0",
            "record.0.volume" to "V job=7:1 total_ml=1 measured=2",
            "record.0.job" to "8:1").forEach { (key, value) ->
            val p = Properties().apply { load(ByteArrayInputStream(bytes(listOf(entry)))); setProperty(key, value) }
            val corrupt = ByteArrayOutputStream().also { p.store(it, null) }.toByteArray()
            assertThrows(Exception::class.java) { MasterHistory.read(ByteArrayInputStream(corrupt)) }
        }
    }

    @Test fun localOutputRequiresValidFlagsAndKnownPhase() {
        assertEquals(MasterMessage.Local(true, 2, "NONE"), MasterProtocol.parse("LOCAL pump_command=1 pump_phase=2 fault=NONE"))
        assertEquals(MasterMessage.Local(false, 4, "BOOT_LOCK"), MasterProtocol.parse("LOCAL pump_command=0 pump_phase=4 fault=BOOT_LOCK"))
        listOf("LOCAL pump_command=2 pump_phase=2 fault=NONE", "LOCAL pump_command=1 pump_phase=3 fault=NONE",
            "LOCAL pump_command=0 pump_phase=0", "LOCAL pump_command=1 pump_phase=2 fault=").forEach { assertNull(MasterProtocol.parse(it)) }
    }
}
