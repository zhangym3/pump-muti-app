package com.cczb.app

/** Serializes bonding and GATT setup. Bonding alone is not proof of encrypted GATT access. */
internal class MasterPairingGate {
    enum class Bond { NONE, BONDING, BONDED }
    enum class Action { WAIT, REQUEST_BOND, DISCOVER, FAIL }

    private var connected = false
    private var bond = Bond.NONE
    private var requested = false
    private var discovering = false

    fun connected(state: Bond): Action {
        connected = true
        bond = state
        return discoverIfBonded()
    }

    fun bondChanged(state: Bond): Action {
        val previous = bond
        bond = state
        if (!connected) return Action.WAIT
        if (state == Bond.NONE && (previous != Bond.NONE || requested)) return Action.FAIL
        return discoverIfBonded()
    }

    /** Give the Master's security request priority; request bonding only if it did not start. */
    fun fallback(state: Bond): Action {
        val result = bondChanged(state)
        if (result != Action.WAIT || !connected || state != Bond.NONE || requested) return result
        requested = true
        return Action.REQUEST_BOND
    }

    private fun discoverIfBonded(): Action {
        if (!connected || bond != Bond.BONDED || discovering) return Action.WAIT
        discovering = true
        return Action.DISCOVER
    }

    fun reset() {
        connected = false
        bond = Bond.NONE
        requested = false
        discovering = false
    }
}
