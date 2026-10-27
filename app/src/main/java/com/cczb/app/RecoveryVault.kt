package com.cczb.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All instances serialize through one lock; neither the vault nor its key enters Android backup. */
internal class RecoveryVault(context: Context, private val alias: String = "pump.recovery.vault.v1") {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "recovery/vault.bin"))
    private fun exists() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    fun list(): List<RecoveryCredential> = synchronized(lock) { read() }
    fun merge(records: List<RecoveryCredential>) = synchronized(lock) {
        write(RecoveryCredentialCodec.merge(read(), records))
    }
    fun importBackup(bytes: ByteArray, code: ByteArray) = merge(RecoveryBackup.decrypt(bytes, code))
    fun exportBackup(code: ByteArray): ByteArray = synchronized(lock) { RecoveryBackup.encrypt(read(), code) }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        check(create) { "Recovery vault key unavailable; import the encrypted backup" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    private fun read(): List<RecoveryCredential> {
        if (!exists()) return emptyList()
        // Read a bounded stream, including AtomicFile's interrupted-write recovery.
        val bytes = file.openRead().use { input ->
            val bytes = ByteArray(HEADER + RecoveryCredentialCodec.MAX_BYTES + 17)
            var used = 0
            while (used < bytes.size) {
                val count = input.read(bytes, used, bytes.size - used)
                if (count < 0) break
                check(count > 0)
                used += count
            }
            require(used < bytes.size)
            bytes.copyOf(used)
        }
        require(bytes.size >= HEADER + 24)
        val header = ByteBuffer.wrap(bytes, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
        require(MessageDigest.isEqual(MAGIC, ByteArray(8).also { header.get(it) }) && header.short.toInt() == 1)
        val iv = ByteArray(12).also { header.get(it) }
        require(header.int == bytes.size - HEADER)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
        cipher.updateAAD(bytes, 0, HEADER)
        val plain = cipher.doFinal(bytes, HEADER, bytes.size - HEADER)
        return try { RecoveryCredentialCodec.decode(plain) } finally { plain.fill(0) }
    }
    private fun write(records: List<RecoveryCredential>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(!exists()))
        val plain = RecoveryCredentialCodec.encode(records)
        val bytes = try {
            check(cipher.iv.size == 12)
            val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
                .put(MAGIC).putShort(1).put(cipher.iv).putInt(plain.size + 16).array()
            cipher.updateAAD(header)
            header + cipher.doFinal(plain)
        } finally { plain.fill(0) }
        val parent = requireNotNull(file.baseFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create recovery vault" }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        val verified = read()
        check(verified.size == records.size && records.all { record ->
            verified.any { it.sameCredential(record) && it.name == record.name }
        }) { "Recovery vault verification failed" }
    }
    private companion object {
        val lock = Any()
        const val HEADER = 26
        val MAGIC = "PMRECVL1".toByteArray(Charsets.US_ASCII)
    }
}
