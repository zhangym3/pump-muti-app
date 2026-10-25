package com.cczb.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType

@Composable
internal fun CalibrationSettings(
    ui: MasterUiState,
    alias: (Int) -> String,
    onSelect: (Int) -> Unit,
    onRead: () -> Unit,
    onStart: (Long, Int) -> Unit,
    onMark: () -> Unit,
    onStop: () -> Unit,
    onQuery: () -> Unit,
    onSave: () -> Unit
) {
    val calibration = ui.calibration
    val snapshot = calibration.snapshot
    val session = ui.flowCalibration
    val locked = session?.locked == true
    val nodes = ui.slaves.orEmpty().filter { it.slot in 1..32 }.sortedBy { it.slot }
    var expanded by remember { mutableStateOf(false) }
    var standardMl by rememberSaveable(session?.request) {
        mutableStateOf(session?.standardMl?.toString() ?: "500")
    }
    var speed by rememberSaveable(session?.request) { mutableIntStateOf(session?.speed ?: 1) }
    val amount = standardMl.toLongOrNull()?.takeIf { standardMl.matches(Regex("[0-9]{1,4}")) }
    val startBlock = amount?.let { ui.flowCalibrationBlockReason(it, speed) }
        ?: if (amount == null || amount !in 50..2000) "标准水量应为 50–2000 mL 的整数" else null
    val matchingSession = session?.takeIf { it.slot == calibration.slot && it.address == calibration.address }

    PumpCard("交互式流量校准") {
        when {
            !ui.connected -> Supporting("连接主机后可开始校准。")
            ui.hello?.flowCalibration != true -> Supporting("当前主机固件不支持交互式校准，请先更新固件。")
            !ui.inventoryFresh -> Supporting("正在等待从机清单，请刷新主机状态。")
        }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = ui.canReadCalibration && !locked) {
                Text(nodes.firstOrNull { it.slot == calibration.slot && it.address == calibration.address }
                    ?.let { "${alias(it.slot)} · ${addressLabel(it.address)}" } ?: "选择从机")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                nodes.forEach { node ->
                    DropdownMenuItem(text = { Text("${alias(node.slot)} · ${addressLabel(node.address)}") },
                        onClick = { expanded = false; onSelect(node.slot) })
                }
            }
        }
        OutlinedButton(onClick = onRead, enabled = ui.canReadCalibration &&
            nodes.any { it.slot == calibration.slot && it.address == calibration.address }) {
            Text("读取当前系数")
        }
        snapshot?.takeIf { it.epoch == ui.hello?.epoch && it.slot == calibration.slot && it.address == calibration.address }?.let {
            Supporting("已保存：低 ${FlowCalibration.display(it.low)} · 中 ${FlowCalibration.display(it.medium)} · 高 ${FlowCalibration.display(it.high)} 脉冲/mL")
        }

        Text("校准档位", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("低 65%", "中 80%", "高 100%").forEachIndexed { index, label ->
                FilterChip(selected = speed == index + 1, onClick = { speed = index + 1 },
                    enabled = !locked, label = { Text(label) })
            }
        }
        val confirmedSpeed = ui.pwm.snapshot?.takeIf { ui.pwmFresh && it.epoch == ui.hello?.epoch }?.level
        Supporting(if (confirmedSpeed == speed) "主机已确认当前档位" else "开始前将先把主机切换到所选档位")
        OutlinedTextField(value = standardMl, onValueChange = { standardMl = it.filter(Char::isDigit).take(4) },
            label = { Text("量杯标准水量（mL）") }, singleLine = true, enabled = !locked,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(), isError = amount == null || amount !in 50..2000)
        Supporting("先将出水导入废水容器完成预出水，再开始人工量取；输入值应是量杯实测水量。")
        Button(onClick = { amount?.let { onStart(it, speed) } },
            enabled = !locked && startBlock == null, modifier = Modifier.fillMaxWidth()) {
            Text("启动并预出水")
        }
        if (!locked && startBlock != null) Supporting(startBlock)
        Supporting(calibration.message)

        if (session != null) {
            HorizontalDivider()
            Supporting("校准状态：${flowCalibrationPhase(session.phase)} · ${alias(session.slot)} · ${listOf("低", "中", "高").getOrElse(session.speed - 1) { "未知" }}档 · ${session.standardMl} mL")
            if (session.phase == "PREOUT") Supporting("预出水中的脉冲不计入样本；水流稳定后点击开始人工计量。")
            if (session.phase == "MEASURING") {
                val counted = (session.livePulses - session.startPulses).coerceAtLeast(0)
                Supporting("已计入脉冲：$counted · 运行 ${session.runtimeMs / 1000} 秒")
            }
            if (session.phase == "MARK_PENDING") Supporting("正在等待从机确认计量起点，确认前不计入样本。")
            if (session.phase == "STOPPING") Supporting("正在停止水泵并等待从机锁定终点与尾流结算。")
            if (session.phase in setOf("UNKNOWN", "INVALID", "FAILED"))
                Supporting("结果待核实或无效：${session.reason} · ${session.reply}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onMark, enabled = session.canMark && ui.connected,
                    modifier = Modifier.weight(1f)) { Text("开始人工计量") }
                OutlinedButton(onClick = onStop, enabled = ui.canStopFlowCalibration,
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error)) { Text("停止并结算") }
            }
            OutlinedButton(onClick = onQuery, enabled = ui.canQueryFlowCalibration,
                modifier = Modifier.fillMaxWidth()) { Text("查询校准状态") }
            if (session.phase == "COMPLETE") {
                Supporting("计量区间脉冲：${session.measuredPulses ?: 0} · 尾流脉冲：${session.tailPulses ?: 0}")
                Supporting("起点 ${session.startPulses} · 停止锁定 ${session.closePulses} · 终态 ${session.finalPulses}")
                Supporting("计算值：${session.calculatedK?.let(FlowCalibration::display) ?: "无效样本"} 脉冲/mL")
                Button(onClick = onSave, enabled = ui.canSaveFlowCoefficient,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (session.saved) "主机已确认保存当前档" else "保存当前档系数")
                }
                if (!session.saved && !ui.canSaveFlowCoefficient)
                    Supporting(if (snapshot == null) "请重新读取主机当前系数后再保存。" else "当前状态不允许保存，请确认主机空闲且校准结果有效。")
            }
        }
    }
}

private fun flowCalibrationPhase(phase: String): String = when (phase) {
    "START_PENDING", "STARTING" -> "启动中"
    "PREOUT" -> "预出水"
    "MARK_PENDING" -> "等待起点确认"
    "MEASURING" -> "人工计量中"
    "STOPPING" -> "停止结算中"
    "COMPLETE" -> "已完成"
    "INVALID" -> "样本无效"
    "UNKNOWN" -> "结果待核实"
    "FAILED" -> "启动失败"
    else -> "未开始"
}
