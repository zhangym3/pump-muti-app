package com.cczb.app

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/** Public trust material obtained by a trusted installation, never by first BLE discovery. */
internal class TrustedRecoveryMaster(deviceUuid: ByteArray, val epoch: Long, networkId: ByteArray, publicKey: ByteArray) {
    private val device = deviceUuid.copyOf()
    private val network = networkId.copyOf()
    private val key = publicKey.copyOf()
    val id: ByteArray get() = MessageDigest.getInstance("SHA-256").digest(key).copyOf(16)
    init {
        require(device.size == 16 && device.any { it != 0.toByte() })
        require(network.size == 16 && network.any { it != 0.toByte() })
        require(epoch in 1..0xffffffffL)
        RecoveryMasterIdentity.publicKey(key)
    }
    fun deviceUuid() = device.copyOf()
    fun networkId() = network.copyOf()
    fun publicKey() = key.copyOf()
}

/** Expected fields come from the persisted APP operation, not the received receipt. */
internal class ExpectedRecoveryPreparation(
    val master: TrustedRecoveryMaster, operation: ByteArray, val attempt: Long,
    device: ByteArray, binding: ByteArray, val generation: ULong, val credentialEpoch: Long,
    oobCommitment: ByteArray, challenge: ByteArray, val budgetMs: Long
) {
    private val operation = operation.copyOf()
    private val device = device.copyOf()
    private val binding = binding.copyOf()
    private val commitment = oobCommitment.copyOf()
    private val challenge = challenge.copyOf()
    fun matchesAttemptOob(oob: ByteArray): Boolean = try {
        MessageDigest.isEqual(commitment, RecoveryAttemptOob.commitment(operation, attempt, device,
            credentialEpoch, master.id, master.networkId(), binding, generation, oob))
    } catch (_: IllegalArgumentException) { false }

    companion object {
        // Computes the commitment before the expected receipt is persisted or
        // sent. The caller still owns/wipes the OOB; this object never retains it.
        fun withAttemptOob(master: TrustedRecoveryMaster, operation: ByteArray, attempt: Long,
                           device: ByteArray, binding: ByteArray, generation: ULong,
                           credentialEpoch: Long, oob: ByteArray, challenge: ByteArray,
                           budgetMs: Long): ExpectedRecoveryPreparation =
            ExpectedRecoveryPreparation(master, operation, attempt, device, binding, generation,
                credentialEpoch, RecoveryAttemptOob.commitment(operation, attempt, device,
                    credentialEpoch, master.id, master.networkId(), binding, generation, oob), challenge, budgetMs)
                .also { it.encode() }
    }

    fun encode(): ByteArray {
        listOf(operation, device, binding).forEach { require(it.size == 16 && it.any { b -> b != 0.toByte() }) }
        listOf(commitment, challenge).forEach { require(it.size == 32 && it.any { b -> b != 0.toByte() }) }
        require(attempt in 1..0xffffffffL && credentialEpoch in 1..0xffffffffL)
        require(generation != 0uL && budgetMs in 1..60000)
        return ByteBuffer.allocate(176).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x3152504d); putShort(1); putShort(176)
            put(operation); putInt(attempt.toInt()); put(device); put(master.id); put(master.networkId()); put(binding)
            putLong(generation.toLong()); putInt(credentialEpoch.toInt()); put(commitment); put(challenge)
            putInt(15); putInt(budgetMs.toInt())
        }.array()
    }
}

internal object RecoveryMasterIdentity {
    private val order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val parameters: ECParameterSpec get() = AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp256r1"))
    }.getParameterSpec(ECParameterSpec::class.java)

    fun publicKey(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 4.toByte())
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val spec = parameters
        val p = (spec.curve.field as java.security.spec.ECFieldFp).p
        require(x < p && y < p)
        require(y.modPow(BigInteger.valueOf(2), p) == x.modPow(BigInteger.valueOf(3), p)
            .add(spec.curve.a.multiply(x)).add(spec.curve.b).mod(p))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
    }

    private fun verify(key: ByteArray, message: ByteArray, rawSignature: ByteArray): Boolean = try {
        require(rawSignature.size == 64)
        val r = BigInteger(1, rawSignature.copyOfRange(0, 32))
        val s = BigInteger(1, rawSignature.copyOfRange(32, 64))
        require(r.signum() > 0 && r < order && s.signum() > 0 && s <= order.shiftRight(1))
        val rb = r.toByteArray(); val sb = s.toByteArray()
        val der = byteArrayOf(0x30, (4 + rb.size + sb.size).toByte(), 2, rb.size.toByte()) + rb +
            byteArrayOf(2, sb.size.toByte()) + sb
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(key)); update(message); verify(der)
        }
    } catch (_: Exception) { false }

    fun verifyPrepared(expected: ExpectedRecoveryPreparation, body: ByteArray, signature: ByteArray): Boolean = try {
        body.size == 176 && MessageDigest.isEqual(expected.encode(), body) && verify(
            expected.master.publicKey(), "PUMP-MASTER-READY-V1".toByteArray(Charsets.US_ASCII) + body, signature)
    } catch (_: IllegalArgumentException) { false }

    fun verifyProof(trusted: TrustedRecoveryMaster, challenge: ByteArray, signature: ByteArray): Boolean {
        if (challenge.size != 32 || challenge.all { it == 0.toByte() }) return false
        val message = ByteBuffer.allocate(169).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PUMP-MASTER-PROOF-V1".toByteArray(Charsets.US_ASCII)); put(trusted.deviceUuid()); putInt(trusted.epoch.toInt())
            put(trusted.networkId()); put(trusted.id); put(trusted.publicKey()); put(challenge)
        }.array()
        return verify(trusted.publicKey(), message, signature)
    }
}
