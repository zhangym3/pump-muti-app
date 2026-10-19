package com.cczb.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeRulesTest {
    @Test fun onlyFiftyMillilitreMultiplesWithinBoundsAreValid() {
        assertTrue(VolumeRules.isValid(50))
        assertTrue(VolumeRules.isValid(2000))
        assertFalse(VolumeRules.isValid(1))
        assertFalse(VolumeRules.isValid(225))
        assertFalse(VolumeRules.isValid(2001))
    }

    @Test fun buttonStepsCorrectInvalidDraftsAndRespectBounds() {
        assertEquals(200, VolumeRules.previous(223))
        assertEquals(250, VolumeRules.next(223))
        assertEquals(50, VolumeRules.previous(50))
        assertEquals(100, VolumeRules.next(50))
        assertEquals(1950, VolumeRules.previous(2000))
        assertEquals(2000, VolumeRules.next(2000))
        assertEquals(1950, VolumeRules.previous(Long.MAX_VALUE))
        assertEquals(100, VolumeRules.next(Long.MIN_VALUE))
    }

    @Test fun startCommandWhitelistRequiresFiftyMillilitreMultiple() {
        assertTrue(MasterProtocol.isAllowedCommand("start a1 1 7 00000001 250 60000"))
        assertFalse(MasterProtocol.isAllowedCommand("start a1 1 7 00000001 225 60000"))
        assertFalse(MasterProtocol.isAllowedCommand("start a1 1 7 00000001 1 60000"))
    }
}
