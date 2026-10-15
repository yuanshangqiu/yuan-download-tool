package com.yunx.app.data.network

import com.yunx.app.AppContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** SteamTools 平台加速支持的服务目录。 */
data class SteamToolsPlatformService(
    val id: String,
    val title: String,
    val description: String,
    val domains: List<String>
)

/**
 * SteamTools 加速模块内置的服务分组。
 *
 * 这些域名用于平台加速配置和界面展示；启用平台加速后，选中的服务通过本机
 * HypoMux HTTP 代理（127.0.0.1:10811）访问。PAC 规则只把选择的平台域名送入代理。
 * Steam CDN 优化由 HypoMux 引擎
 * 单独打开，其他平台沿用 SteamTools 的系统代理入口。该路径只需要一张可用
 * 网卡；「网络加速」页的多网卡聚合是可选能力，不是平台加速的前置条件。
 */
object SteamToolsPlatformCatalog {
    val services: List<SteamToolsPlatformService> = listOf(
        SteamToolsPlatformService(
            id = "steam",
            title = "Steam 服务",
            description = "商店、社区、登录与下载 CDN",
            domains = listOf("steampowered.com", "steamcommunity.com", "steamstatic.com", "steamcontent.com", "steamcdn-a.akamaihd.net")
        ),
        SteamToolsPlatformService(
            id = "twitch",
            title = "Twitch",
            description = "直播、视频与静态资源",
            domains = listOf("twitch.tv", "jtvnw.net", "ttvnw.net", "twitchcdn.net")
        ),
        SteamToolsPlatformService(
            id = "origin",
            title = "Origin / EA",
            description = "EA 应用、商店与游戏服务",
            domains = listOf("origin.com", "ea.com", "akamaihd.net")
        ),
        SteamToolsPlatformService(
            id = "uplay",
            title = "Uplay / Ubisoft",
            description = "Ubisoft Connect 与 Uplay 服务",
            domains = listOf("ubisoft.com", "uplay.com", "ubi.com")
        ),
        SteamToolsPlatformService(
            id = "cdn",
            title = "公共 CDN",
            description = "常用公共脚本、字体和软件 CDN",
            domains = listOf("cdnjs.cloudflare.com", "cdn.jsdelivr.net", "unpkg.com", "fastly.net")
        ),
        SteamToolsPlatformService(
            id = "spotify",
            title = "Spotify",
            description = "音乐服务与媒体 CDN",
            domains = listOf("spotify.com", "scdn.co", "spotifycdn.com")
        ),
        SteamToolsPlatformService(
            id = "captcha",
            title = "国外验证码平台",
            description = "常见登录验证码与人机验证服务",
            domains = listOf("google.com", "recaptcha.net", "hcaptcha.com", "geetest.com", "funcaptcha.com")
        ),
        SteamToolsPlatformService(
            id = "github",
            title = "GitHub",
            description = "代码托管、Release 与原始文件",
            domains = listOf("github.com", "githubusercontent.com", "githubassets.com", "github.io")
        ),
        SteamToolsPlatformService(
            id = "roblox",
            title = "Roblox",
            description = "Roblox 客户端、资源与 CDN",
            domains = listOf("roblox.com", "rbxcdn.com", "rbxlcdn.com")
        ),
        SteamToolsPlatformService(
            id = "modio",
            title = "mod.io",
            description = "游戏 Mod 分发与 API",
            domains = listOf("mod.io", "modassets.io", "api.mod.io")
        ),
        SteamToolsPlatformService(
            id = "nexusmods",
            title = "Nexus Mods",
            description = "Mod 页面、下载和镜像",
            domains = listOf("nexusmods.com", "nexus-cdn.com")
        ),
        SteamToolsPlatformService(
            id = "cloud-drives",
            title = "网盘服务",
            description = "常见国外网盘和文件传输服务",
            domains = listOf("dropbox.com", "dropboxusercontent.com", "drive.google.com", "mega.nz", "mediafire.com", "onedrive.live.com")
        )
    )
}

enum class SteamToolsProxyMode(
    val title: String,
    val description: String
) {
    WINDOWS_HTTP_PROXY(
        title = "Windows PAC 规则",
        description = "按所选平台域名路由到本地加速代理"
    )
}

data class SteamToolsAccelerationSnapshot(
    val enabled: Boolean = false,
    val mode: SteamToolsProxyMode = SteamToolsProxyMode.WINDOWS_HTTP_PROXY,
    val systemProxyApplied: Boolean = false,
    val isBusy: Boolean = false,
    val selectedServiceIds: Set<String> = SteamToolsPlatformCatalog.services.map { it.id }.toSet(),
    val error: String? = null
)

/**
 * SteamTools 平台加速的桌面适配层。
 *
 * YunX 的「网络加速」只管理 YunX 自己的多网卡请求；这个控制器只在「平台加速」
 * 分区启用时接管 Windows 用户级 PAC 配置，并在退出或关闭时完整恢复原设置。
 */
object SteamToolsAccelerationController {
    const val HTTP_PROXY_PORT = 10811

    private const val SELECTED_SERVICES_KEY = "steamtools_platform_services_v1"
    private val preferences = AppContext.settingsPrefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(
        SteamToolsAccelerationSnapshot(selectedServiceIds = readSelectedServiceIds())
    )
    val state: StateFlow<SteamToolsAccelerationSnapshot> = mutableState.asStateFlow()

    private var previousSystemProxy: SystemProxy.Raw? = null

    fun setServiceSelected(id: String, selected: Boolean) {
        if (SteamToolsPlatformCatalog.services.none { it.id == id }) return
        val current = mutableState.value.selectedServiceIds.toMutableSet()
        if (selected) current += id else current -= id
        saveSelectedServiceIds(current)
        mutableState.value = mutableState.value.copy(selectedServiceIds = current)
    }

    fun selectAllServices() {
        val all = SteamToolsPlatformCatalog.services.map { it.id }.toSet()
        saveSelectedServiceIds(all)
        mutableState.value = mutableState.value.copy(selectedServiceIds = all)
    }

    fun clearServices() {
        saveSelectedServiceIds(emptySet())
        mutableState.value = mutableState.value.copy(selectedServiceIds = emptySet())
    }

    suspend fun enable(mode: SteamToolsProxyMode = SteamToolsProxyMode.WINDOWS_HTTP_PROXY) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (mutableState.value.selectedServiceIds.isEmpty()) {
                mutableState.value = mutableState.value.copy(error = "请至少选择一个平台服务。")
                return@withLock
            }
            if (mutableState.value.enabled && mutableState.value.mode == mode) return@withLock

            disableLocked(stopOwnedEngine = true)
            mutableState.value = mutableState.value.copy(isBusy = true, error = null)
            var launchedPlatformProxy = false
            var pacServerStarted = false
            try {
                // 平台代理使用独立 HypoMux 进程、独立端口和单张网卡。
                // 不启动、不读取也不改变「网络加速」的多网卡聚合状态。
                NetworkAccelerationController.refreshAdapters()
                NetworkAccelerationController.startPlatformProxy(steamCdnEnabled = true)
                launchedPlatformProxy = true
                val selectedDomains = SteamToolsPlatformCatalog.services
                    .filter { it.id in mutableState.value.selectedServiceIds }
                    .flatMap { it.domains }
                    .distinct()
                PlatformPacServer.start(selectedDomains)
                pacServerStarted = true

                previousSystemProxy = SystemProxy.applyLoopbackPac(PlatformPacServer.PORT)
                check(previousSystemProxy != null) {
                    "无法接管 Windows 用户级 PAC 设置（需要 Windows 10/11）。"
                }
                mutableState.value = mutableState.value.copy(
                    enabled = true,
                    mode = mode,
                    systemProxyApplied = true,
                    isBusy = false,
                    error = null
                )
            } catch (failure: Throwable) {
                restoreSystemProxyLocked()
                if (pacServerStarted) PlatformPacServer.stop()
                if (launchedPlatformProxy) runCatching { NetworkAccelerationController.stopPlatformProxy() }
                mutableState.value = mutableState.value.copy(
                    enabled = false,
                    systemProxyApplied = false,
                    isBusy = false,
                    error = failure.message ?: "SteamTools 平台加速启动失败"
                )
            }
        }
    }

    suspend fun disable() = withContext(Dispatchers.IO) {
        mutex.withLock { disableLocked(stopOwnedEngine = true) }
    }

    /** 应用退出时调用，恢复 Windows 代理并释放平台加速拥有的引擎。 */
    fun shutdownAsync() {
        restoreSystemProxyNow()
        scope.launch { runCatching { disable() } }
    }

    private suspend fun disableLocked(stopOwnedEngine: Boolean) {
        restoreSystemProxyLocked()
        PlatformPacServer.stop()
        val selected = mutableState.value.selectedServiceIds
        mutableState.value = SteamToolsAccelerationSnapshot(selectedServiceIds = selected)
        if (stopOwnedEngine) runCatching { NetworkAccelerationController.stopPlatformProxy() }
    }

    private fun restoreSystemProxyLocked() {
        val snapshot = previousSystemProxy ?: return
        SystemProxy.restore(snapshot)
        previousSystemProxy = null
    }

    private fun restoreSystemProxyNow() {
        synchronized(this) { restoreSystemProxyLocked() }
    }

    private fun readSelectedServiceIds(): Set<String> {
        val raw = preferences.get(SELECTED_SERVICES_KEY, null)
            ?: return SteamToolsPlatformCatalog.services.map { it.id }.toSet()
        return raw.split(',')
            .map(String::trim)
            .filter { id -> SteamToolsPlatformCatalog.services.any { it.id == id } }
            .toSet()
    }

    private fun saveSelectedServiceIds(ids: Set<String>) {
        runCatching { preferences.put(SELECTED_SERVICES_KEY, ids.joinToString(",")) }
    }
}
