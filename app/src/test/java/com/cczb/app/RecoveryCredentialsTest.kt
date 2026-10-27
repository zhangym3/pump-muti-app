package com.cczb.app

import org.junit.Assert.*
import org.junit.Test

class RecoveryCredentialsTest {
    private fun credential(n: Int = 1, epoch: Long = 1, name: String = "阀门$n") =
        RecoveryCredential(ByteArray(16) { (it + n).toByte() }, epoch, ByteArray(32) { (it + n).toByte() }, name)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch (_: Exception) { }
    }
    @Test fun boundedCredentialSchemaRoundTripsAndDoesNotAliasArrays() {
        val records = (1..32).map { credential(it, 0xffffffffL, "阀".repeat(42)) }
        val wire = RecoveryCredentialCodec.encode(records)
        val read = RecoveryCredentialCodec.decode(wire)
        assertEquals(32, read.size)
        records.forEach { item -> assertTrue(read.any { it.sameCredential(item) && it.name == item.name }) }
        val uuid = ByteArray(16) { 1 }; val key = ByteArray(32) { 2 }
        val item = RecoveryCredential(uuid, 1, key, "Name")
        uuid.fill(0); key.fill(0); item.deviceUuid().fill(0); item.key().fill(0)
        assertTrue(item.deviceUuid().all { it == 1.toByte() } && item.key().all { it == 2.toByte() })
        assertFalse(item.toString().contains("020202"))
        rejected { RecoveryCredentialCodec.encode(records + credential(33)) }
        rejected { RecoveryCredentialCodec.encode(listOf(item, item)) }
        rejected { RecoveryCredential(ByteArray(16), 1, ByteArray(32) { 1 }, "Name") }
        rejected { credential(epoch = 0) }; rejected { credential(epoch = 0x100000000) }
        rejected { credential(name = "x\n") }; rejected { credential(name = "阀".repeat(43)) }
    }
    @Test fun malformedAndNonCanonicalCredentialRecordsRejected() {
        val wire = RecoveryCredentialCodec.encode(listOf(credential()))
        wire.indices.forEach { end -> rejected { RecoveryCredentialCodec.decode(wire.copyOf(end)) } }
        rejected { RecoveryCredentialCodec.decode(wire + 0) }
        listOf(0, 4, 5, 6, 7).forEach { index ->
            val bad = wire.copyOf(); bad[index] = 0x7f; rejected { RecoveryCredentialCodec.decode(bad) }
        }
        val badName = wire.copyOf(); badName[62] = 0xff.toByte()
        rejected { RecoveryCredentialCodec.decode(badName) }
    }
    @Test fun backupAuthenticatesEveryByteCodeAndLengthAndRandomizesExports() {
        val code = RecoveryBackup.newCode()
        val item = credential()
        val wire = RecoveryBackup.encrypt(listOf(item), code)
        assertTrue(RecoveryBackup.decrypt(wire, code).single().sameCredential(item))
        assertFalse(wire.contentEquals(RecoveryBackup.encrypt(listOf(item), code)))
        wire.indices.forEach { index ->
            val bad = wire.copyOf(); bad[index] = (bad[index].toInt() xor 1).toByte()
            rejected { RecoveryBackup.decrypt(bad, code) }
        }
        rejected { RecoveryBackup.decrypt(wire, code.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }) }
        rejected { RecoveryBackup.decrypt(wire + 0, code) }
        rejected { RecoveryBackup.decrypt(wire.copyOf(wire.size - 1), code) }
        rejected { RecoveryBackup.encrypt(listOf(item), ByteArray(32)) }
        rejected { RecoveryBackup.decrypt(wire, ByteArray(31)) }
        assertTrue(RecoveryBackup.decrypt(RecoveryBackup.encrypt(emptyList(), code), code).isEmpty())
    }
    @Test fun importingCannotSilentlyRollbackOrReplaceDevicePermissions() {
        val item = credential(epoch = 5)
        assertEquals(1, RecoveryCredentialCodec.merge(listOf(item), listOf(item)).size)
        assertEquals(2, RecoveryCredentialCodec.merge(listOf(item), listOf(credential(2))).size)
        rejected { RecoveryCredentialCodec.merge(listOf(item), listOf(credential(epoch = 4))) }
        rejected { RecoveryCredentialCodec.merge(listOf(item), listOf(credential(epoch = 6))) }
        val changedKey = item.key().also { it[0] = 99 }
        rejected { RecoveryCredentialCodec.merge(listOf(item), listOf(RecoveryCredential(item.deviceUuid(), 5, changedKey, "x"))) }
    }
}
