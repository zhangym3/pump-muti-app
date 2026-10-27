package com.cczb.app

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class RecoveryMasterIdentityTest {
    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // P-256 generator/public scalar=1, synthetic data shared with actual SDK mbedTLS tests.
    private val public = ("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
        "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5").hex()
    private val trust = TrustedRecoveryMaster(ByteArray(16) { 9 }, 1, ByteArray(16) { 3 }, public)
    private val signature = ("51ce0be9d9175d6ec98d485a4d0950eac32b843e4443fb91bcc903a5cd7912d6" +
        "1a547c0d435c04e287e142181f3b4df16af94fd8645baa41cdfbb3060d72230a").hex()
    private val body = ("4d5052310100b000010101010101010101010101010101010700000002020202020202020202020202020202" +
        "698bea63dc44a344663ff1429aea10840303030303030303030303030303030304040404040404040404040404040404" +
        "2a00000000000000010000000505050505050505050505050505050505050505050505050505050505050505" +
        "06060606060606060606060606060606060606060606060606060606060606060f000000983a0000").hex()
    private fun expected(attempt: Long = 7, challenge: ByteArray = ByteArray(32) { 6 },
        master: TrustedRecoveryMaster = trust, generation: ULong = 42uL, budget: Long = 15000) =
        ExpectedRecoveryPreparation(master, ByteArray(16) { 1 }, attempt, ByteArray(16) { 2 },
            ByteArray(16) { 4 }, generation, 1, ByteArray(32) { 5 }, challenge, budget)

    @Test fun verifiesActualFirmwareVector() {
        assertArrayEquals("698bea63dc44a344663ff1429aea1084".hex(), trust.id)
        assertArrayEquals(body, expected().encode())
        assertTrue(RecoveryMasterIdentity.verifyPrepared(expected(), body, signature))
    }

    @Test fun rejectsEveryChangedByteAndSignature() {
        body.indices.forEach { i ->
            val wrong = body.copyOf(); wrong[i] = (wrong[i].toInt() xor 1).toByte()
            assertFalse(RecoveryMasterIdentity.verifyPrepared(expected(), wrong, signature))
        }
        signature.indices.forEach { i ->
            val wrong = signature.copyOf(); wrong[i] = (wrong[i].toInt() xor 1).toByte()
            assertFalse(RecoveryMasterIdentity.verifyPrepared(expected(), body, wrong))
        }
        assertFalse(RecoveryMasterIdentity.verifyPrepared(expected(), body + 0, signature))
        assertFalse(RecoveryMasterIdentity.verifyPrepared(expected(), body, signature.copyOf(63)))
    }

    @Test fun rejectsStaleAttemptChallengeWrongTargetAndInvalidBudgets() {
        assertFalse(RecoveryMasterIdentity.verifyPrepared(expected(attempt = 8), body, signature))
        assertFalse(RecoveryMasterIdentity.verifyPrepared(expected(challenge = ByteArray(32) { 7 }), body, signature))
        assertFalse(RecoveryMasterIdentity.verifyPrepared(expected(generation = 43uL), body, signature))
        assertFalse(RecoveryMasterIdentity.verifyPrepared(expected(budget = 60001), body, signature))
        val otherNetwork = TrustedRecoveryMaster(ByteArray(16) { 9 }, 1, ByteArray(16) { 8 }, public)
        assertFalse(RecoveryMasterIdentity.verifyPrepared(expected(master = otherNetwork), body, signature))
        assertFalse(RecoveryMasterIdentity.verifyProof(trust, ByteArray(32), signature))
    }

    @Test fun rejectsHighSAndNonP256PointsAndOwnsInputArrays() {
        val order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
        val high = order.subtract(BigInteger(1, signature.copyOfRange(32, 64))).toByteArray().takeLast(32).toByteArray()
        assertFalse(RecoveryMasterIdentity.verifyPrepared(expected(), body, signature.copyOfRange(0, 32) + high))
        val invalid = listOf(ByteArray(65), public.copyOf(64), public.copyOf().also { it[2] = 0 })
        invalid.forEach { bytes ->
            try { TrustedRecoveryMaster(ByteArray(16) { 9 }, 1, ByteArray(16) { 3 }, bytes); fail("invalid key accepted") }
            catch (_: IllegalArgumentException) { /* refused */ }
        }
        val copy = trust.publicKey(); copy.fill(0)
        val challenge = ByteArray(32) { 6 }; val operation = expected(challenge = challenge); challenge.fill(0)
        assertTrue(RecoveryMasterIdentity.verifyPrepared(operation, body, signature))
    }
}
