package com.cczb.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

internal fun displayTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
internal fun addressLabel(address: Long): String = address.toString(16).padStart(4, '0').uppercase()

internal fun taskStatus(task: MasterTask, report: MasterMessage.Report?): String = when {
    task.outcome == MasterOutcome.PENDING -> "启动待确认"
    task.outcome == MasterOutcome.REJECTED -> "主机未接受"
    report?.unknown != null && report.unknown != 0L -> "部分结果待核实"
    report?.failed != null && report.failed != 0L -> "部分阀门执行异常"
    task.outcome == MasterOutcome.FINISHED -> if (report?.phase == 7) "任务异常结束" else "任务已结束"
    report != null -> MasterProtocol.phase(report.phase)
    else -> "已接受 · 等待任务状态"
}

@Composable
internal fun PumpCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun Supporting(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun StatePill(text: String, attention: Boolean = false) {
    Surface(shape = RoundedCornerShape(8.dp), color = if (attention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium,
            color = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun Feedback(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun MasterSummary(ui: MasterUiState, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatePill(when {
            !ui.connected -> "未连接"
            !ui.statusFresh -> "同步中"
            ui.hello?.fault != "NONE" || ui.localFresh && ui.local?.fault?.let { it != "NONE" } == true -> "需检查"
            ui.task?.unresolved == true -> "任务进行中"
            ui.unknownResult -> "结果待核实"
            ui.canStart -> "就绪"
            else -> "待配置"
        }, ui.hello?.fault?.let { it != "NONE" } == true || ui.unknownResult)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ui.hello?.let { "主机 ${addressLabel(it.master)}" } ?: "主机状态待同步", style = MaterialTheme.typography.titleSmall)
            Supporting("${ui.pumpOutputLabel} · ${ui.slaves?.let { "${it.count(MasterSlave::online)}/${it.size} 台从机在线" } ?: "从机清单同步中"}")
        }
        TextButton(onClick = onRefresh, enabled = ui.connected) { Text("刷新") }
    }
}

@Composable
internal fun CompactDeviceAccess(ui: MasterUiState, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_devices), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("从机设备", style = MaterialTheme.typography.titleSmall)
            Supporting(ui.slaves?.let { "${it.size} 台 · ${it.count(MasterSlave::online)} 台在线" } ?: "连接主机后同步设备清单")
        }
        Text("管理  ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun TaskStatusHero(task: MasterTask, report: MasterMessage.Report?) {
    val count = java.lang.Long.bitCount(task.mask)
    val complete = java.lang.Long.bitCount((report?.done ?: 0L) and task.mask)
    val failed = report?.let { (it.failed or it.unknown) and task.mask != 0L } == true
    val finished = task.outcome == MasterOutcome.FINISHED
    val success = finished && !failed && report?.done?.and(task.mask) == task.mask
    val green = androidx.compose.ui.graphics.Color(0xFF079455)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
            when {
                !finished -> CircularProgressIndicator(
                    progress = { if (count == 0) 0f else (complete.toFloat() / count).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxSize(), strokeWidth = 9.dp, trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                success -> Surface(modifier = Modifier.size(104.dp), shape = RoundedCornerShape(50), color = green) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_check), null, modifier = Modifier.size(54.dp), tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
                else -> Surface(modifier = Modifier.size(104.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("!", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (!finished) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Supporting("已完成")
                Text("$complete / $count", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Supporting("路")
            }
        }
        Text(when {
            success -> "出水完成"
            failed -> "任务完成 · 有结果待核实"
            finished -> taskStatus(task, report)
            else -> taskStatus(task, report)
        }, style = MaterialTheme.typography.titleLarge)
        Supporting("$count 路 · 每路 ${task.ml} mL · 按编号顺序执行")
    }
}

@Composable
internal fun TaskDetails(
    task: MasterTask,
    report: MasterMessage.Report?,
    volume: MasterMessage.Volume?,
    flows: Map<Long, MasterMessage.Flow> = emptyMap(),
    alias: (Int) -> String = { "${it.toString().padStart(2, '0')} 号阀门" },
    snapshot: Boolean = false
) {
    var expanded by rememberSaveable(task.client, task.seq) { mutableStateOf(false) }
    val slots = (1..32).filter { task.mask and (1L shl (it - 1)) != 0L }
    val count = slots.size
    PumpCard(if (snapshot) "已保存的任务结果" else "本次任务") {
        Supporting("任务 ${task.job ?: "编号待确认"}")
        Text(taskStatus(task, report), style = MaterialTheme.typography.headlineSmall)
        Supporting("$count 路 · 每路 ${task.ml} mL · 按编号顺序执行")
        val total = task.ml * count
        if (volume != null) {
            Text("已收到 ${volume.ml} mL", style = MaterialTheme.typography.headlineSmall)
            LinearProgressIndicator(progress = { (volume.ml.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Supporting("总目标 $total mL · ${java.lang.Long.bitCount(volume.measured)}/$count 路有数据")
            if (volume.measured != task.mask) Supporting("水量数据尚不完整，不能作为最终总量。")
        } else {
            Text("累计水量待确认", style = MaterialTheme.typography.titleMedium)
            Supporting("总目标 $total mL · 尚未收到计量数据")
        }
        report?.let {
            Supporting("已收到终态 ${java.lang.Long.bitCount(it.done)}/$count 路")
            if (it.failed != 0L) Text("异常槽位：${MasterProtocol.slots(it.failed)}", color = MaterialTheme.colorScheme.error)
            if (it.unknown != 0L) Text("结果未知槽位：${MasterProtocol.slots(it.unknown)}", color = MaterialTheme.colorScheme.error)
        }
        if (snapshot) Supporting("这是已保存的数据，当前状态请连接主机后查询。")
    }
    PumpCard("逐路结果") {
        (if (expanded) slots else slots.take(8)).forEach { slot ->
            val bit = 1L shl (slot - 1)
            val label = when {
                report == null -> if (task.outcome == MasterOutcome.REJECTED) "未执行" else "尚未收到节点结果"
                report.unknown and bit != 0L -> "结果未知"
                report.failed and bit != 0L -> "执行异常"
                report.done and bit != 0L -> "已收到终态"
                task.outcome == MasterOutcome.FINISHED -> "尚未确认"
                else -> "尚未收到终态"
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val terminal = report?.let { (it.done or it.failed or it.unknown) and bit != 0L } == true
                val complete = report?.let { it.done and bit != 0L } == true
                Surface(shape = RoundedCornerShape(50), color = when {
                    complete -> androidx.compose.ui.graphics.Color(0xFFE7F7EF)
                    report?.let { (it.failed or it.unknown) and bit != 0L } == true -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }) {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                        if (complete) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color(0xFF079455))
                        else Text(slot.toString().padStart(2, '0'), color = if (terminal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(alias(slot), style = MaterialTheme.typography.bodyMedium)
                    Supporting(label)
                }
                StatePill(when {
                    report?.let { it.unknown and bit != 0L } == true -> "待核实"
                    report?.let { it.failed and bit != 0L } == true -> "异常"
                    complete -> "已完成"
                    task.outcome == MasterOutcome.REJECTED -> "未执行"
                    task.outcome == MasterOutcome.FINISHED -> "尚未确认"
                    report == null -> "等待结果"
                    else -> "进行中"
                }, attention = report?.let { (it.failed or it.unknown) and bit != 0L } == true)
            }
            if (slot != slots.last()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        if (slots.size > 8) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "查看全部 $count 路") }
        Supporting("节点水量暂无分槽位数据；未收到结果不代表出水量为零。")
        if (flows.isNotEmpty()) {
            HorizontalDivider()
            Supporting("按节点地址返回的计量")
            flows.toSortedMap().forEach { (address, flow) ->
                Text("地址 ${addressLabel(address)} · ${flow.ml} mL · ${flow.phase}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
