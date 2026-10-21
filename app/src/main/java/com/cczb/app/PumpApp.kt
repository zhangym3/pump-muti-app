package com.cczb.app

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PumpApp(model: MasterControlViewModel) {
    val ui by model.ui.collectAsState()
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("pump-ui", 0) }
    val aliases = remember { mutableStateMapOf<Int, String>().apply {
        (1..32).forEach { slot -> preferences.getString("slot.$slot", null)?.let { put(slot, it) } }
    } }
    val alias: (Int) -> String = { aliases[it] ?: "${it.toString().padStart(2, '0')} 号阀门" }
    var navigation by rememberSaveable(stateSaver = PumpNavigation.Saver) { mutableStateOf(PumpNavigation()) }
    val pageState = rememberSaveableStateHolder()
    val controlScroll = rememberScrollState()
    val devicesScroll = rememberScrollState()
    val recordsScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var showConnection by rememberSaveable { mutableStateOf(false) }
    var mask by rememberSaveable { mutableLongStateOf(0L) }
    var amount by rememberSaveable { mutableStateOf("500") }
    var seconds by rememberSaveable { mutableStateOf(preferences.getString("seconds", "60") ?: "60") }
    var individual by rememberSaveable { mutableStateOf(false) }
    var valveAmounts by rememberSaveable { mutableStateOf(ArrayList(List(32) { "500" })) }
    var showAddDevice by rememberSaveable { mutableStateOf(false) }
    var duration by rememberSaveable { mutableIntStateOf(5) }
    var confirmStart by remember { mutableStateOf(false) }
    var confirmRetry by remember { mutableStateOf(false) }
    var confirmMeshReset by remember { mutableStateOf(false) }
    var commissionSlot by rememberSaveable { mutableIntStateOf(0) }
    var renameSlot by rememberSaveable { mutableIntStateOf(0) }
    var renameText by rememberSaveable { mutableStateOf("") }
    val task = ui.task
    val unresolved = task?.unresolved == true
    val ml = amount.toLongOrNull()
    val maxSeconds = seconds.toLongOrNull()
    val encodedAmounts = if (individual) ValveAmounts.encode(mask, valveAmounts) else null
    val valid = mask in 1..0xffff_ffffL && mask and ui.selectableMask.inv() == 0L &&
        (if (individual) encodedAmounts != null && ui.hello?.individual == true else ml != null && VolumeRules.isValid(ml)) &&
        maxSeconds != null && maxSeconds in 1..240
    val openPump = {
        navigation = navigation.openPump()
        scope.launch { devicesScroll.scrollTo(0) }
        Unit
    }
    LaunchedEffect(ui.slaves, ui.inventoryFresh) {
        if (ui.inventoryFresh && !unresolved) mask = mask and ui.selectableMask
    }
    LaunchedEffect(unresolved, navigation.tab) {
        if (unresolved && navigation.tab == PumpTab.CONTROL) controlScroll.scrollTo(0)
    }
    BackHandler(navigation.back() != null) { navigation.back()?.let { navigation = it } }

    if (confirmStart) TaskStartConfirmation(ui, mask, amount, seconds, individual, valveAmounts, valid, alias,
        onDismiss = { confirmStart = false }, onConfirm = {
            if (valid && ui.canStart) {
                confirmStart = false
                model.start(mask, if (individual) 0 else ml!!, maxSeconds!!, encodedAmounts ?: "")
            }
        }, onQuery = model::query)
    if (confirmRetry) AlertDialog(
        onDismissRequest = { confirmRetry = false }, title = { Text("重试原请求") },
        text = { Text("沿用原节点、水量、序号和时限。已接受的请求由主机去重；尚未接受时可能开始出水。请先查询原任务。") },
        confirmButton = { TextButton(onClick = { confirmRetry = false; model.retryOriginal() }, enabled = ui.canRetry) { Text("确认重试") } },
        dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text("取消") } }
    )
    if (confirmMeshReset) AlertDialog(
        onDismissRequest = { confirmMeshReset = false },
        title = { Text("确认重置从机组网") },
        text = { Text("主机会清除所有已保存的 Mesh 从机连接和从机登记信息，并自动重启。主机 PIN、泵设置和任务记录会保留。重置后需要重新点击“添加设备”完成组网。") },
        confirmButton = { TextButton(onClick = {
            confirmMeshReset = false
            model.resetMesh()
        }, enabled = ui.canResetMesh && !ui.meshPending) { Text("确认重置") } },
        dismissButton = { TextButton(onClick = { confirmMeshReset = false }) { Text("取消") } }
    )
    if (commissionSlot != 0) {
        val target = ui.slaves?.firstOrNull { it.slot == commissionSlot }
        AlertDialog(
            onDismissRequest = { commissionSlot = 0 },
            title = { Text("启用配置 · ${commissionSlot.toString().padStart(2, '0')} 号阀门") },
            text = { Text("确认已现场检查阀门和流量计接线、核对流量系数 K，并确认安全互锁有效。确认后该阀门将允许接受出水任务。此操作只保存配置，不会启动水泵或阀门。") },
            confirmButton = { TextButton(onClick = {
                commissionSlot = 0
                model.commissionSlave(target?.slot ?: 0)
            }, enabled = target != null && ui.canCommission(target)) { Text("确认启用") } },
            dismissButton = { TextButton(onClick = { commissionSlot = 0 }) { Text("取消") } }
        )
    }
    if (renameSlot != 0) AlertDialog(
        onDismissRequest = { renameSlot = 0 }, title = { Text("$renameSlot 号阀门 · 本地别名") },
        text = { OutlinedTextField(renameText, { renameText = it.take(16) }, singleLine = true, label = { Text("别名（最多 16 字）") }, supportingText = { Text("留空恢复默认名称，仅保存到本手机。") }) },
        confirmButton = { TextButton(onClick = {
            val value = renameText.trim()
            if (value.isEmpty()) { aliases.remove(renameSlot); preferences.edit().remove("slot.$renameSlot").apply() }
            else { aliases[renameSlot] = value; preferences.edit().putString("slot.$renameSlot", value).apply() }
            renameSlot = 0
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { renameSlot = 0 }) { Text("取消") } }
    )
    if (showConnection) ConnectionSheet(ui, model) { showConnection = false }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column {
                Text(navigation.tab.title, style = MaterialTheme.typography.titleLarge)
                Text(when (ui.connection) {
                    MasterConnectionState.Connected -> "${ui.deviceName} · 已连接"
                    MasterConnectionState.Connecting -> "正在连接主机…"
                    is MasterConnectionState.Error -> "连接失败 · 点击右侧重新连接"
                    else -> "主机未连接"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } },
            actions = {
                IconButton(onClick = model::refresh, enabled = ui.connected) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新主机状态")
                }
                TextButton(onClick = { showConnection = true }) {
                    Icon(painterResource(R.drawable.ic_bluetooth), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (ui.connected) "主机" else "连接")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
    }, bottomBar = {
        Column {
            if (ui.pumpTestUnresolved) Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Supporting(if (!ui.connected) "水泵测试状态待确认，请重连查询" else ui.testMessage)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (navigation.tab != PumpTab.DEVICES || !navigation.pumpExpanded)
                            TextButton(onClick = openPump, modifier = Modifier.weight(1f)) { Text("查看水泵测试") }
                        if (!ui.connected) Button(onClick = { showConnection = true }, modifier = Modifier.weight(1f)) { Text("重连查询测试") }
                        else OutlinedButton(onClick = model::stopTest, enabled = ui.canStopTest, modifier = Modifier.weight(1f)) { Text("停止原测试") }
                    }
                }
            }
            if (unresolved) Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Supporting(when {
                        !ui.connected -> "连接已断开，任务可能仍在执行；请重连查询。"
                        ui.stopPending -> "停止待确认 · 等待主机回复与阀门结算"
                        task?.job == null -> "启动待确认 · 先查询原任务，勿重复出水"
                        else -> "${taskStatus(task!!, ui.report)} · ${java.lang.Long.bitCount(task.mask)} 路"
                    })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (navigation.tab != PumpTab.CONTROL)
                            TextButton(onClick = { navigation = navigation.select(PumpTab.CONTROL) }, modifier = Modifier.weight(1f)) { Text("查看当前任务") }
                        if (!ui.connected) Button(onClick = { showConnection = true }, modifier = Modifier.weight(1.5f)) { Text("重连并查询") }
                        else Button(onClick = model::stop, enabled = ui.canStop, modifier = Modifier.weight(1.5f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                            Text(if (ui.stopPending) "再次请求停止" else "停止本次任务")
                        }
                    }
                }
            }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                PumpTab.entries.forEach { target ->
                    NavigationBarItem(selected = navigation.tab == target, onClick = { navigation = navigation.select(target) },
                        icon = { Icon(painterResource(target.icon), null) }, label = { Text(target.label) })
                }
            }
        }
    }) { insets ->
        val scroll = when (navigation.tab) {
            PumpTab.CONTROL -> controlScroll
            PumpTab.DEVICES -> devicesScroll
            PumpTab.RECORDS -> recordsScroll
            PumpTab.SETTINGS -> settingsScroll
        }
        pageState.SaveableStateProvider(navigation.tab.name) {
            Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(scroll).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (navigation.tab) {
                    PumpTab.CONTROL -> {
                        CurrentTaskSection(ui, alias, onQuery = model::query, onRetry = { confirmRetry = true })
                        if (!unresolved) {
                            if (ui.connected && ui.hello?.fault == "BOOT_LOCK") {
                                OutlinedButton(onClick = openPump, modifier = Modifier.fillMaxWidth()) { Text("查看并解除主机启动锁") }
                            }
                            if (ui.message != "扫描并连接 PUMP_MASTER 设备；首次连接请完成系统 PIN 配对。" && ui.message != "主控已连接") Feedback(ui.message)
                            TaskComposer(ui, mask, amount, seconds, individual, valveAmounts, alias,
                                onMask = { mask = it }, onAmount = { amount = it }, onMode = { individual = it },
                                onValveAmount = { slot, value -> valveAmounts = ArrayList(valveAmounts).also { it[slot - 1] = value } },
                                onConfirm = { confirmStart = true }, onSpeed = model::setSpeed)
                        }
                    }
                    PumpTab.DEVICES -> DevicesScreen(ui, alias, { renameSlot = it; renameText = aliases[it] ?: "" },
                        onPump = { navigation = navigation.copy(pumpExpanded = !navigation.pumpExpanded) }, onRefresh = model::refresh,
                        showAddDevice = showAddDevice, onOpenAdd = { showAddDevice = true; model.scanSlaves() },
                        onJoin = model::joinSlave, onRetry = model::retrySlave, onResetMesh = { confirmMeshReset = true },
                        onCommission = { commissionSlot = it }, onCloseAdd = { showAddDevice = false },
                        pumpExpanded = navigation.pumpExpanded, pumpContent = {
                            PumpTestScreen(ui, duration, { duration = it }, model::refresh,
                                { if (ui.canStartTest) model.startTest(duration) }, model::stopTest, model::setSpeed, model::resetBootLock)
                        })
                    PumpTab.RECORDS -> RecordsScreen(ui, alias, navigation.recordKey,
                        onToggle = { navigation = navigation.toggleRecord(it) }, onDraft = { previous ->
                            if (!unresolved && !ui.taskNeedsReconciliation && !ui.unknownResult && !previous.unresolved) {
                                mask = previous.mask and ui.selectableMask
                                individual = previous.individual
                                if (previous.individual) valveAmounts = ArrayList((1..32).map { if (it in ValveAmounts.slots(previous.mask)) previous.target(it).toString() else "500" })
                                else amount = VolumeRules.snap(previous.ml).toString()
                                navigation = navigation.select(PumpTab.CONTROL)
                                scope.launch { controlScroll.scrollTo(0) }
                            }
                        })
                    PumpTab.SETTINGS -> SettingsScreen(ui, seconds, onSeconds = {
                        seconds = it
                        if (it.toLongOrNull()?.let { value -> value in 1..240 } == true) preferences.edit().putString("seconds", it).apply()
                    }, alias = alias, onSelectCalibration = model::selectCalibration,
                        onReadCalibration = model::readCalibration, onSaveCalibration = model::saveCalibration)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
private fun ConnectionSheet(ui: MasterUiState, model: MasterControlViewModel, onDismiss: () -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) { permissionError = null; model.manager.startScan() }
        else permissionError = "请在系统设置允许附近设备权限；Android 11 还需位置权限和位置服务。"
    }
    DisposableEffect(Unit) { onDispose { model.manager.stopScan() } }
    val scanning = ui.scan is MasterScanState.Scanning
    val connecting = ui.connection is MasterConnectionState.Connecting
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("连接主机", style = MaterialTheme.typography.headlineSmall)
            Supporting("Master 是 BLE GATT 设备，部分手机系统蓝牙列表可能不显示；请在这里扫描。首次连接时在 Android 系统窗口输入六位 PIN，未弹窗时请查看通知栏。")
            Feedback(ui.message)
            (ui.connection as? MasterConnectionState.Error)?.let { Feedback(it.message) }
            if (ui.connected || connecting) {
                Text(ui.deviceName)
                Feedback(if (connecting) "正在连接与配对，请留意系统 PIN 窗口。" else "蓝牙已连接，主机业务状态请查看控制页。")
                OutlinedButton(onClick = model.manager::disconnect, modifier = Modifier.fillMaxWidth()) { Text("断开蓝牙") }
            } else {
                Button(onClick = {
                    if (scanning) model.manager.stopScan()
                    else permissionLauncher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (scanning) "停止扫描" else "扫描 Master 主机") }
                Row(Modifier.fillMaxWidth().clickable { showAll = !showAll }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(showAll, { showAll = it })
                    Text("显示全部蓝牙设备")
                }
                val devices = ui.devices.filter { showAll || it.advertisedName?.startsWith("PUMP_MASTER", ignoreCase = true) == true }
                if (devices.isEmpty()) Supporting(if (scanning) "正在查找附近 BLE 主机…" else "未发现主机时，请确认供电、BLE 广播日志和附近设备权限。")
                devices.take(40).forEach { device ->
                    OutlinedButton(onClick = { model.connect(device); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text("${device.advertisedName ?: "未命名设备"}\n${device.device.address} · ${device.rssi} dBm")
                    }
                }
            }
            permissionError?.let { Feedback(it) }
            (ui.scan as? MasterScanState.Error)?.let { Feedback(it.message) }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
        }
    }
}
