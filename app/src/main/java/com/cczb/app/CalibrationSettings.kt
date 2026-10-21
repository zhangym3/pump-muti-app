package com.cczb.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

@Composable
internal fun CalibrationSettings(
    ui: MasterUiState, alias: (Int) -> String,
    onSelect: (Int) -> Unit, onRead: () -> Unit, onSave: (String, String, String) -> Unit
) {
    val calibration = ui.calibration
    val snapshot = calibration.snapshot
    var expanded by remember { mutableStateOf(false) }
    val nodes = ui.slaves.orEmpty().filter { it.slot in 1..32 }.sortedBy { it.slot }
    PumpCard("霍尔流量分档调校") {
        Supporting("每个从节点分别保存低 50%、中 75%、高 100% 的每毫升脉冲数。系数越大，同一目标水量需要的脉冲越多。")
        when {
            !ui.connected -> Supporting("连接主机后可读取和保存调校参数。")
            ui.hello?.calibration != true -> Supporting("请更新主机与从机固件后使用分档调校。")
            !ui.inventoryFresh -> Supporting("正在等待从节点清单，请刷新主机状态。")
            nodes.isEmpty() -> Supporting("请先在设备页添加从节点。")
        }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = ui.canReadCalibration && nodes.isNotEmpty()) {
                Text(nodes.firstOrNull { it.slot == calibration.slot && it.address == calibration.address }
                    ?.let { "${alias(it.slot)} · ${addressLabel(it.address)}" } ?: "选择从节点")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                nodes.forEach { node ->
                    DropdownMenuItem(text = { Text("${alias(node.slot)} · ${addressLabel(node.address)}") },
                        onClick = { expanded = false; onSelect(node.slot) })
                }
            }
        }
        OutlinedButton(onClick = onRead, enabled = ui.canReadCalibration &&
            nodes.any { it.slot == calibration.slot && it.address == calibration.address }) { Text("读取主机保存值") }
        Supporting(calibration.message)
        if (snapshot != null) key(snapshot.epoch, snapshot.slot, snapshot.address, snapshot.revision) {
            var low by rememberSaveable { mutableStateOf(FlowCalibration.display(snapshot.low)) }
            var medium by rememberSaveable { mutableStateOf(FlowCalibration.display(snapshot.medium)) }
            var high by rememberSaveable { mutableStateOf(FlowCalibration.display(snapshot.high)) }
            var speed by rememberSaveable { mutableIntStateOf(1) }
            var pulses by rememberSaveable { mutableStateOf("") }
            var volume by rememberSaveable { mutableStateOf("") }
            var confirmation by remember { mutableStateOf(false) }
            val editable = ui.canSaveCalibration
            val labels = listOf("低档 50%", "中档 75%", "高档 100%")
            Supporting("主机已读值：${snapshot.values.joinToString(" / ") { FlowCalibration.display(it) }} 脉冲/mL · 版本 ${snapshot.revision}")
            listOf(low, medium, high).forEachIndexed { index, value ->
                OutlinedTextField(value = value, onValueChange = { input ->
                    val next = input.take(12)
                    when (index) { 0 -> low = next; 1 -> medium = next; else -> high = next }
                }, label = { Text("${labels[index]} · 脉冲/mL") }, enabled = editable,
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(), isError = FlowCalibration.parse(value) == null)
            }
            Supporting("支持 0.001–1000.000 脉冲/mL，最多三位小数。三档可独立修改，保存时一并提交。")
            Text("按实测出水计算", style = MaterialTheme.typography.titleSmall)
            Supporting("在控制页选择同一节点、同一档位完成单路出水，记录结束后的总脉冲数，并用量杯测实际出水量。请勿填写 APP 根据脉冲换算的水量。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                labels.forEachIndexed { index, label ->
                    FilterChip(selected = speed == index + 1, onClick = { speed = index + 1 },
                        enabled = editable, label = { Text(label) })
                }
            }
            OutlinedTextField(pulses, { pulses = it.filter(Char::isDigit).take(10) },
                label = { Text("测试总脉冲数") }, singleLine = true, enabled = editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(volume, { volume = it.take(12) }, label = { Text("量杯实测出水量（mL）") },
                singleLine = true, enabled = editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            val calculated = FlowCalibration.calculate(pulses, volume)
            if (calculated != null) Supporting("计算结果：${FlowCalibration.display(calculated)} 脉冲/mL")
            else if (pulses.isNotEmpty() || volume.isNotEmpty()) Supporting("请输入大于零的脉冲数和实测水量；0 脉冲无法校准。")
            OutlinedButton(onClick = {
                calculated?.let { k -> when (speed) {
                    1 -> low = FlowCalibration.display(k)
                    2 -> medium = FlowCalibration.display(k)
                    else -> high = FlowCalibration.display(k)
                } }
            }, enabled = editable && calculated != null) { Text("填入${labels[speed - 1]}系数") }
            Button(onClick = { confirmation = true }, enabled = editable &&
                listOf(low, medium, high).all { FlowCalibration.parse(it) != null }) { Text("保存三档调校") }
            if (!editable && calibration.pending == null) Supporting("保存前需重新读取成功，并确认任务、试泵及组网已结束。")
            Supporting("保存仅更新计量参数，下次定量任务按所选档位使用；从机需同步更新固件。建议每档重复量杯验证后取稳定值。")
            if (confirmation) AlertDialog(onDismissRequest = { confirmation = false },
                title = { Text("保存 ${alias(calibration.slot)} 的调校？") },
                text = { Text("低档 $low\n中档 $medium\n高档 $high\n单位：脉冲/mL；下次定量任务生效。") },
                confirmButton = { TextButton(onClick = { confirmation = false; onSave(low, medium, high) },
                    enabled = editable) { Text("确认保存") } },
                dismissButton = { TextButton(onClick = { confirmation = false }) { Text("取消") } })
        }
    }
}
