package com.cczb.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt in with recoveryProbe=1 and an exact deviceUuid; never sends control commands. */
@SuppressLint("MissingPermission")
@RunWith(AndroidJUnit4::class)
class RecoveryDiscoveryDeviceTest {
    @Test fun oldMeshNodeRemainsDiscoverableAndReadableAtDefaultMtu() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("recoveryProbe") == "1")
        val expected = requireNotNull(args.getString("deviceUuid"))
        require(expected.matches(Regex("[0-9a-fA-F]{32}")))
        val rounds = (args.getString("rounds") ?: "30").toInt().also { require(it in 1..100) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        assertTrue("Bluetooth must be enabled", adapter.isEnabled)
        val scanner = requireNotNull(adapter.bluetoothLeScanner) { "BLE scanner unavailable" }
        val service = UUID.fromString("a64d1001-2f9c-4b6e-8db7-a570530e1000")
        val info = UUID.fromString("a64d1002-2f9c-4b6e-8db7-a570530e1000")
        val scanDurations = mutableListOf<Long>()
        var discovered = 0
        var readable = 0
        val failures = mutableListOf<String>()
        var previousScanStart = 0L
        repeat(rounds) { round ->
            // Android limits scan starts to five per 30 seconds for an app.
            if (round != 0) SystemClock.sleep((6200 - (SystemClock.elapsedRealtime() - previousScanStart)).coerceAtLeast(0))
            val found = CountDownLatch(1)
            val peer = AtomicReference<BluetoothDevice>()
            val scanError = AtomicReference<String>()
            var rssi = 0
            val scan = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    if (peer.compareAndSet(null, result.device)) rssi = result.rssi
                    found.countDown()
                }
                override fun onScanFailed(errorCode: Int) { scanError.set("scan error $errorCode"); found.countDown() }
            }
            val started = SystemClock.elapsedRealtime()
            previousScanStart = started
            var phase = "scan"
            var scanMs: Long? = null
            var gatt: BluetoothGatt? = null
            val disconnected = CountDownLatch(1)
            try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).apply {
                // Optional exact address obtained from trusted USB diagnostics. UUID is still
                // verified after connecting; a MAC address is not an authentication factor.
                args.getString("deviceAddress")?.let { setDeviceAddress(it) }
            }.build()
            scanner.startScan(listOf(filter),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
            try { assertTrue("not discovered in 15s", found.await(15, TimeUnit.SECONDS)) }
            finally { scanner.stopScan(scan) }
            assertNull(scanError.get())
            val device = requireNotNull(peer.get())
            scanMs = SystemClock.elapsedRealtime() - started
            scanDurations += scanMs
            discovered++
            phase = "connect/read"
            val finished = CountDownLatch(1)
            val failure = AtomicReference<String>()
            val bytes = AtomicReference<ByteArray>()
            var negotiatedMtu = 23 // Deliberately never call requestMtu.
            val callback = object : BluetoothGattCallback() {
                private fun fail(message: String) { failure.compareAndSet(null, message); finished.countDown() }
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                    Log.i("RecoveryG0", "round=${round + 1} connection_status=$status state=$state")
                    if (status != BluetoothGatt.GATT_SUCCESS) fail("connection status=$status state=$state")
                    if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        if (!g.discoverServices()) fail("discoverServices rejected")
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        disconnected.countDown()
                        if (finished.count != 0L) fail("early disconnect status=$status")
                    }
                }
                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { negotiatedMtu = mtu }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val characteristic = g.getService(service)?.getCharacteristic(info)
                    if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || !g.readCharacteristic(characteristic))
                        fail("recovery discovery service/read unavailable status=$status")
                }
                override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    if (c.uuid != info) return
                    if (status != BluetoothGatt.GATT_SUCCESS) fail("read status=$status")
                    else { bytes.set(value.copyOf()); finished.countDown() }
                }
                @Deprecated("Compatibility callback for Android 11 and 12")
                override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    if (android.os.Build.VERSION.SDK_INT < 33)
                        onCharacteristicRead(g, c, c.value ?: byteArrayOf(), status)
                }
            }
            gatt = requireNotNull(device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE))
                assertTrue("read timeout", finished.await(12, TimeUnit.SECONDS))
                assertNull(failure.get())
                val value = requireNotNull(bytes.get())
                assertEquals(20, value.size)
                assertEquals("discovery schema", 1, (value[0].toInt() and 255) or ((value[1].toInt() and 255) shl 8))
                assertEquals("no recovery commands advertised before security gate", 0, value[2].toInt() or value[3].toInt())
                val actual = value.drop(4).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals("exact device identity", expected.lowercase(), actual)
                assertEquals("must exercise MTU=23", 23, negotiatedMtu)
                readable++
                Log.i("RecoveryG0", "round=${round + 1} scan_ms=$scanMs total_ms=${SystemClock.elapsedRealtime() - started} rssi=$rssi mtu=$negotiatedMtu uuid=$actual PASS")
            } catch (error: AssertionError) {
                failures += "round=${round + 1} phase=$phase reason=${error.message}"
                Log.e("RecoveryG0", "${failures.last()} scan_ms=$scanMs total_ms=${SystemClock.elapsedRealtime() - started} rssi=$rssi FAIL")
            } catch (error: Exception) {
                failures += "round=${round + 1} phase=$phase reason=${error.javaClass.simpleName}:${error.message}"
                Log.e("RecoveryG0", "${failures.last()} scan_ms=$scanMs total_ms=${SystemClock.elapsedRealtime() - started} rssi=$rssi FAIL")
            } finally {
                gatt?.let {
                    try { it.disconnect(); disconnected.await(3, TimeUnit.SECONDS) }
                    finally { it.close() }
                }
            }
        }
        // Keep every first attempt. A GATT retry must not hide the raw failure rate.
        val summary = "rounds=$rounds discovered=$discovered readable=$readable scan_max_ms=${scanDurations.maxOrNull()} device=${expected.lowercase()}"
        Log.i("RecoveryG0", "SUMMARY $summary failures=${failures.size}")
        assertTrue("Discovery below 95%: $summary; ${failures.joinToString()}", discovered * 100 >= rounds * 95)
        assertTrue("Read below 95%: $summary; ${failures.joinToString()}", readable * 100 >= rounds * 95)
    }
}
