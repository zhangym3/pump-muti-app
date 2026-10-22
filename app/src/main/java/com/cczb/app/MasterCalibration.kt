package com.cczb.app

import java.math.BigDecimal
import java.math.RoundingMode

/** Wire K is pulses / 1000 mL. All UI conversions use decimal, never Float. */
object FlowCalibration {
    private val decimal = Regex("[0-9]{1,7}(\\.[0-9]{1,3})?")
    fun parse(text: String): Long? = text.takeIf { decimal.matches(it) }?.toBigDecimalOrNull()
        ?.movePointRight(3)?.toLong()?.takeIf { it in 1..1_000_000 }
    fun display(k: Long): String = BigDecimal.valueOf(k, 3).setScale(3).toPlainString()
    fun calculate(pulses: String, actualMl: String): Long? {
        val count = pulses.takeIf { it.matches(Regex("[0-9]{1,10}")) }?.toLongOrNull()
            ?.takeIf { it in 1..0xffff_ffffL } ?: return null
        val volume = actualMl.takeIf { decimal.matches(it) }?.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal(1_000_000) } ?: return null
        return calculate(count, volume)
    }

    fun calculate(pulses: Long, standardMl: Long): Long? =
        if (pulses !in 1..0xffff_ffffL || standardMl !in 1..1_000_000) null
        else calculate(pulses, BigDecimal.valueOf(standardMl))

    private fun calculate(pulses: Long, volume: BigDecimal): Long? =
        BigDecimal.valueOf(pulses).multiply(BigDecimal(1000))
            .divide(volume, 0, RoundingMode.HALF_UP).toLong().takeIf { it in 1..1_000_000 }
}

data class FlowCalibrationSession(
    val epoch: Long,
    val client: String,
    val request: Long,
    val slot: Int,
    val address: Long,
    val standardMl: Long,
    val speed: Int,
    val job: MasterJobKey? = null,
    val phase: String = "START_PENDING",
    val operationSequence: Long = 0,
    val reply: String = "PENDING",
    val startPulses: Long = 0,
    val closePulses: Long = 0,
    val finalPulses: Long = 0,
    val livePulses: Long = 0,
    val runtimeMs: Long = 0,
    val reason: String = "NONE",
    val saved: Boolean = false,
    val recoveryEpoch: Long? = null,
    val calibrationSaveRequest: Long? = null
) {
    val locked get() = phase in setOf("START_PENDING", "STARTING", "PREOUT", "MARK_PENDING", "MEASURING", "STOPPING", "UNKNOWN")
    val canMark get() = phase == "PREOUT" && job != null && operationSequence < 0xffff_ffffL
    val canStop get() = locked && operationSequence < 0xffff_ffffL
    val measuredPulses: Long? get() = closePulses.takeIf { phase == "COMPLETE" && it > startPulses }?.minus(startPulses)
    val tailPulses: Long? get() = if (phase == "COMPLETE" && finalPulses >= closePulses) finalPulses - closePulses else null
    val calculatedK: Long? get() = measuredPulses?.let { FlowCalibration.calculate(it, standardMl) }

    fun startCommand() = "flow-cal-start $epoch $client $request $slot ${address.toString(16)} $standardMl $speed"
    fun markCommand(): String {
        val key = checkNotNull(job)
        val sequence = if (phase == "MARK_PENDING") operationSequence else operationSequence + 1
        return "flow-cal-mark ${commandEpoch()} $client $request $sequence ${key.epoch} ${key.id}"
    }
    fun stopCommand(): String {
        val sequence = if (phase == "STOPPING") operationSequence else operationSequence + 1
        return "flow-cal-stop ${commandEpoch()} $client $request $sequence " +
        "${job?.epoch ?: 0} ${job?.id ?: 0}"
    }
    fun queryCommand(): String = "flow-cal-query ${commandEpoch()} $client $request" +
        (job?.let { " ${it.epoch} ${it.id}" } ?: "")

    fun commandEpoch() = recoveryEpoch ?: epoch

    fun reconcileHostEpoch(currentEpoch: Long): FlowCalibrationSession = when {
        currentEpoch <= 0 || currentEpoch == commandEpoch() -> this
        phase in setOf("COMPLETE", "INVALID", "FAILED") -> this
        else -> copy(recoveryEpoch = currentEpoch, phase = "UNKNOWN", reply = "UNKNOWN", reason = "REBOOT")
    }

    fun receive(value: MasterMessage.FlowCalibrationStatus, currentEpoch: Long): FlowCalibrationSession {
        if (value.epoch != currentEpoch || value.epoch != commandEpoch() || value.client != client || value.request != request) return this
        if (value.phase == "IDLE") {
            if (!((value.slot == 0 && value.address == 0L) || value.slot == slot && value.address == address)) return this
            val rejected = value.reply in setOf("BUSY", "INVALID", "STALE", "CONFLICT", "NOT_READY", "STORAGE",
                "UNAUTHORIZED", "OFFLINE", "CAPACITY", "UNKNOWN") && job == null
            return copy(phase = if (rejected) "FAILED" else "UNKNOWN", reply = value.reply,
                reason = if (rejected) value.reason else "UNKNOWN")
        }
        if (value.slot != slot || value.address != address) return this
        if (value.standardMl != 0L && value.standardMl != standardMl || value.speed != 0 && value.speed != speed || value.job == null ||
            job != null && value.job != job || value.operationSequence < operationSequence) return this
        if (phase in setOf("COMPLETE", "INVALID", "FAILED") && value.phase !in setOf("COMPLETE", "INVALID")) return this
        if (value.phase != "UNKNOWN" && phase != "UNKNOWN" && phaseRank(value.phase) < phaseRank(phase)) return this
        return copy(job = value.job, phase = value.phase, operationSequence = value.operationSequence,
            reply = value.reply, startPulses = value.startPulses, closePulses = value.closePulses,
            finalPulses = value.finalPulses, livePulses = value.livePulses, runtimeMs = value.runtimeMs,
            reason = value.reason, recoveryEpoch = recoveryEpoch)
    }

    fun requestedMark() = copy(operationSequence = operationSequence + 1, phase = "MARK_PENDING", reply = "PENDING")
    fun requestedStop() = if (phase == "STOPPING") this else
        copy(operationSequence = operationSequence + 1, phase = "STOPPING", reply = "PENDING")

    private fun phaseRank(value: String): Int = when (value) {
        "START_PENDING" -> 0
        "STARTING" -> 1
        "PREOUT" -> 2
        "MARK_PENDING" -> 3
        "MEASURING" -> 4
        "STOPPING" -> 5
        "COMPLETE", "INVALID", "FAILED" -> 6
        else -> -1
    }

    fun encode(): String = listOf(epoch, client, request, slot, address.toString(16), standardMl, speed,
        job?.epoch ?: 0, job?.id ?: 0, phase, operationSequence, reply, startPulses, closePulses,
        finalPulses, livePulses, runtimeMs, reason, if (saved) 1 else 0, recoveryEpoch ?: 0,
        calibrationSaveRequest ?: 0).joinToString(",")

    companion object {
        private val phases = setOf("START_PENDING", "STARTING", "PREOUT", "MARK_PENDING", "MEASURING", "STOPPING",
            "COMPLETE", "INVALID", "UNKNOWN", "FAILED")

        fun decode(value: String, expectedClient: String, maxRequest: Long): FlowCalibrationSession {
            val fields = value.split(',')
            require(fields.size in 19..21)
            val session = FlowCalibrationSession(
                epoch = fields[0].toLong().also { require(it in 1..0xffff_ffffL) },
                client = MasterProtocol.client(fields[1]).also { require(it == expectedClient) }!!,
                request = fields[2].toLong().also { require(it in 1..maxRequest) },
                slot = fields[3].toInt().also { require(it in 1..32) },
                address = fields[4].toLong(16).also { require(it in 1..0x7fff) },
                standardMl = fields[5].toLong().also { require(it in 50..2000) },
                speed = fields[6].toInt().also { require(it in 1..3) },
                job = fields[7].toLong().also { require(it in 0..0xffff_ffffL) }.let { jobEpoch ->
                    fields[8].toLong().also { require(it in 0..0xffff_ffffL) }.let { jobId ->
                        require((jobEpoch == 0L) == (jobId == 0L))
                        if (jobEpoch == 0L) null else MasterJobKey(jobEpoch, jobId)
                    }
                },
                phase = fields[9].also { require(it in phases) },
                operationSequence = fields[10].toLong().also { require(it in 0..0xffff_ffffL) },
                reply = fields[11].also { require(it in MasterProtocol.repliesForLedger) },
                startPulses = fields[12].toLong().also { require(it in 0..0xffff_ffffL) },
                closePulses = fields[13].toLong().also { require(it in 0..0xffff_ffffL) },
                finalPulses = fields[14].toLong().also { require(it in 0..0xffff_ffffL) },
                livePulses = fields[15].toLong().also { require(it in 0..0xffff_ffffL) },
                runtimeMs = fields[16].toLong().also { require(it in 0..0xffff_ffffL) },
                reason = fields[17].also { require(it.matches(Regex("[A-Z_]{1,32}"))) },
                saved = fields[18].also { require(it in setOf("0", "1")) } == "1",
                recoveryEpoch = fields.getOrNull(19)?.toLong()?.also { require(it in 0..0xffff_ffffL) }
                    ?.takeIf { it > 0 },
                calibrationSaveRequest = fields.getOrNull(20)?.toLong()?.also { require(it in 0..0xffff_ffffL) }
                    ?.takeIf { it > 0 }
            )
            require((session.job == null) == (session.phase in setOf("START_PENDING", "FAILED")) ||
                session.phase in setOf("UNKNOWN", "INVALID"))
            require(session.closePulses >= session.startPulses || session.phase != "COMPLETE")
            require(session.finalPulses >= session.closePulses || session.phase != "COMPLETE")
            return session
        }
    }
}

data class CalibrationPending(val epoch: Long, val request: Long, val slot: Int, val address: Long,
                              val revision: Long? = null, val values: List<Long>? = null)

data class MasterCalibration(
    val slot: Int = 0, val address: Long = 0,
    val snapshot: MasterMessage.Calibration? = null,
    val pending: CalibrationPending? = null,
    val message: String = "选择从节点后读取三档计量系数"
) {
    fun receive(value: MasterMessage.Calibration, epoch: Long): MasterCalibration {
        val wait = pending ?: return this
        if (value.epoch != epoch || wait.epoch != epoch || value.request != wait.request ||
            value.slot != slot || value.address != address || value.slot != wait.slot || value.address != wait.address ||
            snapshot?.let { it.epoch == epoch && value.revision < it.revision } == true) return this
        val confirmed = value.reply == "OK" && (wait.values == null ||
            value.values == wait.values && value.revision == wait.revision?.plus(1) &&
                snapshot?.let { it.baseK == value.baseK && it.baseVersion == value.baseVersion } == true)
        return copy(snapshot = if (value.reply == "STORAGE" || value.reply == "OK" && !confirmed) null else value,
            pending = null, message = when {
            confirmed && wait.values != null -> "主机已保存三档系数，下次定量任务生效"
            confirmed -> "已读取主机保存值"
            value.reply == "BUSY" -> "当前有任务或组网操作，请结束后重试"
            value.reply == "STALE" -> "配置已变化，已同步当前值；请核对后重新保存"
            value.reply == "STORAGE" -> "主机存储异常，保存结果未确认，请重新连接核实"
            else -> "调校未确认：${value.reply}，请重新读取核实"
        })
    }
    fun uncertain(text: String) = copy(snapshot = null, pending = null, message = text)
}
