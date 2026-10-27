package com.cczb.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** One GATT lifetime per instance. Stale callbacks cannot complete a new connection. */
@SuppressLint("MissingPermission")
internal class RecoveryBleClient private constructor(private val onTrace: (String) -> Unit) : RecoveryAtt {
    private enum class Kind { SERVICES, READ, WRITE }
    private class Pending(val kind: Kind, val characteristic: BluetoothGattCharacteristic?,
                          val result: CompletableDeferred<ByteArray> = CompletableDeferred())
    private val lock = Any()
    private val io = Mutex()
    private val connection = CompletableDeferred<Unit>()
    private var gatt: BluetoothGatt? = null
    private var pending: Pending? = null
    private var closed = false
    private val startedAt = SystemClock.elapsedRealtime()
    @Volatile private var phase = RecoveryGattPhase.CONNECT
    private lateinit var rx: BluetoothGattCharacteristic
    private lateinit var tx: BluetoothGattCharacteristic
    private lateinit var info: BluetoothGattCharacteristic
    @Volatile var negotiatedMtu = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!current(g)) return
            trace("connection_callback", status, newState)
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) connection.complete(Unit)
            else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED)
                stop(RecoveryTransportException(phase, RecoveryGattSource.CONNECTION, status, newState))
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(g, Kind.SERVICES, null, status, byteArrayOf())
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            finish(g, Kind.WRITE, c, status, byteArrayOf())
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            finish(g, Kind.READ, c, status, value)
        @Deprecated("Android 11/12 compatibility")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) finish(g, Kind.READ, c, status, c.value ?: byteArrayOf())
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!current(g)) return
            trace("mtu_callback", status)
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
        }
    }

    // Only fixed event names, phases and numeric metadata. Never include peer addresses,
    // characteristic values, authentication frames or exception messages.
    private fun trace(event: String, status: Int? = null, state: Int? = null) {
        runCatching { onTrace("phase=$phase event=$event status=$status state=$state elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}") }
    }

    private fun current(g: BluetoothGatt) = synchronized(lock) { !closed && gatt === g }
    private fun finish(g: BluetoothGatt, kind: Kind, c: BluetoothGattCharacteristic?, status: Int, value: ByteArray) {
        synchronized(lock) {
            if (closed || gatt !== g) return
            val operation = pending ?: return
            if (operation.kind != kind || operation.characteristic !== c) return
            trace("${kind.name.lowercase()}_callback", status)
            if (status == BluetoothGatt.GATT_SUCCESS) operation.result.complete(value.copyOf())
            else operation.result.completeExceptionally(RecoveryTransportException(phase,
                when (kind) {
                    Kind.SERVICES -> RecoveryGattSource.SERVICES
                    Kind.READ -> RecoveryGattSource.READ
                    Kind.WRITE -> RecoveryGattSource.WRITE
                }, status))
        }
    }

    private suspend fun operation(kind: Kind, characteristic: BluetoothGattCharacteristic?, step: RecoveryGattPhase,
                                  start: (BluetoothGatt) -> Boolean): ByteArray = io.withLock {
        val operation = Pending(kind, characteristic)
        try {
            val active = synchronized(lock) {
                check(!closed && pending == null) { "Recovery GATT unavailable" }
                phase = step
                pending = operation
                requireNotNull(gatt)
            }
            trace("request")
            if (!start(active)) throw RecoveryTransportException(phase, RecoveryGattSource.REQUEST)
            withTimeout(4_000) { operation.result.await() }
        } catch (error: Exception) { trace("aborted_${error.javaClass.simpleName}"); stop(error); throw error }
        finally { synchronized(lock) { if (pending === operation) pending = null } }
    }

    suspend fun readInfo(): ByteArray = operation(Kind.READ, info, RecoveryGattPhase.INFO) { it.readCharacteristic(info) }
    override suspend fun read(): ByteArray = operation(Kind.READ, tx, RecoveryGattPhase.READ) { it.readCharacteristic(tx) }
    override suspend fun write(value: ByteArray) {
        require(value.size in 1..20)
        operation(Kind.WRITE, rx, RecoveryGattPhase.WRITE) {
            if (Build.VERSION.SDK_INT >= 33)
                it.writeCharacteristic(rx, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else {
                @Suppress("DEPRECATION")
                rx.value = value
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                it.writeCharacteristic(rx)
            }
        }
    }

    private fun stop(error: Exception) {
        val active = synchronized(lock) {
            if (closed) return
            trace("closed_${error.javaClass.simpleName}")
            closed = true
            connection.completeExceptionally(error)
            pending?.result?.completeExceptionally(error)
            gatt.also { gatt = null }
        }
        try { active?.disconnect() } catch (_: RuntimeException) { }
        try { active?.close() } catch (_: RuntimeException) { }
    }
    override fun close() = stop(RecoveryTransportException(phase, RecoveryGattSource.CLOSE))

    companion object {
        suspend fun connect(context: Context, device: BluetoothDevice, onTrace: (String) -> Unit = {}): RecoveryBleClient {
            val client = RecoveryBleClient(onTrace)
            try {
                client.trace("request")
                synchronized(client.lock) {
                    client.gatt = requireNotNull(device.connectGatt(context.applicationContext, false, client.callback, BluetoothDevice.TRANSPORT_LE))
                }
                withTimeout(12_000) { client.connection.await() }
                client.operation(Kind.SERVICES, null, RecoveryGattPhase.SERVICES) { it.discoverServices() }
                synchronized(client.lock) {
                    check(!client.closed)
                    val service = requireNotNull(client.gatt?.getService(RecoveryWire.SERVICE)) { "Recovery service unavailable" }
                    client.info = requireNotNull(service.getCharacteristic(RecoveryWire.INFO))
                    client.rx = requireNotNull(service.getCharacteristic(RecoveryWire.RX))
                    client.tx = requireNotNull(service.getCharacteristic(RecoveryWire.TX))
                    require(client.info.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 &&
                        client.tx.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 &&
                        client.rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) { "Unsupported recovery characteristics" }
                }
                // Deliberately do not request a larger MTU or system pairing.
                return client
            } catch (error: Exception) { client.trace("aborted_${error.javaClass.simpleName}"); client.close(); throw error }
        }
    }
}
