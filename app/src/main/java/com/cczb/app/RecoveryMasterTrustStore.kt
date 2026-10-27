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

/** Public trust anchors still need integrity. No Android backup or first-discovered-peer trust. */
internal class RecoveryMasterTrustStore(context: Context, private val alias: String = "pump.recovery.master.trust.v1") {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "recovery/master-trust.bin"))
    private fun exists() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    fun list(): List<RecoveryMasterTrustFile> = synchronized(lock) { read() }
    fun importTrustedInstallation(record: RecoveryMasterTrustFile) = synchronized(lock) {
        val current = read()
        val merged = RecoveryMasterTrustCodec.merge(current, record)
        if (!MessageDigest.isEqual(RecoveryMasterTrustCodec.encode(current), RecoveryMasterTrustCodec.encode(merged))) write(merged)
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        check(create) { "Master trust key unavailable; trusted reinstallation required" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    private fun read(): List<RecoveryMasterTrustFile> {
        if (!exists()) return emptyList()
        val bytes = file.openRead().use { input ->
            val buffer = ByteArray(HEADER + RecoveryMasterTrustCodec.MAX_BYTES + 17)
            var used = 0
            while (used < buffer.size) {
                val count = input.read(buffer, used, buffer.size - used)
                if (count < 0) break
                check(count > 0); used += count
            }
            require(used < buffer.size)
            buffer.copyOf(used)
        }
        require(bytes.size >= HEADER + 24)
        val header = ByteBuffer.wrap(bytes, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
        require(MessageDigest.isEqual(MAGIC, ByteArray(8).also { header.get(it) }) && header.short.toInt() == 1)
        val iv = ByteArray(12).also { header.get(it) }
        require(header.int == bytes.size - HEADER)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
        cipher.updateAAD(bytes, 0, HEADER)
        return RecoveryMasterTrustCodec.decode(cipher.doFinal(bytes, HEADER, bytes.size - HEADER))
    }
    private fun write(records: List<RecoveryMasterTrustFile>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(!exists()))
        check(cipher.iv.size == 12)
        val plain = RecoveryMasterTrustCodec.encode(records)
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC).putShort(1).put(cipher.iv).putInt(plain.size + 16).array()
        cipher.updateAAD(header)
        val bytes = header + cipher.doFinal(plain)
        val parent = requireNotNull(file.baseFile.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        check(MessageDigest.isEqual(plain, RecoveryMasterTrustCodec.encode(read()))) { "Master trust write verification failed" }
    }
    private companion object {
        val lock = Any()
        const val HEADER = 26
        val MAGIC = "PMMTVL01".toByteArray(Charsets.US_ASCII)
    }
}
