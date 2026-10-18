package com.cczb.app

import com.cczb.app.MasterPairingGate.Action.*
import com.cczb.app.MasterPairingGate.Bond.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MasterPairingGateTest {
    @Test fun firstConnectionWaitsForSystemPinBeforeDiscovering() {
        val gate = MasterPairingGate()
        assertEquals(WAIT, gate.connected(NONE))
        assertEquals(WAIT, gate.bondChanged(BONDING))
        assertEquals(WAIT, gate.fallback(BONDING))
        assertEquals(DISCOVER, gate.bondChanged(BONDED))
        assertEquals(WAIT, gate.bondChanged(BONDED))
        assertEquals(WAIT, gate.fallback(BONDED))
    }

    @Test fun existingBondDiscoversOnceWithoutRequestingAnotherPin() {
        val gate = MasterPairingGate()
        assertEquals(DISCOVER, gate.connected(BONDED))
        assertEquals(WAIT, gate.fallback(BONDED))
        assertEquals(WAIT, gate.bondChanged(BONDED))
    }

    @Test fun slowMasterFallsBackToOneSystemBondRequest() {
        val gate = MasterPairingGate()
        assertEquals(WAIT, gate.connected(NONE))
        assertEquals(REQUEST_BOND, gate.fallback(NONE))
        assertEquals(WAIT, gate.bondChanged(BONDING))
        assertEquals(WAIT, gate.fallback(BONDING))
        assertEquals(DISCOVER, gate.bondChanged(BONDED))
    }

    @Test fun canceledOrFailedPairingCannotDiscoverServices() {
        val gate = MasterPairingGate()
        gate.connected(BONDING)
        assertEquals(FAIL, gate.bondChanged(NONE))
        gate.reset()
        gate.connected(NONE)
        gate.fallback(NONE)
        assertEquals(FAIL, gate.bondChanged(NONE))
    }

    @Test fun lostBondFailsAndResetDropsTheOldSession() {
        val gate = MasterPairingGate()
        gate.connected(BONDED)
        assertEquals(FAIL, gate.bondChanged(NONE))
        gate.reset()
        assertEquals(WAIT, gate.bondChanged(BONDED))
        assertEquals(WAIT, gate.fallback(NONE))
        assertEquals(WAIT, gate.connected(NONE))
        assertEquals(DISCOVER, gate.bondChanged(BONDED))
    }

    @Test fun bondingAlreadyStartedBeforeGattCallbackIsNotRequestedTwice() {
        val gate = MasterPairingGate()
        assertEquals(WAIT, gate.bondChanged(BONDING))
        assertEquals(WAIT, gate.connected(BONDING))
        assertEquals(WAIT, gate.fallback(BONDING))
        assertEquals(DISCOVER, gate.bondChanged(BONDED))
    }
}
