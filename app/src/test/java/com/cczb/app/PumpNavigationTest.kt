package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class PumpNavigationTest {
    @Test fun backOnAnotherTabNeverClosesHiddenDevicePanel() {
        val state = PumpNavigation().openPump().select(PumpTab.SETTINGS)
        val back = state.back()!!
        assertEquals(PumpTab.CONTROL, back.tab)
        assertTrue(back.pumpExpanded)
        assertNull(back.back())
    }

    @Test fun deviceBackCollapsesTestBeforeReturningToControl() {
        val expanded = PumpNavigation().openPump()
        val collapsed = expanded.back()!!
        assertEquals(PumpTab.DEVICES, collapsed.tab)
        assertFalse(collapsed.pumpExpanded)
        assertEquals(PumpTab.CONTROL, collapsed.back()!!.tab)
    }

    @Test fun recordBackCollapsesVisibleDetailsAndRetainsDevicePanel() {
        val expanded = PumpNavigation().openPump().select(PumpTab.RECORDS).toggleRecord("master/a1/7")
        val collapsed = expanded.back()!!
        assertEquals(PumpTab.RECORDS, collapsed.tab)
        assertNull(collapsed.recordKey)
        assertTrue(collapsed.pumpExpanded)
        assertEquals(PumpTab.CONTROL, collapsed.back()!!.tab)
    }

    @Test fun revisitingTabKeepsExpandedRecordAndTappingItAgainClosesIt() {
        val selected = PumpNavigation(tab = PumpTab.RECORDS).toggleRecord("master/a1/7")
        val returned = selected.select(PumpTab.CONTROL).select(PumpTab.RECORDS)
        assertEquals(selected, returned)
        assertNull(returned.toggleRecord("master/a1/7").recordKey)
        assertEquals("master/a1/8", returned.toggleRecord("master/a1/8").recordKey)
    }
}
