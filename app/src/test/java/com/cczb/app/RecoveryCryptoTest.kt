package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class RecoveryCryptoTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val uuid = ByteArray(16) { (it + 97).toByte() }
    private val clientNonce = ByteArray(32) { (it + 33).toByte() }
    private val deviceNonce = ByteArray(32) { (it + 65).toByte() }
    private fun challenge(epoch: Long = 7) = RecoveryCrypto.Challenge(uuid, epoch, clientNonce, deviceNonce)
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch (_: Exception) { /* expected */ }
    }
    private fun session(client: Boolean) = RecoveryCrypto.authenticate(client, key, challenge(),
        RecoveryCrypto.proof(key, challenge(), device = client))

    @Test fun matchesFirmwareMbedtlsVectors() {
        assertArrayEquals("4fac37f59d3841feabc48f5fb9a2b494dc482648846667a6d52ecce895700596".unhex(), RecoveryCrypto.proof(key, challenge(), true))
        assertArrayEquals("78ca064fae5669a7bd97813d419b082fabdd673cb715d28900ec77f76783d261".unhex(), RecoveryCrypto.proof(key, challenge(), false))
        val vector = "010011002787b23e00435ac601000000000000002356b9dc6417b85afff7b219aa640b99aabeae9773c67ae5b3f09d5006f80d6cad".unhex()
        val plain = ByteArray(17) { it.toByte() }
        session(true).use { assertArrayEquals(vector, it.seal(plain)) }
        session(false).use { assertArrayEquals(plain, it.open(vector)) }
    }

    @Test fun matchesRfc5869CaseOne() {
        assertArrayEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865".unhex(),
            RecoveryCrypto.hkdf(ByteArray(22) { 0x0b }, "000102030405060708090a0b0c".unhex(), "f0f1f2f3f4f5f6f7f8f9".unhex(), 42))
    }

    @Test fun everyByteAuthenticatedAndReplayAndReflectionRejected() {
        session(true).use { client -> session(false).use { device ->
            val plain = ByteArray(512) { it.toByte() }
            val frame = client.seal(plain)
            assertEquals(548, frame.size)
            frame.indices.forEach { i ->
                val bad = frame.copyOf(); bad[i] = (bad[i].toInt() xor 1).toByte()
                rejected { device.open(bad) }
            }
            rejected { client.open(frame) }
            assertArrayEquals(plain, device.open(frame))
            rejected { device.open(frame) }
            assertArrayEquals(plain, client.open(device.seal(plain)))
            val second = client.seal(byteArrayOf(1))
            val third = client.seal(byteArrayOf(2))
            rejected { device.open(third) }
            assertArrayEquals(byteArrayOf(1), device.open(second))
            assertArrayEquals(byteArrayOf(2), device.open(third))
        } }
    }

    @Test fun transcriptRolesCredentialsAndSessionReplacementAreBound() {
        val proof = RecoveryCrypto.proof(key, challenge(), true)
        rejected { RecoveryCrypto.authenticate(true, key, challenge(8), proof) }
        val otherKey = key.copyOf().also { it[0] = 99 }
        rejected { RecoveryCrypto.authenticate(true, otherKey, challenge(), proof) }
        rejected { RecoveryCrypto.authenticate(false, key, challenge(), proof) }
        val variants = listOf(
            RecoveryCrypto.Challenge(uuid.copyOf().also { it[0] = 1 }, 7, clientNonce, deviceNonce),
            RecoveryCrypto.Challenge(uuid, 7, clientNonce.copyOf().also { it[0] = 1 }, deviceNonce),
            RecoveryCrypto.Challenge(uuid, 7, clientNonce, deviceNonce.copyOf().also { it[0] = 1 }))
        variants.forEach { changed -> rejected { RecoveryCrypto.authenticate(true, key, changed, proof) } }
        rejected { RecoveryCrypto.authenticate(true, key, challenge(), ByteArray(32)) }
        session(true).use { old ->
            val frame = old.seal(byteArrayOf(7))
            val changed = variants.last()
            RecoveryCrypto.authenticate(false, key, changed, RecoveryCrypto.proof(key, changed, false)).use {
                rejected { it.open(frame) }
            }
            old.close()
            rejected { old.seal(byteArrayOf(7)) }
            rejected { old.open(frame) }
        }
    }

    @Test fun malformedInputAndSharedMutableChallengeRejected() {
        rejected { RecoveryCrypto.Challenge(uuid, 0, clientNonce, deviceNonce) }
        rejected { RecoveryCrypto.Challenge(uuid, 0x100000000, clientNonce, deviceNonce) }
        rejected { RecoveryCrypto.Challenge(uuid, 7, clientNonce, clientNonce) }
        rejected { RecoveryCrypto.proof(ByteArray(32), challenge(), true) }
        val copy = uuid.copyOf()
        val c = RecoveryCrypto.Challenge(copy, 7, clientNonce, deviceNonce)
        copy.fill(0)
        assertArrayEquals(challenge().transcript(), c.transcript())
        session(true).use { client ->
            rejected { client.seal(ByteArray(0)) }
            rejected { client.seal(ByteArray(513)) }
            rejected { client.open(ByteArray(36)) }
            rejected { client.open(ByteArray(549)) }
        }
    }
}
