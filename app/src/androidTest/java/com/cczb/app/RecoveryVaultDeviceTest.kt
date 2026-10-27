package com.cczb.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Uses a separate directory and alias. Does not touch product credentials or BLE. */
@RunWith(AndroidJUnit4::class)
class RecoveryVaultDeviceTest {
    @Test fun portableBackupInteroperatesWithUsbInstaller() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val code = ByteArray(32) { (it + 129).toByte() }
        val source = instrumentation.context.assets.open("recovery-backup-python.pmrec").use { it.readBytes() }
        val records = RecoveryBackup.decrypt(source, code)
        assertEquals(1, records.size)
        assertArrayEquals(ByteArray(16) { (it + 97).toByte() }, records.single().deviceUuid())
        assertArrayEquals(ByteArray(32) { (it + 1).toByte() }, records.single().key())
        assertEquals("阀门测试", records.single().name)
        assertEquals(1L, records.single().epoch)
        // Public test fixture only. No real device credential is written here.
        val exported = RecoveryBackup.encrypt(records, code)
        File(instrumentation.targetContext.cacheDir, "recovery-backup-android.hex").writeText(
            exported.joinToString("") { "%02x".format(it.toInt() and 255) })
        code.fill(0)
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch (_: Exception) { }
    }
    @Test fun keystorePersistenceTamperLossAndBackupImport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "vault-test-${UUID.randomUUID()}")
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val alias = "pump.recovery.test.${UUID.randomUUID()}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val vault = RecoveryVault(context, alias)
            assertTrue(vault.list().isEmpty())
            val item = RecoveryCredential(ByteArray(16) { (it + 1).toByte() }, 7, ByteArray(32) { (it + 41).toByte() }, "测试设备")
            vault.merge(listOf(item))
            assertTrue(RecoveryVault(context, alias).list().single().sameCredential(item))
            val key = requireNotNull(store.getKey(alias, null))
            assertNull("Keystore key must not be exportable", key.encoded)
            val code = RecoveryBackup.newCode()
            val backup = vault.exportBackup(code)
            assertTrue(RecoveryBackup.decrypt(backup, code).single().sameCredential(item))
            val file = File(root, "recovery/vault.bin")
            val saved = file.readBytes()
            file.writeBytes(saved.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            rejected { vault.list() }; rejected { vault.merge(emptyList()) }
            assertFalse(file.readBytes().contentEquals(saved)) // No erase/reset fallback.
            file.writeBytes(saved)
            // AtomicFile recovers a previously committed backup, without trusting a partial base.
            File(file.path + ".bak").writeBytes(saved); file.writeBytes(byteArrayOf(1))
            assertTrue(vault.list().single().sameCredential(item))
            store.deleteEntry(alias)
            rejected { vault.list() }; rejected { vault.merge(listOf(item)) }
            assertFalse(store.containsAlias(alias)) // Missing key must not be silently regenerated.
            // A new phone has an empty vault; the portable backup uses its independent code.
            assertTrue(file.delete())
            vault.importBackup(backup, code)
            assertTrue(vault.list().single().sameCredential(item))
            assertFalse(saved.contentEquals(file.readBytes()))
            code.fill(0)
        } finally {
            store.deleteEntry(alias)
            check(root.canonicalPath.startsWith(instrumentation.targetContext.cacheDir.canonicalPath + File.separator))
            root.deleteRecursively()
        }
    }
    @Test fun androidProviderMatchesFirmwareVector() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val challenge = RecoveryCrypto.Challenge(ByteArray(16) { (it + 97).toByte() }, 7,
            ByteArray(32) { (it + 33).toByte() }, ByteArray(32) { (it + 65).toByte() })
        val hex = "010011002787b23e00435ac601000000000000002356b9dc6417b85afff7b219aa640b99aabeae9773c67ae5b3f09d5006f80d6cad"
        val vector = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        RecoveryCrypto.authenticate(true, key, challenge, RecoveryCrypto.proof(key, challenge, true)).use {
            assertArrayEquals(vector, it.seal(ByteArray(17) { i -> i.toByte() }))
        }
        RecoveryCrypto.authenticate(false, key, challenge, RecoveryCrypto.proof(key, challenge, false)).use {
            assertArrayEquals(ByteArray(17) { i -> i.toByte() }, it.open(vector))
        }
    }
}
