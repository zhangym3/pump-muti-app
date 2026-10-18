package com.cczb.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun TaskComposer(
    ui: MasterUiState, mask: Long, amount: String, seconds: String, canStart: Boolean,
    alias: (Int) -> String, onSelect: () -> Unit, onAmount: (String) -> Unit,
    onSeconds: (String) -> Unit, onReview: () -> Unit
) {
    val ml = amount.toLongOrNull()
    val duration = seconds.toLongOrNull()
    val valid = mask != 0L && mask and ui.selectableMask.inv() == 0L && ml != null && ml in 1..1_000_000 && duration != null && duration in 1..240
    val count = java.lang.Long.bitCount(mask)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(onClick = onSelect, modifier = Modifier.weight(1f).heightIn(min = 112.dp), shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(painterResource(R.drawable.ic_devices), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("出水阀门", style = MaterialTheme.typography.titleSmall)
                }
                Text(if (count == 0) "未选择" else "已选 $count 路", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Supporting(if (count == 0) "请选择在线阀门" else MasterProtocol.slots(mask).split(',').take(3).joinToString("、") { "${it.trim()} 号" })
            }
        }
        Card(modifier = Modifier.weight(1f).heightIn(min = 112.dp), shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("预计参数", style = MaterialTheme.typography.titleSmall)
                Text("${if (ml != null) ml * count else "—"} mL", style = MaterialTheme.typography.titleLarge)
                Supporting("$count 路 × $amount mL")
            }
        }
    }
    PumpCard("每路水量（mL）") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(100, 250, 500, 1000).forEach { value ->
                FilterChip(selected = amount == value.toString(), onClick = { onAmount(value.toString()) },
                    label = { Text("$value") }, modifier = Modifier.weight(1f))
            }
        }
        StepperRow("mL", amount, 1, 1_000_000, 50, onAmount)
        HorizontalDivider()
        Text("单路最长时间（秒）", style = MaterialTheme.typography.titleSmall)
        StepperRow("秒", seconds, 1, 240, 5, onSeconds)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Supporting("执行顺序")
            Text("按编号顺序", style = MaterialTheme.typography.bodyMedium)
        }
    }
    Button(onClick = onReview, enabled = canStart && valid, modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp), shape = RoundedCornerShape(8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_power), null, Modifier.size(18.dp))
                Text("检查并启动", style = MaterialTheme.typography.titleMedium)
            }
            Text("$count 路 · $amount mL · 单路上限 ${duration ?: 0} 秒", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
    if (!valid) Supporting("选择阀门后可继续；水量范围 1–1000000 mL，时限范围 1–240 秒。")
    Supporting("按编号逐路执行 · 已接受任务不受手机断连影响")
}

@Composable
private fun StepperRow(unit: String, value: String, min: Int, max: Int, step: Int, onValue: (String) -> Unit) {
    val number = value.toIntOrNull() ?: min
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = { onValue((number - step).coerceAtLeast(min).toString()) }, enabled = number > min,
            modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(8.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text("$value $unit", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        FilledTonalButton(onClick = { onValue((number + step).coerceAtMost(max).toString()) }, enabled = number < max,
            modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(8.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
    OutlinedTextField(value, { onValue(it.filter(Char::isDigit).take(7)) }, label = { Text("自定义 $unit") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
        isError = value.toIntOrNull()?.let { it !in min..max } != false, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("范围 $min–$max $unit") })
}

@Composable
internal fun ValveSelectionScreen(ui: MasterUiState, mask: Long, alias: (Int) -> String,
                                  onMask: (Long) -> Unit, onBack: () -> Unit, onReview: () -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val nodes = ui.slaves.orEmpty().filter { it.slot in 1..32 }.sortedBy { it.slot }
    val visible = nodes.filter { when (filter) { 1 -> mask and (1L shl (it.slot - 1)) != 0L; 2 -> it.online; 3 -> !it.online; else -> true } }
    val count = java.lang.Long.bitCount(mask)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("全部 ${nodes.size}", "已选 $count", "在线 ${nodes.count { it.online }}", "离线 ${nodes.count { !it.online }}").forEachIndexed { index, label ->
            FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) }, modifier = Modifier.weight(1f))
        }
    }
    if (!ui.inventoryFresh) Feedback("从机清单未同步，请返回控制页刷新主机状态。")
    if (nodes.isEmpty()) PumpCard("暂无可选阀门") { Supporting("请先在设备页添加从机并完成配置。") }
    visible.forEach { node ->
        val bit = 1L shl (node.slot - 1)
        val selected = mask and bit != 0L
        Card(onClick = { if (ui.inventoryFresh && node.selectable) onMask(mask xor bit) }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        if (selected) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        else Icon(painterResource(R.drawable.ic_devices), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(alias(node.slot), style = MaterialTheme.typography.titleSmall)
                    Supporting("${addressLabel(node.address)} · ${if (node.commissioned) "已配置" else "待配置"}")
                }
                StatePill(if (node.selectable) "在线" else if (!node.online) "离线" else "待配置", attention = !node.selectable)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onMask(ui.selectableMask) }, enabled = ui.inventoryFresh && ui.selectableMask != 0L, modifier = Modifier.weight(1f)) { Text("全选在线") }
        OutlinedButton(onClick = { onMask(ui.selectableMask and mask.inv()) }, enabled = ui.inventoryFresh && ui.selectableMask != 0L, modifier = Modifier.weight(1f)) { Text("反选") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("已选 $count / ${nodes.size} 路", Modifier.weight(1f))
        TextButton(onClick = { onMask(0) }, enabled = mask != 0L) { Text("清空") }
    }
    Button(onClick = onReview, enabled = ui.inventoryFresh && count > 0 && mask and ui.selectableMask.inv() == 0L,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("确认选择（$count）") }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回控制") }
}

@Composable
internal fun TaskReviewScreen(ui: MasterUiState, mask: Long, amount: String, seconds: String, alias: (Int) -> String,
                              onBack: () -> Unit, onConfirm: () -> Unit) {
    val count = java.lang.Long.bitCount(mask)
    val ml = amount.toLongOrNull() ?: 0
    PumpCard("任务预览") {
        Text("${count} 路阀门", style = MaterialTheme.typography.headlineSmall)
        Supporting(MasterProtocol.slots(mask).split(',').joinToString("、") { alias(it.trim().toInt()) })
        HorizontalDivider()
        ReviewLine("执行顺序", "按编号顺序")
        ReviewLine("每路水量", "$ml mL")
        ReviewLine("目标总量", "${ml * count} mL")
        ReviewLine("单路时限", "$seconds 秒")
    }
    Feedback("启动前请确认供水与出水通路正常。启动后主机将逐路执行；手机断连不会取消已接受任务。")
    Button(onClick = onConfirm, enabled = ui.canStart && count > 0,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("确认选择并启动")
            Text("$count 路 · $ml mL/路 · 上限 $seconds 秒/路", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回修改") }
}

@Composable
private fun ReviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Supporting(label)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun SlotPager(group: Int, onGroup: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onGroup(group - 1) }, enabled = group > 0) { Text("上一组") }
        Text("${group * 8 + 1}–${group * 8 + 8}", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { onGroup(group + 1) }, enabled = group < 3) { Text("下一组") }
    }
}

@Composable
internal fun DevicesScreen(ui: MasterUiState, alias: (Int) -> String, onRename: (Int) -> Unit, onPump: () -> Unit, onRefresh: () -> Unit,
                           showAddDevice: Boolean, onOpenAdd: () -> Unit,
                           onJoin: (String) -> Unit = {}, onRetry: (Long) -> Unit = {},
                           onResetMesh: () -> Unit = {}) {
    var showMaintenance by rememberSaveable { mutableStateOf(false) }
    MasterSummary(ui, onRefresh)
    Button(onClick = onOpenAdd, enabled = ui.canRequestMeshScan, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
        Text(when (ui.mesh?.phase) {
            "SCANNING" -> "正在搜索设备…"
            "JOINING", "CONFIGURING", "WAIT_HELLO" -> ui.mesh?.label ?: "正在组网…"
            else -> "添加设备"
        })
    }
    if (showAddDevice) {
        Supporting(if (ui.mesh?.phase == "SCANNING") "正在搜索附近未组网的从机" else "从机需保持上电并靠近主机。")
        ui.candidates.forEach { uuid ->
            OutlinedButton(onClick = { onJoin(uuid) }, enabled = ui.canJoinMesh, modifier = Modifier.fillMaxWidth()) {
                Text("${slaveDisplayName(uuid)}  ·  连接")
            }
        }
        if (ui.mesh?.phase == "SCAN_DONE" && ui.candidates.isEmpty())
            Supporting("没有发现可组网的从机。请检查供电后重新搜索。")
        if (ui.meshPending || ui.mesh?.phase in setOf("READY", "FAILED")) Feedback(ui.meshMessage)
    }
    PumpCard("从机 · 阀门") {
        if (ui.slaves.isNullOrEmpty()) Supporting("尚未添加从机")
        else Supporting("${ui.slaves.size} 台设备 · ${ui.slaves.count { it.online }} 台在线")
        ui.slaves.orEmpty().forEach { node ->
            val slot = node.slot
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (slot == 0) "—" else slot.toString().padStart(2, '0'), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (slot == 0) "待配置从机" else alias(slot))
                    Supporting("${if (!ui.inventoryFresh) "状态待同步" else if (!node.online) "离线" else "在线"} · ${if (node.commissioned) "已配置" else "待现场配置"}")
                }
                if (slot > 0) TextButton(onClick = { onRename(slot) }) { Text("编辑") }
            }
            if (!node.online || slot == 0) OutlinedButton(onClick = { onRetry(node.address) }, enabled = ui.canManageMesh,
                modifier = Modifier.fillMaxWidth()) { Text("重试连接配置 · ${addressLabel(node.address)}") }
        }
        if (ui.slaves?.isEmpty() == true) Supporting("Master 尚未配对从机。")
        if (ui.slaves.orEmpty().any { !it.online || it.slot == 0 })
            Supporting("离线或待配置的从机不能执行出水任务。")
    }
    TextButton(onClick = { showMaintenance = !showMaintenance }) {
        Text(if (showMaintenance) "收起维护" else "设备维护")
    }
    if (showMaintenance) {
        OutlinedButton(onClick = onPump, modifier = Modifier.fillMaxWidth()) { Text("水泵测试与调速") }
        OutlinedButton(onClick = onResetMesh, enabled = ui.canResetMesh && !ui.meshPending,
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(if (ui.meshPending && ui.mesh?.phase == "RESETTING") "正在重置…" else "重置从机组网")
        }
        Supporting("重置会清除主机保存的全部从机连接信息，并自动重启。")
    }
}

@Composable
internal fun PumpTestScreen(ui: MasterUiState, duration: Int, onDuration: (Int) -> Unit,
                            onBack: () -> Unit, onRefresh: () -> Unit,
                            onStart: () -> Unit, onStop: () -> Unit, onSpeed: (Int) -> Unit = {}) {
    val blockReason = ui.testBlockReason
    TextButton(onClick = onBack) { Text("‹ 返回设备") }
    Text("水泵设备", style = MaterialTheme.typography.headlineSmall)
    Supporting(ui.hello?.let { "主机 ${addressLabel(it.master)} · 公共水泵" } ?: "主机 · 公共水泵")
    PumpCard("通水测试") {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(painterResource(R.drawable.ic_power), null, Modifier.padding(20.dp).size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(ui.pumpOutputLabel, style = MaterialTheme.typography.titleLarge)
            Supporting("控制输出状态，以主机返回为准")
        }
        HorizontalDivider()
        Supporting("选择档位和时长，点击启动测试；可随时停止。")
        Text("PWM 调速 · GPIO25", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("低档 50%", "中档 75%", "高档 100%").forEachIndexed { index, label ->
                FilterChip(selected = ui.pwm.snapshot?.level == index + 1, onClick = { onSpeed(index + 1) },
                    enabled = ui.canSetSpeed, label = { Text(label) }, modifier = Modifier.weight(1f))
            }
        }
        ui.pwm.snapshot?.let { pwm ->
            Supporting("主机确认：${listOf("低", "中", "高")[pwm.level - 1]}档 ${pwm.duty}% · ${pwm.hz} Hz${if (ui.pwmFresh) "" else " · 已过期"}")
            Supporting(if (ui.localFresh && ui.local?.pumpCommand == false) "当前输出 0%（停泵）" else "实际输出开关以主机状态为准")
        } ?: Supporting("PWM 档位未同步；请刷新，旧固件需更新后支持调速")
        Feedback(ui.pwm.message)
        Supporting("选择档位不会启动水泵；测试中可切档，原测试时限不变。")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("测试时长")
            Supporting("到时自动停止")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 5, 10).forEach { value ->
                FilterChip(selected = duration == value, onClick = { onDuration(value) }, enabled = !ui.pumpTestUnresolved, label = { Text("$value 秒") }, modifier = Modifier.weight(1f))
            }
        }
        blockReason?.let { Feedback(it) }
        Feedback(if (!ui.connected && ui.pumpTestUnresolved) "连接已断开，测试状态待确认；主机仍执行原时限，请重连查询" else ui.testMessage)
        if (ui.localFresh && ui.local?.testActive == true) Supporting("主机上报剩余 ${ui.local.testMs} ms；以设备状态为准")
        if (ui.task?.unresolved == true) Supporting("出水任务尚未结束，需先确认任务结果。")
        if (ui.unknownResult) Supporting("请先查询并核实上次出水任务的未知结果。")
    }
    Supporting("测试只启动水泵，不自动打开阀门。启动前请准备供水和出水通路。")
    // Never translate these actions into dosing start, USB estop, or a phone-side timer.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onStart, enabled = blockReason == null, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(8.dp)) { Text("启动测试") }
        OutlinedButton(onClick = onStop, enabled = ui.canStopTest, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("停止测试") }
    }
    OutlinedButton(onClick = onRefresh, enabled = ui.connected, modifier = Modifier.fillMaxWidth()) { Text("刷新水泵状态") }
    ui.localAt?.let { Supporting("输出状态时间 ${displayTime(it)}${if (ui.localFresh) "" else " · 已过期"}") }
    Supporting("输出状态不代表传感器确认的实际运行情况，请现场观察。")
}

@Composable
internal fun RecordsScreen(ui: MasterUiState, alias: (Int) -> String, onDraft: (MasterTask) -> Unit) {
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = ui.history.firstOrNull { it.key == selectedKey }
    Text("任务记录", style = MaterialTheme.typography.titleLarge)
    if (!ui.historyHealthy) Feedback("本地记录保存或读取失败，当前记录可能不完整。")
    if (selected != null) {
        TextButton(onClick = { selectedKey = null }) { Text("‹ 返回记录列表") }
        Supporting("${selected.deviceName} · 最后记录 ${displayTime(selected.updatedAt)}")
        TaskDetails(selected.task, selected.report, selected.volume, alias = alias, snapshot = true)
        OutlinedButton(onClick = { onDraft(selected.task) }, enabled = ui.task?.unresolved != true && !ui.unknownResult && !selected.task.unresolved,
            modifier = Modifier.fillMaxWidth()) { Text("以此新建任务") }
        Supporting("只填入草稿，检查并确认后才会启动新的出水。")
    } else {
        Supporting("本手机保存的最近 ${MasterHistory.LIMIT} 项任务；未保存的历史无法自动补回。")
        if (ui.history.isEmpty()) PumpCard("暂无任务记录") { Supporting("创建任务后，设备响应和已收到水量会保存在这里。") }
        ui.history.forEach { entry ->
            Card(onClick = { selectedKey = entry.key }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Supporting("${entry.deviceName} · ${displayTime(entry.updatedAt)}")
                    Text("${java.lang.Long.bitCount(entry.task.mask)} 路 · 每路 ${entry.task.ml} mL", style = MaterialTheme.typography.titleMedium)
                    Text(taskStatus(entry.task, entry.report), color = MaterialTheme.colorScheme.primary)
                    Supporting("任务 ${entry.task.job ?: "待确认"} · ${entry.volume?.let { "已收到 ${it.ml} mL" } ?: "水量待确认"} ›")
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(ui: MasterUiState, seconds: String, onSeconds: (String) -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    PumpCard("任务参数") {
        OutlinedTextField(seconds, { onSeconds(it.filter(Char::isDigit).take(3)) }, label = { Text("单路最长时间（1–240 秒）") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            isError = seconds.toLongOrNull()?.let { it in 1..240 } != true)
        Supporting("用于新任务；正在执行的任务参数保持不变。此时间是上限，不是预计完成时间。")
    }
    TextButton(onClick = { advanced = !advanced }) {
        Text(if (advanced) "收起高级设置" else "高级设置与诊断")
    }
    if (advanced) {
        Supporting("手机固定连接主机；从机组网和设备维护请到“设备”页操作。")
        TextButton(onClick = { diagnostics = !diagnostics }) {
            Text(if (diagnostics) "收起连接诊断" else "查看连接诊断")
        }
        if (diagnostics) {
            ui.hello?.let { Supporting("角色 ${it.role} · 地址 ${addressLabel(it.address)} · 启动标识 ${it.epoch} · 故障 ${it.fault}") }
            Supporting("服务 ${MasterBleManager.SERVICE_UUID}")
            Supporting(ui.log.joinToString("\n").ifEmpty { "尚无通信记录" })
        }
    }
    Supporting("泵阀控制 · ${BuildConfig.VERSION_NAME}")
}
