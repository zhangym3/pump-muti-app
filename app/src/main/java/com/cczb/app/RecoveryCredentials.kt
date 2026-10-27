package com.cczb.app

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A device secret. Array ownership is private; never use a data class for secrets. */
internal class RecoveryCredential(deviceUuid: ByteArray, val epoch: Long, key: ByteArray, val name: String) {
    private val uuid = deviceUuid.copyOf()
    private val secret = key.copyOf()
    init {
        require(uuid.size == 16 && uuid.any { it != 0.toByte() })
        require(secret.size == 32 && secret.any { it != 0.toByte() })
        require(epoch in 1..0xffffffffL)
        require(name.toByteArray(Charsets.UTF_8).size <= 128 && name.none { it.isISOControl() })
    }
    val id: String get() = uuid.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun deviceUuid() = uuid.copyOf()
    fun key() = secret.copyOf()
    fun sameCredential(other: RecoveryCredential): Boolean = epoch == other.epoch &&
        MessageDigest.isEqual(uuid, other.uuid) && MessageDigest.isEqual(secret, other.secret)
    override fun toString() = "RecoveryCredential(id=$id, epoch=$epoch, secret=redacted)"
}

/** Strict, bounded schema shared by local storage and encrypted exports. */
internal object RecoveryCredentialCodec {
    const val MAX_DEVICES = 32
    const val MAX_BYTES = 8 + MAX_DEVICES * (16 + 4 + 32 + 2 + 128)
    fun encode(records: List<RecoveryCredential>): ByteArray {
        require(records.size <= MAX_DEVICES && records.map { it.id }.toSet().size == records.size)
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x52, 0x43, 0x56, 0x31, 1, 0, records.size.toByte(), 0))
        records.sortedBy { it.id }.forEach { record ->
            val key = record.key()
            try {
                output.write(record.deviceUuid())
                output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(record.epoch.toInt()).array())
                output.write(key)
                val name = record.name.toByteArray(Charsets.UTF_8)
                output.write(byteArrayOf(name.size.toByte(), 0)); output.write(name)
            } finally { key.fill(0) }
        }
        return output.toByteArray()
    }
    fun decode(bytes: ByteArray): List<RecoveryCredential> {
        require(bytes.size in 8..MAX_BYTES)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(input.int == 0x31564352 && input.short.toInt() == 1)
        val count = input.short.toInt() and 0xffff
        require(count <= MAX_DEVICES)
        val records = ArrayList<RecoveryCredential>(count)
        val ids = HashSet<String>()
        repeat(count) {
            require(input.remaining() >= 54)
            val uuid = ByteArray(16).also { input.get(it) }
            val epoch = input.int.toLong() and 0xffffffffL
            val key = ByteArray(32).also { input.get(it) }
            try {
                val length = input.short.toInt() and 0xffff
                require(length <= 128 && length <= input.remaining())
                val nameBytes = ByteArray(length).also { input.get(it) }
                val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes)).toString()
                val credential = RecoveryCredential(uuid, epoch, key, name)
                require(ids.add(credential.id)) { "Duplicate recovery device" }
                records += credential
            } finally { key.fill(0) }
        }
        require(!input.hasRemaining()) { "Trailing recovery data" }
        return records
    }
    /** A backup is not proof of a device's current credential; conflicts require reauthentication. */
    fun merge(existing: List<RecoveryCredential>, imported: List<RecoveryCredential>): List<RecoveryCredential> {
        val records = existing.associateBy { it.id }.toMutableMap()
        imported.forEach { record ->
            records[record.id]?.let { require(it.sameCredential(record)) { "Recovery credential conflict" } }
            records[record.id] = record
        }
        require(records.size <= MAX_DEVICES)
        return records.values.sortedBy { it.id }
    }
}

/** Exports require a separate random 256-bit recovery code, never a user password. */
internal object RecoveryBackup {
    private const val HEADER = 42
    private val magic = "PMRECBA1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()
    fun newCode() = ByteArray(32).also { random.nextBytes(it) }
    fun encrypt(records: List<RecoveryCredential>, code: ByteArray): ByteArray {
        val plain = RecoveryCredentialCodec.encode(records)
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            .put(magic).putShort(1).put(salt).put(iv).putInt(plain.size + 16).array()
        return try {
            val key = derive(code, salt)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                cipher.updateAAD(header)
                header + cipher.doFinal(plain)
            } finally { key.fill(0) }
        } finally { plain.fill(0) }
    }
    fun decrypt(bytes: ByteArray, code: ByteArray): List<RecoveryCredential> {
        require(bytes.size in (HEADER + 24)..(HEADER + RecoveryCredentialCodec.MAX_BYTES + 16))
        val header = ByteBuffer.wrap(bytes, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
        require(MessageDigest.isEqual(magic, ByteArray(8).also { header.get(it) }) && header.short.toInt() == 1)
        val salt = ByteArray(16).also { header.get(it) }
        val iv = ByteArray(12).also { header.get(it) }
        require(header.int == bytes.size - HEADER)
        val key = derive(code, salt)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(bytes, 0, HEADER)
            cipher.doFinal(bytes, HEADER, bytes.size - HEADER)
        } finally { key.fill(0) }
        return try { RecoveryCredentialCodec.decode(plain) } finally { plain.fill(0) }
    }
    private fun derive(code: ByteArray, salt: ByteArray): ByteArray {
        require(code.size == 32 && code.any { it != 0.toByte() }) { "A random 256-bit recovery code is required" }
        return RecoveryCrypto.hkdf(code, salt, "PUMP-RECOVERY-V1/backup-key".toByteArray(Charsets.US_ASCII), 32)
    }
}
