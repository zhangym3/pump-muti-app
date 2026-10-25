package com.cczb.app

data class MasterSlave(val slot: Int, val address: Long, val online: Boolean, val commissioned: Boolean) {
    val selectable get() = slot in 1..32 && online && commissioned
}

internal fun taskNodeAddresses(mask: Long, nodes: List<MasterSlave>): Map<Int, Long> {
    val selected = (1..32).filter { mask and (1L shl (it - 1)) != 0L }.toSet()
    val addresses = nodes.filter { it.slot in selected }.associate { it.slot to it.address }
    return addresses.takeIf { it.keys == selected } ?: emptyMap()
}

/** A partial BLE stream must never replace the last complete inventory. */
class MasterInventoryCollector(val epoch: Long, val request: Long) {
    private var expected: Int? = null
    private var invalid = false
    private val nodes = linkedMapOf<Long, MasterSlave>()
    fun accept(message: MasterMessage): List<MasterSlave>? {
        when (message) {
            is MasterMessage.InventoryBegin -> if (message.epoch == epoch && message.request == request) {
                if (expected != null) invalid = true
                expected = message.count
            }
            is MasterMessage.InventoryNode -> if (message.epoch == epoch && message.request == request) {
                val node = message.node
                if (expected == null || nodes.containsKey(node.address) ||
                    node.slot != 0 && nodes.values.any { it.slot == node.slot }) invalid = true
                nodes[node.address] = node
                if (nodes.size > (expected ?: 0)) invalid = true
            }
            is MasterMessage.InventoryEnd -> if (message.epoch == epoch && message.request == request) {
                if (!invalid && expected == message.count && nodes.size == message.count)
                    return nodes.values.sortedWith(compareBy<MasterSlave> { if (it.slot == 0) 33 else it.slot }.thenBy { it.address })
                invalid = true
            }
            else -> Unit
        }
        return null
    }
}

data class MasterMeshState(
    val phase: String, val busy: Boolean, val capable: Boolean, val reason: String,
    val operation: Long, val scan: Long, val found: Int, val target: Long,
    val supportsRemoval: Boolean = false, val configStep: Int = 0,
    val configAttempt: Int = 0, val configRetryWait: Boolean = false,
    val configRetryRemainingMs: Int = 0
) {
    val label get() = when (phase) {
        "STARTING" -> "正在初始化组网"
        "IDLE" -> "可以扫描从机"
        "SCANNING" -> "正在扫描附近从机…"
        "SCAN_DONE" -> "扫描完成，发现 $found 台从机"
        "JOINING" -> "正在连接并配对从机…"
        "CONFIGURING" -> {
            val step = when (configStep) {
                0 -> "正在绑定密钥和配置 Mesh"
                1 -> "读取从机能力"
                2 -> "绑定 AppKey"
                3 -> "绑定控制模型"
                4 -> "配置订阅地址"
                else -> "配置 Mesh"
            }
            val attempt = if (configAttempt in 1..3) "（第 $configAttempt/3 次）" else ""
            if (configRetryWait) {
                val wait = if (configRetryRemainingMs > 0) "（约 ${(configRetryRemainingMs + 999) / 1000} 秒）" else ""
                "$step 未收到回复，等待分段清理后重试$attempt$wait…"
            }
            else "$step$attempt…"
        }
        "WAIT_HELLO" -> "正在等待从机握手…"
        "READY" -> "从机组网成功"
        "CANCELLING" -> "正在结束超时的配对…"
        "RESETTING" -> "正在清除全部从机连接…"
        "RESET_DONE" -> "从机组网已重置"
        "REMOVING", "REMOVE_CLEANUP" -> "正在删除从机 ${addressLabel(target)}…"
        "REMOVE_DONE" -> if (reason == "REMOTE_RESET") "从机已删除，已收到退网确认"
            else "从机已从主机删除；未确认从机退网，重新添加前请在从机本地清除 Mesh"
        "AUTO_MIGRATING" -> "主机正在重启，连接恢复后即可添加从机…"
        "LEGACY_NETWORK" -> "主机 Mesh 当前不可用，请重连或重置从机组网"
        "FAILED" -> "组网未完成：" + when (reason) {
            "REMOVE_RESTART_REQUIRED" -> "删除未完成，请重启主机后刷新清单核实"
            "REMOVE_STORAGE" -> "删除记录保存失败，请重启主机恢复后核实清单"
            "ENABLE_FAILED" -> "Master 无法启用 Provisioner，请检查 Mesh 初始化日志或固件状态"
            "PROVISION_TIMEOUT" -> "配对超时，请靠近 Master 后重新扫描"
            "PROVISION_FAILED", "PROVISION_START" -> "配对失败，请检查从机网络状态后重新扫描"
            "CONFIG_TIMEOUT", "CONFIG_SEND" -> "配置通信超时，可刷新清单后重试连接配置"
            "CONFIG_REJECTED" -> "从机不支持所需模型或拒绝配置，请检查固件"
            "HELLO_TIMEOUT" -> "未收到从机握手，可刷新清单后重试连接配置"
            "RESTART_REQUIRED" -> "配对未能正常结束，请重启 Master 后重连"
            else -> "请刷新状态并检查 Master 日志（$reason）"
        }
        else -> "组网状态：$phase"
    }
}

data class MasterRemoval(
    val epoch: Long, val request: Long, val address: Long, val slot: Int,
    val pending: Boolean = true, val succeeded: Boolean = false,
    val message: String = "已发送删除请求，等待主机确认"
) {
    fun receive(update: MasterMessage.Mesh): MasterRemoval {
        val mesh = update.state
        if (succeeded || update.epoch != epoch || mesh.operation != request || mesh.target != address) return this
        return when (mesh.phase) {
            "REMOVE_DONE" -> copy(pending = false, succeeded = true, message = mesh.label)
            "FAILED" -> copy(pending = false, message = mesh.label)
            "REMOVING", "REMOVE_CLEANUP" -> copy(message = mesh.label)
            else -> this
        }
    }
}
