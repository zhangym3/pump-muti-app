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
        return BigDecimal.valueOf(count).multiply(BigDecimal(1000))
            .divide(volume, 0, RoundingMode.HALF_UP).toLong().takeIf { it in 1..1_000_000 }
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
