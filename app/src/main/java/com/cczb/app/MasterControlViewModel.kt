package com.cczb.app

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MasterUiState(
    val connection: MasterConnectionState = MasterConnectionState.Disconnected,
    val scan: MasterScanState = MasterScanState.Idle,
    val devices: List<MasterDevice> = emptyList(), val deviceName: String = "未选择主控",
    val masterAddress: String? = null,
    val retryDevice: MasterDevice? = null,
    val hello: MasterMessage.Hello? = null, val task: MasterTask? = null,
    val caps: MasterMessage.Caps? = null, val maintenance: MasterMessage.Maintenance? = null,
    val meshBudget: MasterMessage.MeshBudget? = null,
    val report: MasterMessage.Report? = null, val volume: MasterMessage.Volume? = null,
    val flows: Map<Long, MasterMessage.Flow> = emptyMap(), val flowClockAt: Long = 0,
    val taskAddresses: Map<Int, Long> = emptyMap(),
    val liveFlows: Map<Long, MasterMessage.Live> = emptyMap(),
    val storageHealthy: Boolean = true,
    val statusFresh: Boolean = false, val statusAt: Long? = null,
    val local: MasterMessage.Local? = null, val localFresh: Boolean = false, val localAt: Long? = null,
    val stopPending: Boolean = false,
    val pumpResetRequest: Long? = null, val pumpResetMessage: String = "",
    val pumpTest: MasterPumpTest? = null, val testStopPending: Boolean = false,
    val testMessage: String = "选择档位和时长，点击启动测试；可随时停止",
    val slaves: List<MasterSlave>? = null, val inventoryFresh: Boolean = false,
    val inventoryLoading: Boolean = false, val inventoryMessage: String = "从机清单待同步",
    val mesh: MasterMeshState? = null, val meshFresh: Boolean = false,
    val meshPending: Boolean = false, val candidates: List<String> = emptyList(),
    val meshMessage: String = "连接主机后可扫描附近从机",
    val removal: MasterRemoval? = null,
    val commissionRequest: Long? = null, val commissionPendingSlot: Int? = null,
    val commissionMessage: String = "",
    val pwm: MasterPumpPwm = MasterPumpPwm(), val pwmFresh: Boolean = false,
    val calibration: MasterCalibration = MasterCalibration(),
    val flowCalibration: FlowCalibrationSession? = null,
    val history: List<MasterHistoryEntry> = emptyList(), val historyHealthy: Boolean = true,
    val message: String = "扫描并连接 PUMP_MASTER 设备；首次连接请完成系统 PIN 配对。",
    val log: List<String> = emptyList()
) {
    val savedTaskObservation: MasterHistoryEntry? get() = task?.let {
        MasterHistory.matchingEntry(history, masterAddress, it)
    }
    val connected get() = connection is MasterConnectionState.Connected
    val flowCalibrationLocked get() = flowCalibration?.locked == true
    val canReadCalibration get() = connected && statusFresh && hello?.role == "PUMP_MASTER" &&
        hello.calibration && inventoryFresh && calibration.pending == null && !flowCalibrationLocked
    val canSaveCalibration get() = canReadCalibration && calibration.snapshot?.epoch == hello?.epoch &&
        calibration.snapshot?.reply == "OK" && calibration.snapshot.revision < 0xffff_ffffL &&
        calibration.snapshot.slot == calibration.slot && calibration.snapshot.address == calibration.address &&
        slaves.orEmpty().any { it.slot == calibration.slot && it.address == calibration.address } &&
        localFresh && local?.pumpCommand == false && local.testActive != true && !managementBusy && !pumpTestUnresolved &&
        !taskNeedsReconciliation && !unknownResult && storageHealthy && historyHealthy && pwm.pending == null
    fun flowCalibrationBlockReason(standardMl: Long, speed: Int): String? = when {
        !connected -> "请先连接 PUMP_MASTER 主机"
        hello == null || !statusFresh -> "正在核实主机状态，请刷新"
        hello.role != "PUMP_MASTER" || !hello.ready -> "主机尚未就绪"
        !hello.calibration || !hello.flowCalibration -> "请更新主机固件后使用交互式计量校准"
        flowCalibrationLocked -> "当前计量校准仍在执行或对账"
        standardMl !in 50..2000 -> "标准水量应为 50–2000 mL 的整数"
        speed !in 1..3 -> "请选择低、中或高档"
        !inventoryFresh -> "从机清单尚未同步"
        slaves.orEmpty().none { it.slot == calibration.slot && it.address == calibration.address && it.selectable } ->
            "请选择在线且已启用的从机"
        !meshFresh || mesh?.capable != true || managementBusy -> "主机组网状态未就绪或正在执行组网操作"
        calibration.pending != null -> "计量系数读取或保存待确认"
        calibration.snapshot?.let { it.epoch == hello.epoch && it.reply == "OK" &&
            it.slot == calibration.slot && it.address == calibration.address } != true -> "请先读取该从机当前三档系数"
        !localFresh || local?.pumpCommand != false || local?.testActive == true -> "请确认水泵已关闭并刷新状态"
        !pwmFresh || pwm.snapshot?.epoch != hello.epoch || pwm.pending != null -> "水泵档位状态待同步"
        !storageHealthy || !historyHealthy -> "本地控制记录异常，暂不能开始校准"
        unknownResult || taskNeedsReconciliation -> "请先核实上一项出水任务的结果"
        pumpTestUnresolved -> "水泵测试尚未结束或待确认"
        else -> null
    }
    val canSaveFlowCoefficient get() = flowCalibration?.let { session ->
        session.phase == "COMPLETE" && !session.saved && session.calculatedK != null && canSaveCalibration &&
            calibration.slot == session.slot && calibration.address == session.address
    } == true
    val canStopFlowCalibration get() = connected && hello?.role == "PUMP_MASTER" && flowCalibration?.canStop == true
    val canQueryFlowCalibration get() = connected && hello?.role == "PUMP_MASTER" && flowCalibration != null
    val managementBusy get() = meshPending || mesh?.busy == true
    val meshBudgetMessage: String? get() {
        val budget = meshBudget ?: return null
        if (!connected || !meshFresh || budget.epoch != hello?.epoch || budget.operation != mesh?.operation) return null
        if (budget.recovery == "RESTART_REQUIRED")
            return "本次连接配置已隔离，请重启主机后重新连接并刷新清单；重复点击重试无法恢复。"
        return if (mesh?.busy == true && budget.remainingMs > 0)
            "最近一次主机状态：本次连接配置剩余上限约 ${(budget.remainingMs + 999) / 1000} 秒。可刷新核实进度。"
        else null
    }
    val selectableMask get() = if (!inventoryFresh) 0L else slaves.orEmpty().filter { it.selectable }
        .fold(0L) { mask, node -> mask or (1L shl (node.slot - 1)) }
    val canViewMesh get() = connected && statusFresh && hello?.role == "PUMP_MASTER" && meshFresh
    val canManageMesh get() = connected && statusFresh && hello?.role == "PUMP_MASTER" &&
        meshFresh && mesh?.capable == true && !managementBusy && storageHealthy && historyHealthy &&
        !flowCalibrationLocked && !unknownResult && !taskNeedsReconciliation && !pumpTestUnresolved && localFresh && local?.pumpCommand == false && pwm.pending == null
    val canResetMesh get() = connected && statusFresh && hello?.role == "PUMP_MASTER" && meshFresh &&
        mesh?.capable == true && !managementBusy && storageHealthy && historyHealthy &&
        !flowCalibrationLocked && !unknownResult && !taskNeedsReconciliation && !pumpTestUnresolved && localFresh && local?.pumpCommand == false &&
        pwm.pending == null
    fun canOpenSlaveRemoval(node: MasterSlave) =
        slaves.orEmpty().any { it.address == node.address && it.slot == node.slot }
    fun slaveRemovalBlockReason(node: MasterSlave): String? = when {
        !connected -> "请先连接主机。"
        !statusFresh || hello == null -> "主机状态待同步，请刷新后重试。"
        hello.role != "PUMP_MASTER" -> "当前连接的不是主节点。"
        !inventoryFresh -> "从机清单已过期，请刷新并等待同步完成。"
        !meshFresh -> "组网状态待同步，请刷新后重试。"
        mesh?.capable != true -> "主机 Mesh Provisioner 当前不可用，请检查主机状态。"
        mesh.supportsRemoval != true -> "当前主机固件不支持单台删除，请先更新主机固件。"
        managementBusy -> "主机正在执行组网操作，请等待完成。"
        !storageHealthy -> "主机存储状态异常，暂不能删除从机。"
        !historyHealthy -> "APP 任务历史状态异常，请先检查历史记录。"
        flowCalibrationLocked -> "计量校准正在执行或待确认，请先停止并完成对账。"
        unknownResult -> "存在结果未知的出水任务，请先查询并核实。"
        taskNeedsReconciliation -> "上一任务仍在对账，请先刷新任务结果。"
        pumpTestUnresolved -> "水泵测试尚未结束或状态待确认。"
        !localFresh -> "水泵状态待同步，请刷新后重试。"
        local?.pumpCommand != false -> "水泵输出未确认关闭，请先停止水泵并刷新状态。"
        local?.testActive == true -> "水泵测试正在运行，请等待结束。"
        pwm.pending != null -> "水泵调速尚未确认，请刷新状态后重试。"
        removal?.pending == true -> "另一项从机删除正在进行，请等待完成。"
        commissionRequest != null -> "从机启用配置正在进行，请等待完成。"
        !canOpenSlaveRemoval(node) -> "从机清单已变化，请刷新后重试。"
        else -> null
    }
    fun canRemoveSlave(node: MasterSlave) = slaveRemovalBlockReason(node) == null
    val canResetBootLock get() = connected && statusFresh && hello?.role == "PUMP_MASTER" &&
        local?.pumpResetSupported == true &&
        hello.fault == "BOOT_LOCK" && hello.epoch > 0 && localFresh && local?.fault == "BOOT_LOCK" &&
        local?.phase == 4 && local.pumpCommand == false && meshFresh && !managementBusy &&
        storageHealthy && historyHealthy && !flowCalibrationLocked && !unknownResult && !taskNeedsReconciliation &&
        !pumpTestUnresolved && pumpResetRequest == null
    // Scanning/provisioning is an operation owned by the Master.  It does not
    // create a pump task, so an old pending task or a PWM acknowledgement must
    // not make the Add device action permanently grey.  The Master remains the
    // authority and rejects the command when it is actually busy.
    val canMeshProvision get() = connected && statusFresh && hello?.role == "PUMP_MASTER" &&
        meshFresh && mesh?.capable == true && !managementBusy &&
        !flowCalibrationLocked && !unknownResult && localFresh && local?.pumpCommand == false
    val canScanMesh get() = canMeshProvision && mesh?.phase in setOf("IDLE", "SCAN_DONE", "READY", "REMOVE_DONE", "FAILED")
    val canJoinMesh get() = canMeshProvision && inventoryFresh && slaves.orEmpty().size < (caps?.maxNodes ?: 5) && mesh?.phase == "SCAN_DONE" && candidates.isNotEmpty()
    fun canCommission(node: MasterSlave) = connected && statusFresh && hello?.role == "PUMP_MASTER" &&
        inventoryFresh && node.slot in 1..32 && node.online && !node.commissioned &&
        meshFresh && mesh?.capable == true && !managementBusy && storageHealthy && historyHealthy &&
        !flowCalibrationLocked && !unknownResult && !taskNeedsReconciliation && !pumpTestUnresolved && localFresh && local?.pumpCommand == false
    // A connected Master may still be waiting for the first local/Mesh
    // snapshot. Keep the button actionable so a tap requests a refresh and
    // explains the missing prerequisite instead of looking dead.
    // Once BLE is connected, let the user enter the scan flow while the first
    // HELLO/MESH snapshots are still arriving. scanSlaves() refreshes those
    // snapshots and reports the exact missing prerequisite. A busy Master is
    // still kept out of the action; legacy cleanup is automatic in firmware.
    val canRequestMeshScan get() = connected &&
        (hello == null || hello.role == "PUMP_MASTER") && !managementBusy
    val meshScanBlockReason: String? get() = when {
        !connected -> "请先连接主机"
        hello == null -> "正在确认主机身份，请稍候"
        hello.role != "PUMP_MASTER" -> "当前连接的不是主节点"
        !statusFresh || !meshFresh -> "正在同步主机组网状态，请稍候或点击刷新"
        managementBusy -> "主机正在扫描或组网，请等待当前操作完成"
        unknownResult -> "主机存在未确认的出水结果，请先查询结果后重试"
        localFresh && local?.pumpCommand == true -> "水泵输出已开启，请先停止水泵"
        !localFresh -> "水泵状态尚未同步，请点击刷新后重试"
        mesh?.phase !in setOf("IDLE", "SCAN_DONE", "READY", "REMOVE_DONE", "FAILED") -> "当前组网状态：${mesh?.label ?: "待同步"}"
        else -> "主机暂时不可组网，请刷新状态后重试"
    }
    val unknownResult get() = task?.let { currentTask ->
        val currentReport = report?.takeIf { it.matches(currentTask) }
        (currentReport?.unknown ?: savedTaskObservation?.report?.takeIf { it.matches(currentTask) }?.unknown ?: 0L) != 0L
    } ?: false
    internal val taskNodeBindings: Map<Int, Long>? get() {
        val currentTask = task ?: return null
        val selectedSlots = (1..32).filter { currentTask.mask and (1L shl (it - 1)) != 0L }.toSet()
        if (taskAddresses.keys != selectedSlots || taskAddresses.values.any { it !in 1..0x7fff } ||
            taskAddresses.values.toSet().size != taskAddresses.size) return null
        return taskAddresses
    }
    val expectedFlowAddresses: Set<Long>? get() = taskNodeBindings?.values?.toSet()
    val taskResultsComplete: Boolean get() {
        val currentTask = task ?: return false
        if (currentTask.outcome != MasterOutcome.FINISHED) return false
        val job = currentTask.job ?: return false
        val currentReport = report?.takeIf { it.matches(currentTask) && it.reply == "OK" } ?: return false
        val terminal = currentReport.done or currentReport.failed or currentReport.unknown
        if (currentReport.phase < 6 || currentReport.unknown != 0L ||
            (terminal and currentTask.mask.inv()) != 0L || (terminal and currentTask.mask) != currentTask.mask) return false
        val currentVolume = volume?.takeIf { it.job == job && it.measured and currentTask.mask.inv() == 0L } ?: return false
        if ((currentVolume.measured and currentTask.mask) != currentTask.mask) return false
        val addresses = expectedFlowAddresses ?: return false
        if (flows.keys != addresses) return false
        return flows.all { (address, flow) ->
            address in addresses && flow.address == address && flow.job == job && flow.terminal && flow.measured
        }
    }
    val taskReconciliationDetails: List<String> get() {
        val currentTask = task ?: return emptyList()
        if (currentTask.outcome == MasterOutcome.PENDING) {
            return listOf("启动请求尚未得到主机确认；请查询原任务，勿重复启动。")
        }
        val job = currentTask.job
        if (currentTask.unresolved) {
            val activeReport = report?.takeIf {
                it.client == currentTask.client && it.seq == currentTask.seq && it.job == job && it.mask == currentTask.mask
            }
            return listOf(if (activeReport != null && activeReport.phase < 6)
                "主机报告任务仍在执行：${MasterProtocol.phase(activeReport.phase)}。"
            else "等待主机确认任务终态；查询可核实任务是否已结束。")
        }
        if (currentTask.outcome != MasterOutcome.FINISHED) return emptyList()
        if (job == null) return listOf("任务编号尚未确认，无法核对节点计量。")

        val currentReport = report?.takeIf {
            it.matches(currentTask)
        }
        if (currentReport == null) return listOf("缺少该任务匹配的主机报告（R）。")
        if (currentReport.reply != "OK") return listOf("缺少该任务匹配的有效主机报告（R）。")
        if (currentReport.phase < 6) return listOf("主机报告任务仍在执行：${MasterProtocol.phase(currentReport.phase)}。")

        val details = mutableListOf<String>()
        if (currentReport.unknown != 0L) {
            details += "以下阀门结果未知：${MasterProtocol.slots(currentReport.unknown and currentTask.mask)}。"
        }
        val terminal = currentReport.done or currentReport.failed or currentReport.unknown
        val missingTerminal = currentTask.mask and terminal.inv()
        if (missingTerminal != 0L) details += "以下阀门缺少终态结果：${MasterProtocol.slots(missingTerminal)}。"

        val currentVolume = volume?.takeIf { it.job == job }
        if (currentVolume == null) details += "缺少该任务的计量汇总（V）。"
        else {
            val missingMeasured = currentTask.mask and currentVolume.measured.inv()
            if (missingMeasured != 0L) details += "以下阀门缺少有效计量：${MasterProtocol.slots(missingMeasured)}。"
        }

        val addresses = taskNodeBindings
        if (addresses == null) {
            details += if (taskAddresses.isEmpty()) "缺少该任务的阀门地址映射，无法核对节点 FLOW。"
            else "该任务的阀门地址映射不完整、重复或无效，无法核对节点 FLOW。"
        } else {
            (1..32).filter { currentTask.mask and (1L shl (it - 1)) != 0L }.forEach { slot ->
                val address = addresses[slot] ?: return@forEach
                val flow = flows[address]
                if (flow == null || flow.address != address || flow.job != job || !flow.terminal || !flow.measured) {
                    details += "阀门 $slot 缺少该任务的终态计量记录（FLOW）。"
                }
            }
            if (flows.keys.any { it !in addresses.values }) details += "收到不属于该任务槽位映射的 FLOW 记录。"
        }
        return details.ifEmpty { if (taskResultsComplete) listOf("任务结果已完整对账。") else listOf("节点与计量结果尚未完整同步。") }
    }
    val taskNeedsReconciliation get() = task?.let {
        it.unresolved || it.outcome == MasterOutcome.FINISHED && !taskResultsComplete
    } == true
    val pumpOutputLabel get() = when {
        !connected || !localFresh || local == null -> "水泵输出待同步"
        local.pumpCommand -> "水泵输出已开启"
        else -> "水泵输出已关闭"
    }
    val startBlockReason: String? get() = when {
        maintenance?.let { it.locked || it.pending != 0 } == true -> "维护许可或关闭核对尚未结束，请完成现场维护退出"
        caps?.basic == true && maintenance == null -> "正在核实维护互锁状态"
        slaves.orEmpty().size > (caps?.maxNodes ?: 5) -> "登记节点超过五台，请先核实并移除多余节点"
        flowCalibrationLocked -> "计量校准正在执行或待确认，请先停止并完成对账"
        calibration.pending != null -> "计量调校待主机确认，请稍候"
        !connected -> "请先连接主机"
        hello == null -> "正在核实主机身份与状态"
        hello.role != "PUMP_MASTER" -> "当前连接的是从机，请连接 PUMP_MASTER 主机"
        !statusFresh -> "主机状态已过期，请刷新或重新连接"
        !hello.mesh -> "主机尚未完成 Mesh 配网"
        hello.address != hello.master -> "主机 Mesh 地址与固定 Master 配置不一致，请重启主机并重新连接"
        !hello.commissioned -> "硬件输出尚未配置，请完成现场维护"
        hello.fault != "NONE" -> "主机故障：${hello.fault}，请现场检查"
        localFresh && local != null && local.fault != "NONE" -> "水泵故障：${local.fault}，请现场检查"
        !hello.ready -> "主机尚未就绪"
        managementBusy -> "主机正在扫描或组网，请等待完成"
        !inventoryFresh || slaves == null -> "从机清单待同步，请刷新"
        selectableMask == 0L -> "没有在线且已配置的从机，请到设备页扫描组网并完成现场配置"
        !storageHealthy -> "请求记录无法保存，禁止新任务"
        !historyHealthy -> "任务记录不完整，请先核实历史结果"
        taskNeedsReconciliation -> "上一请求尚未结束或流量、节点结果仍在对账"
        unknownResult -> "上次任务存在未知结果，请查询并核实"
        pumpTestUnresolved -> "水泵测试尚未结束或状态待确认"
        pwm.pending != null -> "调速待确认，请先同步水泵档位"
        else -> null
    }
    val canStart get() = startBlockReason == null
    val pumpTestUnresolved get() = pumpTest?.unresolved == true || localFresh && local?.testActive == true
    val manualTestSupported get() = pwmFresh && pwm.snapshot?.let { it.epoch == hello?.epoch && it.manualTest } == true
    val dryTestSupported get() = manualTestSupported && pwm.snapshot?.dryMaxMs == 3000L
    val dryTestBlockReason: String? get() = testBlockReason ?: when {
        !dryTestSupported -> "请刷新状态；主机需更新至支持限时测试的固件"
        pwm.snapshot?.revision == 0xffff_ffffL -> "档位序号已用尽，请重启主机后重新连接"
        else -> null
    }
    val canStartDryTest get() = dryTestBlockReason == null
    val testBlockReason: String? get() = when {
        caps?.basic == true -> "基础版本仅提供多路定量；保留原测试的停止与查询"
        maintenance?.locked == true -> "维护许可未退出，禁止启动水泵"
        flowCalibrationLocked -> "计量校准正在执行或待确认，请先停止并完成对账"
        calibration.pending != null -> "计量调校待主机确认，请稍候"
        !connected -> "请先连接 PUMP_MASTER 主机，并完成系统 PIN 配对"
        hello == null || !statusFresh -> "正在核实主机状态，请刷新"
        hello.role != "PUMP_MASTER" -> "当前连接的是从机，请连接 PUMP_MASTER 主机"
        !localFresh || local == null -> "水泵状态未同步，请刷新"
        !local.testSupported -> "主机固件不支持独立试泵，请更新固件"
        managementBusy -> "主机正在扫描或组网，请等待完成"
        !hello.commissioned -> "水泵输出尚未启用，请现场核实接线并配置 commissioned"
        local.fault == "BOOT_LOCK" && !manualTestSupported -> "当前固件启动锁定；请更新主机固件并重新连接"
        local.fault !in setOf("NONE", "BOOT_LOCK") -> "水泵故障：${local.fault}，请现场检查"
        hello.epoch == 0L -> "主机启动标识无效，请重新连接"
        !storageHealthy -> "控制记录无法保存，禁止新测试"
        !historyHealthy || unknownResult -> "出水记录存在未知结果，请先核实"
        taskNeedsReconciliation -> "出水任务或节点计量仍在对账，请先确认任务结果"
        pumpTestUnresolved -> "测试执行中或结果待确认，请查询或停止测试"
        local.pumpCommand -> "水泵输出已开启，请先确认当前任务"
        !local.pumpReady -> "主机暂未允许试泵：请刷新并检查冷却、任务或存储状态"
        pwm.pending != null -> "调速待确认，请先同步水泵档位"
        pwm.snapshot != null && !pwmFresh -> "PWM 档位已过期，请刷新"
        else -> null
    }
    val canStartTest get() = testBlockReason == null
    val canSetSpeed get() = maintenance?.locked != true && connected && statusFresh && !managementBusy && hello?.role == "PUMP_MASTER" &&
        hello.commissioned && localFresh && (local?.fault == "NONE" || local?.fault == "BOOT_LOCK" && manualTestSupported) && pwmFresh &&
        pwm.snapshot?.epoch == hello.epoch && pwm.snapshot.revision < 0xffff_ffffL &&
        pwm.pending == null && calibration.pending == null && !flowCalibrationLocked && storageHealthy && historyHealthy && !unknownResult && !taskNeedsReconciliation &&
        (local?.pumpCommand == false || local?.testActive == true) && pwm.snapshot?.dryActive != true &&
        !(pumpTest?.unresolved == true && pumpTest.dryRun)
    val canStopTest get() = connected && hello?.role == "PUMP_MASTER" && pumpTest?.unresolved == true
    val canStop get() = connected && hello?.role == "PUMP_MASTER" && task?.unresolved == true
    val canRetry get() = connected && statusFresh && !managementBusy && !flowCalibrationLocked && hello?.ready == true && storageHealthy && task?.outcome == MasterOutcome.PENDING && !task.stopRequested && task.epoch == hello.epoch
    val maxConcurrent get() = caps?.takeIf { it.epoch == hello?.epoch }?.maxConcurrent ?: 1
    fun canStartMask(mask: Long) = mask in 1..0xffff_ffffL && java.lang.Long.bitCount(mask) in 1..maxConcurrent

    fun restoreTaskObservation(): MasterUiState {
        val entry = savedTaskObservation
        return copy(report = entry?.report, volume = entry?.volume,
            flows = entry?.flows.orEmpty().mapValues { it.value.copy(receivedAt = 0) },
            liveFlows = emptyMap(), flowClockAt = 0, taskAddresses = entry?.nodes.orEmpty())
    }
}

internal fun MasterMessage.Report.matches(task: MasterTask) =
    client == task.client && seq == task.seq && job != null && job.epoch == task.epoch &&
        job == task.job && mask == task.mask

class MasterControlViewModel(application: Application) : AndroidViewModel(application), MasterBleManager.Listener {
    val manager = MasterBleManager(application, this)
    private val state = MutableStateFlow(MasterUiState())
    val ui = state.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    // noBackupFilesDir prevents restoring the same identity onto another phone.
    private val file = AtomicFile(File(application.noBackupFilesDir, "master-control.properties"))
    private val historyFile = AtomicFile(File(application.noBackupFilesDir, "master-history.properties"))
    private var ledger: MasterLedger? = null
    private var address: String? = null
    private var directStartReply = false
    private var stopSequence: Long? = null
    private var stopSentAt: Long = 0
    private var lastControlAt = -10_000L
    private var lastHelloAt = 0L
    private var lastLocalAt = 0L
    private var lastPwmAt = 0L
    private var pwmSentAt = 0L
    private var calibrationSentAt = 0L
    private var flowCalibrationQueryAt = 0L
    private var requestId = SystemClock.elapsedRealtime() % 0xffff_ff00L
    private var inventoryCollector: MasterInventoryCollector? = null
    private var inventorySentAt = 0L
    private var inventoryReceivedAt = 0L
    private var meshRequest = 0L
    private var meshSentAt = 0L
    private var removalSentAt = 0L
    private var meshReceivedAt = 0L
    private var commissionSentAt = 0L
    private var pumpResetSentAt = 0L
    private fun nextRequest(): Long { requestId = requestId % 0xffff_ffffL + 1; return requestId }
    private val poll = object : Runnable {
        override fun run() {
            if (state.value.connected) {
                val now = SystemClock.elapsedRealtime()
                val removal = state.value.removal
                if (removal?.pending == true && now - removalSentAt > 30_000) {
                    state.value = state.value.copy(meshPending = false, meshFresh = false, inventoryFresh = false,
                        removal = removal.copy(pending = false, message = "删除结果未确认，请刷新清单或重连核实；不会自动重发删除"))
                    requestMeshState()
                    requestInventory(force = true)
                }
                if (state.value.calibration.pending != null && now - calibrationSentAt > 8_000)
                    state.value = state.value.copy(calibration = state.value.calibration.uncertain(
                        "设备未确认调校结果，请重新读取；不会自动重发保存"))
                if (state.value.inventoryFresh && now - inventoryReceivedAt > 15_000)
                    state.value = state.value.copy(inventoryFresh = false)
                if (state.value.meshFresh && now - meshReceivedAt > 15_000)
                    state.value = state.value.copy(meshFresh = false)
                if (inventoryCollector != null && now - inventorySentAt > 8_000) {
                    inventoryCollector = null
                    state.value = state.value.copy(inventoryLoading = false, inventoryFresh = false, inventoryMessage = "从机清单同步超时，请刷新；需新版 Master 固件")
                }
                if (state.value.hello?.role == "PUMP_MASTER") {
                    if (state.value.commissionRequest != null && now - commissionSentAt > 12_000) {
                        state.value = state.value.copy(commissionRequest = null, commissionPendingSlot = null,
                            commissionMessage = "启用结果超时，请刷新从机清单核实状态")
                    }
                    if (inventoryCollector == null && now - inventorySentAt > 10_000) requestInventory()
                    val mesh = state.value.mesh
                    val operationActive = mesh?.phase in setOf(
                        "SCANNING", "JOINING", "CONFIGURING", "WAIT_HELLO", "CANCELLING", "RESETTING", "REMOVING", "REMOVE_CLEANUP"
                    )
                    if (state.value.managementBusy && (operationActive || state.value.meshPending) && now - meshSentAt >= 1_500) {
                        // Keep the operation request id: the Master tags MESH with
                        // this id and includes the scan's CAND entries in this reply.
                        meshSentAt = now
                        manager.sendCommand("mesh-state ${state.value.hello?.epoch} $meshRequest")
                    } else if (!state.value.managementBusy && now - meshSentAt > 10_000) {
                        requestMeshState()
                    }
                }
                val fresh = state.value.hello != null && SystemClock.elapsedRealtime() - lastHelloAt <= 15_000
                if (state.value.statusFresh != fresh) state.value = state.value.copy(statusFresh = fresh)
                val localFresh = state.value.local != null && SystemClock.elapsedRealtime() - lastLocalAt <= 15_000
                if (state.value.localFresh != localFresh) state.value = state.value.copy(localFresh = localFresh)
                val pwmFresh = state.value.pwm.snapshot != null && SystemClock.elapsedRealtime() - lastPwmAt <= 15_000
                if (state.value.pwmFresh != pwmFresh) state.value = state.value.copy(pwmFresh = pwmFresh)
                if ((state.value.flows.isNotEmpty() || state.value.liveFlows.isNotEmpty()) && state.value.flowClockAt != now)
                    state.value = state.value.copy(flowClockAt = now)
                if (state.value.pwm.pending != null && SystemClock.elapsedRealtime() - pwmSentAt > 8_000)
                    state.value = state.value.copy(pwm = state.value.pwm.copy(message = "调速尚未确认，请刷新或重连核实；不会自动重发"))
                val flowSession = state.value.flowCalibration
                val flowHello = state.value.hello
                if (flowSession?.locked == true && flowHello?.role == "PUMP_MASTER") {
                    val recovered = flowSession.reconcileHostEpoch(flowHello.epoch)
                    if (recovered != flowSession) persistFlowCalibration(recovered)
                    val current = state.value.flowCalibration
                    if (current?.locked == true && now - flowCalibrationQueryAt >= 1_800) {
                        flowCalibrationQueryAt = now
                        manager.sendCommand(current.queryCommand())
                    }
                }
                if (state.value.pumpResetRequest != null && now - pumpResetSentAt > 8_000)
                    state.value = state.value.copy(pumpResetRequest = null,
                        pumpResetMessage = "主机未确认解除结果，请刷新状态核实；不会自动重发")
                if (state.value.hello == null || SystemClock.elapsedRealtime() - lastHelloAt > 10_000) manager.requestState()
                if (state.value.hello?.role == "PUMP_MASTER" && state.value.taskNeedsReconciliation) query()
                if (state.value.pumpTestUnresolved) { queryTest(); manager.sendCommand("local") }
            }
            handler.postDelayed(this, 2_000)
        }
    }
    init {
        runCatching {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) file.openRead().use(MasterLedger::read)
            else MasterLedger.fresh().also { save(it) }
        }.onSuccess { ledger = it }.onFailure { storageError(it) }
        runCatching {
            if (historyFile.baseFile.exists() || File(historyFile.baseFile.path + ".bak").exists())
                historyFile.openRead().use(MasterHistory::read) else emptyList()
        }.onSuccess { state.value = state.value.copy(history = it) }
            .onFailure { state.value = state.value.copy(historyHealthy = false) }
        handler.postDelayed(poll, 2_000)
    }
    private fun save(next: MasterLedger) {
        val output = file.startWrite()
        try { next.write(output); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        ledger = next
    }
    private fun storageError(error: Throwable) {
        state.value = state.value.copy(storageHealthy = false, message = "控制记录保存失败，已禁止新任务：${error.message}")
    }
    private fun persistTask(task: MasterTask): Boolean = runCatching {
        val current = checkNotNull(ledger)
        val device = checkNotNull(address)
        save(current.copy(sequence = maxOf(current.sequence, task.seq, task.stopSequence ?: 0), tasks = current.tasks + (device to task)))
    }.onFailure(::storageError).isSuccess
    private fun persistTest(test: MasterPumpTest): Boolean = runCatching {
        val current = checkNotNull(ledger)
        save(current.copy(sequence = maxOf(current.sequence, test.seq), pumpTests = current.pumpTests + (checkNotNull(address) to test)))
    }.onFailure(::storageError).isSuccess

    private fun nextDurableSequence(): Long? = runCatching {
        val current = checkNotNull(ledger)
        current.nextSequence().also { save(current.copy(sequence = it)) }
    }.onFailure(::storageError).getOrNull()

    private fun persistFlowCalibration(session: FlowCalibrationSession): Boolean = runCatching {
        val current = checkNotNull(ledger)
        val device = checkNotNull(address)
        val highWater = maxOf(current.sequence, session.request, session.calibrationSaveRequest ?: 0L)
        save(current.copy(sequence = highWater, flowCalibrations = current.flowCalibrations + (device to session)))
        state.value = state.value.copy(flowCalibration = session)
    }.onFailure(::storageError).isSuccess

    private fun recordObservation() {
        val current = state.value
        val task = current.task ?: return
        val device = address ?: return
        val previous = MasterHistory.matchingEntry(current.history, device, task)
        val entry = MasterHistoryEntry(device, current.deviceName, task, System.currentTimeMillis(),
            current.report ?: previous?.report, current.volume ?: previous?.volume,
            (previous?.flows ?: emptyMap()) + current.flows.mapValues { it.value.copy(receivedAt = 0) },
            current.taskAddresses.ifEmpty { previous?.nodes ?: emptyMap() })
        if (current.historyHealthy && previous != null && entry.copy(updatedAt = previous.updatedAt) == previous) return
        val next = MasterHistory.upsert(current.history, entry)
        runCatching {
            val output = historyFile.startWrite()
            try { MasterHistory.write(next, output); historyFile.finishWrite(output) }
            catch (error: Exception) { historyFile.failWrite(output); throw error }
        }.onSuccess { state.value = state.value.copy(history = next, historyHealthy = true) }
            .onFailure { state.value = state.value.copy(history = next, historyHealthy = false) }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: MasterDevice) {
        address = device.device.address.uppercase()
        directStartReply = false
        stopSequence = null
        // Do not carry an in-flight inventory/mesh request across BLE sessions.
        // The visible state is reset below, but these private collectors would
        // otherwise make the first request on a newly connected Master a no-op.
        inventoryCollector = null
        inventorySentAt = 0L
        inventoryReceivedAt = 0L
        meshRequest = 0L
        meshSentAt = 0L
        meshReceivedAt = 0L
        val savedTask = ledger?.tasks?.get(address)
        stopSequence = savedTask?.stopSequence
        stopSentAt = 0
        val savedFlowCalibration = ledger?.flowCalibrations?.get(address)
        val restoredState = state.value.copy(connection = MasterConnectionState.Connecting, hello = null, caps = null, maintenance = null, meshBudget = null, statusFresh = false, statusAt = null, report = null, volume = null, flows = emptyMap(), liveFlows = emptyMap(), local = null, localFresh = false, localAt = null, stopPending = savedTask?.let { it.stopRequested && it.unresolved } == true,
            slaves = null, inventoryFresh = false, inventoryLoading = false, mesh = null, meshFresh = false, meshPending = false, candidates = emptyList(),
            pwm = MasterPumpPwm(), pwmFresh = false,
            calibration = MasterCalibration(slot = savedFlowCalibration?.slot ?: 0, address = savedFlowCalibration?.address ?: 0),
            flowCalibration = savedFlowCalibration,
            pumpTest = ledger?.pumpTests?.get(address), testStopPending = false, testMessage = "正在同步原测试状态；不会自动重新启动",
            task = savedTask, masterAddress = address, taskAddresses = emptyMap(), removal = null,
            deviceName = device.advertisedName ?: "Master ${address}", retryDevice = device,
            message = "正在连接；请完成系统 PIN 配对").restoreTaskObservation()
        state.value = if (restoredState.taskResultsComplete)
            restoredState.copy(message = "正在连接；已恢复本机保存的完整任务结果") else restoredState
        manager.connect(device.device)
    }

    fun retryConnection() {
        state.value.retryDevice?.let(::connect)
    }
    fun start(mask: Long, ml: Long, seconds: Long, amounts: String = "") {
        if (!state.value.canStart) return
        if (!state.value.canStartMask(mask)) { message("当前主机最多支持 ${state.value.maxConcurrent} 路同时出水，请调整选择或更新固件"); return }
        if (state.value.caps?.basic == true && amounts.isNotEmpty()) { message("基础版本统一设置每路目标量"); return }
        if (mask !in 1..0xffff_ffffL || mask and state.value.selectableMask.inv() != 0L || (if (amounts.isEmpty()) !VolumeRules.isValid(ml) else ml != 0L || !ValveAmounts.isValid(mask, amounts)) || seconds !in 1..240) {
            message("请选择节点，水量 50–2000 mL 且为 50 的整数倍，时限 1–240 秒")
            return
        }
        val current = ledger ?: return
        val hello = state.value.hello ?: return
        if (amounts.isNotEmpty() && !hello.individual) { message("请更新主机固件以使用单独设置"); return }
        val task = runCatching { MasterTask(current.client, current.nextSequence(), hello.epoch, mask, ml, seconds * 1000, amounts = amounts) }
            .onFailure(::storageError).getOrNull() ?: return
        if (!MasterProtocol.isAllowedCommand(task.command())) { message("请选择节点，水量 50–2000 mL 且为 50 的整数倍，时限 1–240 秒"); return }
        if (SystemClock.elapsedRealtime() - lastControlAt < 300) { message("请稍后再启动"); return }
        if (!persistTask(task)) return
        directStartReply = true
        stopSequence = null
        lastControlAt = SystemClock.elapsedRealtime()
        state.value = state.value.copy(task = task, taskAddresses = taskNodeAddresses(task.mask, state.value.slaves.orEmpty()),
            report = null, volume = null, flows = emptyMap(), liveFlows = emptyMap(), stopPending = false, message = "等待 Master 确认任务；请勿重复启动")
        recordObservation()
        manager.sendCommand(task.command())
    }
    fun query() {
        val task = state.value.task ?: return
        if (!state.value.connected || state.value.hello?.role != "PUMP_MASTER") return
        if (SystemClock.elapsedRealtime() - lastControlAt < 300) return
        directStartReply = false
        lastControlAt = SystemClock.elapsedRealtime()
        manager.sendCommand(task.query())
    }
    fun retryOriginal() {
        if (!state.value.canRetry) return
        val task = state.value.task ?: return
        if (SystemClock.elapsedRealtime() - lastControlAt < 300) { message("请稍后重试"); return }
        if (!persistTask(task)) return
        directStartReply = true
        lastControlAt = SystemClock.elapsedRealtime()
        message("正在重试原请求，沿用原身份、序号和参数，等待 Master 确认")
        manager.sendCommand(task.command())
    }
    fun selectCalibration(slot: Int) {
        if (state.value.calibration.pending != null || state.value.flowCalibrationLocked) return
        val node = state.value.slaves?.firstOrNull { it.slot == slot && it.slot in 1..32 } ?: return
        state.value = state.value.copy(calibration = MasterCalibration(slot = slot, address = node.address))
        readCalibration()
    }
    fun readCalibration() {
        val current = state.value
        if (!current.canReadCalibration) return
        val c = current.calibration
        if (current.slaves.orEmpty().none { it.slot == c.slot && it.address == c.address }) return
        val epoch = current.hello?.epoch ?: return
        val request = nextRequest()
        calibrationSentAt = SystemClock.elapsedRealtime()
        state.value = current.copy(calibration = c.copy(snapshot = null,
            pending = CalibrationPending(epoch, request, c.slot, c.address), message = "正在读取三档计量系数…"))
        manager.sendCommand("cal-get $epoch $request ${c.slot} ${c.address.toString(16)}")
    }
    fun saveCalibration(low: String, medium: String, high: String) {
        val current = state.value
        if (!current.canSaveCalibration) return
        val values = listOf(low, medium, high).map { FlowCalibration.parse(it) ?: return }
        val c = current.calibration
        val snapshot = c.snapshot ?: return
        val request = nextRequest()
        calibrationSentAt = SystemClock.elapsedRealtime()
        state.value = current.copy(calibration = c.copy(
            pending = CalibrationPending(snapshot.epoch, request, c.slot, c.address, snapshot.revision, values),
            message = "等待主机写入并确认三档计量系数…"))
        manager.sendCommand("cal-set ${snapshot.epoch} $request ${c.slot} ${c.address.toString(16)} ${snapshot.revision} ${snapshot.baseK} ${snapshot.baseVersion} ${values.joinToString(" ")}")
    }
    fun startFlowCalibration(standardMl: Long, speed: Int) {
        val current = state.value
        current.flowCalibrationBlockReason(standardMl, speed)?.let { reason ->
            state.value = current.copy(calibration = current.calibration.copy(message = reason))
            return
        }
        val pwm = current.pwm.snapshot ?: return
        if (pwm.level != speed) {
            if (!current.canSetSpeed) {
                state.value = current.copy(calibration = current.calibration.copy(message = "当前条件不允许调速，请刷新主机状态后重试"))
                return
            }
            setSpeed(speed)
            state.value = state.value.copy(calibration = state.value.calibration.copy(
                message = "正在切换到${listOf("低", "中", "高")[speed - 1]}档；收到主机确认后再次启动校准"))
            return
        }
        val hello = current.hello ?: return
        val client = ledger?.client ?: return
        val request = nextDurableSequence() ?: return
        val session = FlowCalibrationSession(hello.epoch, client, request, current.calibration.slot,
            current.calibration.address, standardMl, speed)
        if (!MasterProtocol.isAllowedCommand(session.startCommand())) {
            state.value = current.copy(calibration = current.calibration.copy(message = "校准参数无效，请检查从机、档位和标准水量"))
            return
        }
        if (!persistFlowCalibration(session)) return
        flowCalibrationQueryAt = SystemClock.elapsedRealtime()
        manager.sendCommand(session.startCommand())
    }
    fun markFlowCalibration() {
        val current = state.value
        val session = current.flowCalibration?.takeIf { it.canMark } ?: return
        val updated = session.requestedMark()
        if (!persistFlowCalibration(updated)) return
        manager.sendCommand(updated.markCommand())
    }
    fun stopFlowCalibration() {
        val current = state.value
        if (!current.canStopFlowCalibration) return
        val session = checkNotNull(current.flowCalibration)
        val updated = session.requestedStop()
        if (!persistFlowCalibration(updated)) state.value = state.value.copy(flowCalibration = updated)
        manager.sendCommand(updated.stopCommand())
    }
    fun queryFlowCalibration() {
        val current = state.value
        val session = current.flowCalibration ?: return
        val hello = current.hello ?: return
        if (!current.connected || hello.role != "PUMP_MASTER") return
        val recovered = session.reconcileHostEpoch(hello.epoch)
        if (recovered != session) persistFlowCalibration(recovered)
        val active = state.value.flowCalibration ?: recovered
        flowCalibrationQueryAt = SystemClock.elapsedRealtime()
        manager.sendCommand(active.queryCommand())
    }
    fun saveFlowCalibrationCoefficient() {
        val current = state.value
        if (!current.canSaveFlowCoefficient) return
        val session = checkNotNull(current.flowCalibration)
        val coefficient = checkNotNull(session.calculatedK)
        val snapshot = checkNotNull(current.calibration.snapshot)
        val values = snapshot.values.toMutableList().apply { this[session.speed - 1] = coefficient }
        val request = nextDurableSequence() ?: return
        val updated = session.copy(saved = false, calibrationSaveRequest = request)
        if (!persistFlowCalibration(updated)) return
        val calibration = current.calibration
        state.value = state.value.copy(calibration = calibration.copy(
            pending = CalibrationPending(snapshot.epoch, request, snapshot.slot, snapshot.address,
                snapshot.revision, values), message = "等待主机保存并确认当前档系数…"))
        manager.sendCommand("cal-set ${snapshot.epoch} $request ${snapshot.slot} ${snapshot.address.toString(16)} " +
            "${snapshot.revision} ${snapshot.baseK} ${snapshot.baseVersion} ${values.joinToString(" ")}")
    }
    fun setSpeed(level: Int) {
        val current = state.value
        if (!current.canSetSpeed) return
        val next = current.pwm.request(level)
        if (next.pending == null || next == current.pwm) return
        val snapshot = current.pwm.snapshot ?: return
        pwmSentAt = SystemClock.elapsedRealtime()
        state.value = current.copy(pwm = next)
        manager.sendCommand(snapshot.command(level))
    }
    fun startTest(seconds: Int, dryRun: Boolean = false) {
        if (dryRun) {
            if (!state.value.canStartDryTest || seconds != 3) return
        } else if (!state.value.canStartTest || seconds !in listOf(3, 5, 10)) return
        val current = ledger ?: return
        val test = runCatching { MasterPumpTest(current.client, current.nextSequence(), checkNotNull(state.value.hello).epoch, seconds * 1000L, dryRun = dryRun) }
            .onFailure(::storageError).getOrNull() ?: return
        if (!persistTest(test)) return
        state.value = state.value.copy(pumpTest = test, testStopPending = false, testMessage = "启动待确认，请勿重复启动；主机到时自动停泵")
        manager.sendCommand(test.command())
    }
    fun stopTest() {
        if (!state.value.canStopTest) return
        val test = state.value.pumpTest ?: return
        state.value = state.value.copy(testStopPending = true, testMessage = "停止待确认；正在取消原测试请求")
        manager.sendCommand(test.stop())
    }
    fun queryTest() {
        val test = state.value.pumpTest ?: return
        if (state.value.connected && state.value.hello?.role == "PUMP_MASTER") manager.sendCommand(test.query())
    }
    private fun resetMeshState() {
        inventoryCollector = null; inventorySentAt = 0; inventoryReceivedAt = 0; meshRequest = 0; meshSentAt = 0; meshReceivedAt = 0
        state.value = state.value.copy(slaves = null, inventoryFresh = false, inventoryLoading = false,
            inventoryMessage = "从机清单待同步", mesh = null, meshFresh = false, meshPending = false, candidates = emptyList(), meshMessage = "组网状态待同步",
            removal = state.value.removal?.takeIf { it.pending }?.copy(pending = false,
                message = "连接中断，删除结果待核实；重连后请刷新清单，不会自动重发删除"))
    }
    private fun requestInventory(force: Boolean = false) {
        val current = state.value
        val hello = current.hello ?: return
        if (!current.connected || hello.role != "PUMP_MASTER") return
        if (force) inventoryCollector = null
        if (inventoryCollector != null) return
        val req = nextRequest()
        inventoryCollector = MasterInventoryCollector(hello.epoch, req)
        inventorySentAt = SystemClock.elapsedRealtime()
        state.value = current.copy(inventoryLoading = true, inventoryMessage = "正在同步从机清单…")
        manager.sendCommand("inventory ${hello.epoch} $req")
    }
    private fun requestMeshState(force: Boolean = false) {
        val hello = state.value.hello ?: return
        if (!state.value.connected || hello.role != "PUMP_MASTER") return
        if (!state.value.managementBusy || meshRequest == 0L) meshRequest = nextRequest()
        meshSentAt = SystemClock.elapsedRealtime()
        manager.sendCommand("mesh-state ${hello.epoch} $meshRequest")
    }
    private fun meshCommand(command: String, argument: String = "", allowed: (MasterUiState) -> Boolean = { it.canManageMesh }) {
        if (!allowed(state.value)) return
        val hello = state.value.hello ?: return
        meshRequest = nextRequest(); meshSentAt = SystemClock.elapsedRealtime()
        state.value = state.value.copy(meshPending = true, meshMessage = "已发送请求，等待 Master 确认")
        manager.sendCommand("$command ${hello.epoch} $meshRequest${if (argument.isEmpty()) "" else " $argument"}")
    }
    fun resetMesh() {
        if (!state.value.canResetMesh) return
        state.value = state.value.copy(meshPending = true, meshMessage = "已发送重置请求，等待 Master 清除从机并重启")
        manager.sendCommand("mesh-reset CONFIRM")
    }
    fun removeSlave(address: Long) {
        val current = state.value
        val node = current.slaves?.firstOrNull { it.address == address } ?: return
        if (!current.canRemoveSlave(node)) return
        val epoch = current.hello?.epoch ?: return
        meshRequest = nextRequest(); meshSentAt = SystemClock.elapsedRealtime(); removalSentAt = meshSentAt
        inventoryCollector = null
        state.value = current.copy(meshPending = true, inventoryFresh = false, inventoryLoading = false,
            removal = MasterRemoval(epoch, meshRequest, address, node.slot))
        manager.sendCommand("mesh-remove $epoch $meshRequest ${address.toString(16)} CONFIRM")
    }
    fun resetBootLock() {
        val current = state.value
        if (!current.canResetBootLock) return
        val epoch = current.hello?.epoch ?: return
        val request = nextRequest()
        pumpResetSentAt = SystemClock.elapsedRealtime()
        state.value = current.copy(pumpResetRequest = request,
            pumpResetMessage = "请求已发送，等待主机安全校验与确认")
        manager.sendCommand("pump-reset $epoch $request CONFIRM")
    }
    fun scanSlaves() {
        val current = state.value
        if (!current.canScanMesh) {
            state.value = current.copy(meshMessage = current.meshScanBlockReason ?: "主机暂时不可组网，请刷新状态后重试")
            if (current.connected) refresh()
            return
        }
        meshCommand("mesh-scan", allowed = { it.canScanMesh })
    }
    fun joinSlave(uuid: String) { if (uuid in state.value.candidates) meshCommand("mesh-join", uuid, allowed = { it.canJoinMesh }) }
    fun commissionSlave(slot: Int) {
        val current = state.value
        val node = current.slaves?.firstOrNull { it.slot == slot } ?: return
        if (!current.canCommission(node) || current.commissionRequest != null) return
        val hello = current.hello ?: return
        val request = nextRequest()
        inventoryCollector = null
        state.value = current.copy(commissionRequest = request, commissionPendingSlot = slot,
            commissionMessage = "已发送启用请求，等待阀门写入并回报")
        commissionSentAt = SystemClock.elapsedRealtime()
        manager.sendCommand("commission ${hello.epoch} $request $slot CONFIRM")
    }
    fun retrySlave(address: Long) { if (state.value.slaves.orEmpty().any { it.address == address }) meshCommand("mesh-retry", address.toString(16)) }
    fun refresh() { if (state.value.connected) { manager.requestState(); query(); queryTest(); requestInventory(force = true); requestMeshState(force = true) } }
    fun stop() {
        val current = state.value
        if (!current.canStop) { query(); return }
        val task = current.task ?: return
        val next = runCatching { task.copy(stopRequested = true,
            stopSequence = task.stopSequence ?: checkNotNull(ledger).nextSequence()) }
            .onFailure(::storageError).getOrNull() ?: task.copy(stopRequested = true)
        val saved = persistTask(next)
        state.value = state.value.copy(task = next, stopPending = true)
        if (saved) message(if (next.job == null) "已保存停止意图；查询原任务后停止，不会重发启动" else "已请求停止，等待 Master 回复和阀门结算")
        sendStopIntent()
        if (next.job == null) query()
    }
    private fun sendStopIntent() {
        val current = state.value
        val task = current.task?.takeIf { it.stopRequested && it.unresolved } ?: return
        val hello = current.hello?.takeIf { current.connected && it.role == "PUMP_MASTER" } ?: return
        val command = task.stopCommand(hello.epoch) ?: return
        val now = SystemClock.elapsedRealtime()
        if (stopSentAt != 0L && now - stopSentAt < 1_000) return
        val seq = task.stopSequence ?: task.seq // An emergency stop remains possible after local write failure.
        stopSequence = seq; stopSentAt = now
        manager.sendCommand(command)
    }
    private fun message(text: String) { state.value = state.value.copy(message = text) }
    override fun onConnectionStateChanged(state: MasterConnectionState) {
        this.state.value = this.state.value.copy(connection = state,
            hello = if (state is MasterConnectionState.Connected) this.state.value.hello else null,
            statusFresh = state is MasterConnectionState.Connected && this.state.value.statusFresh,
            localFresh = state is MasterConnectionState.Connected && this.state.value.localFresh)
        if (state !is MasterConnectionState.Connected) {
            resetMeshState()
            val selected = this.state.value.flowCalibration
            this.state.value = this.state.value.copy(pwm = MasterPumpPwm(), pwmFresh = false,
                caps = null, maintenance = null, meshBudget = null,
                calibration = MasterCalibration(slot = selected?.slot ?: this.state.value.calibration.slot,
                    address = selected?.address ?: this.state.value.calibration.address), liveFlows = emptyMap())
            directStartReply = false
            if (state is MasterConnectionState.Disconnected || state is MasterConnectionState.Error)
                message("连接已断开；已接受任务由 Master 继续执行，重连后查询原任务")
        }
    }
    override fun onScanStateChanged(state: MasterScanState) { this.state.value = this.state.value.copy(scan = state) }
    override fun onDevicesChanged(devices: List<MasterDevice>) { state.value = state.value.copy(devices = devices) }
    override fun onError(message: String) { message(message) }
    override fun onCommandResult(command: String, success: Boolean, message: String) {
        if (command.startsWith("mesh-remove ") && !success) {
            val removal = state.value.removal
            if (removal != null && removal.request == command.split(' ').getOrNull(2)?.toLongOrNull()) {
                state.value = state.value.copy(meshPending = false, meshFresh = false, inventoryFresh = false,
                    removal = removal.copy(pending = false, message = "$message；删除结果待核实，请刷新清单"))
                requestMeshState()
                requestInventory(force = true)
            }
            return
        }
        if (!success && (command.startsWith("cal-get ") || command.startsWith("cal-set "))) {
            val pending = state.value.calibration.pending
            val parts = command.split(' ')
            if (pending?.request == parts.getOrNull(2)?.toLongOrNull() && pending?.epoch == parts.getOrNull(1)?.toLongOrNull())
                state.value = state.value.copy(calibration = state.value.calibration.uncertain("$message；请重新读取主机保存值"))
            return
        }
        if (command.startsWith("pump-reset ") && !success) {
            state.value = state.value.copy(pumpResetRequest = null,
                pumpResetMessage = "$message；解除结果待主机状态核实")
            return
        }
        if (command.startsWith("inventory ") && !success) {
            inventoryCollector = null
            inventorySentAt = SystemClock.elapsedRealtime()
            state.value = state.value.copy(inventoryLoading = false, inventoryFresh = false,
                inventoryMessage = "从机清单请求发送失败：$message")
            return
        }
        if (command.startsWith("commission ") && !success) {
            state.value = state.value.copy(commissionRequest = null, commissionPendingSlot = null,
                commissionMessage = "启用请求未发送：$message")
            return
        }
        if (command == "mesh-reset CONFIRM") {
            if (!success) state.value = state.value.copy(meshPending = false, meshMessage = message)
            else state.value = state.value.copy(meshMessage = "重置命令已写入，等待 Master 清除从机并重启")
            return
        }
        if (!success && command.startsWith("pump-speed ")) {
            state.value = state.value.copy(pwm = state.value.pwm.copy(message = "$message；档位待确认，请刷新或重连核实"))
            return
        }
        if (!success && (command.startsWith("pump-test") || command.startsWith("pump-dry-test "))) {
            state.value = state.value.copy(testMessage = "$message；测试状态待确认，请重连或刷新，勿重复启动")
            return
        }
        if (!success) this.message("$message；任务结果需通过查询确认")
        // Successful GATT writes intentionally do not change task acceptance.
    }
    override fun onStatus(status: String) {
        if (!status.startsWith("LIVE ")) state.value = state.value.copy(log = (state.value.log + status).takeLast(24))
        // Older Master firmware may reject the inventory command because it
        // does not know the INV_* fields. Stop waiting immediately so the
        // valve picker explains the protocol mismatch and can be retried
        // after the firmware is updated.
        if (inventoryCollector != null && status.trim().startsWith("INVALID mesh_fields")) {
            inventoryCollector = null
            inventorySentAt = SystemClock.elapsedRealtime()
            state.value = state.value.copy(
                inventoryLoading = false,
                inventoryFresh = false,
                inventoryMessage = "主机不支持从机清单字段，请升级 Master 固件后重试"
            )
        }
        if (status.contains("UNAUTHORIZED maintenance_requires_USB")) {
            state.value = state.value.copy(pumpResetRequest = null,
                pumpResetMessage = "当前主机固件仅支持 USB 维护；请更新主机固件后使用手机解除启动锁")
        }
        if (status.trim() == "OK mesh_reset_started") {
            state.value = state.value.copy(meshMessage = "Master 正在清除全部从机连接信息")
        } else if (status.trim() == "OK mesh_reset rebooting") {
            state.value = state.value.copy(meshPending = false, slaves = emptyList(), candidates = emptyList(), inventoryFresh = false,
                meshFresh = false, meshMessage = "从机组网已重置，Master 正在重启；重连后可重新添加从机")
        } else if (status.trim() in setOf("INVALID mesh_reset_confirmation", "MESH_RESET_NOT_REQUIRED", "MESH_RESET_NOT_READY",
                "BUSY mesh_reset_requires_idle", "MESH_RESET_FAILED")) {
            state.value = state.value.copy(meshPending = false, meshMessage = status.trim())
        }
        when (val parsed = MasterProtocol.parse(status)) {
            is MasterMessage.FlowCalibrationStatus -> {
                val current = state.value
                val session = current.flowCalibration ?: return
                val epoch = current.hello?.epoch ?: return
                val next = session.receive(parsed, epoch)
                if (next != session) persistFlowCalibration(next)
            }
            is MasterMessage.Calibration -> {
                val current = state.value
                val epoch = current.hello?.epoch ?: return
                val pending = current.calibration.pending
                val flowSave = current.flowCalibration?.takeIf {
                    it.calibrationSaveRequest == parsed.request && pending?.request == parsed.request
                }
                val updated = current.calibration.receive(parsed, epoch)
                state.value = current.copy(calibration = updated)
                if (flowSave != null) {
                    val confirmed = updated.message.startsWith("主机已保存")
                    persistFlowCalibration(flowSave.copy(saved = confirmed, calibrationSaveRequest = null))
                }
            }
            is MasterMessage.PumpReset -> {
                val current = state.value
                if (parsed.epoch != current.hello?.epoch || parsed.request != current.pumpResetRequest) return
                val success = parsed.reply == "OK" && parsed.fault == "NONE" && !parsed.output
                state.value = current.copy(pumpResetRequest = null, pumpResetMessage = when {
                    success -> "启动锁已解除；水泵仍为关闭状态，正在刷新主机状态"
                    parsed.reply == "BUSY" -> "主机正在执行任务或组网操作，请等待完成后刷新重试"
                    parsed.reply == "STORAGE" -> "主机存储异常，未解除启动锁"
                    parsed.reply == "STALE" -> "主机启动状态已变化，请刷新后重试"
                    else -> "主机未解除启动锁：${parsed.reply}（${parsed.fault}）"
                })
                if (success) manager.requestState()
            }
            is MasterMessage.Caps -> if (parsed.epoch == state.value.hello?.epoch) state.value = state.value.copy(caps = parsed)
            is MasterMessage.Maintenance -> if (state.value.caps?.maintenance == true) state.value = state.value.copy(maintenance = parsed)
            is MasterMessage.MeshBudget -> if (parsed.epoch == state.value.hello?.epoch) {
                state.value = state.value.copy(meshBudget = parsed)
                if (parsed.recovery == "RESTART_REQUIRED") message("组网资源状态不可信，需要受控重启主机；重连后先核实原任务")
            }
            is MasterMessage.Hello -> {
                val first = state.value.hello == null
                val existingFlow = state.value.flowCalibration
                val recoveredFlow = existingFlow?.reconcileHostEpoch(parsed.epoch)
                if (recoveredFlow != null && recoveredFlow != existingFlow) persistFlowCalibration(recoveredFlow)
                if (state.value.hello?.epoch != parsed.epoch) {
                    resetMeshState()
                    val selected = state.value.flowCalibration
                    state.value = state.value.copy(pwm = MasterPumpPwm(), pwmFresh = false, localFresh = false,
                        caps = null, maintenance = null, meshBudget = null,
                        calibration = MasterCalibration(slot = selected?.slot ?: state.value.calibration.slot,
                            address = selected?.address ?: state.value.calibration.address), liveFlows = emptyMap())
                }
                lastHelloAt = SystemClock.elapsedRealtime()
                state.value = state.value.copy(hello = parsed, statusFresh = true, statusAt = System.currentTimeMillis())
                if (first) { requestInventory(); requestMeshState() }
                state.value.flowCalibration?.takeIf { it.locked }?.let {
                    flowCalibrationQueryAt = SystemClock.elapsedRealtime()
                    manager.sendCommand(it.queryCommand())
                }
                if (parsed.role != "PUMP_MASTER") message("当前设备不是 Master，请连接 PUMP_MASTER 设备")
                else if (state.value.task?.let { it.unresolved && it.epoch != parsed.epoch } == true)
                    message("主机已重启，上次请求结果待核实；不会自动重新出水")
                else if (first) {
                    val restored = state.value.taskResultsComplete && state.value.savedTaskObservation != null
                    message(when {
                        restored -> "主控已连接，已恢复本机保存的完整任务结果"
                        parsed.ready -> "主控已连接"
                        else -> "主控已连接，请查看配网、输出配置与故障状态"
                    })
                    query(); queryTest()
                }
            }
            is MasterMessage.InventoryBegin, is MasterMessage.InventoryNode, is MasterMessage.InventoryEnd -> {
                val complete = inventoryCollector?.accept(parsed) ?: return
                inventoryCollector = null; inventoryReceivedAt = SystemClock.elapsedRealtime()
                // Current inventory cannot establish which device occupied a slot in an older task.
                state.value = state.value.copy(slaves = complete, inventoryFresh = true, inventoryLoading = false,
                    inventoryMessage = "已同步 ${complete.size} 台配对从机")
            }
            is MasterMessage.Mesh -> {
                if (parsed.epoch != state.value.hello?.epoch || parsed.request != meshRequest) return
                val old = state.value.mesh
                meshReceivedAt = SystemClock.elapsedRealtime()
                val beforeRemoval = state.value.removal
                val removal = beforeRemoval?.receive(parsed)
                val finishedRemoval = beforeRemoval?.pending == true && removal?.pending == false
                state.value = state.value.copy(mesh = parsed.state, meshFresh = true,
                    meshPending = parsed.state.phase == "RESETTING" || removal?.pending == true,
                    removal = removal,
                    calibration = if (beforeRemoval?.succeeded != true && removal?.succeeded == true &&
                        state.value.calibration.address == removal.address)
                        MasterCalibration() else state.value.calibration,
                    candidates = emptyList(), meshMessage = parsed.state.label)
                if (finishedRemoval || old?.phase != parsed.state.phase && parsed.state.phase in setOf("READY", "FAILED", "REMOVE_DONE")) {
                    state.value = state.value.copy(inventoryFresh = false)
                    requestInventory(force = true)
                }
            }
            is MasterMessage.Removal -> {
                val removal = state.value.removal ?: return
                if (removal.succeeded) return
                if ((parsed.epoch != removal.epoch && parsed.reply != "STALE") || parsed.request != removal.request || parsed.address != removal.address) return
                state.value = state.value.copy(meshPending = false,
                    removal = removal.copy(pending = false, message = "删除未接受：${commissionReplyLabel(parsed.reply)}"))
                requestMeshState()
                if (parsed.reply == "STALE") manager.requestState()
                requestInventory(force = true)
            }
            is MasterMessage.Candidate -> {
                val current = state.value
                if (parsed.epoch == current.hello?.epoch && parsed.scan == current.mesh?.scan &&
                    current.candidates.size < 8 && parsed.uuid !in current.candidates)
                    state.value = current.copy(candidates = current.candidates + parsed.uuid)
            }
            is MasterMessage.Commission -> {
                val current = state.value
                if (parsed.request != current.commissionRequest || parsed.epoch != current.hello?.epoch ||
                    parsed.slot != current.commissionPendingSlot) return
                if (parsed.reply == "OK" && parsed.commissioned) {
                    inventoryCollector = null
                    state.value = current.copy(commissionRequest = null, commissionPendingSlot = null,
                        commissionMessage = "${parsed.slot} 号阀门已启用，正在刷新设备状态", inventoryFresh = false)
                    requestInventory()
                } else {
                    state.value = current.copy(commissionRequest = null, commissionPendingSlot = null,
                        commissionMessage = "${parsed.slot} 号阀门启用失败：${commissionReplyLabel(parsed.reply)}（${parsed.reply}）")
                }
            }
            is MasterMessage.PumpPwm -> {
                val hello = state.value.hello ?: return
                if (hello.role != "PUMP_MASTER" || parsed.epoch != hello.epoch) return
                val next = state.value.pwm.receive(parsed, hello.epoch)
                if (next == state.value.pwm && state.value.pwm.snapshot != parsed) return
                lastPwmAt = SystemClock.elapsedRealtime()
                state.value = state.value.copy(pwm = next, pwmFresh = true)
            }
            is MasterMessage.Local -> {
                val hello = state.value.hello ?: return
                if (hello.role != "PUMP_MASTER") return
                lastLocalAt = SystemClock.elapsedRealtime()
                state.value = state.value.copy(local = parsed, localFresh = true, localAt = System.currentTimeMillis())
                val previous = state.value.pumpTest
                val identity = ledger?.client
                if (parsed.testActive && identity != null && parsed.testClient == identity && parsed.testSeq > 0) {
                    val test = when {
                        previous?.seq == parsed.testSeq && previous.epoch == hello.epoch -> previous.reconcile(
                            MasterMessage.PumpTest(identity, parsed.testSeq, hello.epoch, "OK", "RUNNING", "QUERY"))
                        previous == null || parsed.testSeq > previous.seq -> MasterPumpTest(
                            identity, parsed.testSeq, hello.epoch, maxOf(1L, parsed.testMs), MasterOutcome.ACCEPTED)
                        else -> previous
                    }
                    if (test != previous) persistTest(test)
                    state.value = state.value.copy(pumpTest = test, testMessage = if (test.unresolved)
                        "测试中，主机报告剩余 ${parsed.testMs} ms" else "收到旧测试快照，正在核实当前输出")
                } else if (previous?.unresolved == true && !parsed.pumpCommand && hello.epoch != previous.epoch) {
                    val test = previous.copy(outcome = MasterOutcome.FINISHED)
                    persistTest(test)
                    state.value = state.value.copy(pumpTest = test, testStopPending = false, testMessage = "主机已重启，确认输出关闭；原测试不会自动恢复")
                }
            }
            is MasterMessage.PumpTest -> {
                val test = state.value.pumpTest ?: return
                if (parsed.client != test.client || parsed.seq != test.seq || parsed.epoch != test.epoch) return
                if (!test.unresolved) return
                val next = test.reconcile(parsed)
                if (next != test) persistTest(next)
                state.value = state.value.copy(pumpTest = next, testStopPending = state.value.testStopPending && next.unresolved, testMessage = when {
                    parsed.state == "RUNNING" -> if (state.value.testStopPending) "停止待确认；主机仍报告测试中" else "主机已确认测试中，到时自动停止"
                    parsed.state == "STOPPED" -> "主机已确认测试输出关闭"
                    next.outcome == MasterOutcome.REJECTED -> "主机未接受测试：${parsed.reply}"
                    else -> "测试状态待确认：${parsed.reply}；请刷新或停止原测试"
                })
            }
            is MasterMessage.Report -> {
                val previous = state.value.task ?: return
                val updated = state.value.receiveTaskReport(parsed, stopSequence, directStartReply) ?: return
                val next = updated.task ?: return
                val saved = next == previous || persistTask(next)
                directStartReply = false
                state.value = (if (saved) updated else updated.copy(storageHealthy = false, message = state.value.message)).pruneLiveFlows()
                if (parsed.reply == "OK" || next != previous) recordObservation()
                sendStopIntent()
            }
            is MasterMessage.Volume -> if (parsed.job == state.value.task?.job) {
                val task = state.value.task ?: return
                if (parsed.measured and task.mask.inv() == 0L) {
                    val mergedVolume = MasterResultMerge.volume(state.value.volume, parsed)
                    if (mergedVolume != state.value.volume) {
                        state.value = state.value.copy(volume = mergedVolume)
                        recordObservation()
                    }
                }
            }
            is MasterMessage.Live -> state.value = state.value.receiveLive(parsed, SystemClock.elapsedRealtime())
            is MasterMessage.Flow -> if (parsed.job == state.value.task?.job) {
                val expected = state.value.expectedFlowAddresses
                if (expected == null || parsed.address in expected) {
                    val flow = parsed.copy(receivedAt = SystemClock.elapsedRealtime())
                    state.value = state.value.copy(
                        flows = state.value.flows + (flow.address to MasterResultMerge.flow(state.value.flows[flow.address], flow)),
                        flowClockAt = flow.receivedAt
                    ).pruneLiveFlows()
                    recordObservation()
                }
            }
            null -> if (status == "RATE_LIMIT" || status.startsWith("INVALID") || status.startsWith("UNAUTHORIZED"))
                message("$status；请查询任务确认状态")
        }
    }
    override fun onCleared() {
        handler.removeCallbacksAndMessages(null)
        manager.close()
        super.onCleared()
    }
}
