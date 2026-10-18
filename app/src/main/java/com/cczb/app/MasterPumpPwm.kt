package com.cczb.app

/** A GATT write never confirms a speed. Only the current boot's device revision does. */
data class MasterPwmPending(val epoch: Long, val revision: Long, val level: Int)
data class MasterPumpPwm(
    val snapshot: MasterMessage.PumpPwm? = null,
    val pending: MasterPwmPending? = null,
    val message: String = "等待主机同步 PWM 档位"
) {
    fun request(level: Int): MasterPumpPwm {
        val current = snapshot ?: return this
        if (pending != null || level !in 1..3 || current.revision == 0xffff_ffffL || current.level == level) return this
        return copy(pending = MasterPwmPending(current.epoch, current.revision, level), message = "调速待主机确认")
    }
    fun receive(value: MasterMessage.PumpPwm, epoch: Long): MasterPumpPwm {
        if (value.epoch != epoch || snapshot?.let { it.epoch == epoch && value.revision < it.revision } == true) return this
        val waiting = pending
        val resolved = waiting != null && (value.revision > waiting.revision || value.reply != "OK")
        return copy(snapshot = value, pending = if (resolved) null else waiting, message = when {
            resolved && value.reply != "OK" -> "主机未接受调速：${value.reply}；已同步当前档位"
            resolved && value.level != waiting?.level -> "主机档位已变化，已同步当前设置"
            resolved -> "主机已确认档位"
            waiting != null -> message
            else -> "已同步主机档位；选择档位不会启动水泵"
        })
    }
}
