package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.NetworkAccelerationController
import com.yunx.app.data.network.NetworkAdapterOption
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun NetworkAccelerationScreen() {
    val snapshot by NetworkAccelerationController.state.collectAsState()
    val scope = rememberCoroutineScope()
    val selectedCount = snapshot.adapters.count { it.selected }

    LaunchedEffect(Unit) {
        if (!snapshot.isRunning) NetworkAccelerationController.refreshAdapters()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("HypoMux 多网卡聚合", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "选择有线、Wi-Fi 或手机热点网卡。HypoMux 会把 YunX 的并发连接分配到所选链路，并根据实时吞吐逐步调整调度。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                if (snapshot.isRunning) {
                                    NetworkAccelerationController.stop()
                                }
                                else NetworkAccelerationController.start()
                            }
                        },
                        enabled = !snapshot.isBusy && (snapshot.isRunning || selectedCount >= 2)
                    ) {
                        if (snapshot.isBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (snapshot.isRunning) "停止聚合" else "启动聚合")
                    }
                    Text(
                        if (snapshot.isRunning) "运行中 · ${snapshot.engineVersion.ifBlank { "HypoMux" }}"
                        else "已停止 · 已选 $selectedCount 张网卡",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (snapshot.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (snapshot.isRunning) {
                    HorizontalDivider()
                    Text(
                        "下载 ${formatRate(snapshot.totalDownloadBps)}  ·  上传 ${formatRate(snapshot.totalUploadBps)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "调度：${strategyLabel(snapshot.schedulingStrategy)} · ${schedulingStateLabel(snapshot.schedulingState)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("可用网卡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "至少选择两张有 IPv4 地址的活动网卡。虚拟网卡默认不选，可按需启用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { NetworkAccelerationController.refreshAdapters() } },
                        enabled = !snapshot.isBusy && !snapshot.isRunning
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新网卡")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (snapshot.adapters.isEmpty()) {
                    Text("没有发现可用的网络适配器。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    snapshot.adapters.forEachIndexed { index, adapter ->
                        AdapterRow(
                            adapter = adapter,
                            enabled = !snapshot.isRunning && !snapshot.isBusy,
                            onSelected = { NetworkAccelerationController.setAdapterSelected(adapter.id, it) }
                        )
                        if (index != snapshot.adapters.lastIndex) HorizontalDivider()
                    }
                }
            }
        }

        if (snapshot.error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    snapshot.error.orEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("运行范围", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "HypoMux 多网卡聚合只接管 YunX 的请求，不修改系统路由。需要加速 Steam、Twitch、Origin、Uplay、GitHub 等平台时，请打开左侧独立的「平台加速」分区。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AdapterRow(
    adapter: NetworkAdapterOption,
    enabled: Boolean,
    onSelected: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = adapter.selected, onCheckedChange = onSelected, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(adapter.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (adapter.virtual) {
                    Text("虚拟", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "${adapter.sourceIPv4}  ·  接口 ${adapter.interfaceIndex}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (adapter.selected) {
            Column(horizontalAlignment = Alignment.End) {
                Text("↓ ${formatRate(adapter.downloadBps)}", style = MaterialTheme.typography.bodySmall)
                Text("↑ ${formatRate(adapter.uploadBps)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun strategyLabel(strategy: String): String = when (strategy) {
    "adaptive-throughput" -> "智能吞吐调度"
    "weighted" -> "加权轮询"
    "round-robin" -> "轮询"
    else -> "自动"
}

private fun schedulingStateLabel(state: String): String = when (state) {
    "warming-up" -> "正在观察链路"
    "insufficient-demand" -> "连接数不足，保持均衡"
    "balanced" -> "均衡分配"
    "adapting" -> "正在调整新连接分配"
    "throughput-guard" -> "吞吐保护中"
    else -> "待采样"
}

private fun formatRate(bytesPerSecond: Double): String {
    if (!bytesPerSecond.isFinite() || bytesPerSecond < 0.0) return "0 B/s"
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSecond
    var unit = 0
    while (value >= 1000.0 && unit < units.lastIndex) {
        value /= 1000.0
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}"
    else String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}
