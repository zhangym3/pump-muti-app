package com.cczb.app

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import org.junit.Assert.*
import org.junit.Test

class RecoveryMasterTrustTest {
    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val public = ("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
        "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5").hex()
    private val signature = ("ad54eabaa39ee7456bf9a8add25ecb43cc596f8374ba7cc4228379c09413555a" +
        "304c6b1cbac2d3e300c545ff2a9b03bad627e67d57ed4133182611bef5c8e570").hex()
    private fun bytes(epoch: Int = 1, device: ByteArray = ByteArray(16) { 9 }) =
        ByteBuffer.allocate(225).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PMMSTR01".toByteArray(Charsets.US_ASCII)); putShort(1); putShort(225)
            put(device); putInt(epoch); put(ByteArray(16) { 3 })
            put("698bea63dc44a344663ff1429aea1084".hex()); put(public); put(ByteArray(32) { 6 }); put(signature)
        }.array()
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid trust rejection") } catch (_: Exception) { }
    }

    @Test fun sdkProofInteroperatesAndRejectsEveryChangedByte() {
        val bytes = bytes()
        val record = RecoveryMasterTrustFile.decode(bytes)
        assertTrue(RecoveryMasterIdentity.verifyProof(record.master, ByteArray(32) { 6 }, signature))
        assertFalse(RecoveryMasterIdentity.verifyProof(record.master, ByteArray(32) { 7 }, signature))
        assertEquals("698bea63dc44a344663ff1429aea1084", record.fingerprint)
        assertEquals("09090909090909090909090909090909", record.deviceId)
        bytes.indices.forEach { i ->
            rejected { RecoveryMasterTrustFile.decode(bytes.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }) }
            rejected { RecoveryMasterTrustFile.decode(bytes.copyOf(i)) }
        }
        rejected { RecoveryMasterTrustFile.decode(bytes + 0) }
    }

    @Test fun ownsSourceArraysAndDuplicateImportsDoNotReplaceProof() {
        val source = bytes()
        val record = RecoveryMasterTrustFile.decode(source)
        source.fill(0)
        record.encode().fill(0)
        assertArrayEquals(bytes(), record.encode())
        val list = RecoveryMasterTrustCodec.merge(emptyList(), record)
        assertEquals(1, RecoveryMasterTrustCodec.merge(list, RecoveryMasterTrustFile.decode(bytes())).size)
        assertArrayEquals(record.encode(), RecoveryMasterTrustCodec.decode(RecoveryMasterTrustCodec.encode(list)).single().encode())
    }

    // Produce a second valid proof with the public synthetic private scalar=1.
    // It is valid cryptography, yet an epoch/key/device conflict must still be refused.
    private fun signed(source: ByteArray): RecoveryMasterTrustFile {
        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val key = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger.ONE, parameters))
        val message = "PUMP-MASTER-PROOF-V1".toByteArray(Charsets.US_ASCII) + source.copyOfRange(12, 161)
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(message); sign() }
        var at = 2
        fun integer(): BigInteger {
            check(der[at++].toInt() == 2)
            val length = der[at++].toInt()
            return BigInteger(der.copyOfRange(at, at + length)).also { at += length }
        }
        val r = integer(); val s = integer()
        val order = parameters.order
        fun BigInteger.raw() = (ByteArray(32) + toByteArray()).takeLast(32).toByteArray()
        val raw = r.raw() + (if (s > order.shiftRight(1)) order.subtract(s) else s).raw()
        return RecoveryMasterTrustFile.decode(source.copyOf().also { raw.copyInto(it, 161) })
    }

    @Test fun refusesValidButConflictingDeviceNetworkAndEpoch() {
        val installed = listOf(RecoveryMasterTrustFile.decode(bytes()))
        val conflicts = listOf(bytes(epoch = 2), bytes(device = ByteArray(16) { 10 }),
            bytes().also { it[32] = 4 })
        conflicts.forEach { source -> rejected { RecoveryMasterTrustCodec.merge(installed, signed(source)) } }
        assertArrayEquals(bytes(), installed.single().encode())
    }

    @Test fun boundedCanonicalStorageRejectsDuplicatesAndTrailingBytes() {
        val record = RecoveryMasterTrustFile.decode(bytes())
        rejected { RecoveryMasterTrustCodec.encode(listOf(record, record)) }
        val encoded = RecoveryMasterTrustCodec.encode(listOf(record))
        rejected { RecoveryMasterTrustCodec.decode(encoded + 0) }
        rejected { RecoveryMasterTrustCodec.decode(encoded.copyOf().also { it[6] = 127 }) }
        rejected { RecoveryMasterTrustCodec.decode(ByteArray(RecoveryMasterTrustCodec.MAX_BYTES + 1)) }
        assertTrue(RecoveryMasterTrustCodec.decode(RecoveryMasterTrustCodec.encode(emptyList())).isEmpty())
    }
}
