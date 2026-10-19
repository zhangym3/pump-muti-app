package com.cczb.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class ValveAmountsTest {
    private val values = List(32) { if (it == 31) "2000" else "300" }
    private val encoded = requireNotNull(ValveAmounts.encode(0x80000001, values))
    private val task = MasterTask("ffffffffffffffff", 0xffff_ffff, 0xffff_ffff, 0x80000001, 0, 240000, amounts = encoded)
    private val address = "AA:BB:CC:DD:EE:01"

    @Test fun sparsePlanPreservesSlot32AndFitsSingleBleWrite() {
        assertEquals("06" + "00".repeat(30) + "28", encoded)
        assertTrue(ValveAmounts.isValid(task.mask, encoded))
        assertEquals(300L, task.target(1))
        assertEquals(2000L, task.target(32))
        assertEquals(2300L, task.totalMl)
        assertTrue(task.command().toByteArray().size <= 144)
        assertTrue(MasterProtocol.isAllowedCommand(task.command()))
        val full = requireNotNull(ValveAmounts.encode(0xffff_ffff, List(32) { "2000" }))
        assertEquals(64000L, task.copy(mask = 0xffff_ffff, amounts = full).totalMl)
    }

    @Test fun malformedPlansNeverReachCommandBoundary() {
        listOf(encoded.dropLast(1), "00".repeat(32), "29" + encoded.drop(2), "gg" + encoded.drop(2),
            encoded.take(2) + "01" + encoded.drop(4)).forEach {
            assertFalse(it, MasterProtocol.isAllowedCommand(task.copy(amounts = it).command()))
        }
        listOf("", "0", "225", "2001", "2050").forEach { bad ->
            assertNull(ValveAmounts.encode(1, values.toMutableList().also { it[0] = bad }))
        }
        assertNull(ValveAmounts.encode(0, values))
    }

    @Test fun taskIdentityAndPerValveTargetsSurviveLedgerAndHistoryRoundTrip() {
        val original = MasterLedger(task.client, task.seq, mapOf(address to task))
        val bytes = ByteArrayOutputStream().also(original::write).toByteArray()
        assertEquals(original, MasterLedger.read(ByteArrayInputStream(bytes)))
        val entry = MasterHistoryEntry(address, "主机", task, 1000)
        val history = ByteArrayOutputStream().also { MasterHistory.write(listOf(entry), it) }.toByteArray()
        assertEquals(listOf(entry), MasterHistory.read(ByteArrayInputStream(history)))
        val corrupt = Properties().apply { load(ByteArrayInputStream(bytes)); setProperty("amounts.$address", "00".repeat(32)) }
        assertThrows(Exception::class.java) {
            MasterLedger.read(ByteArrayInputStream(ByteArrayOutputStream().also { corrupt.store(it, null) }.toByteArray()))
        }
    }

    @Test fun legacyAcceptedRequestsAndTotalsRemainReadableAfterUiLimitChange() {
        val legacy = task.copy(ml = 1_000_000, amounts = "")
        val ledger = MasterLedger(legacy.client, legacy.seq, mapOf(address to legacy))
        assertEquals(ledger, MasterLedger.read(ByteArrayInputStream(ByteArrayOutputStream().also(ledger::write).toByteArray())))
        assertEquals(2_000_000L, legacy.totalMl)
        val entry = MasterHistoryEntry(address, "主机", legacy, 1000)
        assertEquals(listOf(entry), MasterHistory.read(ByteArrayInputStream(ByteArrayOutputStream().also {
            MasterHistory.write(listOf(entry), it)
        }.toByteArray())))
    }

    @Test fun oldMastersDoNotAdvertiseIndividualSupport() {
        val hello = "HELLO role=PUMP_MASTER addr=0001 master=0001 epoch=7 mesh=1 commissioned=1 pump_fault=NONE"
        assertFalse((MasterProtocol.parse(hello) as MasterMessage.Hello).individual)
        assertTrue((MasterProtocol.parse("$hello individual=1") as MasterMessage.Hello).individual)
        assertNull(MasterProtocol.parse("$hello individual=2"))
    }
}
