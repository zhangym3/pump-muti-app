package com.cczb.app

/** Progress is session-local monitoring. It never updates the task ledger or final totals. */
private fun MasterUiState.knownLiveAddresses(): Set<Long>? {
    val current = task ?: return null
    val selectedSlots = (1..32).filter { current.mask and (1L shl (it - 1)) != 0L }
    if (selectedSlots.isEmpty()) return null
    val bySlot = slaves.orEmpty().filter { it.slot in 1..32 }.associateBy { it.slot }
    if (selectedSlots.any { bySlot[it] == null }) return null
    return selectedSlots.mapNotNull { bySlot[it]?.address }.toSet()
        .takeIf { it.size == selectedSlots.size }
}

private fun MasterUiState.acceptsLive(job: MasterJobKey, address: Long): Boolean {
    val current = task ?: return false
    val knownAddresses = knownLiveAddresses() ?: return false
    if (!connected || hello?.role != "PUMP_MASTER" || hello.epoch != job.epoch ||
        !current.unresolved || current.job != job || address !in knownAddresses) return false
    if (flows[address]?.let { it.job == job && it.terminal } == true) return false
    val currentReport = report?.takeIf { it.job == job } ?: return true
    if (currentReport.phase >= 6) return false
    val slot = slaves.orEmpty().firstOrNull { it.address == address }?.slot ?: return false
    val terminal = currentReport.done or currentReport.failed or currentReport.unknown
    return terminal and (1L shl (slot - 1)) == 0L
}

internal fun MasterUiState.receiveLive(sample: MasterMessage.Live, now: Long): MasterUiState {
    if (!acceptsLive(sample.job, sample.address) || sample.runtimeMs !in 1..240_000 ||
        sample.runtimeMs > (task?.maxMs ?: 0) || now <= 0) return this
    val previous = liveFlows[sample.address]
    if (previous != null && previous.job == sample.job &&
        (sample.runtimeMs <= previous.runtimeMs || sample.pulses < previous.pulses || sample.ml < previous.ml)) return this
    return copy(liveFlows = liveFlows + (sample.address to sample.copy(receivedAt = now)), flowClockAt = now)
}

internal fun MasterUiState.pruneLiveFlows(): MasterUiState = copy(
    liveFlows = liveFlows.filterValues { acceptsLive(it.job, it.address) }
)
