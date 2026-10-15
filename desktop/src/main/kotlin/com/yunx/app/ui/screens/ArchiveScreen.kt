package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.archive.SevenZipEngine
import com.yunx.app.ui.SnackbarController
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 独立的 7-Zip 压缩/解压分区。 */
@Composable
fun ArchiveScreen() {
    val scope = rememberCoroutineScope()
    var inputPath by remember { mutableStateOf<String?>(null) }
    var archivePath by remember { mutableStateOf<String?>(null) }
    var extractArchive by remember { mutableStateOf<String?>(null) }
    var extractDirectory by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lastOutput by remember { mutableStateOf<String?>(null) }

    fun start(action: suspend () -> SevenZipEngine.Result) {
        if (busy) return
        scope.launch {
            busy = true
            lastOutput = null
            val result = withContext(Dispatchers.IO) { runCatching { action() }.getOrElse { error ->
                SevenZipEngine.Result(-1, error.message ?: "7-Zip 操作失败")
            } }
            busy = false
            lastOutput = result.output.takeIf { it.isNotBlank() }
            if (result.succeeded) SnackbarController.show("7-Zip 操作完成")
            else SnackbarController.show("7-Zip 操作失败：${result.output.lineSequence().lastOrNull().orEmpty()}")
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Archive, contentDescription = null)
                    Column {
                        Text("7-Zip 压缩/解压", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("内置 7-Zip ${SevenZipEngine.version()} 引擎，支持 7z、zip、tar、gz 等常用格式。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("压缩", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { inputPath = DesktopActions.pickFile() }) { Text("选择文件") }
                    Button(onClick = { inputPath = DesktopActions.pickDirectory() }) { Text("选择文件夹") }
                }
                PathLine("待压缩", inputPath)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val base = inputPath?.let { File(it).nameWithoutExtension.ifBlank { "archive" } } ?: "archive"
                            archivePath = DesktopActions.saveFile(
                                defaultName = "$base.7z",
                                title = "保存 7z 压缩包",
                                filters = listOf("7z 压缩包 (*.7z)" to "*.7z"),
                                defaultExtension = "7z"
                            )
                        },
                        enabled = inputPath != null && !busy
                    ) { Text("选择输出文件") }
                    Button(
                        onClick = {
                            val source = inputPath?.let(::File)
                            val target = archivePath?.let(::File)
                            if (source != null && target != null) start { SevenZipEngine.compress(source, target) }
                        },
                        enabled = inputPath != null && archivePath != null && !busy
                    ) {
                        if (busy) CircularProgressIndicator(strokeWidth = 2.dp) else Text("开始压缩")
                    }
                }
                PathLine("输出文件", archivePath)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("解压", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(onClick = {
                    extractArchive = DesktopActions.pickFile(
                        listOf("压缩文件" to "*.7z;*.zip;*.tar;*.gz;*.bz2;*.xz;*.cab")
                    )
                }, enabled = !busy) { Text("选择压缩文件") }
                PathLine("压缩文件", extractArchive)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { extractDirectory = DesktopActions.pickDirectory() }, enabled = extractArchive != null && !busy) {
                        Text("选择解压目录")
                    }
                    Button(
                        onClick = {
                            val source = extractArchive?.let(::File)
                            val target = extractDirectory?.let(::File)
                            if (source != null && target != null) start { SevenZipEngine.extract(source, target) }
                        },
                        enabled = extractArchive != null && extractDirectory != null && !busy
                    ) { Text("开始解压") }
                }
                PathLine("解压目录", extractDirectory)
                TextButton(
                    onClick = {
                        val archive = extractArchive?.let(::File) ?: return@TextButton
                        start { SevenZipEngine.list(archive) }
                    },
                    enabled = extractArchive != null && !busy
                ) { Text("查看压缩包内容") }
            }
        }

        lastOutput?.let { output ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("7-Zip 输出", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(output, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PathLine(label: String, path: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$label：${path ?: "未选择"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
