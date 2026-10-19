package com.cczb.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class MasterProtocolTest {
    private val task = MasterTask("fedcba9876543210", 42, 7, 0x80000001, 500, 60_000)
    private fun report(reply: String = "OK", phase: Int = 3, job: String = "7:1", client: String = task.client,
                       seq: Long = task.seq, mask: String = "80000001") = MasterProtocol.parse(
        "R client=$client seq=$seq reply=$reply job=$job phase=$phase mask=$mask done=00000000 failed=00000000 unknown=00000000"
    ) as MasterMessage.Report

    @Test fun notificationsReassembleEveryPossibleByteBoundaryAndMultipleLines() {
        val text = "HELLO role=PUMP_MASTER addr=0001 master=0001 epoch=7 mesh=1 commissioned=1 pump_fault=NONE\nV job=7:1 total_ml=123 measured=80000001\n"
        val bytes = text.toByteArray()
        for (split in 0..bytes.size) {
            val assembler = MasterLineAssembler()
            val lines = assembler.accept(bytes.copyOfRange(0, split)) + assembler.accept(bytes.copyOfRange(split, bytes.size))
            assertEquals(2, lines.size)
            assertTrue(MasterProtocol.parse(lines[0]) is MasterMessage.Hello)
            assertTrue(MasterProtocol.parse(lines[1]) is MasterMessage.Volume)
        }
        val unicode = "测试\n".toByteArray()
        val assembler = MasterLineAssembler()
        assertTrue(assembler.accept(unicode.copyOfRange(0, 1)).isEmpty())
        assertEquals(listOf("测试"), assembler.accept(unicode.copyOfRange(1, unicode.size)))
    }
    @Test fun overflowingOrDisconnectedPartialLineCannotBecomeAValidResponse() {
        val assembler = MasterLineAssembler(10)
        assertTrue(assembler.accept("xxxxxxxxxxxOK\n".toByteArray()).isEmpty())
        assembler.accept("old".toByteArray())
        assembler.reset()
        assertEquals(listOf("new"), assembler.accept("new\n".toByteArray()))
    }
    @Test fun profileCommandsPreserveBit32AndRejectLegacyAndLineInjection() {
        assertEquals("start fedcba9876543210 42 7 80000001 500 60000", task.command())
        assertTrue(MasterProtocol.isAllowedCommand(task.command()))
        assertTrue(MasterProtocol.isAllowedCommand("stop ${task.client} 43 7 7 1"))
        assertEquals("1, 32", MasterProtocol.slots(task.mask))
        listOf("PUMP:500", "STOP", "pin 123456", "hello\nlocal", "start a1 1 7 100000000 500 60000",
            "start a1 1 7 1 500 240001", "stop a1 1 7 0 0", "query 0 1").forEach {
            assertFalse(it, MasterProtocol.isAllowedCommand(it))
        }
    }
    @Test fun helloMustIdentifyACommissionedMasterInItsOwnMeshAddress() {
        val hello = MasterProtocol.parse("HELLO role=PUMP_MASTER addr=0001 master=0001 epoch=7 mesh=1 commissioned=1 pump_fault=NONE") as MasterMessage.Hello
        assertTrue(hello.ready)
        assertFalse(hello.copy(role = "VALVE_SLAVE").ready)
        assertFalse(hello.copy(address = 2).ready)
        assertFalse(hello.copy(fault = "BOOT_LOCK").ready)
        assertFalse(hello.copy(commissioned = false).ready)
    }
    @Test fun taskAcceptanceRequiresMatchingIdentitySequenceEpochAndSelectedNodes() {
        assertEquals(task, task.reconcile(report(client = "a1"), true))
        assertEquals(task, task.reconcile(report(seq = 41), true))
        assertEquals(task, task.reconcile(report(job = "6:1"), true))
        assertEquals(task, task.reconcile(report(mask = "00000001"), true))
        val accepted = task.reconcile(report(), true)
        assertEquals(MasterOutcome.ACCEPTED, accepted.outcome)
        assertEquals(MasterJobKey(7, 1), accepted.job)
        assertEquals(accepted, accepted.reconcile(report(job = "7:2"), false))
        assertEquals(MasterOutcome.FINISHED, accepted.reconcile(report(phase = 6), false).outcome)
    }
    @Test fun unknownQueryOrFailedStopNeverPermitsAnotherStart() {
        val unknown = report("UNKNOWN", phase = 0, job = "0:0", mask = "00000000")
        assertTrue(task.reconcile(unknown, false).unresolved)
        val notReady = report("NOT_READY", phase = 0, job = "0:0", mask = "00000000")
        assertTrue(task.reconcile(notReady, false).unresolved)
        assertFalse(task.reconcile(notReady, true).unresolved)
        val accepted = task.reconcile(report(), true)
        assertTrue(accepted.reconcile(report("STALE", phase = 0), false).unresolved)
        val finished = accepted.reconcile(report(phase = 6), false)
        assertEquals(finished, finished.reconcile(report(phase = 3), false))
    }
    @Test fun sequentialNodesRepeatActivePhasesWithoutEndingTheJob() {
        var current = task
        for (phase in listOf(1, 2, 3, 4, 5, 1, 2, 3, 4)) {
            current = current.reconcile(report(phase = phase), false)
            assertEquals(MasterOutcome.ACCEPTED, current.outcome)
            assertTrue(current.unresolved)
        }
        assertFalse(current.reconcile(report(phase = 6), false).unresolved)
    }
    @Test fun ledgerRoundTripRetainsUnknownRequestAcrossProcessDeathAndIsolatesMasters() {
        val ledger = MasterLedger(task.client, task.seq, mapOf("AA:BB:CC:DD:EE:01" to task,
            "AA:BB:CC:DD:EE:02" to task.copy(seq = 41, mask = 2)))
        val bytes = ByteArrayOutputStream().also(ledger::write).toByteArray()
        val restored = MasterLedger.read(ByteArrayInputStream(bytes))
        assertEquals(ledger, restored)
        assertTrue(restored.tasks.getValue("AA:BB:CC:DD:EE:01").unresolved)
        assertEquals(43L, restored.nextSequence())
    }
    @Test fun sequenceNeverWrapsAndCorruptedIdentityNeverSilentlyRegenerates() {
        assertThrows(IllegalStateException::class.java) { MasterLedger(task.client, 0xffff_ffffL).nextSequence() }
        assertThrows(Exception::class.java) { MasterLedger.read(ByteArrayInputStream("version=1\nclient=0\nsequence=0".toByteArray())) }
    }
    @Test fun malformedReportsAndCoverageOutsideSelectedMaskAreRejected() {
        assertNull(MasterProtocol.parse("R client=a1 seq=1 reply=OK job=7:1 phase=3 mask=00000001 done=00000002 failed=0 unknown=0"))
        assertNull(MasterProtocol.parse("R client=a1 seq=1 reply=OK job=7:0 phase=3 mask=1 done=0 failed=0 unknown=0"))
        assertNull(MasterProtocol.parse("V job=7:1 total_ml=4294967296 measured=1"))
        assertNull(MasterProtocol.parse("V job=7:1 total_ml=100 total_ml=200 measured=1"))
    }

    @Test fun flowSnapshotsCarryRevisionTerminalAndMeasurementMetadata() {
        val flow = MasterProtocol.parse("FLOW addr=0002 job=7:1 pulses=123 ml=12 phase=DONE revision=8 terminal=1 measured=1")
            as MasterMessage.Flow
        assertEquals(123L, flow.pulses)
        assertEquals(8L, flow.revision)
        assertTrue(flow.terminal)
        assertTrue(flow.measured)
        val reboot = MasterProtocol.parse("FLOW addr=0002 job=7:1 pulses=0 ml=0 phase=FAULT revision=9 terminal=1 measured=0")
            as MasterMessage.Flow
        assertFalse(reboot.measured)
        assertNull(MasterProtocol.parse("FLOW addr=0002 job=7:1 pulses=123 ml=12 phase=DONE revision=8 terminal=2 measured=1"))
    }
}
