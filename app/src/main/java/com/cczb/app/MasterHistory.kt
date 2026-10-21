package com.cczb.app

import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/** Phone-local observations. Missing device results remain missing after a restart. */
data class MasterHistoryEntry(
    val address: String,
    val deviceName: String,
    val task: MasterTask,
    val updatedAt: Long,
    val report: MasterMessage.Report? = null,
    val volume: MasterMessage.Volume? = null,
    val flows: Map<Long, MasterMessage.Flow> = emptyMap()
) {
    val key get() = "$address/${task.client}/${task.seq}"
}

object MasterHistory {
    const val LIMIT = 100

    fun upsert(entries: List<MasterHistoryEntry>, entry: MasterHistoryEntry): List<MasterHistoryEntry> =
        (entries.filterNot { it.key == entry.key } + entry).sortedByDescending { it.updatedAt }.take(LIMIT)

    fun write(entries: List<MasterHistoryEntry>, output: OutputStream) {
        val p = Properties()
        p.setProperty("version", "1")
        val saved = entries.take(LIMIT)
        p.setProperty("count", saved.size.toString())
        saved.forEachIndexed { index, item ->
            val prefix = "record.$index."
            p.setProperty(prefix + "address", item.address)
            p.setProperty(prefix + "name", item.deviceName)
            p.setProperty(prefix + "time", item.updatedAt.toString())
            p.setProperty(prefix + "client", item.task.client)
            p.setProperty(prefix + "task", item.task.command())
            p.setProperty(prefix + "outcome", item.task.outcome.name)
            p.setProperty(prefix + "job", item.task.job?.toString() ?: "")
            item.report?.let { r ->
                p.setProperty(prefix + "report", "R client=${r.client} seq=${r.seq} reply=${r.reply} job=${r.job ?: "0:0"} phase=${r.phase} mask=${r.mask.toString(16)} done=${r.done.toString(16)} failed=${r.failed.toString(16)} unknown=${r.unknown.toString(16)} reason=${r.reason}")
            }
            item.volume?.let { v ->
                p.setProperty(prefix + "volume", "V job=${v.job} total_ml=${v.ml} measured=${v.measured.toString(16)}")
            }
            require(item.flows.size <= 32)
            p.setProperty(prefix + "flowCount", item.flows.size.toString())
            item.flows.values.sortedBy { it.address }.forEachIndexed { i, f ->
                p.setProperty(prefix + "flow.$i", "FLOW addr=${f.address.toString(16)} job=${f.job} pulses=${f.pulses} ml=${f.ml} phase=${f.phase} revision=${f.revision} terminal=${if (f.terminal) 1 else 0} measured=${if (f.measured) 1 else 0} reason=${f.reason}")
            }
        }
        p.store(output, "Phone-local task observations")
    }

    fun read(input: InputStream): List<MasterHistoryEntry> {
        val p = Properties().apply { load(input) }
        require(p.getProperty("version") == "1")
        val count = p.getProperty("count").toInt().also { require(it in 0..LIMIT) }
        return (0 until count).map { index ->
            val prefix = "record.$index."
            val address = requireNotNull(p.getProperty(prefix + "address"))
            require(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(address))
            val command = p.getProperty(prefix + "task")
            require((command.startsWith("start ") || command.startsWith("start-each ")) && MasterProtocol.isAllowedCommand(command))
            val fields = command.split(' ')
            val individual = fields[0] == "start-each"
            require(fields[1] == p.getProperty(prefix + "client"))
            val job = p.getProperty(prefix + "job").takeIf { it.isNotEmpty() }?.split(':')?.let {
                require(it.size == 2)
                MasterJobKey(it[0].toLong(), it[1].toLong()).also { key ->
                    require(key.epoch == fields[3].toLong() && key.id in 1..0xffff_ffffL)
                }
            }
            val task = MasterTask(fields[1], fields[2].toLong(), fields[3].toLong(), fields[4].toLong(16),
                if (individual) 0 else fields[5].toLong(), fields[if (individual) 5 else 6].toLong(),
                MasterOutcome.valueOf(p.getProperty(prefix + "outcome")), job, if (individual) fields[6] else "")
            require(task.outcome !in setOf(MasterOutcome.ACCEPTED, MasterOutcome.FINISHED) || job != null)
            val report = p.getProperty(prefix + "report")?.let {
                (MasterProtocol.parse(it) as? MasterMessage.Report).also { r ->
                    require(r != null && r.client == task.client && r.seq == task.seq && r.job == task.job && r.mask == task.mask)
                }
            }
            val volume = p.getProperty(prefix + "volume")?.let {
                (MasterProtocol.parse(it) as? MasterMessage.Volume).also { v ->
                    require(v != null && v.job == task.job && v.measured and task.mask.inv() == 0L)
                }?.takeIf { it.measured != 0L }
            }
            val flowCount = (p.getProperty(prefix + "flowCount") ?: "0").toInt().also { require(it in 0..32) }
            val flows = (0 until flowCount).map {
                (MasterProtocol.parse(requireNotNull(p.getProperty(prefix + "flow.$it"))) as? MasterMessage.Flow)
                    .also { flow -> require(flow != null && flow.job == task.job) }!!
            }.associateBy { it.address }
            require(flows.size == flowCount)
            MasterHistoryEntry(address, p.getProperty(prefix + "name") ?: "主机", task,
                p.getProperty(prefix + "time").toLong().also { require(it > 0) }, report, volume, flows)
        }.sortedByDescending { it.updatedAt }
    }
}
