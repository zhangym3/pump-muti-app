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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pumpDetail by rememberSaveable { mutableStateOf(false) }
    var taskPage by rememberSaveable { mutableStateOf("home") }
    var showConnection by rememberSaveable { mutableStateOf(false) }
    var mask by rememberSaveable { mutableLongStateOf(0L) }
    var amount by rememberSaveable { mutableStateOf("500") }
    var seconds by rememberSaveable { mutableStateOf(preferences.getString("seconds", "60") ?: "60") }
    var individual by rememberSaveable { mutableStateOf(false) }
    var valveAmounts by rememberSaveable { mutableStateOf(ArrayList(List(32) { "500" })) }
    var showDraft by rememberSaveable { mutableStateOf(true) }
    var showAddDevice by rememberSaveable { mutableStateOf(false) }
    var seenTask by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableIntStateOf(5) }
    var confirmStart by remember { mutableStateOf(false) }
    var confirmRetry by remember { mutableStateOf(false) }
    var confirmMeshReset by remember { mutableStateOf(false) }
    var commissionSlot by rememberSaveable { mutableIntStateOf(0) }
    var renameSlot by rememberSaveable { mutableIntStateOf(0) }
    var renameText by rememberSaveable { mutableStateOf("") }
    val task = ui.task
    val unresolved = task?.unresolved == true
    val count = java.lang.Long.bitCount(mask)
    val ml = amount.toLongOrNull()
    val maxSeconds = seconds.toLongOrNull()
    val encodedAmounts = if (individual) ValveAmounts.encode(mask, valveAmounts) else null
    val draftTotal = if (individual) ValveAmounts.slots(mask).sumOf { valveAmounts[it - 1].toLongOrNull() ?: 0 } else (ml ?: 0) * count
    val valid = mask in 1..0xffff_ffffL && mask and ui.selectableMask.inv() == 0L &&
        (if (individual) encodedAmounts != null && ui.hello?.individual == true else ml != null && VolumeRules.isValid(ml)) &&
        maxSeconds != null && maxSeconds in 1..240
    val openPump = { tab = 1; pumpDetail = true }
    LaunchedEffect(ui.slaves, ui.inventoryFresh) {
        if (ui.inventoryFresh && !unresolved) mask = mask and ui.selectableMask
    }

    LaunchedEffect(task?.client, task?.seq) {
        if (task != null && seenTask != "${task.client}/${task.seq}") {
            seenTask = "${task.client}/${task.seq}"
            showDraft = false
            if (task.unresolved) {
                mask = task.mask
                individual = task.individual
                if (task.individual) valveAmounts = ArrayList((1..32).map { if (it in ValveAmounts.slots(task.mask)) task.target(it).toString() else "500" })
                else amount = VolumeRules.snap(task.ml).toString()
            }
        }
    }
    BackHandler(pumpDetail) { pumpDetail = false }
    BackHandler(taskPage != "home") { taskPage = if (taskPage == "review") "select" else "home" }

    if (confirmStart) AlertDialog(
        onDismissRequest = { confirmStart = false }, title = { Text("确认本次出水") },
        text = { Text("阀门：${MasterProtocol.slots(mask)}\n${if (individual) ValveAmounts.slots(mask).joinToString("、") { "${alias(it)} ${valveAmounts[it - 1]} mL" } else "每路 $amount mL × $count 路"}\n总目标 $draftTotal mL\n按编号顺序执行，每路最长 $seconds 秒。\n\n手机断连后，已接受的任务继续执行。") },
        confirmButton = { TextButton(onClick = {
            confirmStart = false
            if (valid && ui.canStart) { model.start(mask, if (individual) 0 else ml!!, maxSeconds!!, encodedAmounts ?: ""); showDraft = false }
        }, enabled = valid && ui.canStart) { Text("确认启动") } },
        dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("取消") } }
    )
    if (confirmRetry) AlertDialog(
        onDismissRequest = { confirmRetry = false }, title = { Text("重试原请求") },
        text = { Text("沿用原节点、水量、序号和时限。已接受的请求由主机去重；尚未接受时可能开始出水。请先查询原任务。") },
        confirmButton = { TextButton(onClick = { confirmRetry = false; model.retryOriginal() }, enabled = ui.canRetry) { Text("确认重试") } },
        dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text("取消") } }
    )
    if (confirmMeshReset) AlertDialog(
        onDismissRequest = { confirmMeshReset = false },
        title = { Text("确认重置从机组网") },
        text = { Text("主机会清除所有已保存的 Mesh 从机连接和从机登记信息，并自动重启。主机 PIN、泵设置和任务记录会保留。重置后需要重新点击“添加从机”完成组网。") },
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
        if (tab == 0 && taskPage == "home" && showDraft && !unresolved) {
            Spacer(Modifier.statusBarsPadding())
        } else TopAppBar(
            modifier = if (tab == 1) Modifier.height(56.dp) else Modifier,
            navigationIcon = {
                if (tab == 0 && (taskPage != "home" || unresolved || task?.outcome == MasterOutcome.FINISHED && !showDraft)) {
                    IconButton(onClick = {
                        if (taskPage != "home") taskPage = if (taskPage == "review") "select" else "home"
                        else if (!unresolved) showDraft = true
                    }) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回") }
                }
            },
            title = {
                if (tab == 0 && taskPage == "home" && showDraft && !unresolved) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.ic_water), contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("泵阀控制", style = MaterialTheme.typography.titleLarge)
                            Supporting("主从协同 · 顺序定量出水")
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(when {
                            tab != 0 -> listOf("出水控制", "设备", "记录", "设置").getOrElse(tab) { "出水控制" }
                            taskPage == "select" -> "选择出水阀门"
                            taskPage == "review" -> "确认出水任务"
                            unresolved -> "正在出水"
                            task != null && task.outcome == MasterOutcome.FINISHED && !showDraft -> "出水完成"
                            else -> "泵阀控制"
                        }, style = if (tab == 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                        if (taskPage == "select") Supporting("从机阀门 · 在线且已配置")
                        if (taskPage == "review") Supporting("核对参数后启动任务")
                    }
                }
            },
            actions = {
                if (tab == 0 && taskPage == "select") {
                    IconButton(onClick = model::refresh, enabled = ui.connected) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新从机清单")
                    }
                } else if (tab == 0 && (unresolved || task != null && task.outcome == MasterOutcome.FINISHED && !showDraft)) {
                    IconButton(onClick = model::query, enabled = ui.connected) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新任务状态")
                    }
                } else if (tab == 3) {
                    IconButton(onClick = { tab = 0; pumpDetail = false }) {
                        Icon(painterResource(R.drawable.ic_control), contentDescription = "返回控制")
                    }
                } else {
                    IconButton(onClick = { tab = 3; pumpDetail = false }) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "设置")
                    }
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
                        TextButton(onClick = openPump, modifier = Modifier.weight(1f)) { Text("返回水泵") }
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
                        TextButton(onClick = { tab = 0; pumpDetail = false; showDraft = false }, modifier = Modifier.weight(1f)) { Text("返回任务") }
                        if (!ui.connected) Button(onClick = { showConnection = true }, modifier = Modifier.weight(1.5f)) { Text("重连并查询") }
                        else Button(onClick = model::stop, enabled = ui.canStop, modifier = Modifier.weight(1.5f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                            Text(if (ui.stopPending) "再次请求停止" else "停止本次任务")
                        }
                    }
                }
            }
            if (taskPage == "home" || tab != 0) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("控制" to R.drawable.ic_control, "设备" to R.drawable.ic_devices, "记录" to R.drawable.ic_records, "设置" to R.drawable.ic_settings).forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index; pumpDetail = false },
                        icon = { Icon(painterResource(icon), null) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ))
                }
            }
        }
    }) { insets ->
        val scroll = key(tab, pumpDetail, showDraft, showAddDevice) { rememberScrollState() }
        val compactHome = tab == 0 && taskPage == "home" && showDraft && !unresolved
        Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(scroll).padding(horizontal = 14.dp,
            vertical = if (compactHome) 2.dp else if (tab == 1) 6.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactHome) 6.dp else if (tab == 1) 8.dp else 12.dp)) {
            if (tab == 0 && taskPage == "home" && showDraft && !unresolved) {
                HomeHeader(onSettings = { tab = 3; pumpDetail = false })
            }
            if (tab != 0 || taskPage == "home" && showDraft && !unresolved) ConnectionSummary(ui, onClick = {
                if (ui.connection is MasterConnectionState.Error && ui.retryDevice != null) model.retryConnection()
                else showConnection = true
            }, onDisconnect = model.manager::disconnect)
            when (tab) {
                0 -> {
                    when {
                        taskPage == "select" -> ValveSelectionScreen(ui, mask, alias,
                            onMask = { mask = it }, onBack = { taskPage = "home" }, onReview = { taskPage = "review" })
                        taskPage == "review" -> TaskReviewScreen(ui, mask, amount, seconds, individual, valveAmounts, valid, alias,
                            onBack = { taskPage = "select" }, onConfirm = { confirmStart = true }, onQuery = model::query)
                        task != null && (!showDraft || unresolved) -> {
                            val historyEntry = ui.history.firstOrNull { it.task.client == task.client && it.task.seq == task.seq }
                            TaskStatusHero(task, ui.report)
                            TaskDetails(task, ui.report ?: historyEntry?.report,
                                ui.volume ?: historyEntry?.volume, historyEntry?.flows.orEmpty() + ui.flows, alias,
                                snapshot = !ui.connected || ui.report == null,
                                expectedFlowAddresses = ui.expectedFlowAddresses, nowElapsed = ui.flowClockAt)
                            OutlinedButton(onClick = model::query, enabled = ui.connected, modifier = Modifier.fillMaxWidth()) { Text("查询原任务") }
                            if (task.outcome == MasterOutcome.PENDING) OutlinedButton(onClick = { confirmRetry = true }, enabled = ui.canRetry, modifier = Modifier.fillMaxWidth()) { Text("重试原请求") }
                            if (!unresolved) Button(onClick = { mask = 0; showDraft = true; taskPage = "home" },
                                enabled = !ui.taskNeedsReconciliation && !ui.unknownResult, modifier = Modifier.fillMaxWidth()) { Text("新建出水任务") }
                        }
                        else -> {
                            if (!ui.canStart && ui.inventoryFresh) ui.startBlockReason?.let { Feedback(it) }
                            if (ui.connected && ui.hello?.fault == "BOOT_LOCK") {
                                OutlinedButton(onClick = openPump, modifier = Modifier.fillMaxWidth()) { Text("查看并解除主机启动锁") }
                            }
                            if (ui.message != "扫描并连接 PUMP_MASTER 设备；首次连接请完成系统 PIN 配对。" && ui.message != "主控已连接") Feedback(ui.message)
                            TaskComposer(ui, mask, amount, seconds, individual, valveAmounts, alias,
                                onSelect = { taskPage = "select" }, onMask = { mask = it }, onAmount = { amount = it },
                                onMode = { individual = it }, onValveAmount = { slot, value ->
                                    valveAmounts = ArrayList(valveAmounts).also { it[slot - 1] = value }
                                }, onReview = { taskPage = "review" }, onSpeed = model::setSpeed)
                        }
                    }
                }
                1 -> if (pumpDetail) PumpTestScreen(ui, duration, { duration = it }, { pumpDetail = false }, model::refresh,
                    { if (ui.canStartTest) model.startTest(duration) }, model::stopTest, model::setSpeed, model::resetBootLock)
                    else DevicesScreen(ui, alias, { renameSlot = it; renameText = aliases[it] ?: "" }, openPump, model::refresh,
                        showAddDevice, { showAddDevice = true; model.scanSlaves() }, model::joinSlave, model::retrySlave,
                        { confirmMeshReset = true }, { commissionSlot = it })
                2 -> RecordsScreen(ui, alias) { previous ->
                    if (!unresolved && !ui.unknownResult) {
                        mask = previous.mask and ui.selectableMask
                        individual = previous.individual
                        if (previous.individual) valveAmounts = ArrayList((1..32).map { if (it in ValveAmounts.slots(previous.mask)) previous.target(it).toString() else "500" })
                        else amount = VolumeRules.snap(previous.ml).toString()
                        showDraft = true; tab = 0; pumpDetail = false
                    }
                }
                3 -> SettingsScreen(ui, seconds) {
                    seconds = it
                    if (it.toLongOrNull()?.let { value -> value in 1..240 } == true) preferences.edit().putString("seconds", it).apply()
                }
            }
        }
    }
}

@Composable
private fun ConnectionSummary(ui: MasterUiState, onClick: () -> Unit, onDisconnect: () -> Unit) {
    val connectedGreen = Color(0xFF159447)
    val status = when (ui.connection) {
        MasterConnectionState.Connected -> if (ui.hello == null) "核实中" else "已连接"
        MasterConnectionState.Connecting -> "连接中"
        is MasterConnectionState.Error -> "连接失败"
        else -> "未连接"
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClickLabel = "蓝牙连接详情", onClick = onClick).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null,
                    tint = if (ui.connected) connectedGreen else MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(status, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    color = if (ui.connected) connectedGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (ui.connected) ui.deviceName else "PUMP_MASTER", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (ui.connected) TextButton(onClick = onDisconnect, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("断开", maxLines = 1)
            } else TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(when {
                    ui.connection is MasterConnectionState.Connecting -> "查看"
                    ui.connection is MasterConnectionState.Error && ui.retryDevice != null -> "重试"
                    else -> "连接"
                }, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HomeHeader(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.ic_water),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text("泵阀控制", modifier = Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = "设置", modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
