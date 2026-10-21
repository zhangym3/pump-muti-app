package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class MasterCalibrationTest {
    private val line = "CAL epoch=7 req=42 slot=1 addr=0002 rev=0 bk=5880 bv=1 low=5880 mid=5880 high=5880 reply=OK"
    private val snapshot = MasterMessage.Calibration(7, 42, 1, 2, 0, 5880, 5880, 5880, "OK", 5880, 1)
    private val selected = MasterCalibration(slot = 1, address = 2)
    private val reading = selected.copy(pending = CalibrationPending(7, 42, 1, 2))
    private val saved = reading.receive(snapshot, 7)
    private val values = listOf(6000L, 6200L, 6500L)
    private val saving = saved.copy(pending = CalibrationPending(7, 43, 1, 2, 0, values))
    private val confirmation = snapshot.copy(request = 43, revision = 1, low = 6000, medium = 6200, high = 6500)
    private val ready = MasterUiState(connection = MasterConnectionState.Connected,
        hello = MasterMessage.Hello("PUMP_MASTER", 1, 1, 7, true, true, "NONE", calibration = true),
        statusFresh = true, slaves = listOf(MasterSlave(1, 2, true, true)), inventoryFresh = true,
        local = MasterMessage.Local(false, 0, "NONE"), localFresh = true, calibration = saved)

    @Test fun decimalCoefficientsRetainExactThousandthsAndBounds() {
        assertEquals(5880L, FlowCalibration.parse("5.880"))
        assertEquals(50L, FlowCalibration.parse("0.05"))
        assertEquals(1L, FlowCalibration.parse("0.001"))
        assertEquals(1_000_000L, FlowCalibration.parse("1000.000"))
        assertEquals("5.880", FlowCalibration.display(5880))
        listOf("0", "0.000", "1000.001", "-1", "1.0001", "1e1", "5,880", "", "NaN").forEach {
            assertNull(it, FlowCalibration.parse(it))
        }
    }

    @Test fun measuredPulsesAndCupVolumeCalculateKWithRoundingAndNoZeroSamples() {
        assertEquals(5880L, FlowCalibration.calculate("2940", "500"))
        assertEquals(6000L, FlowCalibration.calculate("2940", "490.000"))
        assertEquals(1333L, FlowCalibration.calculate("4", "3"))
        assertEquals(1334L, FlowCalibration.calculate("2667", "2000"))
        assertEquals(1L, FlowCalibration.calculate("1", "1000"))
        listOf("0" to "500", "2940" to "0", "0" to "0", "1.5" to "10", "1" to "-1",
            "4294967296" to "500", "1" to "1000000", "1001" to "1", "1" to "0.0001",
            "" to "500", "1" to "1000001").forEach { (pulses, ml) ->
            assertNull("$pulses/$ml", FlowCalibration.calculate(pulses, ml))
        }
    }

    @Test fun wireSnapshotRequiresIdentityAllThreeKAndBaseConfiguration() {
        assertEquals(snapshot, MasterProtocol.parse(line))
        listOf(line.replace("epoch=7", "epoch=0"), line.replace("req=42", "req=0"),
            line.replace("slot=1", "slot=33"), line.replace("addr=0002", "addr=8000"),
            line.replace("rev=0", "rev=4294967296"), line.replace("low=5880", "low=0"),
            line.replace("mid=5880", "mid=1000001"), line.replace("high=5880", "high=-1"),
            line.replace("bk=5880", "bk=0"), line.replace("bv=1", "bv=0"),
            line.replace("reply=OK", "reply=BOGUS"), line.replace(" bk=5880", ""),
            line + " low=6000").forEach { assertNull(it, MasterProtocol.parse(it)) }
        val oldHello = "HELLO role=PUMP_MASTER addr=0001 master=0001 epoch=7 mesh=1 commissioned=1 pump_fault=NONE"
        assertFalse((MasterProtocol.parse(oldHello) as MasterMessage.Hello).calibration)
        assertTrue((MasterProtocol.parse("$oldHello calibration=1") as MasterMessage.Hello).calibration)
        assertNull(MasterProtocol.parse("$oldHello calibration=2"))
    }

    @Test fun whitelistChecksExactCalSetFieldOrderAndAllBounds() {
        val command = "cal-set 7 43 1 0002 0 5880 1 6000 6200 6500"
        listOf("cal-get 7 42 1 0002", command,
            "cal-set 4294967295 4294967295 32 7fff 4294967295 1000000 4294967295 1 1000000 1000000").forEach {
            assertTrue(it, MasterProtocol.isAllowedCommand(it))
            assertTrue(it.toByteArray().size <= 159)
        }
        listOf("cal-get 0 42 1 2", "cal-get 7 0 1 2", "cal-get 7 42 0 2", "cal-get 7 42 33 2",
            "cal-get 7 42 1 8000", "cal-get 7 42 1 2 extra", "cal-get 7 4294967296 1 2",
            command.replace("0002 0", "0002 -1"), command.replace("5880 1", "0 1"),
            command.replace("5880 1", "5880 0"), command.replace("6000 6200", "0 6200"),
            command.replace("6200 6500", "6200 1000001"), command + " extra", command + "\nlocal").forEach {
            assertFalse(it, MasterProtocol.isAllowedCommand(it))
        }
    }

    @Test fun fragmentedLongestSnapshotFitsGatewayLineAndParsesAfterNewline() {
        val longest = "CAL epoch=4294967295 req=4294967295 slot=32 addr=7fff rev=4294967295 bk=1000000 bv=4294967295 low=1000000 mid=1000000 high=1000000 reply=UNAUTHORIZED"
        assertTrue(longest.toByteArray().size <= 191)
        val assembler = MasterLineAssembler()
        assertTrue(assembler.accept(longest.take(50).toByteArray()).isEmpty())
        val lines = assembler.accept((longest.drop(50) + "\n").toByteArray())
        assertEquals(listOf(longest), lines)
        assertNotNull(MasterProtocol.parse(lines.single()))
    }

    @Test fun staleForeignAndUnsolicitedRepliesNeverResolvePendingRead() {
        assertEquals(selected, selected.receive(snapshot, 7))
        listOf(snapshot.copy(epoch = 8), snapshot.copy(request = 41), snapshot.copy(slot = 2),
            snapshot.copy(address = 3)).forEach { assertEquals(reading, reading.receive(it, 7)) }
        assertEquals(reading, reading.receive(snapshot, 8))
        assertNull(saved.pending)
        assertEquals(snapshot, saved.snapshot)
    }

    @Test fun saveNeedsExactValuesNextRevisionAndUnchangedBase() {
        assertEquals(snapshot, saving.snapshot)
        assertTrue(saving.receive(confirmation, 7).message.contains("主机已保存"))
        assertNull(saving.receive(confirmation, 7).pending)
        listOf(confirmation.copy(revision = 0), confirmation.copy(revision = 2),
            confirmation.copy(low = 6001), confirmation.copy(baseK = 6000),
            confirmation.copy(baseVersion = 2)).forEach {
            val result = saving.receive(it, 7)
            assertNull(result.snapshot)
            assertNull(result.pending)
            assertFalse(result.message.contains("主机已保存"))
        }
        assertEquals(saving, saving.receive(confirmation.copy(request = 42), 7))
    }

    @Test fun errorsAndUncertainResultsRequireReadBeforeNextSave() {
        for (reply in listOf("BUSY", "STALE", "STORAGE", "NOT_READY", "UNAUTHORIZED")) {
            val next = saving.receive(confirmation.copy(reply = reply), 7)
            assertNull(next.pending)
            assertFalse(ready.copy(calibration = next).canSaveCalibration)
            if (reply == "STORAGE") assertNull(next.snapshot)
        }
        val timeout = saving.uncertain("超时，请重新读取")
        assertNull(timeout.pending)
        assertNull(timeout.snapshot)
        assertEquals(timeout, timeout.receive(confirmation, 7))
        assertFalse(ready.copy(calibration = timeout).canSaveCalibration)
    }

    @Test fun saveRequiresSupportedCurrentMasterExactNodeAndIdleState() {
        assertTrue(ready.canReadCalibration)
        assertTrue(ready.canSaveCalibration)
        listOf(ready.copy(connection = MasterConnectionState.Disconnected), ready.copy(statusFresh = false),
            ready.copy(inventoryFresh = false), ready.copy(localFresh = false), ready.copy(storageHealthy = false),
            ready.copy(hello = ready.hello!!.copy(calibration = false)),
            ready.copy(hello = ready.hello!!.copy(epoch = 8)), ready.copy(calibration = selected),
            ready.copy(slaves = listOf(MasterSlave(1, 3, true, true))), ready.copy(meshPending = true),
            ready.copy(local = ready.local!!.copy(pumpCommand = true)),
            ready.copy(local = ready.local!!.copy(testActive = true)),
            ready.copy(task = MasterTask("a1", 1, 7, 1, 500, 60_000)),
            ready.copy(calibration = saved.copy(address = 3), slaves = listOf(MasterSlave(1, 3, true, true))),
            ready.copy(calibration = saved.copy(snapshot = snapshot.copy(revision = 0xffff_ffffL)))).forEach {
            assertFalse(it.toString(), it.canSaveCalibration)
        }
    }

    @Test fun pendingSaveBlocksStartSpeedAndPumpTestWhileKeepingStopAvailable() {
        val pending = ready.copy(calibration = saving)
        assertFalse(pending.canSaveCalibration)
        assertFalse(pending.canReadCalibration)
        assertFalse(pending.canStart)
        assertFalse(pending.canSetSpeed)
        assertFalse(pending.canStartTest)
        val task = MasterTask("a1", 1, 7, 1, 500, 60_000,
            outcome = MasterOutcome.ACCEPTED, job = MasterJobKey(7, 1))
        assertTrue(pending.copy(task = task).canStop)
    }
}
