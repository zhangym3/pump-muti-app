package com.cczb.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** Opt in only. Exercises authenticated status, never Mesh reset or output commands. */
@SuppressLint("MissingPermission")
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RecoveryAuthenticationDeviceTest {
    private val probeRun: String by lazy {
        requireNotNull(InstrumentationRegistry.getArguments().getString("probeRun"))
            .also { require(it.matches(Regex("[0-9a-f]{32}"))) }
    }
    private fun trace(message: String) = Log.i("RecoveryAuth", "probe_run=$probeRun $message")

    private fun credential(): RecoveryCredential {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("recoveryAuthProbe") == "1")
        trace("event=test_start") // Validate the run context before touching hardware.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permissions = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        val id = requireNotNull(args.getString("deviceUuid")).lowercase()
        require(id.matches(Regex("[0-9a-f]{32}")))
        val vault = RecoveryVault(context)
        // Debug test-only input, delivered over ADB stdin into this app's private directory.
        // The staging payload is consumed once; neither it nor its code is logged.
        val stage = File(context.noBackupFilesDir, "recovery-install-stage")
        if (stage.exists()) {
            val bytes = stage.inputStream().use { input ->
                val bounded = ByteArray(RecoveryCredentialCodec.MAX_BYTES + 128)
                var size = 0
                while (size < bounded.size) {
                    val count = input.read(bounded, size, bounded.size - size)
                    if (count < 0) break
                    check(count > 0); size += count
                }
                require(size < bounded.size)
                bounded.copyOf(size)
            }
            try {
                require(bytes.size >= 36)
                val length = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
                require(length > 0 && bytes.size == length + 36)
                val code = bytes.copyOfRange(4 + length, bytes.size)
                try { vault.importBackup(bytes.copyOfRange(4, 4 + length), code) } finally { code.fill(0) }
            } finally { bytes.fill(0); check(stage.delete()) { "Cannot remove private installation input" } }
        }
        return vault.list().single { it.id == id }
    }

    private suspend fun connect(): RecoveryBleClient {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        check(adapter.isEnabled)
        val address = requireNotNull(InstrumentationRegistry.getArguments().getString("deviceAddress"))
        return RecoveryBleClient.connect(context, adapter.getRemoteDevice(address)) { trace(it) }
    }

    private suspend fun expectAuthorizationRejection(label: String, action: suspend () -> Unit) {
        val failure = try { action(); null } catch (error: Exception) { error }
        assertNotNull("$label unexpectedly succeeded", failure)
        assertTrue("$label inconclusive: expected explicit ATT authorization rejection; ${failure?.javaClass?.simpleName}",
            failure?.isRecoveryAuthorizationRejection() == true)
        trace("negative=$label explicit_att_authorization_rejection=PASS")
    }

    @Test fun a_mutualAuthenticationFreshReconnectAndEncryptedStatus(): Unit = runBlocking {
        val record = credential()
        val rounds = (InstrumentationRegistry.getArguments().getString("rounds") ?: "10").toInt().also { require(it in 1..100) }
        repeat(rounds) { round ->
            val started = SystemClock.elapsedRealtime()
            connect().use { att ->
                val info = att.readInfo()
                RecoveryConnection.authenticate(record, info, RecoveryChannel(att)).use { recovery ->
                    assertEquals(0, recovery.initialStatus.capabilities)
                    assertEquals(record.epoch, recovery.initialStatus.credentialEpoch)
                    repeat(3) { assertEquals(recovery.initialStatus, recovery.queryStatus()) }
                    assertEquals("Must exercise ATT MTU 23", 23, att.negotiatedMtu)
                    trace("round=${round + 1} authenticated_status=PASS mtu=${att.negotiatedMtu} elapsed_ms=${SystemClock.elapsedRealtime() - started} recovery_enabled=0")
                }
            }
            SystemClock.sleep(700)
        }
    }

    private class LiveSession(val att: RecoveryBleClient, val channel: RecoveryChannel,
                              val crypto: RecoveryCrypto.Session) : AutoCloseable {
        override fun close() { crypto.close(); channel.close() }
    }
    private suspend fun live(record: RecoveryCredential, repeatReads: Boolean = false): LiveSession {
        val att = connect()
        val transport = if (!repeatReads) att else object : RecoveryAtt {
            override suspend fun write(value: ByteArray) = att.write(value)
            override suspend fun read(): ByteArray {
                val first = att.read()
                assertArrayEquals("Reading a fragment twice must not advance the response", first, att.read())
                return first
            }
            override fun close() = att.close()
        }
        val channel = RecoveryChannel(transport)
        var crypto: RecoveryCrypto.Session? = null
        val key = record.key()
        try {
            assertArrayEquals(record.deviceUuid(), RecoveryWire.info(att.readInfo()))
            val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val hello = ByteBuffer.allocate(55).order(ByteOrder.LITTLE_ENDIAN).put(1).putShort(1)
                .put(record.deviceUuid()).putInt(record.epoch.toInt()).put(nonce).array()
            val response = channel.exchange(hello)
            assertEquals(65, response.size); assertEquals(0x81.toByte(), response[0])
            val challenge = RecoveryCrypto.Challenge(record.deviceUuid(), record.epoch, nonce, response.copyOfRange(1, 33))
            crypto = RecoveryCrypto.authenticate(true, key, challenge, response.copyOfRange(33, 65))
            val proof = RecoveryCrypto.proof(key, challenge, false)
            val request = byteArrayOf(2) + proof
            proof.fill(0)
            val ack = try { channel.exchange(request) } finally { request.fill(0) }
            assertEquals(0x82.toByte(), ack[0])
            val plain = crypto.open(ack.copyOfRange(1, ack.size))
            try { RecoveryStatus.parse(plain, record.epoch) } finally { plain.fill(0) }
            return LiveSession(att, channel, crypto)
        } catch (error: Throwable) { crypto?.close(); channel.close(); throw error }
        finally { key.fill(0) }
    }

    @Test fun b_defaultMtuMaximumFrameRepeatableReadsAndReplayRejected(): Unit = runBlocking {
        val record = credential()
        var oldFrame: ByteArray? = null
        live(record, repeatReads = true).use { live ->
            // Unsupported plaintext is deliberately inert at the status-only endpoint.
            val frame = byteArrayOf(3) + live.crypto.seal(ByteArray(512) { 0x7f })
            assertEquals(549, frame.size)
            val reply = live.channel.exchange(frame)
            assertEquals(0x83.toByte(), reply[0])
            assertArrayEquals(byteArrayOf(0xff.toByte(), 1), live.crypto.open(reply.copyOfRange(1, reply.size)))
            oldFrame = frame.copyOf()
            expectAuthorizationRejection("replay") { live.channel.exchange(frame) }
            trace("maximum_frame=549 repeated_reads=PASS replay_rejected=PASS")
        }
        SystemClock.sleep(2600) // First authentication failure backoff is two seconds.
        live(record).use { fresh ->
            expectAuthorizationRejection("old_session") { fresh.channel.exchange(requireNotNull(oldFrame)) }
            trace("old_session_rejected=PASS")
        }
        oldFrame?.fill(0)
        SystemClock.sleep(2600)
    }

    @Test fun c_wrongClientProofRejectedAndValidOwnerCanReconnect(): Unit = runBlocking {
        val record = credential()
        connect().use { att ->
            assertArrayEquals(record.deviceUuid(), RecoveryWire.info(att.readInfo()))
            val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val hello = ByteBuffer.allocate(55).order(ByteOrder.LITTLE_ENDIAN).put(1).putShort(1)
                .put(record.deviceUuid()).putInt(record.epoch.toInt())
                .put(nonce).array()
            RecoveryChannel(att).use { channel ->
                val response = channel.exchange(hello)
                assertEquals(65, response.size)
                assertEquals(0x81.toByte(), response[0])
                val challenge = RecoveryCrypto.Challenge(record.deviceUuid(), record.epoch, nonce, response.copyOfRange(1, 33))
                val key = record.key()
                try {
                    RecoveryCrypto.authenticate(true, key, challenge, response.copyOfRange(33, 65)).close()
                } finally { key.fill(0); nonce.fill(0) }
                expectAuthorizationRejection("wrong_client_proof") { channel.exchange(byteArrayOf(2) + ByteArray(32)) }
            }
        }
        // Previous negative tests may leave a longer backoff if this test is selected alone.
        SystemClock.sleep(4600)
        connect().use { att ->
            RecoveryConnection.authenticate(record, att.readInfo(), RecoveryChannel(att)).use {
                assertEquals(it.initialStatus, it.queryStatus())
            }
        }
        trace("wrong_client_proof_rejected=PASS legitimate_reconnect=PASS")
    }
}
