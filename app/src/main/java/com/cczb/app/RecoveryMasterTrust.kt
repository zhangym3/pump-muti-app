package com.cczb.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** A public installation file. Possession proof is checked, but provenance still requires trusted USB delivery. */
internal class RecoveryMasterTrustFile private constructor(private val bytes: ByteArray, val master: TrustedRecoveryMaster) {
    val deviceId: String get() = master.deviceUuid().toHex()
    val fingerprint: String get() = master.id.toHex()
    fun encode() = bytes.copyOf()
    fun sameIdentity(other: RecoveryMasterTrustFile) = master.epoch == other.master.epoch &&
        MessageDigest.isEqual(master.deviceUuid(), other.master.deviceUuid()) &&
        MessageDigest.isEqual(master.networkId(), other.master.networkId()) &&
        MessageDigest.isEqual(master.publicKey(), other.master.publicKey())

    companion object {
        const val SIZE = 225
        private val magic = "PMMSTR01".toByteArray(Charsets.US_ASCII)
        fun decode(source: ByteArray): RecoveryMasterTrustFile {
            require(source.size == SIZE)
            val bytes = source.copyOf()
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(MessageDigest.isEqual(magic, ByteArray(8).also { input.get(it) }))
            require(input.short.toInt() == 1 && input.short.toInt() == SIZE)
            val device = ByteArray(16).also { input.get(it) }
            val epoch = input.int.toLong() and 0xffffffffL
            val network = ByteArray(16).also { input.get(it) }
            val fingerprint = ByteArray(16).also { input.get(it) }
            val publicKey = ByteArray(65).also { input.get(it) }
            val master = TrustedRecoveryMaster(device, epoch, network, publicKey)
            require(MessageDigest.isEqual(master.id, fingerprint))
            val nonce = ByteArray(32).also { input.get(it) }
            val signature = ByteArray(64).also { input.get(it) }
            require(RecoveryMasterIdentity.verifyProof(master, nonce, signature))
            return RecoveryMasterTrustFile(bytes, master)
        }
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** An import cannot silently replace the physical device key or network generation. */
internal object RecoveryMasterTrustCodec {
    const val MAX_MASTERS = 16
    const val MAX_BYTES = 8 + MAX_MASTERS * RecoveryMasterTrustFile.SIZE
    fun merge(existing: List<RecoveryMasterTrustFile>, imported: RecoveryMasterTrustFile): List<RecoveryMasterTrustFile> {
        validate(existing)
        existing.firstOrNull { it.deviceId == imported.deviceId || it.fingerprint == imported.fingerprint }?.let {
            require(it.sameIdentity(imported)) { "Master trust conflict; explicit trusted maintenance required" }
            return existing.sortedBy { record -> record.deviceId }
        }
        return (existing + imported).also { validate(it) }.sortedBy { it.deviceId }
    }
    private fun validate(records: List<RecoveryMasterTrustFile>) {
        require(records.size <= MAX_MASTERS)
        require(records.map { it.deviceId }.toSet().size == records.size)
        require(records.map { it.fingerprint }.toSet().size == records.size)
    }
    fun encode(records: List<RecoveryMasterTrustFile>): ByteArray {
        validate(records)
        return ByteBuffer.allocate(8 + records.size * RecoveryMasterTrustFile.SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x31544d52); putShort(1); putShort(records.size.toShort())
            records.sortedBy { it.deviceId }.forEach { put(it.encode()) }
        }.array()
    }
    fun decode(bytes: ByteArray): List<RecoveryMasterTrustFile> {
        require(bytes.size in 8..MAX_BYTES)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(input.int == 0x31544d52 && input.short.toInt() == 1)
        val count = input.short.toInt() and 0xffff
        require(count <= MAX_MASTERS && input.remaining() == count * RecoveryMasterTrustFile.SIZE)
        val records = List(count) { RecoveryMasterTrustFile.decode(ByteArray(RecoveryMasterTrustFile.SIZE).also { input.get(it) }) }
        require(MessageDigest.isEqual(bytes, encode(records)))
        return records
    }
}
