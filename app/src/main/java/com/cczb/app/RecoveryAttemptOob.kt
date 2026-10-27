package com.cczb.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Same immutable attempt context as the firmware. A commitment is not authorization. */
internal object RecoveryAttemptOob {
    fun commitment(operation: ByteArray, attempt: Long, device: ByteArray, credentialEpoch: Long,
                   master: ByteArray, network: ByteArray, binding: ByteArray,
                   generation: ULong, oob: ByteArray): ByteArray {
        listOf(operation, device, master, network, binding, oob).forEach {
            require(it.size == 16 && it.any { byte -> byte != 0.toByte() })
        }
        require(attempt in 1..0xffffffffL && credentialEpoch in 1..0xffffffffL && generation != 0uL)
        val domain = "PUMP-ATTEMPT-OOB-V1".toByteArray(Charsets.US_ASCII)
        val message = ByteBuffer.allocate(domain.size + 96 + 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(domain); put(operation); putInt(attempt.toInt()); put(device); putInt(credentialEpoch.toInt())
            put(master); put(network); put(binding); putLong(generation.toLong()); put(oob)
        }.array()
        return try { MessageDigest.getInstance("SHA-256").digest(message) }
        finally { message.fill(0) }
    }
}
