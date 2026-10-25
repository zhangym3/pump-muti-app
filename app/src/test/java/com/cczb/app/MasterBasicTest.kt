package com.cczb.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class MasterBasicTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private val task = MasterTask("a1", 42, 7, 31, 500, 60_000)
    private val hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE")
    private val ready = MasterUiState(connection = MasterConnectionState.Connected, hello = hello,
        statusFresh = true, inventoryFresh = true, slaves = (1..5).map { MasterSlave(it, it + 1L, true, true) })
    private fun bytes(ledger: MasterLedger) = ByteArrayOutputStream().also(ledger::write).toByteArray()
    private fun read(p: Properties) = MasterLedger.read(ByteArrayInputStream(ByteArrayOutputStream().also { p.store(it, null) }.toByteArray()))

    @Test fun stopIntentSurvivesDeathBeforeAcceptanceAndNeverProducesAnotherStart() {
        val pending = task.copy(stopRequested = true, stopSequence = 43)
        val restored = MasterLedger.read(ByteArrayInputStream(bytes(MasterLedger("a1", 43, mapOf(address to pending))))).tasks.getValue(address)
        assertEquals(pending, restored)
        assertNull(restored.stopCommand(7))
        assertFalse(ready.copy(task = restored).canRetry)
        val unknown = MasterMessage.Report("a1", 42, "UNKNOWN", null, 0, 0, 0, 0, 0)
        assertEquals(restored, restored.reconcile(unknown, false))
        val report = MasterMessage.Report("a1", 42, "OK", MasterJobKey(7, 3), 3, 31, 0, 0, 0)
        val accepted = restored.reconcile(report, false)
        assertTrue(accepted.stopRequested)
        assertEquals("stop a1 43 8 7 3", accepted.stopCommand(8))
        assertNull(accepted.stopCommand(0))
        assertNull(accepted.copy(outcome = MasterOutcome.FINISHED).stopCommand(8))
        val stopped = ready.copy(task = accepted, stopPending = true).receiveTaskReport(report.copy(seq = 43, phase = 7, done = 31, failed = 31), 43)!!
        assertFalse(stopped.stopPending)
        assertEquals(MasterJobKey(7, 3), stopped.task!!.job)
        assertEquals(42L, stopped.task!!.seq)
        assertFalse(stopped.task!!.unresolved)
    }

    @Test fun ledgerV3RetainsIntentWithoutSequenceAndReadsLegacyVersions() {
        val ledger = MasterLedger("a1", 43, mapOf(address to task.copy(stopRequested = true)))
        assertEquals(ledger, MasterLedger.read(ByteArrayInputStream(bytes(ledger))))
        for (version in 1..2) {
            val p = Properties().apply { load(ByteArrayInputStream(bytes(ledger))); setProperty("version", "$version"); remove("stop.$address") }
            assertEquals(task, read(p).tasks.getValue(address))
        }
        for (bad in listOf("-1", "44", "42", "abc")) {
            val p = Properties().apply { load(ByteArrayInputStream(bytes(ledger))); setProperty("stop.$address", bad) }
            assertThrows(Exception::class.java) { read(p) }
        }
    }

    @Test fun fiveLaneCapabilityIsBoundToCurrentBootAndMaintenanceGatesNewWork() {
        val caps = MasterMessage.Caps(7, 5, 5, true, true)
        assertFalse(ready.canStartMask(31))
        assertFalse(ready.copy(caps = caps.copy(epoch = 6)).canStartMask(31))
        val basic = ready.copy(caps = caps, maintenance = MasterMessage.Maintenance(false, 0, null, 0, 0))
        assertTrue(basic.canStart && basic.canStartMask(31))
        assertFalse(basic.canStartMask(63))
        assertFalse(basic.canStartMask(0))
        assertFalse(basic.canStartMask(1L shl 32))
        assertFalse(basic.copy(maintenance = null).canStart)
        assertFalse(basic.copy(maintenance = basic.maintenance!!.copy(locked = true)).canStart)
        assertFalse(basic.copy(maintenance = basic.maintenance!!.copy(pending = 1)).canStart)
        assertFalse(basic.canStartTest)
    }

    @Test fun capabilitiesMaintenanceAndMeshBudgetParseStrictRanges() {
        val caps = "CAPS epoch=7 max_nodes=5 max_concurrent=5 basic=1 maintenance=1"
        assertEquals(MasterMessage.Caps(7, 5, 5, true, true), MasterProtocol.parse(caps))
        for (bad in listOf(caps.replace("epoch=7", "epoch=0"), caps.replace("nodes=5", "nodes=6"),
            caps.replace("concurrent=5", "concurrent=6"), caps.replace("basic=1", "basic=2"))) assertNull(MasterProtocol.parse(bad))
        val maintenance = "MAINT locked=1 addr=0002 token=7:3 boot=42 pending=0"
        assertEquals(MasterMessage.Maintenance(true, 2, MasterJobKey(7, 3), 42, 0), MasterProtocol.parse(maintenance))
        for (bad in listOf(maintenance.replace("0002", "8000"), maintenance.replace("7:3", "0:0"),
            maintenance.replace("boot=42", "boot=0"), maintenance.replace("pending=0", "pending=4"))) assertNull(MasterProtocol.parse(bad))
        val budget = "MESH_BUDGET epoch=7 op=1 remaining_ms=420000 recovery=RESTART_REQUIRED"
        assertEquals(MasterMessage.MeshBudget(7, 1, 420000, "RESTART_REQUIRED"), MasterProtocol.parse(budget))
        assertNull(MasterProtocol.parse(budget.replace("420000", "420001")))
    }

    @Test fun meshRecoveryAndBudgetAreBoundToCurrentOperationAndConnection() {
        val configuring = ready.copy(meshFresh = true,
            mesh = MasterMeshState("CONFIGURING", true, true, "NONE", 2, 1, 1, 2),
            meshBudget = MasterMessage.MeshBudget(7, 2, 18001, "NONE"))
        assertTrue(configuring.meshBudgetMessage!!.contains("19 秒"))
        assertNull(configuring.copy(meshBudget = configuring.meshBudget!!.copy(epoch = 6)).meshBudgetMessage)
        assertNull(configuring.copy(meshBudget = configuring.meshBudget!!.copy(operation = 1)).meshBudgetMessage)
        assertNull(configuring.copy(meshFresh = false).meshBudgetMessage)
        assertNull(configuring.copy(connection = MasterConnectionState.Disconnected).meshBudgetMessage)
        val isolated = configuring.copy(meshBudget = configuring.meshBudget!!.copy(remainingMs = 0, recovery = "RESTART_REQUIRED"))
        assertTrue(isolated.meshBudgetMessage!!.contains("重启主机"))
        assertNull(configuring.copy(mesh = configuring.mesh!!.copy(phase = "READY", busy = false)).meshBudgetMessage)
    }
}
