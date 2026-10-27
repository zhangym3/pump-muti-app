package com.cczb.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Separate synthetic trust store; never reads or changes installed product credentials or BLE. */
@RunWith(AndroidJUnit4::class)
class RecoveryMasterTrustDeviceTest {
    private fun fixture(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets
        .open("master-trust-mbedtls.pmmaster").use { it.readBytes() }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected trust rejection") } catch (_: Exception) { }
    }
    @Test fun androidProviderAcceptsSdkSignatureAndRejectsStaleNonce() {
        val bytes = fixture()
        val record = RecoveryMasterTrustFile.decode(bytes)
        assertEquals("698bea63dc44a344663ff1429aea1084", record.fingerprint)
        assertTrue(RecoveryMasterIdentity.verifyProof(record.master, bytes.copyOfRange(129, 161), bytes.copyOfRange(161, 225)))
        assertFalse(RecoveryMasterIdentity.verifyProof(record.master, ByteArray(32) { 7 }, bytes.copyOfRange(161, 225)))
    }
    @Test fun trustPersistsWithoutBackupAndRefusesCorruptionOrKeystoreLoss() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "master-trust-test-${UUID.randomUUID()}")
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val alias = "pump.master.trust.test.${UUID.randomUUID()}"
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val trust = RecoveryMasterTrustStore(context, alias)
            assertTrue(trust.list().isEmpty())
            val record = RecoveryMasterTrustFile.decode(fixture())
            trust.importTrustedInstallation(record)
            assertTrue(RecoveryMasterTrustStore(context, alias).list().single().sameIdentity(record))
            assertNull(requireNotNull(keystore.getKey(alias, null)).encoded)
            val file = File(root, "recovery/master-trust.bin")
            val saved = file.readBytes()
            trust.importTrustedInstallation(record)
            assertArrayEquals(saved, file.readBytes()) // Duplicate import avoids a new write.
            val corrupt = saved.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(corrupt)
            rejected { trust.list() }; rejected { trust.importTrustedInstallation(record) }
            assertArrayEquals(corrupt, file.readBytes())
            File(file.path + ".bak").writeBytes(saved); file.writeBytes(byteArrayOf(1))
            assertTrue(trust.list().single().sameIdentity(record))
            keystore.deleteEntry(alias)
            rejected { trust.list() }; rejected { trust.importTrustedInstallation(record) }
            assertFalse(keystore.containsAlias(alias))
            assertArrayEquals(saved, file.readBytes())
        } finally {
            keystore.deleteEntry(alias)
            check(root.canonicalPath.startsWith(target.cacheDir.canonicalPath + File.separator))
            root.deleteRecursively()
        }
    }
}
