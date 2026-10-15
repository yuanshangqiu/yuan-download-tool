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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.SteamToolsAccelerationController
import com.yunx.app.data.network.SteamToolsPlatformCatalog
import com.yunx.app.data.network.SteamToolsPlatformService
import kotlinx.coroutines.launch

/** SteamTools 平台加速独立分区。 */
@Composable
fun PlatformAccelerationScreen() {
    val snapshot by SteamToolsAccelerationController.state.collectAsState()
    val scope = rememberCoroutineScope()

    val selectedCount = snapshot.selectedServiceIds.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SteamTools 平台加速", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "独立管理 SteamTools 的平台加速。所选服务域名通过 PAC 规则走本机代理，单网卡即可使用，Steam CDN 优化同时开启。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (snapshot.isBusy) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Switch(
                            checked = snapshot.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    if (enabled) SteamToolsAccelerationController.enable()
                                    else SteamToolsAccelerationController.disable()
                                }
                            },
                            enabled = snapshot.enabled || selectedCount > 0
                        )
                    }
                }
                Text(
                    when {
                        snapshot.enabled && snapshot.systemProxyApplied -> "运行中 · PAC 规则：选中域名 → 127.0.0.1:10811"
                        snapshot.enabled -> "运行中 · 仅 YunX 请求经过平台加速"
                        snapshot.isBusy -> "正在启动平台加速…"
                        else -> "未启用 · 已选择 $selectedCount 项服务"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (snapshot.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("可加速的平台服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "SteamTools 原有的平台范围均保留，可按需选择。当前启用 $selectedCount / ${SteamToolsPlatformCatalog.services.size} 项。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { SteamToolsAccelerationController.selectAllServices() },
                        enabled = !snapshot.enabled
                    ) { Text("全选") }
                    TextButton(
                        onClick = { SteamToolsAccelerationController.clearServices() },
                        enabled = !snapshot.enabled
                    ) { Text("清空") }
                }
                HorizontalDivider()
                SteamToolsPlatformCatalog.services.forEachIndexed { index, service ->
                    PlatformServiceRow(
                        service = service,
                        selected = service.id in snapshot.selectedServiceIds,
                        enabled = !snapshot.enabled && !snapshot.isBusy,
                        onSelected = { SteamToolsAccelerationController.setServiceSelected(service.id, it) }
                    )
                    if (index != SteamToolsPlatformCatalog.services.lastIndex) HorizontalDivider()
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("运行方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "平台加速运行独立代理实例，自动使用一张活动网卡。Windows PAC 规则只代理所选平台域名，其余域名直连；关闭时恢复原代理设置。支持系统 PAC 的应用可使用此入口，多网卡聚合在「网络加速」分区独立运行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Steam、Twitch、Origin、Uplay、Spotify、GitHub、Roblox、mod.io、Nexus Mods 和网盘客户端仅在支持 Windows PAC 的情况下会按所选域名走代理；不支持系统 PAC 的客户端不会被透明接管。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        snapshot.error?.let { error ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    error,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun PlatformServiceRow(
    service: SteamToolsPlatformService,
    selected: Boolean,
    enabled: Boolean,
    onSelected: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = onSelected, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(service.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                service.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                service.domains.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
