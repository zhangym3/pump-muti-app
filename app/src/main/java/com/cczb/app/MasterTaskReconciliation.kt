package com.cczb.app

/** Shared by BLE reception and regression tests; never issues control commands. */
internal fun MasterUiState.receiveTaskReport(
    incoming: MasterMessage.Report,
    stopSequence: Long? = null,
    directStartReply: Boolean = false
): MasterUiState? {
    val current = task ?: return null
    if (incoming.client != current.client) return null
    val stopReply = incoming.seq == stopSequence && incoming.job != null && incoming.job == current.job
    if (incoming.seq != current.seq && !stopReply) return null
    if (incoming.reply != "OK") {
        // A query miss is not evidence that the stored terminal result was wrong or that a task ended.
        val next = current.reconcile(incoming, directStartReply && !stopReply)
        return copy(task = next, message = "Master 返回 ${incoming.reply}" + when {
            taskResultsComplete -> "；保留本机已完整对账的任务结果"
            next.outcome == MasterOutcome.REJECTED -> "；本次未接受"
            else -> "；结果尚未完整确认，请查询原任务"
        })
    }
    if (incoming.job == null || incoming.job.epoch != current.epoch || incoming.mask != current.mask ||
        current.job != null && current.job != incoming.job) return null
    val old = report?.takeIf { it.matches(current) && it.reply == "OK" }
    // Active phases repeat for each batch; only a final result must not regress.
    if ((current.outcome == MasterOutcome.FINISHED || old?.let { it.phase >= 6 } == true) && incoming.phase < 6) return null
    // A verified stop reply belongs to the original task, even though its request has a new sequence.
    val merged = MasterResultMerge.report(old, incoming.copy(seq = current.seq))
    val next = current.reconcile(merged, false)
    val updated = copy(task = next, report = merged, stopPending = stopPending && next.unresolved)
    return updated.copy(message = when {
        merged.unknown != 0L -> "部分节点结果未知，正在继续对账；请核实水路与节点状态"
        merged.failed != 0L -> "部分节点执行异常，请查看结果"
        merged.phase >= 6 && !updated.taskResultsComplete -> "任务终态已收到，正在补齐流量覆盖和节点结果"
        merged.phase >= 6 -> "任务终态与流量覆盖已确认"
        else -> "Master 已接受任务：${MasterProtocol.phase(merged.phase)}"
    })
}
