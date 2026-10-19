package com.cczb.app

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Properties

/** Identity and high-water mark must be durably saved before sending any start. */
data class MasterLedger(val client: String, val sequence: Long = 0, val tasks: Map<String, MasterTask> = emptyMap(),
                        val pumpTests: Map<String, MasterPumpTest> = emptyMap()) {
    fun nextSequence(): Long {
        check(sequence < 0xffff_ffffL) { "控制序号已用尽，请联系现场维护处理客户端身份" }
        return sequence + 1
    }
    fun write(output: OutputStream) {
        val p = Properties()
        p.setProperty("version", "1")
        p.setProperty("client", client)
        p.setProperty("sequence", sequence.toString())
        tasks.forEach { (address, task) ->
            p.setProperty("task.$address", listOf(task.seq, task.epoch, task.mask, task.ml, task.maxMs,
                task.outcome.name, task.job?.epoch ?: 0, task.job?.id ?: 0).joinToString(","))
            if (task.individual) p.setProperty("amounts.$address", task.amounts)
        }
        pumpTests.forEach { (address, test) ->
            p.setProperty("test.$address", listOf(test.seq, test.epoch, test.durationMs, test.outcome.name, if (test.dryRun) 1 else 0).joinToString(","))
        }
        p.store(output, "Master control identity - never copy between phones")
    }
    companion object {
        fun fresh(): MasterLedger {
            val random = SecureRandom()
            var identity: String?
            do { identity = MasterProtocol.client(ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }) }
            while (identity == null)
            return MasterLedger(identity)
        }
        fun read(input: InputStream): MasterLedger {
            val p = Properties().apply { load(input) }
            require(p.getProperty("version") == "1")
            val client = requireNotNull(MasterProtocol.client(p.getProperty("client")))
            val sequence = p.getProperty("sequence").toLong().also { require(it in 0..0xffff_ffffL) }
            val tasks = p.stringPropertyNames().filter { it.startsWith("task.") }.associate { name ->
                val address = name.removePrefix("task.").also { require(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(it)) }
                val f = p.getProperty(name).split(',').also { require(it.size == 8) }
                val task = MasterTask(client, f[0].toLong(), f[1].toLong(), f[2].toLong(), f[3].toLong(), f[4].toLong(),
                    MasterOutcome.valueOf(f[5]), if (f[6] == "0" && f[7] == "0") null else MasterJobKey(f[6].toLong(), f[7].toLong()),
                    p.getProperty("amounts.$address", ""))
                require(!task.individual || task.ml == 0L)
                require(task.seq in 1..sequence && task.epoch in 1..0xffff_ffffL && task.mask in 1..0xffff_ffffL)
                require(MasterProtocol.isAllowedCommand(task.command()))
                task.job?.let { require(it.epoch == task.epoch && it.id in 1..0xffff_ffffL) }
                require(task.outcome != MasterOutcome.ACCEPTED && task.outcome != MasterOutcome.FINISHED || task.job != null)
                address to task
            }
            val tests = p.stringPropertyNames().filter { it.startsWith("test.") }.associate { name ->
                val address = name.removePrefix("test.").also { require(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(it)) }
                val f = p.getProperty(name).split(',').also { require(it.size in 4..5) }
                require(f.size == 4 || f[4] in listOf("0", "1"))
                val test = MasterPumpTest(client, f[0].toLong(), f[1].toLong(), f[2].toLong(), MasterOutcome.valueOf(f[3]), f.getOrNull(4) == "1")
                require(test.seq in 1..sequence && test.epoch in 1..0xffff_ffffL && MasterProtocol.isAllowedCommand(test.command()))
                address to test
            }
            return MasterLedger(client, sequence, tasks, tests)
        }
    }
}
