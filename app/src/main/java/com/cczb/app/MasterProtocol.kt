package com.cczb.app

import java.io.ByteArrayOutputStream

/** TX notifications are a byte stream. Decode only after a complete newline. */
class MasterLineAssembler(private val maxBytes: Int = 191) {
    private val bytes = ByteArrayOutputStream()
    private var discarding = false
    fun reset() { bytes.reset(); discarding = false }
    fun accept(packet: ByteArray): List<String> = buildList {
        packet.forEach { byte ->
            if (byte == 10.toByte()) {
                if (!discarding && bytes.size() > 0) add(bytes.toString("UTF-8").trimEnd('\r'))
                reset()
            } else if (!discarding) {
                if (bytes.size() >= maxBytes) { bytes.reset(); discarding = true }
                else bytes.write(byte.toInt())
            }
        }
    }
}

data class MasterJobKey(val epoch: Long, val id: Long) {
    override fun toString() = "$epoch:$id"
}

/**
 * The slave does not expose a phone-facing GAP name. Its Mesh PB-ADV UUID
 * carries the same two identity bytes used by the firmware name
 * PUMP_SLAVE_xxxx (UUID bytes 8..9, represented by hex characters 16..19).
 */
fun slaveDisplayName(uuid: String): String {
    if (uuid.length == 32 && uuid.startsWith("dddd504d", ignoreCase = true)) {
        return "PUMP_SLAVE_${uuid.substring(16, 20).uppercase()}"
    }
    return "从机 ${uuid.takeLast(4).uppercase()}"
}

fun commissionReplyLabel(reply: String): String = when (reply) {
    "BUSY" -> "主机、泵或阀门正在执行其他操作"
    "NOT_READY" -> "阀门未就绪，请检查 K 系数/版本、阀门是否关闭及安全互锁"
    "OFFLINE" -> "从机无响应，请检查供电和 Mesh 连接"
    "STORAGE" -> "从机存储失败"
    "INVALID" -> "启用参数无效，或主机收到的请求格式不正确"
    "UNAUTHORIZED" -> "目标从机不属于当前主机"
    "STALE" -> "主机状态已变化，请刷新后重试"
    else -> reply
}

sealed interface MasterMessage {
    data class Calibration(val epoch: Long, val request: Long, val slot: Int, val address: Long,
                           val revision: Long, val low: Long, val medium: Long, val high: Long,
                           val reply: String, val baseK: Long, val baseVersion: Long) : MasterMessage {
        val values get() = listOf(low, medium, high)
    }
    data class FlowCalibrationStatus(
        val epoch: Long, val client: String, val request: Long, val job: MasterJobKey?,
        val slot: Int, val address: Long, val phase: String, val operationSequence: Long,
        val reply: String, val standardMl: Long, val speed: Int, val startPulses: Long,
        val closePulses: Long, val finalPulses: Long, val livePulses: Long,
        val runtimeMs: Long, val reason: String
    ) : MasterMessage
    data class InventoryBegin(val epoch: Long, val request: Long, val count: Int) : MasterMessage
    data class InventoryNode(val epoch: Long, val request: Long, val node: MasterSlave) : MasterMessage
    data class InventoryEnd(val epoch: Long, val request: Long, val count: Int) : MasterMessage
    data class Mesh(val epoch: Long, val request: Long, val state: MasterMeshState) : MasterMessage
    data class Removal(val epoch: Long, val request: Long, val address: Long, val reply: String) : MasterMessage
    data class Candidate(val epoch: Long, val scan: Long, val uuid: String) : MasterMessage
    data class Commission(val epoch: Long, val request: Long, val slot: Int, val address: Long,
                          val reply: String, val commissioned: Boolean) : MasterMessage
    data class Hello(val role: String, val address: Long, val master: Long, val epoch: Long,
                     val mesh: Boolean, val commissioned: Boolean, val fault: String,
                     val individual: Boolean = false, val calibration: Boolean = false,
                     val flowCalibration: Boolean = false) : MasterMessage {
        val ready get() = role == "PUMP_MASTER" && address == master && address in 1..0x7fff &&
            epoch > 0 && mesh && commissioned && fault == "NONE"
    }
    data class Report(val client: String, val seq: Long, val reply: String, val job: MasterJobKey?,
                      val phase: Int, val mask: Long, val done: Long, val failed: Long, val unknown: Long,
                      val reason: String = "UNKNOWN") : MasterMessage
    data class Volume(val job: MasterJobKey, val ml: Long, val measured: Long) : MasterMessage
    data class Live(val job: MasterJobKey, val address: Long, val ml: Long, val pulses: Long,
                    val runtimeMs: Long, val rateMlMin: Long, val receivedAt: Long = 0) : MasterMessage {
        fun isFresh(now: Long) = receivedAt > 0 && now >= receivedAt && now - receivedAt <= 6_000
    }
    data class Flow(
        val job: MasterJobKey,
        val address: Long,
        val ml: Long,
        val phase: String,
        val pulses: Long = 0,
        val revision: Long = 0,
        val terminal: Boolean = phase in setOf("DONE", "FAULT"),
        val measured: Boolean = true,
        val receivedAt: Long = 0,
        val reason: String = "UNKNOWN"
    ) : MasterMessage {
        fun isFresh(now: Long, maxAgeMs: Long = 10_000) =
            receivedAt > 0 && now >= receivedAt && now - receivedAt <= maxAgeMs
    }
    /** This is a control output snapshot, not feedback from a running sensor. */
    data class Local(val pumpCommand: Boolean, val phase: Int, val fault: String,
                     val testSupported: Boolean = false, val testActive: Boolean = false,
                     val testMs: Long = 0, val testClient: String? = null, val testSeq: Long = 0,
                     val pumpReady: Boolean = false, val pumpResetSupported: Boolean = false) : MasterMessage
    data class PumpTest(val client: String, val seq: Long, val epoch: Long, val reply: String, val state: String, val op: String) : MasterMessage
    data class PumpReset(val epoch: Long, val request: Long, val reply: String, val fault: String,
                         val output: Boolean) : MasterMessage
    data class PumpPwm(val epoch: Long, val revision: Long, val level: Int, val duty: Int,
                       val hz: Long, val output: Boolean, val reply: String,
                       val manualTest: Boolean = false, val dryMaxMs: Long = 0,
                       val dryActive: Boolean = false) : MasterMessage {
        fun command(newLevel: Int) = "pump-speed $epoch $revision $newLevel"
    }
}

data class MasterPumpTest(val client: String, val seq: Long, val epoch: Long, val durationMs: Long,
                          val outcome: MasterOutcome = MasterOutcome.PENDING, val dryRun: Boolean = false) {
    val unresolved get() = outcome == MasterOutcome.PENDING || outcome == MasterOutcome.ACCEPTED
    fun command() = "${if (dryRun) "pump-dry-test" else "pump-test"} $client $seq $epoch $durationMs"
    fun stop() = "pump-test-stop $client $seq $epoch"
    fun query() = "pump-test-query $client $seq $epoch"
    fun reconcile(reply: MasterMessage.PumpTest): MasterPumpTest {
        if (reply.client != client || reply.seq != seq || reply.epoch != epoch || !unresolved) return this
        return when {
            reply.state == "RUNNING" -> copy(outcome = MasterOutcome.ACCEPTED)
            reply.state == "STOPPED" -> copy(outcome = MasterOutcome.FINISHED)
            reply.op == "START" && reply.reply in setOf("BUSY", "INVALID", "NOT_READY", "STORAGE", "UNAUTHORIZED", "CAPACITY", "CONFLICT", "STALE") &&
                outcome == MasterOutcome.PENDING -> copy(outcome = MasterOutcome.REJECTED)
            else -> this
        }
    }
}

enum class MasterOutcome { PENDING, ACCEPTED, REJECTED, FINISHED }
data class MasterTask(
    val client: String, val seq: Long, val epoch: Long, val mask: Long, val ml: Long, val maxMs: Long,
    val outcome: MasterOutcome = MasterOutcome.PENDING, val job: MasterJobKey? = null,
    val amounts: String = ""
) {
    val unresolved get() = outcome == MasterOutcome.PENDING || outcome == MasterOutcome.ACCEPTED
    val individual get() = amounts.isNotEmpty()
    fun target(slot: Int) = if (individual) ValveAmounts.at(amounts, slot) else ml
    val totalMl get() = ValveAmounts.slots(mask).sumOf(::target)
    val amountLabel get() = if (individual) "单独设置" else "每路 $ml mL"
    fun command() = if (individual) "start-each $client $seq $epoch ${mask.toString(16).padStart(8, '0')} $maxMs $amounts"
        else "start $client $seq $epoch ${mask.toString(16).padStart(8, '0')} $ml $maxMs"
    fun query() = "query $client $seq"

    /** A transport write is never acceptance. Unknown query results stay unresolved. */
    fun reconcile(report: MasterMessage.Report, directStartReply: Boolean): MasterTask {
        if (report.client != client || report.seq != seq) return this
        if (report.reply == "OK" && report.job != null && report.job.epoch == epoch &&
            report.mask == mask && (job == null || job == report.job)) {
            if (!unresolved) return this
            return copy(job = report.job, outcome = if (report.phase >= 6) MasterOutcome.FINISHED else MasterOutcome.ACCEPTED)
        }
        if (directStartReply && outcome == MasterOutcome.PENDING && report.job == null &&
            report.reply in setOf("BUSY", "INVALID", "STALE", "NOT_READY", "STORAGE", "UNAUTHORIZED", "OFFLINE", "CAPACITY")) {
            return copy(outcome = MasterOutcome.REJECTED)
        }
        return this
    }
}

object MasterProtocol {
    private const val U32 = 0xffff_ffffL
    internal val repliesForLedger = setOf("OK", "BUSY", "INVALID", "STALE", "CONFLICT", "NOT_READY",
        "STORAGE", "UNAUTHORIZED", "OFFLINE", "CAPACITY", "PENDING", "UNKNOWN")
    private val replies = repliesForLedger
    private val hex = Regex("[0-9a-fA-F]{1,16}")
    fun client(value: String): String? = value.takeIf { hex.matches(it) }
        ?.lowercase()?.trimStart('0')?.takeIf { it.isNotEmpty() }
    private fun uint(value: String?, max: Long = U32): Long? = value?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.toLongOrNull()?.takeIf { it in 0..max }
    private fun mask(value: String?): Long? = value?.takeIf { Regex("[0-9a-fA-F]{1,8}").matches(it) }?.toLongOrNull(16)
    private fun key(value: String?): MasterJobKey? {
        val pieces = value?.split(':') ?: return null
        if (pieces.size != 2) return null
        val epoch = uint(pieces[0]) ?: return null
        val id = uint(pieces[1]) ?: return null
        return if (epoch > 0 && id > 0) MasterJobKey(epoch, id) else null
    }
    fun parse(line: String): MasterMessage? {
        val parts = line.trim().split(' ')
        val fields = mutableMapOf<String, String>()
        for (part in parts.drop(1)) {
            val split = part.split('=', limit = 2)
            if (split.size != 2 || fields.put(split[0], split[1]) != null) return null
        }
        fun n(name: String) = uint(fields[name])
        fun h(name: String) = mask(fields[name])
        fun flag(name: String) = n(name)?.takeIf { it <= 1 }
        fun reason() = fields["reason"]?.takeIf { it.matches(Regex("[A-Z_]{1,32}")) } ?: if ("reason" in fields) null else "UNKNOWN"
        return when (parts.firstOrNull()) {
            "FC" -> {
                val sessionClient = client(fields["c"] ?: return null) ?: return null
                val jobText = fields["j"] ?: return null
                val job = if (jobText == "0:0") null else key(jobText) ?: return null
                val phases = listOf("IDLE", "PREOUT", "MARK_PENDING", "MEASURING", "STOPPING", "COMPLETE", "INVALID", "UNKNOWN", "STARTING")
                val repliesByCode = listOf("OK", "BUSY", "INVALID", "STALE", "CONFLICT", "NOT_READY", "STORAGE", "UNAUTHORIZED", "OFFLINE", "CAPACITY", "PENDING", "UNKNOWN")
                val reasonsByCode = listOf("NONE", "TARGET", "MANUAL", "LEASE", "RUNTIME", "NO_FLOW", "PREPARE_TIMEOUT", "TAIL_FLOW", "LOCAL_INTERLOCK", "REBOOT", "PUMP_FAULT", "TRANSPORT", "PROTOCOL")
                val phaseText = fields["p"] ?: return null
                val phase = phaseText.toIntOrNull()?.let { phases.getOrNull(it) }
                    ?: phaseText.takeIf { it in phases } ?: return null
                val standardMl = n("ml")?.takeIf { it <= 2000 } ?: return null
                val speed = n("v")?.takeIf { it <= 3 }?.toInt() ?: return null
                if (phase != "IDLE" && (standardMl !in 50..2000 || speed !in 1..3)) return null
                val replyText = fields["r"] ?: return null
                val reply = replyText.toIntOrNull()?.let { repliesByCode.getOrNull(it) }
                    ?: replyText.takeIf { it in replies } ?: return null
                val reasonText = fields["w"] ?: return null
                val reason = reasonText.toIntOrNull()?.let { reasonsByCode.getOrNull(it) }
                    ?: reasonText.takeIf { it.matches(Regex("[A-Z_]{1,32}")) } ?: return null
                MasterMessage.FlowCalibrationStatus(
                    n("e")?.takeIf { it > 0 } ?: return null, sessionClient,
                    n("q")?.takeIf { it > 0 } ?: return null, job,
                    n("s")?.takeIf { it <= 32 }?.toInt() ?: return null,
                    h("a")?.takeIf { it <= 0x7fff } ?: return null, phase,
                    n("o") ?: return null, reply,
                    standardMl, speed,
                    n("b") ?: return null, n("x") ?: return null, n("f") ?: return null,
                    n("n") ?: return null, n("t") ?: return null,
                    reason)
            }
            "CAL" -> MasterMessage.Calibration(n("epoch")?.takeIf { it > 0 } ?: return null,
                n("req")?.takeIf { it > 0 } ?: return null, n("slot")?.takeIf { it in 1..32 }?.toInt() ?: return null,
                h("addr")?.takeIf { it in 1..0x7fff } ?: return null, n("rev") ?: return null,
                n("low")?.takeIf { it in 1..1_000_000 } ?: return null,
                n("mid")?.takeIf { it in 1..1_000_000 } ?: return null,
                n("high")?.takeIf { it in 1..1_000_000 } ?: return null,
                fields["reply"]?.takeIf { it in replies } ?: return null,
                n("bk")?.takeIf { it in 1..1_000_000 } ?: return null,
                n("bv")?.takeIf { it > 0 } ?: return null)
            "INV_BEGIN", "INV_END" -> {
                val epoch = n("epoch")?.takeIf { it > 0 } ?: return null
                val req = n("req")?.takeIf { it > 0 } ?: return null
                val count = n("count")?.takeIf { it <= 32 }?.toInt() ?: return null
                if (parts[0] == "INV_BEGIN") MasterMessage.InventoryBegin(epoch, req, count)
                else MasterMessage.InventoryEnd(epoch, req, count)
            }
            "INV_NODE" -> MasterMessage.InventoryNode(n("epoch")?.takeIf { it > 0 } ?: return null,
                n("req")?.takeIf { it > 0 } ?: return null,
                MasterSlave(n("slot")?.takeIf { it <= 32 }?.toInt() ?: return null,
                    h("addr")?.takeIf { it in 1..0x7fff } ?: return null,
                    (flag("online") ?: return null) == 1L, (flag("commissioned") ?: return null) == 1L))
            "MESH" -> MasterMessage.Mesh(n("epoch")?.takeIf { it > 0 } ?: return null,
                n("req")?.takeIf { it > 0 } ?: return null,
                MasterMeshState(fields["phase"]?.takeIf { it.matches(Regex("[A-Z_]{1,24}")) } ?: return null,
                    (flag("busy") ?: return null) == 1L, (flag("capable") ?: return null) == 1L,
                    fields["reason"]?.takeIf { it.matches(Regex("[A-Z_]{1,32}")) } ?: return null,
                    n("op") ?: return null, n("scan") ?: return null,
                    n("found")?.takeIf { it <= 8 }?.toInt() ?: return null,
                    h("target")?.takeIf { it <= 0x7fff } ?: return null,
                    fields["remove"]?.let { (flag("remove") ?: return null) == 1L } ?: false))
            "REMOVE" -> MasterMessage.Removal(n("epoch")?.takeIf { it > 0 } ?: return null,
                n("req")?.takeIf { it > 0 } ?: return null,
                h("addr")?.takeIf { it in 1..0x7fff } ?: return null,
                fields["reply"]?.takeIf { it in replies } ?: return null)
            "CAND" -> MasterMessage.Candidate(n("epoch")?.takeIf { it > 0 } ?: return null,
                n("scan")?.takeIf { it > 0 } ?: return null,
                fields["uuid"]?.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }?.lowercase() ?: return null)
            "COMMISSION" -> MasterMessage.Commission(n("epoch")?.takeIf { it > 0 } ?: return null,
                n("req")?.takeIf { it > 0 } ?: return null,
                n("slot")?.takeIf { it in 1..32 }?.toInt() ?: return null,
                h("addr")?.takeIf { it in 1..0x7fff } ?: return null,
                fields["reply"]?.takeIf { it in replies } ?: return null,
                (flag("commissioned") ?: return null) == 1L)
            "HELLO" -> MasterMessage.Hello(fields["role"] ?: return null, h("addr") ?: return null,
                h("master") ?: return null, n("epoch") ?: return null,
                (flag("mesh") ?: return null) == 1L,
                (flag("commissioned") ?: return null) == 1L,
                fields["pump_fault"] ?: return null,
                fields["individual"]?.let { (flag("individual") ?: return null) == 1L } ?: false,
                fields["calibration"]?.let { (flag("calibration") ?: return null) == 1L } ?: false,
                fields["flow_calibration"]?.let { (flag("flow_calibration") ?: return null) == 1L } ?: false)
            "LOCAL" -> {
                val supported = fields["test_supported"]?.let { (flag("test_supported") ?: return null) == 1L } ?: false
                val active = if (supported) (flag("test_active") ?: return null) == 1L else false
                val remaining = if (supported) n("test_ms")?.takeIf { it <= 10000 } ?: return null else 0L
                val testClient = fields["test_client"]?.let(::client)
                val testSeq = if (supported) n("test_seq") ?: return null else 0L
                val resetSupported = fields["pump_reset"]?.let { (flag("pump_reset") ?: return null) == 1L } ?: false
                val output = (flag("pump_command") ?: return null) == 1L
                val phase = n("pump_phase")?.takeIf { it in setOf(0L, 2L, 4L) }?.toInt() ?: return null
                if (active && (!output || phase != 2 || testClient == null || testSeq == 0L)) return null
                MasterMessage.Local(output, phase, fields["fault"]?.takeIf { it.isNotBlank() } ?: return null,
                    supported, active, remaining, testClient, testSeq, if (supported) (flag("pump_ready") ?: return null) == 1L else false,
                    resetSupported)
            }
            "PUMP_TEST" -> MasterMessage.PumpTest(client(fields["client"] ?: return null) ?: return null,
                n("seq")?.takeIf { it > 0 } ?: return null, n("epoch")?.takeIf { it > 0 } ?: return null,
                fields["reply"]?.takeIf { it in replies } ?: return null,
                fields["state"]?.takeIf { it in setOf("UNKNOWN", "RUNNING", "STOPPED") } ?: return null,
                fields["op"]?.takeIf { it in setOf("START", "STOP", "QUERY") } ?: return null)
            "PUMP_RESET" -> MasterMessage.PumpReset(n("epoch")?.takeIf { it > 0 } ?: return null,
                n("req")?.takeIf { it > 0 } ?: return null,
                fields["reply"]?.takeIf { it in replies } ?: return null,
                fields["fault"]?.takeIf { it.matches(Regex("[A-Z_]{1,24}")) } ?: return null,
                (flag("output") ?: return null) == 1L)
            "PUMP_PWM" -> {
                val extended = listOf("test_manual", "dry_max_ms", "dry_active").any(fields::containsKey)
                val manual = if (extended) (flag("test_manual") ?: return null) == 1L else false
                val dryMax = if (extended) n("dry_max_ms")?.takeIf { it <= 3000 } ?: return null else 0L
                val dryActive = if (extended) (flag("dry_active") ?: return null) == 1L else false
                val output = (flag("output") ?: return null) == 1L
                if ((!manual && dryMax != 0L) || dryActive && (!manual || dryMax == 0L || !output || n("level") != 1L)) return null
                MasterMessage.PumpPwm(
                n("epoch")?.takeIf { it > 0 } ?: return null, n("rev") ?: return null,
                n("level")?.takeIf { it in 1..3 }?.toInt() ?: return null,
                n("duty")?.takeIf { it in 1..100 }?.toInt() ?: return null,
                n("hz")?.takeIf { it in 1..1_000_000 } ?: return null,
                output, fields["reply"]?.takeIf { it in replies } ?: return null, manual, dryMax, dryActive)
            }
            "R" -> {
                val reply = fields["reply"]?.takeIf { it in setOf("OK", "BUSY", "INVALID", "STALE", "CONFLICT", "NOT_READY",
                    "STORAGE", "UNAUTHORIZED", "OFFLINE", "CAPACITY", "PENDING", "UNKNOWN") } ?: return null
                val job = key(fields["job"])
                if (job == null && fields["job"] != "0:0") return null
                val selected = h("mask") ?: return null
                val done = h("done") ?: return null
                val failed = h("failed") ?: return null
                val unknown = h("unknown") ?: return null
                if ((done or failed or unknown) and selected.inv() != 0L) return null
                MasterMessage.Report(client(fields["client"] ?: return null) ?: return null,
                    n("seq")?.takeIf { it > 0 } ?: return null, reply, job,
                    n("phase")?.takeIf { it <= 7 }?.toInt() ?: return null, selected, done, failed, unknown, reason() ?: return null)
            }
            "V" -> MasterMessage.Volume(key(fields["job"]) ?: return null, n("total_ml") ?: return null, h("measured") ?: return null)
            "LIVE" -> MasterMessage.Live(key(fields["job"]) ?: return null,
                h("addr")?.takeIf { it in 1..0x7fff } ?: return null,
                n("ml") ?: return null, n("pulses") ?: return null,
                n("runtime_ms")?.takeIf { it in 1..240_000 } ?: return null,
                n("rate_ml_min") ?: return null)
            "FLOW" -> {
                val phase = fields["phase"]?.takeIf { it.matches(Regex("[A-Z_]{1,24}")) } ?: return null
                val terminal = fields["terminal"]?.let { (flag("terminal") ?: return null) == 1L }
                    ?: (phase == "DONE" || phase == "FAULT")
                val measured = fields["measured"]?.let { (flag("measured") ?: return null) == 1L } ?: true
                MasterMessage.Flow(key(fields["job"]) ?: return null,
                    h("addr")?.takeIf { it in 1..0x7fff } ?: return null,
                    n("ml") ?: return null, phase, n("pulses") ?: 0L, n("revision") ?: 0L,
                    terminal, measured, reason = reason() ?: return null)
            }
            else -> null
        }
    }
    fun isAllowedCommand(command: String): Boolean {
        if (command.toByteArray(Charsets.UTF_8).size > 159 || '\n' in command || '\r' in command) return false
        // The phone exposes only the explicit slave-reset action. New Master
        // firmware clears an old Node role during startup automatically.
        if (command == "mesh-reset CONFIRM") return true
        if (command.startsWith("pump-reset ")) {
            val p = command.split(' ')
            return p.size == 4 && p[0] == "pump-reset" && (uint(p[1]) ?: 0) > 0 &&
                (uint(p[2]) ?: 0) > 0 && p[3] == "CONFIRM"
        }
        val p = command.split(' ')
        if (p[0] == "mesh-remove") return p.size == 5 && (uint(p[1]) ?: 0) > 0 &&
            (uint(p[2]) ?: 0) > 0 && (mask(p[3]) ?: 0) in 1..0x7fff && p[4] == "CONFIRM"
        if (p[0] in setOf("cal-get", "cal-set")) {
            if (p.size != (if (p[0] == "cal-set") 11 else 5) || (uint(p[1]) ?: 0) == 0L ||
                (uint(p[2]) ?: 0) == 0L || (uint(p[3], 32) ?: 0) !in 1..32 ||
                (mask(p[4]) ?: 0) !in 1..0x7fff) return false
            return p[0] == "cal-get" || uint(p[5]) != null && (uint(p[6], 1_000_000) ?: 0) > 0 &&
                (uint(p[7]) ?: 0) > 0 && p.drop(8).all { (uint(it, 1_000_000) ?: 0) > 0 }
        }
        if (p.size == 1) return command == "hello" || command == "local"
        if (p[0] == "commission") return p.size == 5 &&
            (uint(p[1]) ?: 0) > 0 && (uint(p[2]) ?: 0) > 0 && (uint(p[3], 32) ?: 0) in 1..32 && p[4] == "CONFIRM"
        if (p[0] in setOf("inventory", "mesh-state", "mesh-scan", "mesh-join", "mesh-retry")) {
            if ((uint(p.getOrNull(1)) ?: 0) == 0L || (uint(p.getOrNull(2)) ?: 0) == 0L) return false
            return when (p[0]) {
                "mesh-join" -> p.size == 4 && p[3].matches(Regex("[0-9a-fA-F]{32}"))
                "mesh-retry" -> p.size == 4 && (mask(p[3]) ?: 0) in 1..0x7fff
                else -> p.size == 3
            }
        }
        if (p[0] == "pump-speed") return p.size == 4 && (uint(p[1]) ?: 0) > 0 &&
            uint(p[2]) != null && (uint(p[3], 3) ?: 0) > 0
        if (p[0] == "flow-cal-start") return p.size == 8 && (uint(p[1]) ?: 0) > 0 &&
            client(p[2]) != null && (uint(p[3]) ?: 0) > 0 && (uint(p[4], 32) ?: 0) in 1..32 &&
            (mask(p[5]) ?: 0) in 1..0x7fff && (uint(p[6], 2000) ?: 0) in 50..2000 &&
            (uint(p[7], 3) ?: 0) in 1..3
        if (p[0] in setOf("flow-cal-mark", "flow-cal-stop")) return p.size == 7 &&
            (uint(p[1]) ?: 0) > 0 && client(p[2]) != null && (uint(p[3]) ?: 0) > 0 &&
            (uint(p[4]) ?: 0) > 0 &&
            ((uint(p[5]) ?: return false) > 0 && (uint(p[6]) ?: return false) > 0 ||
                p[0] == "flow-cal-stop" && (uint(p[5]) ?: return false) == 0L && (uint(p[6]) ?: return false) == 0L)
        if (p[0] == "flow-cal-query") return (p.size == 4 || p.size == 6) &&
            (uint(p[1]) ?: 0) > 0 && client(p[2]) != null && (uint(p[3]) ?: 0) > 0 &&
            (p.size == 4 || (((uint(p[4]) ?: return false) == 0L) == ((uint(p[5]) ?: return false) == 0L)))
        if (client(p.getOrNull(1) ?: return false) == null || (uint(p.getOrNull(2)) ?: 0) == 0L) return false
        return when (p[0]) {
            "query" -> p.size == 3
            "start" -> p.size == 7 && (uint(p[3]) ?: 0) > 0 && (mask(p[4]) ?: 0) > 0 &&
                // Retain legacy accepted requests/history for exact retries after an upgrade.
                (uint(p[5], 1_000_000) ?: 0).let { it >= 50 && it % 50 == 0L } &&
                (uint(p[6], 240_000) ?: 0) > 0
            "start-each" -> p.size == 7 && (uint(p[3]) ?: 0) > 0 &&
                ValveAmounts.isValid(mask(p[4]) ?: 0, p[6]) && (uint(p[5], 240_000) ?: 0) > 0
            "stop" -> p.size == 6 && p.drop(3).all { (uint(it) ?: 0) > 0 }
            "pump-test" -> p.size == 5 && (uint(p[3]) ?: 0) > 0 && (uint(p[4], 10000) ?: 0) > 0
            "pump-dry-test" -> p.size == 5 && (uint(p[3]) ?: 0) > 0 && (uint(p[4], 3000) ?: 0) > 0
            "pump-test-stop", "pump-test-query" -> p.size == 4 && (uint(p[3]) ?: 0) > 0
            else -> false
        }
    }
    fun slots(mask: Long) = (1..32).filter { mask and (1L shl (it - 1)) != 0L }.joinToString(", ") { it.toString() }.ifEmpty { "无" }
    fun phase(value: Int) = listOf("空闲", "准备", "开阀", "供水", "停止与结算", "切换节点", "已结束", "异常结束").getOrElse(value) { "未知" }
}

/** Merges delayed BLE snapshots without discarding newer task or node evidence. */
object MasterResultMerge {
    fun report(old: MasterMessage.Report?, incoming: MasterMessage.Report): MasterMessage.Report {
        if (old == null || old.job == null || old.job != incoming.job || old.client != incoming.client ||
            old.mask != incoming.mask) return incoming
        val done = old.done or incoming.done
        val failed = old.failed or incoming.failed
        // Failed marks include unexecuted/unconfirmed nodes. Only explicit
        // terminal evidence from the device can resolve an unknown result.
        val unknown = (old.unknown and (incoming.done and incoming.unknown.inv()).inv()) or incoming.unknown
        return incoming.copy(
            phase = maxOf(old.phase, incoming.phase),
            done = done,
            failed = failed,
            unknown = unknown,
            reason = if (old.reason !in setOf("NONE", "UNKNOWN")) old.reason else incoming.reason
        )
    }

    fun hasVolumeData(volume: MasterMessage.Volume?, job: MasterJobKey?, mask: Long): Boolean =
        volume != null && job != null && volume.job == job && volume.measured != 0L &&
            volume.measured and mask.inv() == 0L

    fun volume(old: MasterMessage.Volume?, incoming: MasterMessage.Volume): MasterMessage.Volume? {
        if (old == null || old.job != incoming.job) return incoming.takeIf { it.measured != 0L }
        if (incoming.measured == 0L) return old
        return incoming.copy(ml = maxOf(old.ml, incoming.ml), measured = old.measured or incoming.measured)
    }

    fun flow(old: MasterMessage.Flow?, incoming: MasterMessage.Flow): MasterMessage.Flow {
        if (old == null || old.job != incoming.job || old.address != incoming.address) return incoming
        if (old.terminal && !incoming.terminal) return old
        if (incoming.revision < old.revision) return old
        if (incoming.revision > old.revision) {
            // A reboot terminal record explicitly invalidates the previously
            // observed total; other same-job totals only grow as pulses arrive.
            return if (incoming.terminal && !incoming.measured) incoming
            else incoming.copy(pulses = maxOf(old.pulses, incoming.pulses), ml = maxOf(old.ml, incoming.ml))
        }
        val rebootTerminal = incoming.terminal && !incoming.measured
        return incoming.copy(
            pulses = if (rebootTerminal) incoming.pulses else maxOf(old.pulses, incoming.pulses),
            ml = if (rebootTerminal) incoming.ml else maxOf(old.ml, incoming.ml),
            phase = if (incoming.terminal || !old.terminal) incoming.phase else old.phase,
            terminal = old.terminal || incoming.terminal,
            measured = if (rebootTerminal) false else old.measured || incoming.measured,
            reason = if (incoming.reason == "UNKNOWN") old.reason else incoming.reason
        )
    }
}

fun stopReasonLabel(reason: String): String = when (reason) {
    "NONE" -> "尚无停止原因"
    "TARGET" -> "达到目标脉冲量"
    "MANUAL" -> "手动停止"
    "LEASE" -> "租约失效，保护停止"
    "TRANSPORT" -> "Mesh 通信异常，保护停止"
    "PROTOCOL" -> "从机协议或能力不兼容"
    "NO_FLOW" -> "未检测到有效流量"
    "RUNTIME" -> "超过运行时限"
    "PREPARE_TIMEOUT" -> "准备超时"
    "TAIL_FLOW" -> "关闭后尾流未稳定"
    "LOCAL_INTERLOCK" -> "本地互锁触发"
    "PUMP_FAULT" -> "水泵保护触发"
    "REBOOT" -> "设备重启，计量待核实"
    "UNKNOWN" -> "停止原因未提供"
    else -> "未知停止原因（$reason）"
}
