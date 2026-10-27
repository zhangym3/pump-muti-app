package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class RecoveryAttemptOobTest {
    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val oob = ByteArray(16) { (0x80 + it).toByte() }
    private val public = ("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
        "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5").hex()
    private fun commitment(operation: ByteArray = ByteArray(16) { 1 }, attempt: Long = 7,
        device: ByteArray = ByteArray(16) { 2 }, epoch: Long = 1, master: ByteArray = ByteArray(16) { 3 },
        network: ByteArray = ByteArray(16) { 4 }, binding: ByteArray = ByteArray(16) { 5 },
        generation: ULong = 42uL, secret: ByteArray = oob) =
        RecoveryAttemptOob.commitment(operation, attempt, device, epoch, master, network, binding, generation, secret)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Invalid OOB context accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun matchesIndependentPythonAndFirmwareVector() {
        val expected = "7f3fc9ce23dabe9b7d65893a46b93333a0a84292f8872e686e7616e177f5c033".hex()
        assertArrayEquals(expected, commitment())
        assertArrayEquals(ByteArray(16) { (0x80 + it).toByte() }, oob)
    }

    @Test fun everyImmutableFieldAndOobByteChangesCommitment() {
        val original = commitment()
        val variants = listOf(commitment(operation = ByteArray(16) { 9 }), commitment(attempt = 8),
            commitment(device = ByteArray(16) { 9 }), commitment(epoch = 2),
            commitment(master = ByteArray(16) { 9 }), commitment(network = ByteArray(16) { 9 }),
            commitment(binding = ByteArray(16) { 9 }), commitment(generation = 43uL)) +
            oob.indices.map { index -> commitment(secret = oob.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }) }
        variants.forEach { assertFalse(original.contentEquals(it)) }
    }

    @Test fun zeroShortOversizedAndOutOfRangeContextRejected() {
        rejected { commitment(operation = ByteArray(16)) }
        rejected { commitment(device = ByteArray(15)) }
        rejected { commitment(master = ByteArray(17) { 1 }) }
        rejected { commitment(network = ByteArray(16)) }
        rejected { commitment(binding = ByteArray(16)) }
        rejected { commitment(secret = ByteArray(16)) }
        rejected { commitment(secret = ByteArray(32) { 1 }) }
        listOf(0L, -1L, 0x100000000L).forEach { value ->
            rejected { commitment(attempt = value) }; rejected { commitment(epoch = value) }
        }
        rejected { commitment(generation = 0uL) }
        assertEquals(32, commitment(attempt = 0xffffffffL, epoch = 0xffffffffL, generation = ULong.MAX_VALUE).size)
    }

    @Test fun expectedReceiptBindsOobWithoutRetainingCallerArrays() {
        val trust = TrustedRecoveryMaster(ByteArray(16) { 9 }, 1, ByteArray(16) { 4 }, public)
        val secret = oob.copyOf()
        val expected = ExpectedRecoveryPreparation.withAttemptOob(trust, ByteArray(16) { 1 }, 7,
            ByteArray(16) { 2 }, ByteArray(16) { 5 }, 42uL, 1, secret, ByteArray(32) { 6 }, 15000)
        val body = expected.encode()
        assertArrayEquals(commitment(master = trust.id), body.copyOfRange(104, 136))
        assertTrue(expected.matchesAttemptOob(secret))
        secret.fill(0)
        assertFalse(expected.matchesAttemptOob(secret))
        assertTrue(expected.matchesAttemptOob(oob))
        assertArrayEquals(body, expected.encode())
        rejected { ExpectedRecoveryPreparation.withAttemptOob(trust, ByteArray(16) { 1 }, 7,
            ByteArray(16) { 2 }, ByteArray(16) { 5 }, 42uL, 1, oob, ByteArray(32), 15000) }
    }
}
