package com.cczb.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire-compatible with firmware recovery_crypto. Not an authorization to reset Mesh. */
internal object RecoveryCrypto {
    const val MAX_PLAIN = 512
    private const val HEADER = 20
    private const val TAG = 16
    private val domain = "PUMP-RECOVERY-V1".toByteArray(Charsets.US_ASCII)

    class Challenge(device: ByteArray, val credentialEpoch: Long, clientNonce: ByteArray, deviceNonce: ByteArray) {
        private val device = device.copyOf()
        private val clientNonce = clientNonce.copyOf()
        private val deviceNonce = deviceNonce.copyOf()

        init {
            require(device.size == 16 && device.any { it != 0.toByte() })
            require(credentialEpoch in 1..0xffffffffL)
            require(clientNonce.size == 32 && clientNonce.any { it != 0.toByte() })
            require(deviceNonce.size == 32 && deviceNonce.any { it != 0.toByte() })
            require(!MessageDigest.isEqual(clientNonce, deviceNonce))
        }

        fun transcript(): ByteArray = ByteBuffer.allocate(102).order(ByteOrder.LITTLE_ENDIAN)
            .put(domain).putShort(1).put(device).putInt(credentialEpoch.toInt())
            .put(clientNonce).put(deviceNonce).array()
    }

    fun proof(key: ByteArray, challenge: Challenge, device: Boolean): ByteArray {
        requireKey(key)
        val label = if (device) "device-proof" else "client-proof"
        return hmac(key, label.toByteArray(Charsets.US_ASCII), challenge.transcript())
    }

    /** Validate the peer proof before returning a session. Nonces must be fresh for each connection. */
    fun authenticate(client: Boolean, key: ByteArray, challenge: Challenge, peerProof: ByteArray): Session {
        val expected = proof(key, challenge, device = client)
        val valid = MessageDigest.isEqual(expected, peerProof)
        expected.fill(0)
        if (!valid) throw SecurityException("Recovery authentication failed")
        val salt = MessageDigest.getInstance("SHA-256").digest(challenge.transcript())
        val keys = hkdf(key, salt, "PUMP-RECOVERY-V1/session-keys".toByteArray(Charsets.US_ASCII), 72)
        return try {
            Session(client, keys.copyOfRange(if (client) 0 else 32, if (client) 32 else 64),
                keys.copyOfRange(if (client) 32 else 0, if (client) 64 else 32), keys.copyOfRange(64, 72))
        } finally { keys.fill(0) }
    }

    /** RFC 5869 HKDF-SHA256; no platform-specific HKDF API required on Android 11. */
    internal fun hkdf(key: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
        require(size in 1..(255 * 32))
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, key)
        val output = ByteArray(size)
        var previous = ByteArray(0)
        try {
            var offset = 0
            var counter = 1
            while (offset < size) {
                val next = hmac(prk, previous, info, byteArrayOf(counter.toByte()))
                previous.fill(0)
                previous = next
                val count = minOf(32, size - offset)
                previous.copyInto(output, offset, 0, count)
                offset += count
                counter++
            }
            return output
        } finally { prk.fill(0); previous.fill(0) }
    }

    private fun requireKey(key: ByteArray) = require(key.size == 32 && key.any { it != 0.toByte() })
    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { update(it) }
        doFinal()
    }

    /** One connection only. The transport must close on disconnect, timeout or connection replacement. */
    class Session internal constructor(
        private val client: Boolean, private val txKey: ByteArray,
        private val rxKey: ByteArray, private val id: ByteArray
    ) : AutoCloseable {
        private var tx = 1uL
        private var rx = 1uL
        private var ready = true

        @Synchronized fun seal(plain: ByteArray): ByteArray {
            check(ready) { "Recovery session closed" }
            require(plain.size in 1..MAX_PLAIN)
            if (tx == ULong.MAX_VALUE) { close(); error("Recovery sequence exhausted") }
            val direction = if (client) 0 else 1
            val sequence = tx++
            val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
                .put(1).put(direction.toByte()).putShort(plain.size.toShort()).put(id).putLong(sequence.toLong()).array()
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(txKey, "AES"), GCMParameterSpec(128, nonce(direction, sequence)))
                cipher.updateAAD(header)
                header + cipher.doFinal(plain)
            } catch (error: Exception) { close(); throw error }
        }

        @Synchronized fun open(frame: ByteArray): ByteArray {
            check(ready) { "Recovery session closed" }
            require(frame.size in (HEADER + TAG + 1)..(HEADER + TAG + MAX_PLAIN)) { "Invalid recovery frame" }
            val direction = if (client) 1 else 0
            val header = ByteBuffer.wrap(frame, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
            val version = header.get().toInt()
            val actualDirection = header.get().toInt()
            val length = header.short.toInt() and 0xffff
            val actualId = ByteArray(8).also { header.get(it) }
            val sequence = header.long.toULong()
            if (version != 1 || actualDirection != direction || length != frame.size - HEADER - TAG ||
                !MessageDigest.isEqual(id, actualId) || sequence != rx || rx == ULong.MAX_VALUE)
                throw SecurityException("Invalid recovery frame context")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rxKey, "AES"), GCMParameterSpec(128, nonce(direction, sequence)))
            cipher.updateAAD(frame, 0, HEADER)
            // A bad tag must not consume the sequence of a later valid frame.
            val plain = cipher.doFinal(frame, HEADER, frame.size - HEADER)
            rx++
            return plain
        }

        @Synchronized override fun close() {
            txKey.fill(0); rxKey.fill(0); id.fill(0)
            ready = false
        }

        private fun nonce(direction: Int, sequence: ULong): ByteArray = ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN).put('R'.code.toByte()).put('E'.code.toByte())
            .put('C'.code.toByte()).put(direction.toByte()).putLong(sequence.toLong()).array()
    }
}
