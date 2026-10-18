package com.cczb.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class MasterPumpTestTest {
    private val hello = MasterMessage.Hello("PUMP_MASTER", 0, 1, 7, false, true, "NONE")
    private val local = MasterMessage.Local(false, 0, "NONE", testSupported = true, pumpReady = true)
    private val ready = MasterUiState(connection = MasterConnectionState.Connected, hello = hello,
        statusFresh = true, local = local, localFresh = true)
    private val test = MasterPumpTest("a1", 8, 7, 3000)
    private fun reply(state: String, code: String = "OK", op: String = "QUERY") =
        MasterMessage.PumpTest(test.client, test.seq, test.epoch, code, state, op)

    @Test fun independentTestDoesNotRequireMeshButDosingDoesAndExplainsWhy() {
        assertTrue(ready.canStartTest)
        assertFalse(ready.canStart)
        assertEquals("主机尚未完成 Mesh 配网", ready.startBlockReason)
        assertTrue(ready.copy(hello = hello.copy(mesh = true, address = 2)).startBlockReason!!.contains("地址"))
    }
    @Test fun testRequiresCurrentMasterCapabilityCommissioningAndUnlockedOutput() {
        listOf(ready.copy(connection = MasterConnectionState.Disconnected), ready.copy(statusFresh = false),
            ready.copy(localFresh = false), ready.copy(hello = hello.copy(role = "VALVE_SLAVE")),
            ready.copy(hello = hello.copy(commissioned = false)), ready.copy(local = local.copy(testSupported = false)),
            ready.copy(local = local.copy(fault = "BOOT_LOCK")), ready.copy(local = local.copy(pumpReady = false)),
            ready.copy(storageHealthy = false), ready.copy(historyHealthy = false),
            ready.copy(task = MasterTask("a1", 1, 7, 1, 500, 60000))).forEach { assertFalse(it.canStartTest) }
        assertTrue(ready.copy(local = local.copy(fault = "BOOT_LOCK")).testBlockReason!!.contains("更新主机固件"))
    }
    @Test fun pendingTestBlocksAllStartsAndCanStopWithoutFreshnessOrStorage() {
        val pending = ready.copy(pumpTest = test)
        assertFalse(pending.canStartTest)
        assertFalse(pending.copy(hello = hello.copy(address = 1, mesh = true)).canStart)
        assertTrue(pending.copy(localFresh = false, statusFresh = false, storageHealthy = false).canStopTest)
        assertFalse(pending.copy(connection = MasterConnectionState.Disconnected).canStopTest)
    }
    @Test fun protocolBoundsAndWhitelistPreventUnboundedTestAndMaintenanceCommands() {
        listOf(test.command(), test.stop(), test.query(), "pump-test a1 9 7 10000").forEach { assertTrue(it, MasterProtocol.isAllowedCommand(it)) }
        listOf("pump-test a1 1 7 0", "pump-test a1 1 7 10001", "pump-test a1 1 0 3000",
            "pump-test-stop a1 0 7", "pump-test a1 1 7 3000\nlocal", "pump-reset", "estop").forEach { assertFalse(it, MasterProtocol.isAllowedCommand(it)) }
    }
    @Test fun extendedLocalMustCarryCompleteValidCapabilityAndRunningIdentity() {
        val text = "LOCAL pump_command=1 pump_phase=2 fault=NONE test_supported=1 test_active=1 test_ms=2999 test_client=a1 test_seq=8 pump_ready=0"
        val parsed = MasterProtocol.parse(text) as MasterMessage.Local
        assertTrue(parsed.testSupported && parsed.testActive)
        assertEquals(2999L, parsed.testMs)
        listOf(text.replace("test_ms=2999", "test_ms=10001"), text.replace("test_client=a1", "test_client=0"),
            text.replace("pump_command=1", "pump_command=0"), text.replace(" pump_ready=0", "")).forEach { assertNull(MasterProtocol.parse(it)) }
        assertFalse((MasterProtocol.parse("LOCAL pump_command=0 pump_phase=0 fault=NONE") as MasterMessage.Local).testSupported)
        assertEquals(reply("RUNNING"), MasterProtocol.parse("PUMP_TEST client=a1 seq=8 epoch=7 reply=OK state=RUNNING op=QUERY"))
    }
    @Test fun responseMatchingAndStopFailureNeverReleaseAnUnknownStart() {
        assertEquals(test, test.reconcile(reply("RUNNING").copy(seq = 9)))
        assertEquals(test, test.reconcile(reply("RUNNING").copy(epoch = 8)))
        assertEquals(test, test.reconcile(reply("RUNNING").copy(client = "b2")))
        assertTrue(test.reconcile(reply("UNKNOWN", "STORAGE", "STOP")).unresolved)
        assertTrue(test.reconcile(reply("UNKNOWN")).unresolved)
        assertEquals(MasterOutcome.REJECTED, test.reconcile(reply("UNKNOWN", "NOT_READY", "START")).outcome)
        val running = test.reconcile(reply("RUNNING"))
        assertEquals(MasterOutcome.ACCEPTED, running.outcome)
        val stopped = running.reconcile(reply("STOPPED"))
        assertEquals(MasterOutcome.FINISHED, stopped.outcome)
        assertEquals(stopped, stopped.reconcile(reply("RUNNING")))
    }
    @Test fun durableTestIdentitySurvivesProcessDeathAndSharesDosingSequence() {
        val ledger = MasterLedger(test.client, test.seq, pumpTests = mapOf("AA:BB:CC:DD:EE:01" to test))
        val bytes = ByteArrayOutputStream().also(ledger::write).toByteArray()
        val restored = MasterLedger.read(ByteArrayInputStream(bytes))
        assertEquals(ledger, restored)
        assertEquals(9L, restored.nextSequence())
        assertTrue(restored.pumpTests.values.single().unresolved)
    }
    @Test fun updatedMasterAllowsPhoneTestsWithoutUnlockingDosing() {
        val locked = ready.copy(hello = hello.copy(fault = "BOOT_LOCK"), local = local.copy(fault = "BOOT_LOCK", phase = 4),
            pwm = MasterPumpPwm(MasterMessage.PumpPwm(7, 0, 1, 30, 20000, false, "OK", true, 3000)), pwmFresh = true)
        assertTrue(locked.canStartTest)
        assertTrue(locked.canStartDryTest)
        assertTrue(locked.canSetSpeed)
        assertFalse(locked.copy(hello = locked.hello!!.copy(mesh = true, address = 1)).canStart)
        listOf(locked.copy(pwmFresh = false), locked.copy(pwm = MasterPumpPwm()),
            locked.copy(pwm = MasterPumpPwm(locked.pwm.snapshot!!.copy(epoch = 8))),
            locked.copy(local = local.copy(fault = "TRANSPORT")),
            locked.copy(local = local.copy(fault = "AUTHORIZATION"))).forEach { assertFalse(it.canStartDryTest) }
        assertFalse(locked.copy(pwm = MasterPumpPwm(locked.pwm.snapshot!!.copy(revision = 0xffff_ffffL))).canStartDryTest)
    }
    @Test fun quickTestIsBoundedAndDurablyRestoredWithoutAutomaticReplay() {
        val quick = test.copy(dryRun = true)
        assertEquals("pump-dry-test a1 8 7 3000", quick.command())
        assertTrue(MasterProtocol.isAllowedCommand(quick.command()))
        listOf("pump-dry-test a1 8 7 3001", "pump-dry-test a1 8 7 0", "pump-dry-test a1 8 0 3000",
            "pump-dry-test a1 8 7 3000 extra").forEach { assertFalse(MasterProtocol.isAllowedCommand(it)) }
        val old = "version=1\nclient=a1\nsequence=8\ntest.AA\\:BB\\:CC\\:DD\\:EE\\:01=8,7,3000,PENDING\n"
        assertFalse(MasterLedger.read(ByteArrayInputStream(old.toByteArray())).pumpTests.values.single().dryRun)
        val ledger = MasterLedger(quick.client, quick.seq, pumpTests = mapOf("AA:BB:CC:DD:EE:01" to quick))
        val bytes = ByteArrayOutputStream().also(ledger::write).toByteArray()
        assertEquals(ledger, MasterLedger.read(ByteArrayInputStream(bytes)))
        assertThrows(IllegalArgumentException::class.java) {
            MasterLedger.read(ByteArrayInputStream(old.replace("3000,PENDING", "3000,PENDING,2").toByteArray()))
        }
        assertFalse(ready.copy(pumpTest = quick).canSetSpeed)
        assertTrue(ready.copy(pumpTest = quick, pwmFresh = false, localFresh = false).canStopTest)
    }
}
