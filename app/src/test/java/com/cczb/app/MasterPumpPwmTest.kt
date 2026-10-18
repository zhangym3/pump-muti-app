package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class MasterPumpPwmTest {
    private val snapshot = MasterMessage.PumpPwm(7, 0, 1, 30, 20000, false, "OK")
    private val ready = MasterUiState(connection = MasterConnectionState.Connected,
        hello = MasterMessage.Hello("PUMP_MASTER", 0, 1, 7, false, true, "NONE"), statusFresh = true,
        local = MasterMessage.Local(false, 0, "NONE", testSupported = true, pumpReady = true), localFresh = true,
        pwm = MasterPumpPwm().receive(snapshot, 7), pwmFresh = true)

    @Test fun parsesCompleteBoundedDevicePwmSnapshot() {
        val text = "PUMP_PWM epoch=7 rev=0 level=1 duty=30 hz=20000 output=0 reply=OK"
        assertEquals(snapshot, MasterProtocol.parse(text))
        listOf(text.replace("epoch=7", "epoch=0"), text.replace("rev=0", "rev=4294967296"),
            text.replace("level=1", "level=4"), text.replace("duty=30", "duty=0"),
            text.replace("hz=20000", "hz=0"), text.replace("output=0", "output=2"),
            text.replace("reply=OK", "reply=BOGUS"), text + " rev=1").forEach { assertNull(it, MasterProtocol.parse(it)) }
    }
    @Test fun whitelistRequiresCurrentBootRevisionAndThreeLevels() {
        listOf(snapshot.command(1), snapshot.command(2), snapshot.command(3)).forEach {
            assertTrue(it, MasterProtocol.isAllowedCommand(it))
        }
        listOf("pump-speed 0 0 2", "pump-speed 7 -1 2", "pump-speed 7 4294967296 2",
            "pump-speed 7 0 0", "pump-speed 7 0 4", "pump-speed 7 0 2 extra",
            "pump-speed 7 0 2\nlocal").forEach { assertFalse(it, MasterProtocol.isAllowedCommand(it)) }
    }
    @Test fun selectionWaitsForDeviceRevisionAndNeverOptimisticallyChangesLevel() {
        val current = ready.pwm
        val pending = current.request(2)
        assertEquals(1, pending.snapshot!!.level)
        assertEquals(MasterPwmPending(7, 0, 2), pending.pending)
        assertEquals(pending, pending.request(3))
        assertNotNull(pending.receive(snapshot, 7).pending)
        val confirmed = pending.receive(snapshot.copy(revision = 1, level = 2, duty = 60), 7)
        assertNull(confirmed.pending)
        assertEquals(2, confirmed.snapshot!!.level)
        assertEquals(current, current.request(1))
        assertEquals(current, current.request(4))
    }
    @Test fun ignoresOldBootOrRevisionAndReconcilesRejectionWithoutRetry() {
        val pending = ready.pwm.request(3)
        assertEquals(pending, pending.receive(snapshot.copy(epoch = 8, revision = 1), 7))
        val rejected = pending.receive(snapshot.copy(reply = "BUSY"), 7)
        assertNull(rejected.pending)
        assertEquals(1, rejected.snapshot!!.level)
        val next = pending.receive(snapshot.copy(revision = 4, level = 2, duty = 60), 7)
        assertNull(next.pending)
        assertEquals(next, next.receive(snapshot.copy(revision = 3), 7))
        assertNull(MasterPumpPwm(snapshot.copy(revision = 0xffff_ffffL)).request(2).pending)
    }
    @Test fun speedRequiresFreshUnlockedMasterAndPendingBlocksTestButNotStop() {
        assertTrue(ready.canSetSpeed)
        listOf(ready.copy(pwmFresh = false), ready.copy(localFresh = false), ready.copy(statusFresh = false),
            ready.copy(connection = MasterConnectionState.Disconnected), ready.copy(pwm = MasterPumpPwm()),
            ready.copy(hello = ready.hello!!.copy(commissioned = false)),
            ready.copy(local = ready.local!!.copy(fault = "BOOT_LOCK")),
            ready.copy(task = MasterTask("a1", 1, 7, 1, 50, 3000))).forEach { assertFalse(it.canSetSpeed) }
        val pending = ready.copy(pwm = ready.pwm.request(2))
        assertFalse(pending.canStartTest)
        assertFalse(pending.canSetSpeed)
        assertTrue(pending.copy(pumpTest = MasterPumpTest("a1", 1, 7, 3000), pwmFresh = false, localFresh = false).canStopTest)
        assertTrue(ready.copy(local = ready.local!!.copy(pumpCommand = true, testActive = true)).canSetSpeed)
        assertFalse(ready.copy(local = ready.local!!.copy(pumpCommand = true)).canSetSpeed)
    }
    @Test fun testCapabilitiesRequireCompleteConsistentFieldsAndFitOneLine() {
        val text = "PUMP_PWM epoch=7 rev=1 level=1 duty=30 hz=20000 output=1 reply=OK test_manual=1 dry_max_ms=3000 dry_active=1"
        val parsed = MasterProtocol.parse(text) as MasterMessage.PumpPwm
        assertTrue(parsed.manualTest && parsed.dryActive)
        assertEquals(3000L, parsed.dryMaxMs)
        listOf(text.replace(" dry_active=1", ""), text.replace("test_manual=1", "test_manual=0"),
            text.replace("dry_max_ms=3000", "dry_max_ms=3001"), text.replace("output=1", "output=0"),
            text.replace("level=1", "level=3"), text.replace("dry_active=1", "dry_active=2")).forEach { assertNull(it, MasterProtocol.parse(it)) }
        val longest = text.replace("epoch=7", "epoch=4294967295").replace("rev=1", "rev=4294967295").replace("reply=OK", "reply=UNAUTHORIZED")
        assertTrue(longest.toByteArray().size <= 191)
        assertEquals(listOf(longest), MasterLineAssembler().accept((longest + "\n").toByteArray()))
        assertFalse(ready.copy(pwm = MasterPumpPwm(parsed)).canSetSpeed)
    }
}
