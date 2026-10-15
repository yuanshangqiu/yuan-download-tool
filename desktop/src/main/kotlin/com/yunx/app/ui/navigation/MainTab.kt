package com.yunx.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 主导航栏的 7 个 Tab。
 */
enum class MainTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Resolve("解析", Icons.Filled.Link, Icons.Outlined.Link),
    Drive("网盘", Icons.Filled.Cloud, Icons.Outlined.Cloud),
    Download("下载", Icons.Filled.Download, Icons.Outlined.Download),
    Network("网络加速", Icons.Filled.Wifi, Icons.Outlined.Wifi),
    Platform("平台加速", Icons.Outlined.Speed, Icons.Outlined.Speed),
    Archive("压缩", Icons.Filled.FolderOpen, Icons.Outlined.FolderOpen),
    Settings("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
}
