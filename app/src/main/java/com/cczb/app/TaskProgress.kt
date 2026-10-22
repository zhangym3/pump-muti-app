package com.cczb.app

internal enum class ValveProgressState(val label: String) {
    WAITING("等待数据"), RUNNING("抽水中"), STALE("数据过期"), DISCONNECTED("连接中断"),
    STOPPING("停止待确认"), RESULT_PENDING("等待结果"), COMPLETE("已完成"),
    STOPPED("已停止"), FAULT("异常停止"), UNKNOWN("待核实"), REJECTED("未执行")
}

/** Display observations only; a full ring never determines whether a valve has stopped. */
internal data class ValveProgress(
    val slot: Int,
    val targetMl: Long,
    val ml: Long?,
    val pulses: Long?,
    val rateMlMin: Long?,
    val state: ValveProgressState,
    val final: MasterMessage.Flow? = null
) {
    val fraction: Float get() = if (targetMl > 0) ((ml ?: 0L).toDouble() / targetMl).coerceIn(0.0, 1.0).toFloat() else 0f
    val percent: Int? get() = ml?.let { waterPercent(it, targetMl) }
}

internal data class TaskProgress(val targetMl: Long, val valves: List<ValveProgress>) {
    val measuredCount get() = valves.count { it.ml != null }
    val endedCount get() = valves.count { it.final != null }
    val reportedMl: Long? get() = valves.mapNotNull { it.ml }.takeIf { it.isNotEmpty() }?.sum()
    val fraction: Float get() = if (targetMl > 0) ((reportedMl ?: 0L).toDouble() / targetMl).coerceIn(0.0, 1.0).toFloat() else 0f
    val percent: Int? get() = reportedMl?.let { waterPercent(it, targetMl) }
}

private fun waterPercent(ml: Long, targetMl: Long): Int =
    if (targetMl > 0) (ml.coerceIn(0L, targetMl) * 100 / targetMl).toInt() else 0

internal fun MasterUiState.taskProgress(): TaskProgress? {
    val current = task ?: return null
    val saved = savedTaskObservation
    // A task's original bindings take precedence over a refreshed device inventory.
    val bindings = when {
        taskAddresses.isNotEmpty() -> taskAddresses
        !saved?.nodes.isNullOrEmpty() -> saved!!.nodes
        else -> slaves.orEmpty().associate { it.slot to it.address }
    }
    val duplicateAddresses = bindings.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val report = report?.takeIf { it.matches(current) && it.reply == "OK" }
        ?: saved?.report?.takeIf { it.matches(current) && it.reply == "OK" }
    val valves = ValveAmounts.slots(current.mask).map { slot ->
        val address = bindings[slot]?.takeIf { it in 1..0x7fff && it !in duplicateAddresses }
        fun MasterMessage.Flow.matches() = job == current.job && this.address == address && terminal
        val final = address?.let { flows[it]?.takeIf { it.matches() } ?: saved?.flows?.get(it)?.takeIf { it.matches() } }
        val live = address?.let { liveFlows[it] }?.takeIf { it.job == current.job && it.address == address }
        val reportedEnded = report?.let { (it.done or it.failed or it.unknown) and (1L shl (slot - 1)) != 0L } == true
        val state = when {
            final?.phase == "FAULT" -> ValveProgressState.FAULT
            final != null && !final.measured -> ValveProgressState.UNKNOWN
            final?.reason == "MANUAL" -> ValveProgressState.STOPPED
            final?.phase == "DONE" && final.reason == "TARGET" -> ValveProgressState.COMPLETE
            final != null -> ValveProgressState.UNKNOWN
            current.outcome == MasterOutcome.REJECTED -> ValveProgressState.REJECTED
            !current.unresolved || reportedEnded -> ValveProgressState.RESULT_PENDING
            stopPending -> ValveProgressState.STOPPING
            !connected -> ValveProgressState.DISCONNECTED
            live != null && live.isFresh(flowClockAt) -> ValveProgressState.RUNNING
            live != null -> ValveProgressState.STALE
            else -> ValveProgressState.WAITING
        }
        ValveProgress(slot, current.target(slot),
            // An unmeasured final result invalidates the older live measurement.
            ml = if (final != null) final.ml.takeIf { final.measured } else live?.ml,
            pulses = if (final != null) final.pulses.takeIf { final.measured } else live?.pulses,
            rateMlMin = live?.rateMlMin?.takeIf { state == ValveProgressState.RUNNING },
            state = state, final = final)
    }
    return TaskProgress(current.totalMl, valves)
}
