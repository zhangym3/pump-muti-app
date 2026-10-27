package com.cczb.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID

data class MasterDevice(val device: BluetoothDevice, val rssi: Int, val advertisedName: String?)

sealed interface MasterConnectionState {
    data object Disconnected : MasterConnectionState
    data object Connecting : MasterConnectionState
    data object Connected : MasterConnectionState
    data class Error(val message: String) : MasterConnectionState
}

sealed interface MasterScanState {
    data object Idle : MasterScanState
    data object Scanning : MasterScanState
    data class Error(val message: String) : MasterScanState
}

/** Master business GATT transport. Mesh provisioning runs on the Master; the
 * app only requests scanning, joining and reset operations. */
@SuppressLint("MissingPermission")
class MasterBleManager(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onConnectionStateChanged(state: MasterConnectionState)
        fun onScanStateChanged(state: MasterScanState)
        fun onDevicesChanged(devices: List<MasterDevice>)
        fun onStatus(status: String)
        fun onError(message: String)
        fun onCommandResult(command: String, success: Boolean, message: String)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("a64d0001-2f9c-4b6e-8db7-a570530e1000")
        private val RX_UUID = UUID.fromString("a64d0002-2f9c-4b6e-8db7-a570530e1000")
        private val TX_UUID = UUID.fromString("a64d0003-2f9c-4b6e-8db7-a570530e1000")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val REQUESTED_MTU = 185
        private const val MIN_CONTROL_MTU = 163
        private const val RECONNECT_SETTLE_MS = 500L
        private const val TAG = "MasterBle"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val devices = linkedMapOf<String, MasterDevice>()
    private val lines = MasterLineAssembler()
    private val queue = ArrayDeque<String>()
    private var scan: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var ready = false
    private var mtu = 23
    private var discovering = false
    private var subscribed = false
    private var mtuRequested = false
    private var mtuAttempts = 0
    private var epoch = 0L
    private var mtuToken = 0L
    private var writeToken = 0L
    private var pending: String? = null
    private var lastWriteAt = -10_000L
    private val pairing = MasterPairingGate()
    private var bondReceiver: BroadcastReceiver? = null
    private val drain = Runnable { writeNext() }

    private fun trace(message: String) {
        Log.i(TAG, "session=$epoch $message")
        listener.onStatus(message)
    }

    private fun bondState(device: BluetoothDevice): MasterPairingGate.Bond = when (device.bondState) {
        BluetoothDevice.BOND_BONDED -> MasterPairingGate.Bond.BONDED
        BluetoothDevice.BOND_BONDING -> MasterPairingGate.Bond.BONDING
        else -> MasterPairingGate.Bond.NONE
    }

    private fun observeBonding(expectedEpoch: Long) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onMain {
                if (epoch != expectedEpoch) return@onMain
                val source = gatt ?: return@onMain
                val peer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                } ?: return@onMain
                try {
                    if (peer.address != source.device.address) return@onMain
                    when (intent.action) {
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val state = bondState(source.device)
                            trace("BLE 配对状态：${peer.address} ${intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)} → ${intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)}，实际=$state")
                            handlePairing(source, pairing.bondChanged(state))
                        }
                        BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                            val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                            trace("BLE 系统配对请求：variant=$variant，请按系统提示完成；开发版无需 PIN，启用 PIN 的主机需输入六位数字")
                            // Observe only. Android owns the dialog and PIN entry.
                        }
                    }
                } catch (_: SecurityException) { fail("蓝牙权限已被撤销，请重新授权") }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        }
        // Bluetooth broadcasts can originate from a privileged Bluetooth process.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        bondReceiver = receiver
    }

    private fun handlePairing(source: BluetoothGatt, action: MasterPairingGate.Action) {
        if (source !== gatt) return
        when (action) {
            MasterPairingGate.Action.WAIT -> Unit
            MasterPairingGate.Action.FAIL -> fail("蓝牙配对失败或已取消，请按系统提示重试；更换固件或 PIN 后若旧绑定失效，请仅忘记该 Master 的旧配对")
            MasterPairingGate.Action.REQUEST_BOND -> {
                trace("BLE 主机尚未触发配对，APP 请求系统蓝牙配对")
                val started = source.device.createBond()
                val current = bondState(source.device)
                if (!started && current == MasterPairingGate.Bond.NONE) {
                    fail("无法启动系统 PIN 配对，请重新打开手机蓝牙后重试")
                } else if (current != MasterPairingGate.Bond.NONE) {
                    handlePairing(source, pairing.bondChanged(current))
                }
                // createBond() is asynchronous; NONE immediately after true is not failure.
            }
            MasterPairingGate.Action.DISCOVER -> {
                trace("BLE 已绑定，准备发现服务并验证加密订阅")
                val expectedEpoch = epoch
                handler.postDelayed({
                    if (epoch == expectedEpoch && source === gatt) {
                        try { discover(source) }
                        catch (_: SecurityException) { fail("蓝牙权限已被撤销，请重新授权") }
                        catch (_: IllegalStateException) { fail("蓝牙连接状态异常，请重新连接") }
                    }
                }, 300)
            }
        }
    }

    private fun awaitBonding(source: BluetoothGatt) {
        val state = bondState(source.device)
        trace("BLE 已连接：${source.device.address} bond=$state；首次连接请完成系统蓝牙配对")
        handlePairing(source, pairing.connected(state))
        val expectedEpoch = epoch
        // Master requests encryption on connect. Avoid competing pairing requests.
        handler.postDelayed({
            if (epoch != expectedEpoch || source !== gatt || discovering) return@postDelayed
            try { handlePairing(source, pairing.fallback(bondState(source.device))) }
            catch (_: SecurityException) { fail("蓝牙权限已被撤销，请重新授权") }
            catch (_: IllegalStateException) { fail("系统配对无法启动，请重新打开手机蓝牙") }
        }, 1_500)
    }

    private fun permissionsGranted(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    fun startScan() = onMain {
        if (!permissionsGranted()) { listener.onError("请先授予蓝牙权限"); return@onMain }
        try {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R &&
                context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
                listener.onError("Android 11 扫描蓝牙需要开启系统位置服务")
                return@onMain
            }
            val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            if (scanner == null) { listener.onError("请打开手机蓝牙，并确认手机支持 BLE"); return@onMain }
            stopScan()
            devices.clear()
            listener.onDevicesChanged(emptyList())
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = accept(result)
                override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::accept)
                private fun accept(result: ScanResult) {
                    handler.post {
                        if (scan !== this) return@post
                        val record = result.scanRecord
                        val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
                        // Some phones deliver the advertising packet before the scan response.
                        // The service UUID is in the advertising packet, so do not hide a valid
                        // Master merely because its name has not arrived yet.
                        val isMasterService = record?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
                        val displayName = name ?: if (isMasterService) "PUMP_MASTER" else null
                        val address = runCatching { result.device.address }.getOrNull() ?: return@post
                        if (devices.size < 256 || address in devices) {
                            devices[address] = MasterDevice(result.device, result.rssi, displayName)
                            listener.onDevicesChanged(devices.values.toList())
                        }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    handler.post {
                        if (scan !== this) return@post
                        scan = null
                        val reason = when (errorCode) {
                            SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "扫描过于频繁，请稍后重试"
                            SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "请关闭并重新打开手机蓝牙后重试"
                            else -> "扫描失败（$errorCode），请检查蓝牙权限或重启蓝牙"
                        }
                        listener.onScanStateChanged(MasterScanState.Error(reason))
                    }
                }
            }
            scan = callback
            listener.onScanStateChanged(MasterScanState.Scanning)
            scanner.startScan(callback)
            handler.postDelayed({ if (scan === callback) stopScan() }, 15_000)
        } catch (_: SecurityException) {
            stopScan()
            listener.onScanStateChanged(MasterScanState.Error("蓝牙权限不足，请在系统设置中允许附近设备权限"))
        } catch (_: IllegalStateException) {
            stopScan()
            listener.onScanStateChanged(MasterScanState.Error("蓝牙扫描无法启动，请重新打开蓝牙"))
        }
    }

    fun stopScan() = onMain {
        val old = scan
        scan = null
        if (old != null) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(old) }
            listener.onScanStateChanged(MasterScanState.Idle)
        }
    }

    fun connect(device: BluetoothDevice) = onMain {
        if (!permissionsGranted()) { listener.onError("请先授予蓝牙权限"); return@onMain }
        stopScan()
        closeSession()
        listener.onConnectionStateChanged(MasterConnectionState.Connecting)
        // Android may return GATT 133 when a previous BluetoothGatt is still
        // being torn down. Let disconnect/close callbacks settle before retrying.
        val expectedEpoch = epoch
        handler.postDelayed({
            if (epoch != expectedEpoch) return@postDelayed
            try {
                if (adapter?.isEnabled != true) { fail("请先打开手机蓝牙"); return@postDelayed }
                observeBonding(expectedEpoch)
                trace("BLE 开始直连：${device.address} bond=${bondState(device)} transport=LE")
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) { fail("蓝牙连接未能启动"); return@postDelayed }
                handler.postDelayed({
                    if (epoch == expectedEpoch && gatt != null && !ready)
                        fail("连接超时，请完成系统蓝牙配对，并检查 Master 控制服务")
                }, 90_000)
            } catch (_: SecurityException) { fail("蓝牙权限已被撤销，请重新授权") }
              catch (_: IllegalStateException) { fail("蓝牙连接无法启动，请重新打开蓝牙") }
        }, RECONNECT_SETTLE_MS)
    }

    fun disconnect() = onMain {
        closeSession()
        listener.onConnectionStateChanged(MasterConnectionState.Disconnected)
    }

    fun requestState() { sendCommand("hello"); sendCommand("local") }

    fun sendCommand(command: String) = onMain {
        if (!MasterProtocol.isAllowedCommand(command)) {
            listener.onCommandResult(command, false, "命令无效")
            return@onMain
        }
        if (!ready || gatt == null || rx == null) {
            listener.onCommandResult(command, false, "蓝牙命令通道尚未就绪")
            return@onMain
        }
        // Polling cannot crowd out a stop. Coalesce identical queries within this session.
        if (command == pending || command in queue) return@onMain
        if (command.startsWith("stop ") || command.startsWith("pump-test-stop ")) queue.addFirst(command)
        else if (queue.size < 8) queue.addLast(command)
        else { listener.onCommandResult(command, false, "发送队列已满，请稍后查询"); return@onMain }
        writeNext()
    }

    private fun writeNext() {
        if (pending != null || !ready) return
        val current = gatt ?: return
        val characteristic = rx ?: return
        val command = queue.peekFirst() ?: return
        handler.removeCallbacks(drain)
        val delay = 300 - (SystemClock.elapsedRealtime() - lastWriteAt)
        if (!command.startsWith("stop ") && !command.startsWith("pump-test-stop ") && delay > 0) { handler.postDelayed(drain, delay); return }
        queue.removeFirst()
        val bytes = command.toByteArray(Charsets.UTF_8)
        if (bytes.size > mtu - 3) { fail("当前蓝牙 MTU 无法容纳命令，请重新连接"); return }
        pending = command
        lastWriteAt = SystemClock.elapsedRealtime()
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                current.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                current.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) { false } catch (_: IllegalStateException) { false }
        if (!started) { fail("命令提交失败，已断开连接；任务结果需查询确认"); return }
        val expectedEpoch = epoch
        val token = ++writeToken
        handler.postDelayed({
            if (gatt === current && epoch == expectedEpoch && token == writeToken && pending == command)
                fail("命令写入超时，连接已重置；任务结果需查询确认")
        }, 5_000)
    }

    private fun fail(message: String) {
        Log.e(TAG, "session=$epoch $message")
        closeSession()
        listener.onConnectionStateChanged(MasterConnectionState.Error(message))
        listener.onError(message)
    }

    private fun closeSession() {
        epoch++
        mtuToken++
        writeToken++
        handler.removeCallbacks(drain)
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver = null
        pairing.reset()
        val old = gatt
        gatt = null
        ready = false
        rx = null
        mtu = 23
        discovering = false
        subscribed = false
        mtuRequested = false
        mtuAttempts = 0
        lines.reset()
        val dropped = listOfNotNull(pending) + queue.toList()
        pending = null
        queue.clear()
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
        dropped.forEach { listener.onCommandResult(it, false, "连接已关闭，无法确认命令执行结果") }
    }

    /** Late callbacks from a replaced GATT must not update a new device or request. */
    private fun receive(source: BluetoothGatt, action: () -> Unit) {
        handler.post {
            if (source !== gatt) { runCatching { source.close() }; return@post }
            try { action() }
            catch (_: SecurityException) { fail("蓝牙权限已被撤销，请重新授权") }
            catch (_: IllegalStateException) { fail("蓝牙连接状态异常，请重新连接") }
        }
    }

    private fun discover(source: BluetoothGatt) {
        if (discovering || source !== gatt) return
        if (source.device.bondState != BluetoothDevice.BOND_BONDED) {
            fail("系统蓝牙配对尚未完成，请完成配对后重试")
            return
        }
        discovering = true
        trace("BLE 正在发现 Master 服务")
        if (!source.discoverServices()) fail("服务发现请求未能启动")
    }

    /** Protected CCCD must succeed before MTU exchange and command writes. */
    private fun negotiateMtu(source: BluetoothGatt) {
        if (source !== gatt || !subscribed || ready || mtuRequested) return
        if (mtu >= MIN_CONTROL_MTU) { controlReady(); return }
        mtuRequested = true
        mtuAttempts++
        val token = ++mtuToken
        trace("BLE 请求 MTU=$REQUESTED_MTU，第 $mtuAttempts 次")
        val started = try { source.requestMtu(REQUESTED_MTU) }
            catch (_: SecurityException) { fail("蓝牙权限已被撤销，请重新授权"); return }
            catch (_: IllegalStateException) { fail("蓝牙连接状态异常，请重新连接"); return }
        if (!started) {
            mtuRequested = false
            retryMtu(source, "请求未能提交")
            return
        }
        handler.postDelayed({
            if (token == mtuToken && source === gatt && mtuRequested)
                fail("蓝牙 MTU 协商超时；请断开其他蓝牙调试/配网 APP 后重新连接")
        }, 12_000)
    }

    private fun retryMtu(source: BluetoothGatt, reason: String) {
        val token = ++mtuToken
        if (mtuAttempts >= 2) {
            fail("蓝牙 MTU 协商失败（$reason，当前 $mtu）；请断开其他蓝牙 APP 后重连，仍失败需检查 Master 固件")
            return
        }
        trace("BLE MTU 协商暂未成功：$reason，稍后重试")
        handler.postDelayed({
            if (token == mtuToken && source === gatt) negotiateMtu(source)
        }, 1_000)
    }

    private fun controlReady() {
        if (ready || !subscribed || mtu < MIN_CONTROL_MTU) return
        ready = true
        trace("BLE 控制通道就绪，MTU=$mtu")
        listener.onConnectionStateChanged(MasterConnectionState.Connected)
        requestState()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = receive(gatt) {
            trace("BLE 连接回调：peer=${gatt.device.address} GATT=$status state=$newState bond=${bondState(gatt.device)}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val message = if (status == 133) {
                    "蓝牙连接失败 GATT=133：请关闭其他蓝牙 APP，重新打开手机蓝牙后点击重试；仍失败请重启主机"
                } else "蓝牙连接失败：GATT=$status"
                fail(message)
                return@receive
            }
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                closeSession()
                listener.onConnectionStateChanged(MasterConnectionState.Disconnected)
            } else if (newState == BluetoothGatt.STATE_CONNECTED) {
                awaitBonding(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = receive(gatt) {
            trace("BLE MTU 回调：mtu=$mtu GATT=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (mtuRequested) {
                    mtuRequested = false
                    retryMtu(gatt, "GATT=$status")
                }
                return@receive
            }
            val requested = mtuRequested
            mtuToken++
            mtuRequested = false
            this@MasterBleManager.mtu = mtu
            if (mtu < MIN_CONTROL_MTU && (requested || ready)) {
                fail("Master 实际协商 MTU=$mtu，需要 ≥ $MIN_CONTROL_MTU；请更新主节点固件，并断开其他蓝牙 APP 后重连")
            } else controlReady()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = receive(gatt) {
            trace("BLE 服务发现回调：GATT=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("服务发现失败：GATT=$status"); return@receive }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                val discovered = gatt.services
                    .map { it.uuid.toString() }
                    .distinct()
                    .joinToString(", ")
                    .ifEmpty { "无" }
                trace("BLE 实际发现服务：$discovered")
                fail(
                    "未找到 Master 控制服务。请确认主机固件已启用手机控制；" +
                        "启用 PIN 时需先配置六位 PIN 并重启；旧绑定失效时仅忘记该主机后重试"
                )
                return@receive
            }
            rx = service.getCharacteristic(RX_UUID)
            val tx = service.getCharacteristic(TX_UUID)
            if (rx == null || tx == null || rx!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
                fail("Master 命令或状态通道不完整"); return@receive
            }
            val notify = tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val indicate = tx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!notify && !indicate || !gatt.setCharacteristicNotification(tx, true)) {
                fail("无法启用 Master 状态通知"); return@receive
            }
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd == null) { fail("状态通道缺少 CCCD 描述符"); return@receive }
            val value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            // Bonding has completed. Protected CCCD success verifies the firmware's required encryption.
            trace("BLE 写入加密 CCCD，验证主机连接与通知通道")
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            if (!started) fail("通知订阅请求失败，请完成系统蓝牙配对后重连")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = receive(gatt) {
            if (descriptor.uuid != CCCD_UUID || descriptor.characteristic.uuid != TX_UUID) return@receive
            trace("BLE 加密 CCCD 回调：GATT=$status bond=${bondState(gatt.device)}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("通知订阅失败：GATT=$status，请完成系统蓝牙配对后重连"); return@receive
            }
            if (!subscribed) {
                subscribed = true
                trace("BLE 加密通知订阅成功，准备协商 MTU")
                handler.postDelayed({
                    if (gatt === this@MasterBleManager.gatt && subscribed && !ready) negotiateMtu(gatt)
                }, 300)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val bytes = value.copyOf()
            receive(gatt) { if (ready && characteristic.uuid == TX_UUID) lines.accept(bytes).forEach(listener::onStatus) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value?.copyOf() ?: return
            receive(gatt) { if (ready && characteristic.uuid == TX_UUID) lines.accept(bytes).forEach(listener::onStatus) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = receive(gatt) {
            if (characteristic.uuid != RX_UUID) return@receive
            val command = pending ?: return@receive
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("命令写入失败：GATT=$status，任务结果需查询确认"); return@receive }
            pending = null
            writeToken++
            listener.onCommandResult(command, true, "命令已写入，等待主机业务回复")
            writeNext()
        }
    }

    fun close() = onMain { stopScan(); closeSession() }
}
