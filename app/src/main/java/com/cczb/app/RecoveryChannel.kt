package com.cczb.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** An ATT connection owns exactly one channel. No automatic reconnect or retry. */
internal interface RecoveryAtt : AutoCloseable {
    suspend fun write(value: ByteArray)
    suspend fun read(): ByteArray
}

internal object RecoveryWire {
    val SERVICE: UUID = UUID.fromString("a64d1001-2f9c-4b6e-8db7-a570530e1000")
    val INFO: UUID = UUID.fromString("a64d1002-2f9c-4b6e-8db7-a570530e1000")
    val RX: UUID = UUID.fromString("a64d1003-2f9c-4b6e-8db7-a570530e1000")
    val TX: UUID = UUID.fromString("a64d1004-2f9c-4b6e-8db7-a570530e1000")
    const val MAX_MESSAGE = 549
    fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    fun info(bytes: ByteArray): ByteArray {
        require(bytes.size == 20 && u16(bytes, 0) == 1) { "Unsupported recovery discovery" }
        return bytes.copyOfRange(4, 20).also { require(it.any { b -> b != 0.toByte() }) }
    }
    fun fragment(id: Int, total: Int, offset: Int, payload: ByteArray): ByteArray {
        require(id in 1..65535 && total in 1..MAX_MESSAGE && offset in 0 until total)
        require(payload.size in 1..13 && payload.size <= total - offset)
        return ByteBuffer.allocate(7 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(1).putShort(id.toShort()).putShort(total.toShort()).putShort(offset.toShort()).put(payload).array()
    }
    fun pull(id: Int, offset: Int): ByteArray {
        require(id in 1..65535 && offset in 0 until MAX_MESSAGE)
        return ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(2).putShort(id.toShort()).putShort(offset.toShort()).array()
    }
}

/** Serial write-with-response and pull/read; every value fits ATT MTU 23. */
internal class RecoveryChannel(private val att: RecoveryAtt) : AutoCloseable {
    private val mutex = Mutex()
    @Volatile private var closed = false
    private var transfer = 0

    suspend fun exchange(message: ByteArray): ByteArray = mutex.withLock {
        check(!closed) { "Recovery channel closed" }
        require(message.size in 1..RecoveryWire.MAX_MESSAGE)
        try {
            check(transfer < 65535) { "Recovery transfer exhausted" }
            val id = ++transfer
            withTimeout(8_000) {
                // Firmware bounds the entire request assembly to three seconds.
                withTimeout(3_000) {
                    var offset = 0
                    while (offset < message.size) {
                        val end = minOf(offset + 13, message.size)
                        val part = RecoveryWire.fragment(id, message.size, offset, message.copyOfRange(offset, end))
                        try { att.write(part) } finally { part.fill(0) }
                        offset = end
                    }
                }
                var response: ByteArray? = null
                var offset = 0
                do {
                    att.write(RecoveryWire.pull(id, offset))
                    val part = att.read()
                    try {
                        require(part.size in 8..20 && part[0] == 1.toByte()) { "Invalid recovery fragment" }
                        val total = RecoveryWire.u16(part, 3)
                        require(RecoveryWire.u16(part, 1) == id && RecoveryWire.u16(part, 5) == offset &&
                            total in 1..RecoveryWire.MAX_MESSAGE && part.size - 7 <= total - offset) { "Recovery fragment context mismatch" }
                        val target = response ?: ByteArray(total).also { response = it }
                        require(target.size == total) { "Recovery response length changed" }
                        part.copyInto(target, offset, 7)
                        offset += part.size - 7
                    } finally { part.fill(0) }
                } while (offset < requireNotNull(response).size)
                requireNotNull(response)
            }
        } catch (error: Exception) { close(); throw error }
    }

    override fun close() {
        if (!closed) { closed = true; att.close() }
    }
}

internal data class RecoveryStatus(val schema: Int, val capabilities: Int, val credentialEpoch: Long) {
    companion object {
        fun parse(plain: ByteArray, epoch: Long): RecoveryStatus {
            require(plain.size == 9 && plain[0] == 0x81.toByte()) { "Invalid authenticated recovery status" }
            val input = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
            input.get()
            val status = RecoveryStatus(input.short.toInt() and 65535, input.short.toInt() and 65535,
                input.int.toLong() and 0xffffffffL)
            require(status.schema == 1 && status.credentialEpoch == epoch) { "Recovery status context mismatch" }
            return status
        }
    }
}

/** Authenticated status access only. Network recovery remains gated by capabilities. */
internal class RecoveryConnection private constructor(
    private val channel: RecoveryChannel, private val session: RecoveryCrypto.Session,
    val initialStatus: RecoveryStatus
) : AutoCloseable {
    private val mutex = Mutex()

    suspend fun queryStatus(): RecoveryStatus = mutex.withLock {
        try {
            val request = byteArrayOf(3) + session.seal(byteArrayOf(1))
            val response = try { channel.exchange(request) } finally { request.fill(0) }
            require(response.isNotEmpty() && response[0] == 0x83.toByte()) { "Invalid recovery response" }
            val plain = session.open(response.copyOfRange(1, response.size))
            try { RecoveryStatus.parse(plain, initialStatus.credentialEpoch) } finally { plain.fill(0) }
        } catch (error: Exception) { close(); throw error }
    }

    override fun close() { session.close(); channel.close() }

    companion object {
        suspend fun authenticate(credential: RecoveryCredential, publicInfo: ByteArray,
                                 channel: RecoveryChannel): RecoveryConnection {
            var session: RecoveryCrypto.Session? = null
            val key = credential.key()
            val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            try {
                require(MessageDigest.isEqual(RecoveryWire.info(publicInfo), credential.deviceUuid())) { "Recovery device identity mismatch" }
                val hello = ByteBuffer.allocate(55).order(ByteOrder.LITTLE_ENDIAN).put(1).putShort(1)
                    .put(credential.deviceUuid()).putInt(credential.epoch.toInt()).put(nonce).array()
                val response = channel.exchange(hello)
                require(response.size == 65 && response[0] == 0x81.toByte()) { "Invalid recovery challenge" }
                val challenge = RecoveryCrypto.Challenge(credential.deviceUuid(), credential.epoch, nonce, response.copyOfRange(1, 33))
                // The device must prove its key before the phone sends its own proof.
                session = RecoveryCrypto.authenticate(true, key, challenge, response.copyOfRange(33, 65))
                val proof = RecoveryCrypto.proof(key, challenge, false)
                val auth = byteArrayOf(2) + proof
                proof.fill(0)
                val ack = try { channel.exchange(auth) } finally { auth.fill(0) }
                require(ack.isNotEmpty() && ack[0] == 0x82.toByte()) { "Recovery authentication acknowledgement missing" }
                val plain = session.open(ack.copyOfRange(1, ack.size))
                val status = try { RecoveryStatus.parse(plain, credential.epoch) } finally { plain.fill(0) }
                return RecoveryConnection(channel, session, status)
            } catch (error: Exception) { session?.close(); channel.close(); throw error }
            finally { key.fill(0); nonce.fill(0) }
        }
    }
}
