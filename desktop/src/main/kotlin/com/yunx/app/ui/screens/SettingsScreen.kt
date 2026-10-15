package com.yunx.app.ui.screens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yunx.app.AppContext
import com.yunx.app.data.backup.AuthBackupManager
import com.yunx.app.data.backup.AuthCrypto
import com.yunx.app.data.download.DownloadDebugLog
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.download.DownloadSaver
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.theme.ThemeController
import com.yunx.app.util.DesktopActions
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.Log
import com.yunx.app.util.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 可选的下载线程数档位（最高 512） */
private val threadOptions = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512)

/** 按平台下载线程数设置项 */
private data class ThreadPlatform(val platform: String, val label: String)

private val threadPlatforms = listOf(
    ThreadPlatform(DownloadPlatform.QUARK, "夸克网盘"),
    ThreadPlatform(DownloadPlatform.UC, "UC 网盘"),
    ThreadPlatform(DownloadPlatform.XUNLEI, "迅雷网盘"),
    ThreadPlatform(DownloadPlatform.BAIDU, "百度网盘"),
    ThreadPlatform(DownloadPlatform.C139, "139 网盘"),
    ThreadPlatform(DownloadPlatform.PAN123, "123 云盘"),
    ThreadPlatform(DownloadPlatform.PAN115, "115 网盘"),
    ThreadPlatform(DownloadPlatform.GUANGYA, "光鸭云盘"),
    ThreadPlatform(DownloadPlatform.ILANZOU, "蓝奏云优享版"),
    ThreadPlatform(DownloadPlatform.LANZOU, "蓝奏云"),
)

/**
 * 设置页：下载线程数设置、主题外观、日志与网盘认证。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    onThemeClick: () -> Unit,
    onAboutClick: () -> Unit,
    /** 打开「实验性功能」二级页 */
    onExperimentalClick: () -> Unit,
    /** 打开「下载引擎」二级页（内置分片下载器 / 外部 Gopeed 引擎） */
    onDownloadEngineClick: () -> Unit,
    /** 打开「储存目录」二级页（下载保存目录 / 下载缓存 / 数据库位置） */
    onStorageClick: () -> Unit,
    backupManager: AuthBackupManager,
    modifier: Modifier = Modifier
) {
    var showThreadsDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    // 网盘认证导出弹窗（AES 加密 + 导出范围）
    var showExportAuthDialog by remember { mutableStateOf(false) }
    // 网盘认证导入：加密文件内容（非空时弹解密密码框）
    var pendingImportContent by remember { mutableStateOf<String?>(null) }
    var showImportAuthDialog by remember { mutableStateOf(false) }
    // 导出/导入处理中（PBKDF2 21万次迭代派生密钥，偶发 1~3s，期间显示加载弹窗）
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    // 按平台线程数：二级弹窗当前选择的平台
    var selectedThreadPlatform by remember { mutableStateOf(threadPlatforms.first()) }
    var showPlatformThreadDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 下载保存目录：本地状态驱动 UI 刷新，同时同步 Preferences
    val settingsRepo = remember { SettingsRepository() }
    var downloadDirUri by remember { mutableStateOf(settingsRepo.downloadDirUri) }
    var showDevMenu by remember { mutableStateOf(false) }
    // 诊断模式开关（开发调试菜单里）：本地状态驱动 UI，运行态在 DiagnosticLog 里（改完立刻生效）
    var diagnosticOn by remember { mutableStateOf(settingsRepo.diagnosticMode) }
    // 下载调试开关（开发调试菜单里）：开启后下载页右键任务可查看「调试信息」；关闭会清空日志，故需二次确认
    var downloadDebugOn by remember { mutableStateOf(settingsRepo.downloadDebug) }
    var showDisableDownloadDebugConfirm by remember { mutableStateOf(false) }
    // 全量 SHA-256 校验开关（开发调试菜单里）：默认关，开启后保存文件时顺带算整文件摘要写进调试日志
    var fullShaOn by remember { mutableStateOf(settingsRepo.fullFileSha256) }
    // 网络与下载策略（本地状态驱动 UI，同时同步 Preferences）
    var maxConcurrent by remember { mutableStateOf(settingsRepo.maxConcurrentDownloads) }
    var speedLimitBps by remember { mutableStateOf(settingsRepo.downloadSpeedLimit) }
    var retryCount by remember { mutableStateOf(settingsRepo.downloadRetryCount) }
    // 夸克取链方式：免转存（默认，不写入网盘）↔ 转存（先存临时目录再取链）
    var quarkNoSave by remember { mutableStateOf(settingsRepo.quarkNoSaveDownload) }
    var showConcurrencyDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showRetryDialog by remember { mutableStateOf(false) }
    // 用户体验与系统适配：下载时阻止休眠 / 通知中心进度
    var keepAwake by remember { mutableStateOf(settingsRepo.keepAwakeWhileDownloading) }
    var showSpeed by remember { mutableStateOf(settingsRepo.notificationShowSpeed) }
    var clipboardDetect by remember { mutableStateOf(settingsRepo.clipboardLinkDetection) }
    // 关闭主窗口时的行为：ask / exit / tray
    var closeBehavior by remember { mutableStateOf(settingsRepo.closeBehavior) }
    var showCloseBehaviorDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionLabel("下载")
        SettingsItem(
            icon = Icons.Outlined.Tune,
            title = "下载线程数",
            description = "按网盘分别设置分片并发数（默认 32，最高 512）",
            onClick = { showThreadsDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 储存目录（二级页）：下载保存目录 / 下载缓存目录 / 数据库位置
        SettingsItem(
            icon = Icons.Outlined.FolderOpen,
            title = "储存目录",
            description = downloadDirUri?.let { "下载保存已自定义：${DownloadSaver.safDirDisplay(it)}" }
                ?: "下载保存目录、下载缓存、数据库位置",
            onClick = onStorageClick
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 网络与下载策略
        SettingsItem(
            icon = Icons.Outlined.Layers,
            title = "最大同时下载任务数",
            description = "同时下载 $maxConcurrent 个任务，超出的排队等待（内置下载器与 Gopeed 引擎共用）",
            onClick = { showConcurrencyDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Speed,
            title = "下载速度限制",
            description = speedLimitText(speedLimitBps),
            onClick = { showSpeedDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Refresh,
            title = "失败自动重试",
            description = if (retryCount == 0) "失败后不自动重试" else "失败后自动重试 $retryCount 次（断点续传）",
            onClick = { showRetryDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 取链方式：免转存（默认）↔ 转存。只影响夸克 —— 其分享文件可以「用分享凭证直接换直链」，
        // 不必先转存进用户网盘；关掉后回到「转存到 YunX临时转存 再取链」的老流程。
        SettingsItem(
            icon = Icons.Outlined.SwapHoriz,
            title = "免转存下载",
            description = if (quarkNoSave) {
                "夸克：解析出直链后直接下载，不把文件转存到自己的网盘"
            } else {
                "夸克：先转存到临时目录再取链（下载完成后自动清理）"
            },
            onClick = {
                quarkNoSave = !quarkNoSave
                settingsRepo.quarkNoSaveDownload = quarkNoSave
            },
            trailing = { Switch(checked = quarkNoSave, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 用户体验与系统适配：下载时阻止休眠 / 通知中心进度
        SettingsItem(
            icon = Icons.Outlined.Power,
            title = "下载时阻止电脑休眠",
            description = "下载期间系统不会自动进入睡眠，任务结束后恢复",
            onClick = {
                keepAwake = !keepAwake
                settingsRepo.keepAwakeWhileDownloading = keepAwake
            },
            trailing = { Switch(checked = keepAwake, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Notifications,
            title = "通知中心下载进度",
            description = if (showSpeed) "Windows 通知中心进度条 + 下载速度" else "Windows 通知中心仅显示进度条（隐藏速度）",
            onClick = {
                showSpeed = !showSpeed
                settingsRepo.notificationShowSpeed = showSpeed
            },
            trailing = { Switch(checked = showSpeed, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.ContentPaste,
            title = "剪贴板分享链接检测",
            description = if (clipboardDetect) "主窗口不在前台时检测剪贴板，发现网盘链接自动弹窗提示" else "关闭后台剪贴板检测",
            onClick = {
                clipboardDetect = !clipboardDetect
                settingsRepo.clipboardLinkDetection = clipboardDetect
            },
            trailing = { Switch(checked = clipboardDetect, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Minimize,
            title = "关闭窗口时",
            description = when (closeBehavior) {
                SettingsRepository.CLOSE_BEHAVIOR_TRAY -> "最小化到系统托盘（下载继续）"
                SettingsRepository.CLOSE_BEHAVIOR_EXIT -> "直接退出应用"
                else -> "每次询问（默认）"
            },
            onClick = { showCloseBehaviorDialog = true }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("外观")
        SettingsItem(
            icon = Icons.Outlined.Palette,
            title = "主题与外观",
            description = "主题色、动态色彩与深色模式",
            onClick = onThemeClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 实验性功能二级页：HTTP/2、下载读缓冲、慢连接抢占、主页快捷方式（集中管理 + 一键重置）
        SettingsItem(
            icon = Icons.Outlined.Bolt,
            title = "实验性功能",
            description = "HTTP/2、下载读缓冲、慢连接抢占、主页快捷方式等高风险参数",
            onClick = onExperimentalClick
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 下载引擎二级页：内置分片下载器 ↔ 外部 Gopeed 引擎（仅影响新任务）
        SettingsItem(
            icon = Icons.Outlined.Speed,
            title = "下载引擎",
            description = "切换内置分片下载器 / Gopeed 引擎（引擎不支持限速、重试与镜像回退）",
            onClick = onDownloadEngineClick
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.Article,
            title = "导出日志",
            description = "导出崩溃日志与应用信息，便于排查问题",
            onClick = { showLogDialog = true }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("网盘认证")
        SettingsItem(
            icon = Icons.Outlined.Backup,
            title = "导出网盘认证",
            description = "使用至少 8 位口令加密 Cookie/JWT 后导出",
            onClick = { showExportAuthDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.Restore,
            title = "导入网盘认证",
            description = "选择加密或明文的认证备份文件，恢复网盘登录",
            onClick = {
                val path = DesktopActions.pickFile(
                    filters = listOf(
                        "认证备份文件 (*.yunx;*.json)" to "*.yunx;*.json",
                        "所有文件 (*.*)" to "*.*"
                    )
                )
                if (path != null) {
                    scope.launch {
                        isImporting = true
                        try {
                            // 读取文件内容：先判断是否加密备份，加密则弹密码框
                            val text = runCatching { java.io.File(path).readText() }.getOrNull()
                            if (text == null) {
                                SnackbarController.show("读取文件失败")
                                return@launch
                            }
                            if (AuthCrypto.isEncrypted(text)) {
                                // 加密备份：关闭加载弹窗，弹解密密码框（解密在确认后执行）
                                pendingImportContent = text
                                showImportAuthDialog = true
                            } else {
                                // 明文备份：直接导入
                                val count = runCatching {
                                    withContext(Dispatchers.IO) { backupManager.importJson(text) }
                                }.getOrElse { e ->
                                    SnackbarController.show("导入失败：${e.message}")
                                    return@launch
                                }
                                SnackbarController.show("已恢复 $count 个平台的认证信息")
                            }
                        } finally {
                            isImporting = false
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("关于")
        SettingsItem(
            icon = Icons.Outlined.Info,
            title = "关于云析",
            description = "版本信息、支持平台与技术说明",
            onClick = onAboutClick,
            onLongClick = { showDevMenu = true } // 长按打开隐藏开发调试菜单
        )

    }

    // 导出日志方式选择弹窗
    FadeAlertDialog(
        visible = showLogDialog,
        onDismissRequest = { showLogDialog = false },
        title = { Text("导出日志") },
        text = {
            Column {
                Text(
                    text = "选择日志导出方式：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        showLogDialog = false
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { LogExporter.export() }
                            if (file != null && DesktopActions.revealFile(file.absolutePath)) {
                                SnackbarController.show("日志已导出，已在文件夹中显示")
                            } else {
                                SnackbarController.show("导出日志失败")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导出日志（在文件夹中显示）")
                }
                TextButton(
                    onClick = {
                        showLogDialog = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                LogExporter.saveToDownloads()
                            }
                            SnackbarController.show(if (ok) "已保存到下载目录" else "保存失败")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存到下载目录")
                }
                TextButton(
                    onClick = {
                        showLogDialog = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                LogExporter.clearLog()
                            }
                            SnackbarController.show(if (ok) "日志缓存已清空" else "清空失败")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("清空日志缓存")
                }
                // 诊断模式开着时额外提供 zip：把 diagnostic_logs 下的分模块日志一次带走
                if (diagnosticOn) {
                    TextButton(
                        onClick = {
                            showLogDialog = false
                            scope.launch {
                                val zip = withContext(Dispatchers.IO) { LogExporter.exportDiagnosticZip() }
                                if (zip != null && DesktopActions.revealFile(zip.absolutePath)) {
                                    SnackbarController.show("诊断日志已打包（${zip.name}）")
                                } else {
                                    SnackbarController.show("暂时没有可导出的诊断日志")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("导出诊断日志（zip）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showLogDialog = false }) { Text("取消") }
        }
    )

    // 隐藏开发调试菜单（长按「关于云析」打开）
    FadeAlertDialog(
        visible = showDevMenu,
        onDismissRequest = { showDevMenu = false },
        title = { Text("开发调试") },
        text = {
            Column {
                // 诊断模式：开启后 db / crypto / download / network / operation 等模块的详细日志
                // 写进数据目录 diagnostic_logs（按模块分文件），关闭时一行都不写。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("诊断模式", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (diagnosticOn) {
                                "详细日志写入 ${DiagnosticLog.dirOf().absolutePath}"
                            } else {
                                "关闭：不写任何诊断日志"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = diagnosticOn,
                        onCheckedChange = { on ->
                            diagnosticOn = on
                            settingsRepo.diagnosticMode = on
                            // 动态生效：setEnabled 会立刻起/停写线程，关闭时先 flush
                            DiagnosticLog.setEnabled(on)
                            SnackbarController.show(if (on) "诊断模式已开启" else "诊断模式已关闭")
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 下载调试：开启后按下载任务记录事件流（主要变更 / 错误节点），下载页右键任务可进调试信息页。
                // ★ 关闭会清空全部日志，所以不直接关，先弹二次确认（见下方 FadeAlertDialog）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("下载调试", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (downloadDebugOn) {
                                "已在记录：下载页右键任务 → 调试信息（关闭会清空日志）"
                            } else {
                                "关闭：不记录下载调试日志"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = downloadDebugOn,
                        onCheckedChange = { on ->
                            if (on) {
                                downloadDebugOn = true
                                ThemeController.updateDownloadDebug(true)
                                DownloadDebugLog.setEnabled(true)
                                SnackbarController.show("下载调试已开启")
                            } else {
                                // 关闭会清空全部调试日志：先二次确认再真正关闭
                                showDisableDownloadDebugConfirm = true
                            }
                        }
                    )
                }

                // 全量 SHA-256 只在「下载调试」开启时才有意义（摘要只写进调试日志），
                // 所以调试关着时整行隐藏；关掉下载调试会把它一并强制关闭，下次要手动重新开。
                if (downloadDebugOn) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // 全量 SHA-256（默认关）：保存文件时顺带算整文件摘要并写进下载调试日志。
                    // 与合并复用同一遍 IO，所以不额外读盘，只多一点 CPU —— 大文件想省 CPU 就关着。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("全量 SHA-256", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (fullShaOn) {
                                    "已开启：保存后记录整文件 SHA-256（写入调试日志）"
                                } else {
                                    "关闭：不算整文件摘要（默认，省 CPU）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fullShaOn,
                            onCheckedChange = { on ->
                                fullShaOn = on
                                settingsRepo.fullFileSha256 = on
                                SnackbarController.show(if (on) "已开启全量 SHA-256 校验" else "已关闭全量 SHA-256 校验")
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 导出入口（zip 名 yunx_diagnostic_logs_yyyyMMdd_HHmmss.zip，导出前会先 flush）
                TextButton(
                    onClick = {
                        showDevMenu = false
                        scope.launch {
                            val zip = withContext(Dispatchers.IO) { LogExporter.exportDiagnosticZip() }
                            if (zip != null && DesktopActions.revealFile(zip.absolutePath)) {
                                SnackbarController.show("诊断日志已打包（${zip.name}）")
                            } else {
                                SnackbarController.show("暂时没有可导出的诊断日志")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("导出诊断日志（zip）") }
            }
        },
        confirmButton = {
            TextButton(onClick = { showDevMenu = false }) { Text("关闭") }
        }
    )

    // 关闭「下载调试」的二次确认：关闭会清空已记录的全部调试日志，且不可恢复
    FadeAlertDialog(
        visible = showDisableDownloadDebugConfirm,
        onDismissRequest = { showDisableDownloadDebugConfirm = false },
        title = { Text("关闭下载调试？") },
        text = {
            Text("关闭后会清空已记录的全部下载调试日志，且无法恢复；「全量 SHA-256」也会被一并关闭（下次开启下载调试后需手动重新开启）。确定要关闭吗？")
        },
        confirmButton = {
            TextButton(onClick = {
                showDisableDownloadDebugConfirm = false
                downloadDebugOn = false
                ThemeController.updateDownloadDebug(false)
                // 关闭即清空：DownloadDebugLog.setEnabled(false) 内部会 clear()
                DownloadDebugLog.setEnabled(false)
                // 全量 SHA-256 依赖下载调试（摘要只写调试日志）：一并强制关闭，且不记忆，
                // 下次重新开启下载调试后需要用户手动再开一次。
                fullShaOn = false
                settingsRepo.fullFileSha256 = false
                SnackbarController.show("下载调试已关闭，日志已清空；全量 SHA-256 已一并关闭")
            }) { Text("关闭并清空") }
        },
        dismissButton = {
            TextButton(onClick = { showDisableDownloadDebugConfirm = false }) { Text("取消") }
        }
    )

    // 线程数选择弹窗（按平台）
    FadeAlertDialog(
        visible = showThreadsDialog,
        onDismissRequest = { showThreadsDialog = false },
        title = { Text("下载线程数") },
        text = {
            Column {
                Text(
                    text = "按网盘分别设置分片并发数；线程数不是越多越好，适当调整",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                threadPlatforms.forEach { item ->
                    val current = settingsRepo.downloadThreadsFor(item.platform)
                    val isXunlei = item.platform == DownloadPlatform.XUNLEI
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isXunlei) {
                                selectedThreadPlatform = item
                                showPlatformThreadDialog = true
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (isXunlei) "固定 8 线程" else "$current 线程",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isXunlei) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        if (!isXunlei) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showThreadsDialog = false }) { Text("取消") }
        }
    )

    // 单个平台线程数选择（二级弹窗）
    FadeAlertDialog(
        visible = showPlatformThreadDialog,
        onDismissRequest = { showPlatformThreadDialog = false },
        title = { Text("${selectedThreadPlatform.label}线程数") },
        text = {
            val current = settingsRepo.downloadThreadsFor(selectedThreadPlatform.platform)
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                threadOptions.chunked(2).forEach { rowValues ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowValues.forEach { value ->
                            RadioThreadRow(
                                value = value,
                                threads = current,
                                onSelect = { v ->
                                    settingsRepo.setDownloadThreads(selectedThreadPlatform.platform, v)
                                    showPlatformThreadDialog = false
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 奇数个时补空占位，保持两列对齐
                        if (rowValues.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showPlatformThreadDialog = false }) { Text("取消") }
        }
    )

    // 导出网盘认证弹窗（AES 加密密码 + 导出范围）
    ExportAuthDialog(
        visible = showExportAuthDialog,
        onDismiss = { showExportAuthDialog = false },
        onConfirm = { password, onlyLoggedIn ->
            showExportAuthDialog = false
            isExporting = true
            scope.launch {
                try {
                    val content = runCatching {
                        withContext(Dispatchers.IO) { backupManager.export(password, onlyLoggedIn) }
                    }.onFailure { Log.e("YunX-Auth", "export failed", it) }.getOrNull()
                    if (content == null) {
                        SnackbarController.show("导出失败")
                        return@launch
                    }
                    val encrypted = true
                    // 加载弹窗先关闭，再弹系统「另存为」对话框（EDT 上模态阻塞）
                    isExporting = false
                    val target = DesktopActions.saveFile(
                        defaultName = backupManager.defaultBackupFileName(encrypted),
                        title = "导出网盘认证",
                        filters = listOf("云析认证备份 (*.yunx)" to "*.yunx", "所有文件 (*.*)" to "*.*"),
                        defaultExtension = "yunx"
                    ) ?: return@launch
                    val saved = withContext(Dispatchers.IO) {
                        backupManager.saveTo(content, java.io.File(target))
                    }
                    SnackbarController.show(
                        if (saved) "已导出到 ${java.io.File(target).parent}" else "导出失败"
                    )
                } finally {
                    isExporting = false
                }
            }
        }
    )

    // 导入加密备份弹窗（解密密码）
    ImportAuthDialog(
        visible = showImportAuthDialog,
        onDismiss = {
            showImportAuthDialog = false
            pendingImportContent = null
        },
        onConfirm = { password ->
            showImportAuthDialog = false
            val content = pendingImportContent
            pendingImportContent = null
            if (content != null) {
                isImporting = true
                scope.launch {
                    try {
                        val count = try {
                            withContext(Dispatchers.IO) { backupManager.import(content, password) }
                        } catch (e: javax.crypto.AEADBadTagException) {
                            SnackbarController.show("密码错误，解密失败")
                            return@launch
                        } catch (e: Exception) {
                            SnackbarController.show("导入失败：${e.message}")
                            return@launch
                        }
                        SnackbarController.show("已恢复 $count 个平台的认证信息")
                    } finally {
                        isImporting = false
                    }
                }
            }
        }
    )

    // 导出/导入处理中：转圈加载弹窗（PBKDF2 派生密钥耗时较长，避免用户以为界面卡死）
    OperationLoadingDialog(visible = isExporting, message = "正在导出认证…")
    OperationLoadingDialog(visible = isImporting, message = "正在导入认证…")

    // 最大同时下载任务数
    FadeAlertDialog(
        visible = showConcurrencyDialog,
        onDismissRequest = { showConcurrencyDialog = false },
        title = { Text("最大同时下载任务数") },
        text = {
            val options = listOf(1, 2, 3, 5, 8)
            Column {
                options.forEach { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = maxConcurrent == v,
                            onClick = {
                                maxConcurrent = v
                                settingsRepo.maxConcurrentDownloads = v
                                showConcurrencyDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("同时下载 $v 个任务", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showConcurrencyDialog = false }) { Text("取消") }
        }
    )

    // 下载速度限制：预设档位 + 自定义（KB/s）
    val speedPresets = listOf(0L, 1L * 1024 * 1024, 2L * 1024 * 1024, 5L * 1024 * 1024, 10L * 1024 * 1024)
    // 临时状态提升到弹窗外，供 text 与 confirmButton 共同访问；每次打开弹窗时重置
    var tempSelected by remember { mutableStateOf<Long?>(null) }
    var customKb by remember { mutableStateOf("") }
    LaunchedEffect(showSpeedDialog) {
        if (showSpeedDialog) {
            tempSelected = null
            // 自定义输入：打开时若当前是自定义档位，带出原值（重新打开保留）
            customKb = if (speedLimitBps > 0 && speedLimitBps !in speedPresets) (speedLimitBps / 1024).toString() else ""
        }
    }
    // 自定义选中态：显式识别「-1=自定义」哨兵；未操作时按当前值是否为自定义档位判断
    val isCustom = when {
        tempSelected == -1L -> true
        tempSelected == null -> speedLimitBps > 0 && speedLimitBps !in speedPresets
        else -> false
    }
    FadeAlertDialog(
        visible = showSpeedDialog,
        onDismissRequest = { showSpeedDialog = false },
        title = { Text("下载速度限制") },
        text = {
            val effective = tempSelected ?: speedLimitBps
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                speedPresets.forEach { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !isCustom && effective == v,
                            onClick = { tempSelected = v }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (v == 0L) "不限速" else speedLimitText(v),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                // 自定义档位：点击单选即可选中（进入自定义模式）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isCustom,
                        onClick = {
                            tempSelected = -1L
                            // 当前已是自定义值时带出原值，便于修改
                            if (speedLimitBps > 0 && speedLimitBps !in speedPresets && customKb.isBlank()) {
                                customKb = (speedLimitBps / 1024).toString()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = customKb,
                        onValueChange = {
                            customKb = it.filter(Char::isDigit).take(6)
                            // 输入即视为选择自定义
                            tempSelected = -1L
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("自定义 KB/s") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 以当前选中项为准：选自定义则应用输入；选预设则应用预设值
                    if (isCustom) {
                        val kb = customKb.toLongOrNull()?.coerceAtLeast(1L)
                        if (kb != null) {
                            speedLimitBps = kb * 1024
                            settingsRepo.downloadSpeedLimit = kb * 1024
                        }
                        // 自定义输入为空：保持原值
                    } else if (tempSelected != null) {
                        val v = tempSelected ?: speedLimitBps
                        speedLimitBps = v
                        settingsRepo.downloadSpeedLimit = v
                    }
                    // 未做任何选择：保持当前值
                    showSpeedDialog = false
                }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = { showSpeedDialog = false }) { Text("取消") }
        }
    )

    // 失败自动重试次数
    FadeAlertDialog(
        visible = showRetryDialog,
        onDismissRequest = { showRetryDialog = false },
        title = { Text("失败自动重试") },
        text = {
            val options = listOf(0, 1, 2, 3, 5, 8, 10)
            Column {
                options.forEach { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = retryCount == v,
                            onClick = {
                                retryCount = v
                                settingsRepo.downloadRetryCount = v
                                showRetryDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (v == 0) "不自动重试" else "失败后自动重试 $v 次",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showRetryDialog = false }) { Text("取消") }
        }
    )

    // 关闭窗口行为选择
    FadeAlertDialog(
        visible = showCloseBehaviorDialog,
        onDismissRequest = { showCloseBehaviorDialog = false },
        title = { Text("关闭窗口时") },
        text = {
            val options = listOf(
                SettingsRepository.CLOSE_BEHAVIOR_ASK to "每次询问",
                SettingsRepository.CLOSE_BEHAVIOR_EXIT to "直接退出应用",
                SettingsRepository.CLOSE_BEHAVIOR_TRAY to "最小化到系统托盘"
            )
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = closeBehavior == value,
                            onClick = {
                                closeBehavior = value
                                settingsRepo.closeBehavior = value
                                showCloseBehaviorDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "提示：选择「直接退出」时，若有下载任务进行中会再次询问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { showCloseBehaviorDialog = false }) { Text("取消") }
        }
    )

}

/** 导出网盘认证弹窗：AES 加密密码 + 导出范围（仅已登录 / 全部绑定） */
@Composable
private fun ExportAuthDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String, onlyLoggedIn: Boolean) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var onlyLoggedIn by remember { mutableStateOf(true) }
    FadeAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = { Text("导出网盘认证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "设置至少 8 位密码对认证文件进行 AES 加密。密码请务必牢记，丢失无法找回。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("加密密码（至少 8 位）") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                Text(
                    text = "导出范围",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = onlyLoggedIn,
                        onClick = { onlyLoggedIn = true }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("仅导出当前已登录的网盘", style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !onlyLoggedIn,
                        onClick = { onlyLoggedIn = false }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出全部绑定的网盘", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password, onlyLoggedIn) },
                enabled = password.length >= 8
            ) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 导入加密备份弹窗：输入解密密码 */
@Composable
private fun ImportAuthDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    FadeAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = { Text("导入网盘认证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "该备份文件已加密，请输入导出时设置的密码进行解密。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("解密密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank()
            ) { Text("解密并导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 操作处理中弹窗：转圈加载 + 提示文案，禁止关闭（防止中途取消导致导入/导出状态不一致） */
@Composable
private fun OperationLoadingDialog(visible: Boolean, message: String) {
    FadeAlertDialog(
        visible = visible,
        onDismissRequest = {},
        title = { Text(message) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    /** 长按回调（隐藏菜单等）；null 时不启用长按 */
    onLongClick: (() -> Unit)? = null,
    /** 自定义尾部内容（如「恢复默认」操作）；null 时显示默认 ChevronRight */
    trailing: @Composable (() -> Unit)? = null
) {
    val shape = MaterialTheme.shapes.large
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** 线程数单选行（用于弹窗两列布局，每行占半宽） */
@Composable
private fun RadioThreadRow(
    value: Int,
    threads: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = threads == value,
            onClick = { onSelect(value) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$value 线程",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/** 速度限制展示文案：0=不限速；>=1MB/s 显示 MB/s，否则 KB/s */
private fun speedLimitText(bps: Long): String {
    if (bps <= 0) return "不限速"
    return if (bps >= 1024 * 1024) {
        val mb = bps / (1024.0 * 1024.0)
        if (mb >= 10) String.format("%.0f MB/s", mb) else String.format("%.1f MB/s", mb)
    } else {
        "${bps / 1024} KB/s"
    }
}
