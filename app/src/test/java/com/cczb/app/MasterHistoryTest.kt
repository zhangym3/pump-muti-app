package com.cczb.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class MasterHistoryTest {
    @Test fun reasonsAndNodeResultsSurviveRestartWithoutBecomingFresh() {
        val f = MasterMessage.Flow(MasterJobKey(7, 1), 2, 500, "DONE", 2940, 8, true, true,
            receivedAt = 123, reason = "TARGET")
        val original = entry.copy(report = report.copy(reason = "TRANSPORT"), flows = mapOf(2L to f))
        val restored = MasterHistory.read(ByteArrayInputStream(bytes(listOf(original)))).single()
        assertEquals("TRANSPORT", restored.report!!.reason)
        assertEquals(f.copy(receivedAt = 0), restored.flows.getValue(2))
        assertFalse(restored.flows.getValue(2).isFresh(123))
    }

    @Test fun taskAddressesSurviveRestartAndRejectIncompleteOrDuplicateBindings() {
        val original = entry.copy(nodes = mapOf(1 to 2L, 32 to 33L))
        assertEquals(original, MasterHistory.read(ByteArrayInputStream(bytes(listOf(original)))).single())
        for (invalid in listOf("1:2", "1:2,32:2", "1:2,1:21", "1:8000,32:21", "0:2,32:21")) {
            val p = Properties().apply {
                load(ByteArrayInputStream(bytes(listOf(original))))
                setProperty("record.0.nodes", invalid)
            }
            val corrupt = ByteArrayOutputStream().also { p.store(it, null) }.toByteArray()
            assertThrows(Exception::class.java) { MasterHistory.read(ByteArrayInputStream(corrupt)) }
        }
        val legacy = Properties().apply {
            load(ByteArrayInputStream(bytes(listOf(entry))))
            remove("record.0.nodes")
        }
        val oldBytes = ByteArrayOutputStream().also { legacy.store(it, null) }.toByteArray()
        assertTrue(MasterHistory.read(ByteArrayInputStream(oldBytes)).single().nodes.isEmpty())
    }

    @Test fun oldHistoryWithoutReasonsOrFlowsStillLoadsAndInvalidFlowsFailClosed() {
        val original = Properties().apply { load(ByteArrayInputStream(bytes(listOf(entry)))) }
        original.remove("record.0.flowCount")
        original.setProperty("record.0.report", original.getProperty("record.0.report").substringBefore(" reason="))
        fun read(p: Properties) = MasterHistory.read(ByteArrayInputStream(ByteArrayOutputStream().also { p.store(it, null) }.toByteArray()))
        assertEquals("UNKNOWN", read(original).single().report!!.reason)
        assertTrue(read(original).single().flows.isEmpty())
        val flow = "FLOW addr=0002 job=7:1 pulses=2940 ml=500 phase=DONE revision=8 terminal=1 measured=1 reason=TARGET"
        for (invalid in listOf("job", "duplicate", "count")) {
            val p = Properties().apply { putAll(original); setProperty("record.0.flowCount", "1"); setProperty("record.0.flow.0", flow) }
            when (invalid) {
                "job" -> p.setProperty("record.0.flow.0", flow.replace("job=7:1", "job=7:2"))
                "duplicate" -> { p.setProperty("record.0.flowCount", "2"); p.setProperty("record.0.flow.1", flow) }
                "count" -> p.setProperty("record.0.flowCount", "33")
            }
            assertThrows(Exception::class.java) { read(p) }
        }
    }

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

    @Test fun historyWithoutAnyVolumeCoverageLoadsAsPending() {
        val p = Properties().apply {
            load(ByteArrayInputStream(bytes(listOf(entry))))
            setProperty("record.0.volume", "V job=7:1 total_ml=0 measured=0")
        }
        val saved = ByteArrayOutputStream().also { p.store(it, null) }.toByteArray()
        assertNull(MasterHistory.read(ByteArrayInputStream(saved)).single().volume)
    }

    @Test fun localOutputRequiresValidFlagsAndKnownPhase() {
        assertEquals(MasterMessage.Local(true, 2, "NONE"), MasterProtocol.parse("LOCAL pump_command=1 pump_phase=2 fault=NONE"))
        assertEquals(MasterMessage.Local(false, 4, "BOOT_LOCK"), MasterProtocol.parse("LOCAL pump_command=0 pump_phase=4 fault=BOOT_LOCK"))
        listOf("LOCAL pump_command=2 pump_phase=2 fault=NONE", "LOCAL pump_command=1 pump_phase=3 fault=NONE",
            "LOCAL pump_command=0 pump_phase=0", "LOCAL pump_command=1 pump_phase=2 fault=").forEach { assertNull(MasterProtocol.parse(it)) }
    }
}
