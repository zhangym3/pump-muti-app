package com.cczb.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RecoveryMasterTrustPanel() {
    val context = LocalContext.current
    val store = remember(context) { RecoveryMasterTrustStore(context) }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<RecoveryMasterTrustFile>()) }
    var pending by remember { mutableStateOf<RecoveryMasterTrustFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var healthy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(store) {
        try { records = withContext(Dispatchers.IO) { store.list() }; healthy = true }
        catch (_: Exception) { message = "主机身份库无法读取，请保留原文件并联系维护人员。" }
    }
    val select = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                pending = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val buffer = ByteArray(RecoveryMasterTrustFile.SIZE + 1)
                        var used = 0
                        while (used < buffer.size) {
                            val count = input.read(buffer, used, buffer.size - used)
                            if (count < 0) break
                            require(count > 0); used += count
                        }
                        require(used == RecoveryMasterTrustFile.SIZE)
                        buffer.copyOf(used)
                    }
                    RecoveryMasterTrustFile.decode(bytes)
                }
            } catch (_: Exception) { message = "身份文件校验失败，请使用安装时通过 USB 导出的主机身份文件。" }
            finally { busy = false }
        }
    }
    PumpCard("主机身份") {
        Supporting("本机已保存 ${records.size} 台主机身份。请仅导入由可信安装人员交付的身份文件，并核对指纹。")
        OutlinedButton(onClick = { select.launch(arrayOf("*/*")) }, enabled = healthy && !busy && pending == null) {
            Text("导入主机身份文件")
        }
        records.forEach { Supporting("主机 ${it.deviceId.takeLast(8)} · 指纹 ${it.fingerprint.chunked(8).joinToString(" ")}") }
        if (message.isNotEmpty()) Supporting(message)
    }
    pending?.let { record ->
        AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text("确认主机身份来源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请与可信安装人员核对该主机的指纹：")
                    Text(record.fingerprint.chunked(8).joinToString(" "))
                    Text("文件内的签名不能证明文件来源。确认后仅保存主机身份；恢复操作仍需单独验证管理权限。")
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        records = withContext(Dispatchers.IO) { store.importTrustedInstallation(record); store.list() }
                        pending = null
                        message = "主机身份已保存；连接时仍需验证主机身份和管理权限。"
                    } catch (_: Exception) { pending = null; message = "保存失败：身份冲突或存储异常，原身份记录已保留，请联系维护人员。" }
                    finally { busy = false }
                }
            }) { Text("来源可信，保存身份") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("取消") } })
    }
}
