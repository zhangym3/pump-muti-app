package com.cczb.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only encrypted bytes pass through SAF. Secrets are never saved in Compose saved state. */
@Composable
internal fun RecoveryVaultPanel() {
    val context = LocalContext.current
    val vault = remember(context) { RecoveryVault(context) }
    val scope = rememberCoroutineScope()
    var count by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var importDialog by remember { mutableStateOf(false) }
    var importCode by remember { mutableStateOf("") }
    var exportCode by remember { mutableStateOf<ByteArray?>(null) }
    var exportSaved by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    fun reload() {
        scope.launch {
            try { count = withContext(Dispatchers.IO) { vault.list().size } }
            catch (_: Exception) { message = "凭证库无法读取，请保留备份并联系维护人员。" }
        }
    }
    LaunchedEffect(vault) { reload() }
    DisposableEffect(Unit) {
        onDispose { exportCode?.fill(0); pendingExport?.fill(0) }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri == null || bytes == null) {
            bytes?.fill(0)
            message = if (uri == null) "已取消导出。" else "导出已中断，请重新生成备份和恢复码。"
        } else scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                }
                message = "加密备份已保存；恢复码请与备份文件分开保管。"
            } catch (_: Exception) { message = "备份保存失败，请重新导出。" }
            finally { bytes.fill(0); busy = false }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val codeText = importCode
        importCode = ""
        if (uri != null) scope.launch {
            busy = true
            var code: ByteArray? = null
            try {
                code = RecoveryCode.parse(codeText)
                val importedCount = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        // One byte beyond the largest supported encrypted backup detects oversize input.
                        val buffer = ByteArray(RecoveryCredentialCodec.MAX_BYTES + 59)
                        var used = 0
                        while (used < buffer.size) {
                            val n = input.read(buffer, used, buffer.size - used)
                            if (n < 0) break
                            require(n > 0); used += n
                        }
                        require(used < buffer.size)
                        buffer.copyOf(used)
                    }
                    vault.importBackup(bytes, requireNotNull(code))
                    vault.list().size
                }
                count = importedCount
                message = "凭证已导入，共 $count 台。恢复设备时仍需验证设备当前凭证。"
            } catch (_: Exception) {
                message = "导入失败：请核对文件和恢复码；损坏文件或冲突凭证不会覆盖本机记录。"
            } finally { code?.fill(0); busy = false }
        }
    }
    PumpCard("设备恢复凭证") {
        Supporting("本机已保存 $count 台设备的恢复凭证。换手机前，请导出加密备份并单独保存恢复码。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importDialog = true }, enabled = !busy) { Text("导入凭证备份") }
            OutlinedButton(onClick = {
                scope.launch {
                    busy = true
                    val code = RecoveryBackup.newCode()
                    try {
                        pendingExport = withContext(Dispatchers.IO) { vault.exportBackup(code) }
                        exportCode = code
                        exportSaved = false
                    } catch (_: Exception) { code.fill(0); message = "备份生成失败，原凭证已保留。" }
                    finally { busy = false }
                }
            }, enabled = !busy && count > 0 && pendingExport == null) { Text("导出加密备份") }
        }
        if (message.isNotEmpty()) Supporting(message)
    }
    if (importDialog) AlertDialog(
        onDismissRequest = { importDialog = false; importCode = "" },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("导入恢复凭证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("输入备份对应的 64 位十六进制恢复码，再选择加密备份文件。")
                OutlinedTextField(importCode, { importCode = it.take(96) }, singleLine = true,
                    label = { Text("恢复码") }, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(onClick = { importDialog = false; importFile.launch(arrayOf("*/*")) },
            enabled = RecoveryCode.valid(importCode)) { Text("选择备份文件") } },
        dismissButton = { TextButton(onClick = { importDialog = false; importCode = "" }) { Text("取消") } }
    )
    exportCode?.let { code ->
        fun discard() { code.fill(0); exportCode = null; pendingExport?.fill(0); pendingExport = null }
        AlertDialog(
            onDismissRequest = { discard() },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("单独保存本次恢复码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("此恢复码可解密本次备份。请抄写并与备份文件分开保管，关闭后无法再次查看。")
                    Text(RecoveryCode.format(code))
                    Row { Checkbox(exportSaved, { exportSaved = it }); Text("我已单独保存恢复码") }
                }
            },
            confirmButton = { TextButton(onClick = {
                code.fill(0); exportCode = null
                exportFile.launch("pump-recovery-backup.pmrec")
            }, enabled = exportSaved) { Text("保存加密备份") } },
            dismissButton = { TextButton(onClick = { discard() }) { Text("取消") } }
        )
    }
}

internal object RecoveryCode {
    fun parse(text: String): ByteArray {
        val compact = text.filterNot { it == ' ' || it == '-' }
        require(compact.length == 64 && compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        return ByteArray(32) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }.also {
            require(it.any { b -> b != 0.toByte() })
        }
    }
    fun valid(text: String): Boolean = try { parse(text).fill(0); true } catch (_: IllegalArgumentException) { false }
    fun format(code: ByteArray): String = code.joinToString("") { "%02X".format(it.toInt() and 255) }.chunked(8).joinToString("-")
}
