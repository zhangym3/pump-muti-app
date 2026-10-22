package com.cczb.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TaskProgressSummary(progress: TaskProgress) {
    val animated by animateFloatAsState(progress.fraction, tween(450), label = "totalWaterProgress")
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Supporting("已上报水量")
                    Text(progress.reportedMl?.let { "$it mL" } ?: "—", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Supporting("目标总量")
                    Text("${progress.targetMl} mL", style = MaterialTheme.typography.titleLarge)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("总水量进度", style = MaterialTheme.typography.labelMedium)
                Text(progress.percent?.let { "$it%" } ?: "待确认", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().height(10.dp)
                .clip(RoundedCornerShape(50)).testTag("task-total-progress").semantics {
                    contentDescription = "总水量进度"
                    stateDescription = progress.percent?.let { "$it%" } ?: "等待计量数据"
                    if (progress.percent == null) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                }, trackColor = MaterialTheme.colorScheme.outlineVariant, strokeCap = StrokeCap.Round,
                gapSize = 0.dp, drawStopIndicator = {})
            Supporting("计量 ${progress.measuredCount}/${progress.valves.size} 路 · 已结束 ${progress.endedCount}/${progress.valves.size} 路")
            if (progress.measuredCount in 1 until progress.valves.size) Supporting("总进度仅含已收到的计量数据")
        }
    }
}

@Composable
internal fun LiveFlowPanel(ui: MasterUiState, alias: (Int) -> String) {
    val progress = ui.taskProgress() ?: return
    val rows = if (progress.valves.size == 5) {
        listOf(progress.valves.take(2), listOf(progress.valves[2]), progress.valves.drop(3))
    } else {
        progress.valves.chunked(2)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 10.dp) / 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    row.forEach { valve ->
                        key(valve.slot) { ValveProgressCard(valve, alias(valve.slot), Modifier.width(cardWidth)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ValveProgressCard(valve: ValveProgress, name: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val accent: Color = when (valve.state) {
        ValveProgressState.COMPLETE -> colors.tertiary
        ValveProgressState.FAULT, ValveProgressState.UNKNOWN -> colors.error
        ValveProgressState.RUNNING -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val animated by animateFloatAsState(valve.fraction, tween(450), label = "valveWaterProgress")
    Surface(modifier.testTag("valve-progress-${valve.slot}"), shape = RoundedCornerShape(12.dp),
        color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("#${valve.slot}", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Normal phones show a compact ring beside the quantities. A narrow card or
            // larger font wraps the quantity block below the ring, while keeping two cards per row.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxSize().semantics {
                        contentDescription = "$name 水量进度"
                        stateDescription = valve.percent?.let { "$it%，${valve.state.label}" } ?: "等待计量数据，${valve.state.label}"
                        if (valve.percent == null) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    }, color = accent, trackColor = colors.surfaceVariant, strokeWidth = 6.dp,
                        strokeCap = StrokeCap.Round, gapSize = 0.dp)
                    BasicText(valve.percent?.let { "$it%" } ?: "—", Modifier.width(50.dp),
                        style = MaterialTheme.typography.titleMedium.copy(color = accent, fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 18.sp))
                }
                Column(Modifier.widthIn(min = 68.dp).weight(1f).heightIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    BasicText(valve.ml?.let { "$it mL" } ?: "— mL", Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface,
                            fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 20.sp))
                    Text("目标 ${valve.targetMl} mL", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(valve.state.label, style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }
            Row(Modifier.fillMaxWidth().testTag("valve-metrics-${valve.slot}"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = valve.pulses?.let { "$it 脉冲" } ?: "脉冲 —"
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(valve.pulses?.toString() ?: "—", style = MaterialTheme.typography.labelMedium)
                    Text("脉冲", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = valve.rateMlMin?.let { "当前流速 $it mL/min" } ?: "当前流速 —"
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(valve.rateMlMin?.toString() ?: "—", style = MaterialTheme.typography.labelMedium)
                    Text("mL/min", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            val detail = when {
                valve.final != null && !valve.final.measured -> "最终计量待核实"
                valve.final != null && valve.state != ValveProgressState.COMPLETE -> stopReasonLabel(valve.final.reason)
                valve.ml != null && (valve.state == ValveProgressState.STALE || valve.state == ValveProgressState.DISCONNECTED) -> "累计量为上次快照"
                else -> null
            }
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = accent) }
        }
    }
}
