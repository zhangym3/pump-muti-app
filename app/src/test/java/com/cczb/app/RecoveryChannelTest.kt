package com.cczb.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecoveryChannelTest {
    private val uuid = ByteArray(16) { (it + 97).toByte() }
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val credential = RecoveryCredential(uuid, 7, key, "测试")
    private val info = byteArrayOf(1, 0, 0, 0) + uuid
    private fun status(epoch: Long = 7) = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        .put(0x81.toByte()).putShort(1).putShort(0).putInt(epoch.toInt()).array()
    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected rejection") } catch (_: Exception) { }
    }

    /** Independent ATT peer: retains one response and never advances on a read. */
    private class Peer(private val reply: (ByteArray) -> ByteArray) : RecoveryAtt {
        private var incoming = ByteArrayOutputStream()
        private var id = 0
        private var total = 0
        private var offset = 0
        private var outgoing = byteArrayOf()
        var completed = 0
        var closed = false
        var corrupt: (ByteArray) -> ByteArray = { it }
        override suspend fun write(value: ByteArray) {
            check(!closed)
            check(value.size <= 20)
            val input = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
            when (input.get().toInt()) {
                1 -> {
                    val transfer = input.short.toInt() and 65535
                    val size = input.short.toInt() and 65535
                    val at = input.short.toInt() and 65535
                    if (at == 0) { check(transfer > id); id = transfer; total = size; incoming = ByteArrayOutputStream() }
                    check(transfer == id && total == size && at == incoming.size())
                    incoming.write(value, 7, value.size - 7)
                    if (incoming.size() == total) { outgoing = reply(incoming.toByteArray()); completed++ }
                }
                2 -> { check((input.short.toInt() and 65535) == id); offset = input.short.toInt() and 65535 }
                else -> error("Unknown ATT request")
            }
        }
        override suspend fun read(): ByteArray {
            val end = minOf(offset + 13, outgoing.size)
            return corrupt(ByteBuffer.allocate(7 + end - offset).order(ByteOrder.LITTLE_ENDIAN)
                .put(1).putShort(id.toShort()).putShort(outgoing.size.toShort()).putShort(offset.toShort())
                .put(outgoing, offset, end - offset).array())
        }
        override fun close() { closed = true }
    }

    private class Device(val key: ByteArray, val uuid: ByteArray, val epoch: Long = 7) {
        var challenge: RecoveryCrypto.Challenge? = null
        var session: RecoveryCrypto.Session? = null
        var authenticationAttempts = 0
        var badProof = false
        var badAck = false
        fun reply(message: ByteArray): ByteArray = when (message[0].toInt()) {
            1 -> {
                assertEquals(55, message.size)
                val hello = ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN)
                hello.get(); assertEquals(1, hello.short.toInt())
                assertArrayEquals(uuid, ByteArray(16).also { hello.get(it) })
                assertEquals(epoch, hello.int.toLong() and 0xffffffffL)
                val clientNonce = ByteArray(32).also { hello.get(it) }
                val deviceNonce = ByteArray(32) { (it + 65).toByte() }
                challenge = RecoveryCrypto.Challenge(uuid, epoch, clientNonce, deviceNonce)
                val proof = RecoveryCrypto.proof(key, requireNotNull(challenge), true)
                if (badProof) proof[0] = (proof[0].toInt() xor 1).toByte()
                byteArrayOf(0x81.toByte()) + deviceNonce + proof
            }
            2 -> {
                authenticationAttempts++
                val crypto = RecoveryCrypto.authenticate(false, key, requireNotNull(challenge), message.copyOfRange(1, message.size))
                session = crypto
                val plain = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                    .put(0x81.toByte()).putShort(1).putShort(0).putInt(epoch.toInt()).array()
                (byteArrayOf(0x82.toByte()) + crypto.seal(plain)).also {
                    if (badAck) it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                }
            }
            3 -> {
                val crypto = requireNotNull(session)
                assertArrayEquals(byteArrayOf(1), crypto.open(message.copyOfRange(1, message.size)))
                val plain = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                    .put(0x81.toByte()).putShort(1).putShort(0).putInt(epoch.toInt()).array()
                byteArrayOf(0x83.toByte()) + crypto.seal(plain)
            }
            else -> error("Unexpected recovery message")
        }
    }

    @Test fun everyMessageLengthSurvivesDefaultMtuAndTransferIdsNeverRepeat() = runBlocking {
        val peer = Peer { it.reversedArray() }
        RecoveryChannel(peer).use { channel ->
            for (size in 1..549) {
                val message = ByteArray(size) { (it + size).toByte() }
                assertArrayEquals(message.reversedArray(), channel.exchange(message))
            }
            assertEquals(549, peer.completed)
        }
        assertTrue(peer.closed)
    }

    @Test fun invalidFragmentsCloseConnectionWithoutRetry() = runBlocking {
        val mutations: List<(ByteArray) -> ByteArray> = listOf(
            { it.copyOf(7) }, { it + ByteArray(20) },
            { it.copyOf().also { b -> b[0] = 2 } },
            { it.copyOf().also { b -> b[1] = 0 } },
            { it.copyOf().also { b -> b[3] = 0; b[4] = 0 } },
            { it.copyOf().also { b -> b[5] = 1 } },
            { it.copyOf().also { b -> if (b[5] != 0.toByte()) b[3] = 20 } })
        for (mutation in mutations) {
            val peer = Peer { ByteArray(30) }.apply { corrupt = mutation }
            val channel = RecoveryChannel(peer)
            rejected { channel.exchange(byteArrayOf(1)) }
            assertTrue(peer.closed)
            assertEquals(1, peer.completed)
            rejected { channel.exchange(byteArrayOf(1)) }
        }
    }

    @Test fun cancellationClosesTransportAndCannotReuseChannel() = runBlocking {
        val att = object : RecoveryAtt {
            var closed = false
            override suspend fun write(value: ByteArray) { throw CancellationException("Cancelled") }
            override suspend fun read() = byteArrayOf()
            override fun close() { closed = true }
        }
        val channel = RecoveryChannel(att)
        rejected { channel.exchange(byteArrayOf(1)) }
        assertTrue(att.closed)
        rejected { channel.exchange(byteArrayOf(1)) }
    }

    @Test fun mutualAuthenticationAndEncryptedStatusAndFreshReconnect() = runBlocking {
        var previousNonce: ByteArray? = null
        repeat(2) {
            val device = Device(key, uuid)
            val peer = Peer(device::reply)
            val connection = RecoveryConnection.authenticate(credential, info, RecoveryChannel(peer))
            connection.use {
                assertEquals(RecoveryStatus(1, 0, 7), it.initialStatus)
                repeat(3) { assertEquals(RecoveryStatus(1, 0, 7), connection.queryStatus()) }
                assertEquals(1, device.authenticationAttempts)
                val nonce = requireNotNull(device.challenge).transcript().copyOfRange(38, 70)
                previousNonce?.let { old -> assertFalse(old.contentEquals(nonce)) }
                previousNonce = nonce
            }
            assertTrue(peer.closed)
            rejected { connection.queryStatus() }
            device.session?.close()
        }
    }

    @Test fun untrustedDeviceNeverReceivesClientProofAndInvalidAckNeverAuthenticates() = runBlocking {
        val untrusted = Device(key, uuid).apply { badProof = true }
        val peer = Peer(untrusted::reply)
        rejected { RecoveryConnection.authenticate(credential, info, RecoveryChannel(peer)) }
        assertEquals(0, untrusted.authenticationAttempts)
        assertTrue(peer.closed)
        val broken = Device(key, uuid).apply { badAck = true }
        val second = Peer(broken::reply)
        rejected { RecoveryConnection.authenticate(credential, info, RecoveryChannel(second)) }
        assertEquals(1, broken.authenticationAttempts)
        assertTrue(second.closed)
        broken.session?.close()
        val wrongDevice = Peer { error("Must reject public identity before sending a request") }
        rejected { RecoveryConnection.authenticate(credential, info.copyOf().also { it[4] = 1 }, RecoveryChannel(wrongDevice)) }
        assertTrue(wrongDevice.closed)
        assertEquals(0, wrongDevice.completed)
    }

    @Test fun authenticatedStatusRejectsDowngradeWrongEpochAndTrailingData() {
        assertEquals(RecoveryStatus(1, 0, 7), RecoveryStatus.parse(status(), 7))
        for (bad in listOf(status(8), status() + byteArrayOf(0), status().copyOf(8),
            status().also { it[0] = 1 }, status().also { it[1] = 0 })) {
            try { RecoveryStatus.parse(bad, 7); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        }
    }
}
