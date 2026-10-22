package com.cczb.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TaskComposer(
    ui: MasterUiState, mask: Long, amount: String, seconds: String,
    individual: Boolean, amounts: List<String>, alias: (Int) -> String,
    onMask: (Long) -> Unit, onAmount: (String) -> Unit,
    onMode: (Boolean) -> Unit, onValveAmount: (Int, String) -> Unit, onConfirm: () -> Unit,
    onSpeed: (Int) -> Unit = {}
) {
    val ml = amount.toLongOrNull()
    val duration = seconds.toLongOrNull()
    val plan = if (individual) ValveAmounts.encode(mask, amounts) else null
    val valid = mask != 0L && mask and ui.selectableMask.inv() == 0L &&
        (if (individual) plan != null && ui.hello?.individual == true else ml != null && VolumeRules.isValid(ml)) &&
        duration != null && duration in 1..240
    val count = java.lang.Long.bitCount(mask)
    val draftBlockReason = when {
        !ui.inventoryFresh -> "请连接主机并等待阀门清单同步。"
        ui.selectableMask == 0L -> "没有在线且已启用的阀门，请先在设备页完成组网和现场配置。"
        count == 0 -> "请至少选择一个在线且已启用的阀门。"
        mask and ui.selectableMask.inv() != 0L -> "所选阀门状态已变化，请重新选择。"
        individual && ui.hello?.individual != true -> "当前主机固件不支持单独设置任务；可切换为统一设置。"
        individual && plan == null || !individual && (ml == null || !VolumeRules.isValid(ml)) ->
            "请将所选阀门水量设为 50–2000 mL，且为 50 mL 的整数倍。"
        duration == null || duration !in 1..240 -> "请在设置页将全局单路最长时间设为 1–240 秒。"
        else -> null
    }
    // Selection and background inventory refresh must never regroup the cards.
    val nodes = ui.slaves.orEmpty().filter { it.slot in 1..32 }.sortedBy { it.slot }
    val columns = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360 ||
        androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.25f) 1 else 2

    PumpCard("任务参数", icon = R.drawable.ic_water) {
        Text("出水模式", style = MaterialTheme.typography.labelLarge)
        SegmentedChoices(listOf("统一水量", "各路自定义"), if (individual) 1 else 0,
            enabled = true, onSelected = { onMode(it == 1) })
        if (individual) {
            Supporting("各路水量在阀门卡片中设置 · 50–2000 mL · 步进 50 mL")
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("每路目标水量", style = MaterialTheme.typography.labelLarge)
                Text("50–2000 mL · 步进 50", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AmountPresets(amount, true, onAmount)
            AmountEditor(amount, true, onAmount)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("本次速度", style = MaterialTheme.typography.labelLarge)
            Supporting("选档不启动水泵")
        }
        SegmentedChoices(listOf("低档 50%", "中档 75%", "高档 100%"),
            ui.pwm.snapshot?.level?.minus(1) ?: -1, enabled = ui.canSetSpeed,
            onSelected = { onSpeed(it + 1) })
        val pwm = ui.pwm.snapshot
        val pendingLevel = ui.pwm.pending?.level
        when {
            pendingLevel != null -> Supporting("等待主机确认：${listOf("低档 50%", "中档 75%", "高档 100%")[pendingLevel - 1]}")
            pwm == null -> Supporting("主机档位未同步；连接主机并刷新后可选择。")
            else -> Supporting("主机确认：${listOf("低档 50%", "中档 75%", "高档 100%")[pwm.level - 1]} · ${pwm.hz} Hz${if (ui.pwmFresh) "" else " · 状态已过期"}")
        }
        if (pendingLevel == null && ui.pwm.message != "已同步主机档位；选择档位不会启动水泵" && ui.pwm.message != "等待主机同步 PWM 档位") {
            Supporting(ui.pwm.message)
        }
    }
    if (individual && ui.connected && ui.hello != null && !ui.hello.individual) {
        Feedback("当前主机固件需更新后才能启动单独设置任务；可先编辑各路水量。")
    }
    PumpCard("选择出水阀门", icon = R.drawable.ic_valve, header = {
        Text("已选 $count / ${nodes.size} 路", style = MaterialTheme.typography.labelMedium)
    }) {
        // A fixed status line avoids inserting/removing a full banner on every poll.
        Text(when {
            !ui.inventoryFresh -> ui.inventoryMessage
            ui.inventoryLoading -> "正在更新阀门状态…"
            individual -> "点选阀门，并在已选卡片中编辑各路目标量"
            else -> "可多选 · 任务按阀门编号逐路执行"
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (nodes.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onMask(ui.selectableMask) }, enabled = ui.inventoryFresh && ui.selectableMask != 0L) { Text("全选在线") }
            TextButton(onClick = { onMask(ui.selectableMask and mask.inv()) }, enabled = ui.inventoryFresh && ui.selectableMask != 0L) { Text("反选") }
            TextButton(onClick = { onMask(0) }, enabled = mask != 0L) { Text("清空") }
        }
        if (nodes.isEmpty()) {
            Supporting(if (ui.inventoryFresh) "请前往设备页添加从机并完成启用配置。" else "请连接主机并等待阀门清单同步。")
        } else nodes.chunked(columns).forEach { pair ->
            key(pair.first().slot) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { node ->
                        key(node.slot, node.address) {
                            val selected = mask and (1L shl (node.slot - 1)) != 0L
                            ValveChoiceCard(node, alias(node.slot), selected,
                                if (individual) amounts[node.slot - 1] else amount,
                                enabled = ui.inventoryFresh && node.selectable,
                                individual = individual,
                                onAmount = { onValveAmount(node.slot, it) },
                                onClick = { onMask(mask xor (1L shl (node.slot - 1))) }, modifier = Modifier.weight(1f))
                        }
                    }
                    if (pair.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    val targetTotal = if (individual) ValveAmounts.slots(mask).sumOf { amounts[it - 1].toLongOrNull() ?: 0L }
        else (ml ?: 0L) * count
    TaskDraftSummary(count, targetTotal)
    if (!ui.canStart && ui.connected) ui.startBlockReason?.let { Feedback("暂不可启动：$it") }
    Button(onClick = onConfirm, enabled = valid && ui.canStart,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(15.dp)) {
        Icon(painterResource(R.drawable.ic_power), null, Modifier.size(21.dp))
        Spacer(Modifier.width(10.dp))
        Text("检查并启动", style = MaterialTheme.typography.titleSmall)
    }
    if (!valid && (nodes.isNotEmpty() || ui.inventoryFresh)) draftBlockReason?.let { Feedback(it) }
    Supporting("每路最长 $seconds 秒 · 任务接受后由主机继续执行")
}

@Composable
private fun SegmentedChoices(options: List<String>, selected: Int, enabled: Boolean, onSelected: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                    .clickable(enabled = enabled, role = Role.RadioButton, onClick = { onSelected(index) })
                    .semantics { this.selected = active; contentDescription = label.replace('\n', ' ') },
                shape = RoundedCornerShape(7.dp),
                color = if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Box(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                        maxLines = 2, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun TaskDraftSummary(count: Int, targetTotal: Long) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Supporting("本次目标总量")
                Text("$targetTotal mL", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$count 路阀门", style = MaterialTheme.typography.titleSmall)
                Supporting("按编号逐路执行")
            }
        }
    }
}

@Composable
private fun AmountPresets(value: String, enabled: Boolean, onValue: (String) -> Unit, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 8.dp)) {
        listOf(100, 250, 500, 1000).forEach { preset ->
            val selected = value == preset.toString()
            Surface(modifier = Modifier.weight(1f).heightIn(min = if (compact) 36.dp else 42.dp)
                .clickable(enabled = enabled, role = Role.Button) { onValue(preset.toString()) },
                shape = RoundedCornerShape(8.dp),
                color = if (selected && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) {
                    Text(preset.toString(), maxLines = 1, fontSize = if (compact) 11.sp else 14.sp,
                        color = if (selected && enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f))
                }
            }
        }
    }
}

@Composable
private fun AmountEditor(value: String, enabled: Boolean, onValue: (String) -> Unit, compact: Boolean = false) {
    val ml = value.toLongOrNull()
    val valid = ml != null && VolumeRules.isValid(ml)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        FilledTonalButton(onClick = { onValue(VolumeRules.previous(ml).toString()) },
            enabled = enabled && (ml == null || ml > VolumeRules.MIN_ML),
            modifier = Modifier.width(if (compact) 32.dp else 44.dp).height(48.dp),
            contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(10.dp)) { Text("−", fontSize = 22.sp) }
        androidx.compose.foundation.text.BasicTextField(value = value,
            onValueChange = { onValue(it.filter(Char::isDigit).take(4)) }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(fontSize = if (compact) 20.sp else 24.sp, lineHeight = 30.sp,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f)),
            modifier = Modifier.weight(1f).semantics { contentDescription = "出水量 mL" },
            decorationBox = { input ->
                Column(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .border(1.dp, if (!valid && enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    input()
                    Text("mL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
        FilledTonalButton(onClick = { onValue(VolumeRules.next(ml).toString()) },
            enabled = enabled && (ml == null || ml < VolumeRules.MAX_ML),
            modifier = Modifier.width(if (compact) 32.dp else 44.dp).height(48.dp),
            contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(10.dp)) { Text("+", fontSize = 22.sp) }
    }
}

@Composable
private fun ValveChoiceCard(
    node: MasterSlave,
    name: String,
    selected: Boolean,
    amount: String,
    enabled: Boolean,
    individual: Boolean,
    onAmount: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val outline = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant
    val state = when {
        node.selectable -> "在线" to MaterialTheme.colorScheme.tertiary
        !node.online -> "离线" to MaterialTheme.colorScheme.error
        else -> "未配置" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = modifier.heightIn(min = if (individual) 116.dp else 82.dp),
        shape = RoundedCornerShape(8.dp), border = BorderStroke(if (selected) 1.5.dp else 1.dp, outline),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
        Column(Modifier.fillMaxWidth().padding(7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick).semantics {
                this.selected = selected
                contentDescription = "$name · ID ${node.slot.toString().padStart(2, '0')} · ${state.first}"
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(22.dp).then(if (selected) Modifier else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))),
                    contentAlignment = Alignment.Center) {
                    if (selected) Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.primary) {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_check), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(10.dp), color = if (node.selectable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_valve), null, modifier = Modifier.size(23.dp),
                            tint = if (node.selectable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Supporting("ID: ${node.slot.toString().padStart(2, '0')}")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Surface(Modifier.size(7.dp), shape = RoundedCornerShape(50), color = state.second) {}
                        Text(state.first, color = state.second, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (individual) {
                AmountEditor(amount, selected && enabled, onAmount, compact = true)
                AmountPresets(amount, selected && enabled, onAmount, compact = true)
            } else Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                Text("$amount mL / 路", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), textAlign = TextAlign.Center,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun TaskStartConfirmation(ui: MasterUiState, mask: Long, amount: String, seconds: String,
    individual: Boolean, amounts: List<String>, valid: Boolean, alias: (Int) -> String,
    onDismiss: () -> Unit, onConfirm: () -> Unit, onQuery: () -> Unit = {}) {
    val slots = ValveAmounts.slots(mask)
    val total = if (individual) slots.sumOf { amounts[it - 1].toLongOrNull() ?: 0 } else (amount.toLongOrNull() ?: 0) * slots.size
    AlertDialog(onDismissRequest = onDismiss, title = { Text("确认本次出水") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Supporting("${slots.size} 路阀门 · 按编号顺序执行")
                slots.forEach { slot -> ReviewLine(alias(slot), "${if (individual) amounts[slot - 1] else amount} mL") }
                HorizontalDivider()
                ReviewLine("目标总量", "$total mL")
                ReviewLine("单路时限", "$seconds 秒")
                ReviewLine("水泵速度", ui.pwm.snapshot?.let { listOf("低档 50%", "中档 75%", "高档 100%")[it.level - 1] } ?: "以主机当前档位为准")
                if (!valid) Feedback("任务参数已变化或无效，请返回修改。")
                if (!ui.canStart) ui.startBlockReason?.let { Feedback("暂不可启动：$it") }
                if (ui.taskNeedsReconciliation) {
                    ui.taskReconciliationDetails.forEach { Feedback(it) }
                    OutlinedButton(onClick = onQuery, enabled = ui.connected) { Text("查询原任务") }
                }
                Supporting("请确认供水与出水通路正常。手机断连后，已接受的任务继续执行。")
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = valid && ui.canStart) { Text("确认启动") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回修改") } })
}

@Composable
private fun ReviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Supporting(label, Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun CurrentTaskSection(ui: MasterUiState, alias: (Int) -> String, onQuery: () -> Unit, onRetry: () -> Unit) {
    val task = ui.task ?: return
    var details by rememberSaveable(task.client, task.seq) { mutableStateOf(false) }
    val expanded = details || ui.taskNeedsReconciliation || ui.unknownResult
    val saved = ui.savedTaskObservation
    val report = ui.report ?: saved?.report
    PumpCard(if (task.unresolved) "当前任务" else "最近一次任务", header = {
        if (!ui.taskNeedsReconciliation && !ui.unknownResult)
            TextButton(onClick = { details = !details }) { Text(if (expanded) "收起结果" else "展开结果") }
    }) {
        Text(taskStatus(task, report), style = MaterialTheme.typography.titleMedium)
        Supporting("${java.lang.Long.bitCount(task.mask)} 路 · ${task.amountLabel} · 任务 ${task.job ?: "编号待确认"}")
        TaskProgressSummary(ui, task)
        LiveFlowPanel(ui, alias)
        if (ui.taskNeedsReconciliation) ui.taskReconciliationDetails.forEach { Supporting(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onQuery, enabled = ui.connected) { Text("查询原任务") }
            if (task.outcome == MasterOutcome.PENDING)
                OutlinedButton(onClick = onRetry, enabled = ui.canRetry) { Text("重试原请求") }
        }
    }
    if (expanded) TaskDetails(task, report, ui.volume ?: saved?.volume, saved?.flows.orEmpty() + ui.flows, alias,
        snapshot = !ui.connected || ui.report == null,
        expectedFlowAddresses = ui.expectedFlowAddresses, nowElapsed = ui.flowClockAt)
}

@Composable
private fun TaskProgressSummary(ui: MasterUiState, task: MasterTask) {
    val nodes = ui.slaves.orEmpty().associateBy { it.slot }
    val slots = ValveAmounts.slots(task.mask)
    val readings = slots.mapNotNull { slot ->
        val address = nodes[slot]?.address ?: return@mapNotNull null
        val final = ui.flows[address]?.takeIf { it.job == task.job && it.terminal }
        val live = ui.liveFlows[address]?.takeIf { it.job == task.job }
        (final?.takeIf { it.measured }?.ml ?: live?.ml)?.let { it }
    }
    val current = readings.sum()
    val ended = slots.count { slot ->
        val address = nodes[slot]?.address ?: return@count false
        ui.flows[address]?.let { it.job == task.job && it.terminal } == true
    }
    val target = task.totalMl.coerceAtLeast(1L)
    val percent = (current.toFloat() / target).coerceIn(0f, 1f)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricBlock("目标总量", "${task.totalMl} mL", Modifier.weight(1f))
                MetricBlock("已上报", if (readings.isEmpty()) "—" else "$current mL", Modifier.weight(1f))
                MetricBlock("已结束路数", "$ended/${slots.size}", Modifier.weight(1f))
            }
            if (readings.isNotEmpty()) {
                LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant)
            }
            Supporting("已收到 ${readings.size}/${slots.size} 路计量数据")
        }
    }
}

@Composable
private fun MetricBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Supporting(label)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
internal fun LiveFlowPanel(ui: MasterUiState, alias: (Int) -> String) {
    val task = ui.task ?: return
    (1..32).filter { task.mask and (1L shl (it - 1)) != 0L }.forEach { slot ->
        val node = ui.slaves.orEmpty().firstOrNull { it.slot == slot }
        val final = node?.let { ui.flows[it.address] }?.takeIf { it.job == task.job && it.terminal }
        val sample = node?.let { ui.liveFlows[it.address] }?.takeIf { it.job == task.job && ui.connected }
        val fresh = sample?.isFresh(ui.flowClockAt) == true
        val target = task.target(slot).coerceAtLeast(1L)
        val currentMl = final?.takeIf { it.measured }?.ml ?: sample?.ml
        val progress = ((currentMl ?: 0L).toFloat() / target).coerceIn(0f, 1f)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_water), contentDescription = null, Modifier.size(21.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(alias(slot), style = MaterialTheme.typography.titleSmall)
                        Supporting("目标 $target mL")
                    }
                    StatePill(when {
                        final?.phase == "FAULT" -> "异常停止"
                        final?.reason == "MANUAL" -> "已停止"
                        final?.phase == "DONE" && final.reason == "TARGET" && final.measured -> "已完成"
                        final != null -> "待核实"
                        sample != null && fresh -> "抽水中"
                        sample != null -> "数据过期"
                        !task.unresolved -> "等待结果"
                        else -> "等待数据"
                    }, attention = final != null && (final.phase == "FAULT" || !final.measured))
                }
                if (currentMl != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant)
                when {
                    final != null -> {
                        Text(if (final.measured) "最终出水 ${final.ml} mL · ${final.pulses} 脉冲" else "最终计量待核实")
                        Supporting("已停止 · ${stopReasonLabel(final.reason)}")
                    }
                    sample != null -> {
                        Text("累计 ${sample.ml} mL · ${sample.pulses} 脉冲")
                        Text(if (fresh) "当前流速 ${sample.rateMlMin} mL/min" else "当前流速 —")
                        Supporting(if (fresh) "实时更新 · 约每 2 秒" else "数据已过期，等待更新；累计量为上次快照")
                    }
                    else -> Supporting(when {
                        !ui.connected -> "未连接主机，实时数据暂停"
                        !task.unresolved || ui.stopPending -> "等待最终结果"
                        else -> "等待实时数据"
                    })
                }
            }
        }
    }
}

@Composable
internal fun SlaveRemovalConfirmation(ui: MasterUiState, address: Long, alias: (Int) -> String,
    onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val target = ui.slaves?.firstOrNull { it.address == address }
    val name = target?.takeIf { it.slot > 0 }?.let { alias(it.slot) } ?: "待配置从机"
    AlertDialog(onDismissRequest = onDismiss, title = { Text("确认删除从机") },
        text = { Text(buildString {
            append("删除 $name（${addressLabel(address)}）后，将清除主机保存的该从机连接、登记和调校数据。主机会尝试通知从机退网；若从机离线或无回复，重新添加前需在从机本地清除 Mesh。")
            target?.let { ui.slaveRemovalBlockReason(it)?.let { reason -> append("\n\n当前无法删除：$reason") } }
        }) },
        confirmButton = { TextButton(onClick = { if (target != null && ui.canRemoveSlave(target)) onConfirm(address) },
            enabled = target != null && ui.canRemoveSlave(target)) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun DevicesScreen(ui: MasterUiState, alias: (Int) -> String, onRename: (Int) -> Unit, onPump: () -> Unit, onRefresh: () -> Unit,
                           showAddDevice: Boolean, onOpenAdd: () -> Unit,
                           onJoin: (String) -> Unit = {}, onRetry: (Long) -> Unit = {},
                           onResetMesh: () -> Unit = {}, onCommission: (Int) -> Unit = {},
                           onCloseAdd: () -> Unit = {}, pumpExpanded: Boolean = false,
                           pumpContent: @Composable () -> Unit = {}, onRemove: (Long) -> Unit = {}) {
    var showMaintenance by rememberSaveable { mutableStateOf(false) }
    MasterSummary(ui, onRefresh)
    PumpCard("水泵测试与调速", icon = R.drawable.ic_power, header = {
        TextButton(onClick = onPump) { Text(if (pumpExpanded) "收起测试" else "展开测试") }
    }) {
        Supporting(ui.pumpOutputLabel)
        if (!pumpExpanded) Supporting("在本页展开，选择速度与时长后测试。")
    }
    if (pumpExpanded) pumpContent()
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
        TextButton(onClick = onCloseAdd) { Text("收起搜索结果") }
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
            Supporting("${if (!ui.inventoryFresh) "状态待同步" else if (!node.online) "离线" else "在线"} · ${if (node.commissioned) "已启用" else "待启用配置"}")
                }
                if (slot > 0) TextButton(onClick = { onRename(slot) }) { Text("编辑") }
            }
            OutlinedButton(onClick = { onRemove(node.address) }, enabled = ui.canOpenSlaveRemoval(node),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = "删除从机 ${addressLabel(node.address)}" },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("删除从机 · ${addressLabel(node.address)}")
            }
            if (slot in 1..32 && !node.commissioned) {
                OutlinedButton(onClick = { onCommission(slot) }, enabled = ui.canCommission(node),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (ui.commissionPendingSlot == slot) "正在启用…" else "启用配置 · ${slot.toString().padStart(2, '0')} 号阀门")
                }
            }
            if (!node.online || slot == 0) OutlinedButton(onClick = { onRetry(node.address) }, enabled = ui.canManageMesh,
                modifier = Modifier.fillMaxWidth()) { Text("重试连接配置 · ${addressLabel(node.address)}") }
        }
        if (ui.slaves?.isEmpty() == true) Supporting("Master 尚未配对从机。")
        if (ui.slaves.orEmpty().any { !it.online || it.slot == 0 })
            Supporting("离线或待启用配置的阀门不能执行出水任务。")
        if (ui.commissionMessage.isNotEmpty()) Feedback(ui.commissionMessage)
        ui.removal?.let { Feedback(it.message) }
        if (ui.removal == null && ui.mesh?.phase in setOf("REMOVING", "REMOVE_CLEANUP", "REMOVE_DONE"))
            Feedback(ui.meshMessage)
        if (ui.connected && ui.meshFresh && ui.mesh?.supportsRemoval == false && !ui.slaves.isNullOrEmpty())
            Supporting("单台删除需要更新主机固件。")
    }
    TextButton(onClick = { showMaintenance = !showMaintenance }) {
        Text(if (showMaintenance) "收起维护" else "设备维护")
    }
    if (showMaintenance) {
        OutlinedButton(onClick = onResetMesh, enabled = ui.canResetMesh && !ui.meshPending,
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(if (ui.meshPending && ui.mesh?.phase == "RESETTING") "正在重置…" else "重置从机组网")
        }
        Supporting("重置会清除主机保存的全部从机连接信息，并自动重启。")
    }
}

@Composable
internal fun PumpTestScreen(ui: MasterUiState, duration: Int, onDuration: (Int) -> Unit,
                            onRefresh: () -> Unit,
                            onStart: () -> Unit, onStop: () -> Unit, onSpeed: (Int) -> Unit = {},
                            onResetBootLock: () -> Unit = {}) {
    var confirmBootUnlock by rememberSaveable { mutableStateOf(false) }
    val blockReason = ui.testBlockReason
    if (confirmBootUnlock) AlertDialog(
        onDismissRequest = { confirmBootUnlock = false },
        title = { Text("解除主机启动锁") },
        text = { Text("主机确认当前无任务、无未知结果、无输出且 Mesh 空闲后，将解除本次启动锁定。此操作不会启动水泵；重启主机后仍需再次确认解除。") },
        confirmButton = { TextButton(onClick = {
            confirmBootUnlock = false
            onResetBootLock()
        }, enabled = ui.canResetBootLock) { Text("确认解除") } },
        dismissButton = { TextButton(onClick = { confirmBootUnlock = false }) { Text("取消") } }
    )
    PumpCard("通水测试") {
        if (ui.local?.fault == "BOOT_LOCK") {
            val resetHint = if (!ui.local.pumpResetSupported) "当前主机固件不支持手机解除启动锁，请更新主机固件。" else "主机处于启动锁定，正常出水已禁用。"
            Feedback(ui.pumpResetMessage.ifEmpty { resetHint })
            OutlinedButton(onClick = { confirmBootUnlock = true }, enabled = ui.canResetBootLock,
                modifier = Modifier.fillMaxWidth()) { Text(if (ui.pumpResetRequest != null) "正在确认…" else "解除主机启动锁") }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(painterResource(R.drawable.ic_power), null, Modifier.padding(20.dp).size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(ui.pumpOutputLabel, style = MaterialTheme.typography.titleLarge)
            Supporting("控制输出状态，以主机返回为准")
        }
        HorizontalDivider()
        Supporting("选择档位和时长，点击启动测试；可随时停止。")
        Text("测试速度", style = MaterialTheme.typography.titleMedium)
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
internal fun RecordsScreen(ui: MasterUiState, alias: (Int) -> String, expandedKey: String?,
    onToggle: (String) -> Unit, onDraft: (MasterTask) -> Unit) {
    if (!ui.historyHealthy) Feedback("本地记录保存或读取失败，当前记录可能不完整。")
    Supporting("最近 ${MasterHistory.LIMIT} 项本机记录，点击卡片直接展开结果与脉冲。")
    if (ui.history.isEmpty()) PumpCard("暂无任务记录") { Supporting("创建任务后，设备响应和已收到水量会保存在这里。") }
    ui.history.forEach { entry ->
        key(entry.key) {
            val expanded = entry.key == expandedKey
            Card(onClick = { onToggle(entry.key) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = if (expanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Supporting("${entry.deviceName} · ${displayTime(entry.updatedAt)}")
                    Text("${java.lang.Long.bitCount(entry.task.mask)} 路 · ${entry.task.amountLabel}", style = MaterialTheme.typography.titleMedium)
                    Text(taskStatus(entry.task, entry.report), color = MaterialTheme.colorScheme.primary)
                    val volume = entry.volume.takeIf { MasterResultMerge.hasVolumeData(it, entry.task.job, entry.task.mask) }
                    Supporting("任务 ${entry.task.job ?: "待确认"} · ${volume?.let { "已收到 ${it.ml} mL" } ?: "水量待确认"}")
                    Text(if (expanded) "收起详情 ↑" else "查看结果与脉冲 ↓", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (expanded) {
                TaskDetails(entry.task, entry.report,
                    entry.volume.takeIf { MasterResultMerge.hasVolumeData(it, entry.task.job, entry.task.mask) },
                    entry.flows, alias = alias, snapshot = true)
                OutlinedButton(onClick = { onDraft(entry.task) },
                    enabled = !ui.taskNeedsReconciliation && !ui.unknownResult && !entry.task.unresolved,
                    modifier = Modifier.fillMaxWidth()) { Text("填入控制页草稿") }
                Supporting("只复制参数；在控制页确认后才会启动出水。离线阀门不会选入草稿。")
            }
        }
    }
}

@Composable
internal fun SettingsScreen(ui: MasterUiState, seconds: String, onSeconds: (String) -> Unit,
    alias: (Int) -> String = { "${it} 号阀门" }, onSelectCalibration: (Int) -> Unit = {},
    onReadCalibration: () -> Unit = {}, onStartFlowCalibration: (Long, Int) -> Unit = { _, _ -> },
    onMarkFlowCalibration: () -> Unit = {}, onStopFlowCalibration: () -> Unit = {},
    onQueryFlowCalibration: () -> Unit = {}, onSaveFlowCalibration: () -> Unit = {}) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    PumpCard("全局任务参数") {
        OutlinedTextField(seconds, { onSeconds(it.filter(Char::isDigit).take(3)) }, label = { Text("单路最长时间（1–240 秒）") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            isError = seconds.toLongOrNull()?.let { it in 1..240 } != true)
        Supporting("所有阀门、统一设置和单独设置共用此时限，新任务立即生效；正在执行的任务保持原时限。")
    }
    CalibrationSettings(ui, alias, onSelectCalibration, onReadCalibration, onStartFlowCalibration,
        onMarkFlowCalibration, onStopFlowCalibration, onQueryFlowCalibration, onSaveFlowCalibration)
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
