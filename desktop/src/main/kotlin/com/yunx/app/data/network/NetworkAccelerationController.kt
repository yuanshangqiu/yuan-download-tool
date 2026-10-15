package com.yunx.app.data.network

import com.yunx.app.AppContext
import com.yunx.app.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

data class NetworkAdapterOption(
    val id: String,
    val name: String,
    val displayName: String,
    val sourceIPv4: String,
    val interfaceIndex: Int,
    val selected: Boolean,
    val virtual: Boolean,
    val downloadBps: Double = 0.0,
    val uploadBps: Double = 0.0
)

data class NetworkAccelerationSnapshot(
    val adapters: List<NetworkAdapterOption> = emptyList(),
    val isRunning: Boolean = false,
    val isBusy: Boolean = false,
    val engineVersion: String = "",
    val schedulingStrategy: String = "",
    val schedulingState: String = "",
    val totalDownloadBps: Double = 0.0,
    val totalUploadBps: Double = 0.0,
    val error: String? = null
)

/** Owns separate HypoMux sessions for YunX multi-network aggregation and platform proxying. */
object NetworkAccelerationController {
    private const val TAG = "YunX-HypoMux"
    private const val ENGINE_VERSION = "2.7.0"
    private const val SELECTED_ADAPTERS_KEY = "network_accel_selected_adapters_v1"

    private val preferences = AppContext.settingsPrefs
    private val operationMutex = Mutex()
    private val platformProxyMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(NetworkAccelerationSnapshot())
    val state: StateFlow<NetworkAccelerationSnapshot> = mutableState.asStateFlow()

    @Volatile
    private var engineSession: HypoMuxSession? = null
    @Volatile
    private var platformProxySession: HypoMuxSession? = null
    private var telemetryJob: Job? = null

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread({
                HttpClients.setAggregationProxyEnabled(false)
                engineSession?.shutdown()
                platformProxySession?.shutdown()
            }, "YunX-HypoMux-shutdown")
        )
    }

    suspend fun refreshAdapters() = withContext(Dispatchers.IO) {
        runCatching { scanAdapters() }
            .onSuccess { scanned ->
                val previous = mutableState.value.adapters.associateBy { it.id }
                val saved = readSelectedIds()
                val selectedWereSaved = preferences.get(SELECTED_ADAPTERS_KEY, null) != null
                val updated = scanned.map { adapter ->
                    val old = previous[adapter.id]
                    adapter.copy(
                        selected = old?.selected ?: if (selectedWereSaved) {
                            adapter.id in saved
                        } else {
                            !adapter.virtual
                        },
                        downloadBps = old?.downloadBps ?: 0.0,
                        uploadBps = old?.uploadBps ?: 0.0
                    )
                }
                mutableState.update { it.copy(adapters = updated, error = null) }
                if (!selectedWereSaved && updated.isNotEmpty()) saveSelectedIds(updated)
            }
            .onFailure { failure ->
                mutableState.update { it.copy(error = failure.message ?: "读取网络适配器失败") }
            }
    }

    fun setAdapterSelected(id: String, selected: Boolean) {
        val updated = mutableState.value.adapters.map {
            if (it.id == id) it.copy(selected = selected) else it
        }
        mutableState.update { it.copy(adapters = updated, error = null) }
        saveSelectedIds(updated)
    }

    /** 启动独立的平台代理实例：固定只绑定一张网卡，使用不同于多网卡聚合的端口。 */
    suspend fun startPlatformProxy(steamCdnEnabled: Boolean = true) = withContext(Dispatchers.IO) {
        platformProxyMutex.withLock {
            if (platformProxySession?.isAlive == true) return@withLock
            check(System.getProperty("os.name", "").contains("Windows", ignoreCase = true)) {
                "平台加速目前仅支持 Windows 10/11。"
            }
            val adapter = mutableState.value.adapters.firstOrNull { it.selected && !it.virtual }
                ?: mutableState.value.adapters.firstOrNull { !it.virtual }
                ?: error("没有找到可用的网络适配器。")

            var launched: HypoMuxSession? = null
            try {
                launched = HypoMuxSession.launch()
                val hello = launched.request("engine.hello")
                check(hello.optInt("protocol_version") == 1) { "HypoMux 引擎协议版本不兼容。" }
                val strategy = hello.optJSONArray("scheduling_strategies").strings()
                    .firstOrNull { it == "adaptive-throughput" } ?: "weighted"
                val adapterParams = JSONArray().put(
                    JSONObject()
                        .put("name", adapter.name)
                        .put("source_ip", adapter.sourceIPv4)
                        .put("if_index", adapter.interfaceIndex)
                        .put("weight", 1)
                )
                val dns = JSONObject()
                    .put("policy", "auto")
                    .put("legacy_servers", JSONArray())
                    .put("cache_ttl_ms", 180000)
                    .put("query_timeout_ms", 4000)
                val started = launched.request(
                    "engine.start",
                    JSONObject()
                        .put("mode", "proxy")
                        .put("steam_cdn_enabled", steamCdnEnabled)
                        .put("listen_host", "127.0.0.1")
                        .put("socks_port", PLATFORM_SOCKS_PORT)
                        .put("http_port", PLATFORM_HTTP_PORT)
                        .put("strategy", strategy)
                        .put("weighted", strategy == "weighted")
                        .put("connect_timeout_ms", 6000)
                        .put("dns", dns)
                        .put("adapters", adapterParams)
                )
                val engineState = started.optJSONObject("state")?.optString("state").orEmpty()
                check(engineState == "running") { "平台代理未能启动（状态：${engineState.ifBlank { "未知" }}）。" }
                val endpoint = started.optJSONObject("endpoints")?.optString("http").orEmpty()
                check(endpoint == "127.0.0.1:$PLATFORM_HTTP_PORT") {
                    "HypoMux 返回了意外的平台代理地址：$endpoint"
                }
                platformProxySession = launched
            } catch (failure: Throwable) {
                runCatching { launched?.shutdown() }
                throw failure
            }
        }
    }

    /** 停止独立的平台代理，不影响「网络加速」中的多网卡聚合。 */
    suspend fun stopPlatformProxy() = withContext(Dispatchers.IO) {
        platformProxyMutex.withLock {
            val active = platformProxySession
            platformProxySession = null
            runCatching { active?.shutdown() }
        }
    }

    /**
     * 启动本地 HypoMux 代理。
     * 网络加速页的多网卡代理要求至少两张网卡。平台加速由
     * [startPlatformProxy] 使用另一实例和单张网卡。
     */
    suspend fun start(steamCdnEnabled: Boolean = false) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (mutableState.value.isRunning) return@withLock
            mutableState.update { it.copy(isBusy = true, error = null) }
            var launched: HypoMuxSession? = null
            try {
                check(System.getProperty("os.name", "").contains("Windows", ignoreCase = true)) {
                    "HypoMux 多网卡加速目前仅支持 Windows 10/11。"
                }
                val selected = mutableState.value.adapters.filter { it.selected }
                require(selected.size >= 2) { "请至少选择两张可用网卡，再启动多网卡聚合。" }

                launched = HypoMuxSession.launch()
                engineSession = launched
                val hello = launched.request("engine.hello")
                check(hello.optInt("protocol_version") == 1) { "HypoMux 引擎协议版本不兼容。" }
                val strategies = hello.optJSONArray("scheduling_strategies").strings()
                val strategy = if ("adaptive-throughput" in strategies) "adaptive-throughput" else "weighted"

                val adapterParams = JSONArray()
                selected.forEach { adapter ->
                    adapterParams.put(
                        JSONObject()
                            .put("name", adapter.name)
                            .put("source_ip", adapter.sourceIPv4)
                            .put("if_index", adapter.interfaceIndex)
                            .put("weight", 1)
                    )
                }
                val dns = JSONObject()
                    .put("policy", "auto")
                    .put("legacy_servers", JSONArray())
                    .put("cache_ttl_ms", 180000)
                    .put("query_timeout_ms", 4000)
                val params = JSONObject()
                    .put("mode", "proxy")
                    .put("steam_cdn_enabled", steamCdnEnabled)
                    .put("listen_host", "127.0.0.1")
                    .put("socks_port", 10800)
                    .put("http_port", 10801)
                    .put("strategy", strategy)
                    .put("weighted", strategy == "weighted")
                    .put("connect_timeout_ms", 6000)
                    .put("dns", dns)
                    .put("adapters", adapterParams)
                val started = launched.request("engine.start", params)
                val engineState = started.optJSONObject("state")?.optString("state").orEmpty()
                check(engineState == "running") { "HypoMux 未能启动（状态：${engineState.ifBlank { "未知" }}）。" }
                val httpEndpoint = started.optJSONObject("endpoints")?.optString("http").orEmpty()
                check(httpEndpoint == "127.0.0.1:10801") { "HypoMux 返回了意外的本地代理地址：$httpEndpoint" }

                HttpClients.setAggregationProxyEnabled(true)
                mutableState.update {
                    it.copy(
                        isRunning = true,
                        isBusy = false,
                        engineVersion = hello.optString("engine_version", ENGINE_VERSION),
                        schedulingStrategy = strategy,
                        schedulingState = "warming-up",
                        error = null
                    )
                }
                startTelemetry(launched)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                HttpClients.setAggregationProxyEnabled(false)
                runCatching { launched?.shutdown() }
                if (engineSession === launched) engineSession = null
                mutableState.update { it.copy(isRunning = false, isBusy = false) }
                throw cancelled
            } catch (failure: Throwable) {
                HttpClients.setAggregationProxyEnabled(false)
                runCatching { launched?.shutdown() }
                if (engineSession === launched) engineSession = null
                mutableState.update { it.copy(isRunning = false, isBusy = false, error = failure.message ?: "HypoMux 启动失败") }
                Log.w(TAG, "启动 HypoMux 代理失败：${failure.message}")
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            telemetryJob?.cancel()
            mutableState.update { it.copy(isBusy = true, error = null) }
            val active = engineSession
            var failureMessage: String? = null
            try {
                active?.request("engine.stop")
            } catch (failure: Throwable) {
                failureMessage = failure.message
            } finally {
                runCatching { active?.shutdown() }
                if (engineSession === active) engineSession = null
                HttpClients.setAggregationProxyEnabled(false)
                mutableState.update {
                    it.copy(
                        isRunning = false,
                        isBusy = false,
                        schedulingState = "",
                        totalDownloadBps = 0.0,
                        totalUploadBps = 0.0,
                        adapters = it.adapters.map { adapter -> adapter.copy(downloadBps = 0.0, uploadBps = 0.0) },
                        error = failureMessage
                    )
                }
            }
        }
    }

    fun shutdownAsync() {
        scope.launch { runCatching { stop() } }
    }

    private fun startTelemetry(session: HypoMuxSession) {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            var previousSampleAt = System.nanoTime()
            var previousDown = emptyMap<String, Long>()
            var previousUp = emptyMap<String, Long>()
            while (mutableState.value.isRunning) {
                try {
                    val telemetry = session.request("engine.telemetry")
                    val rows = telemetry.optJSONArray("adapters").objects()
                    val sampledAt = System.nanoTime()
                    val elapsedSeconds = ((sampledAt - previousSampleAt).coerceAtLeast(1L)).toDouble() / 1_000_000_000.0
                    val currentDown = rows.associate { it.optString("name") to it.optLong("bytes_down", 0L) }
                    val currentUp = rows.associate { it.optString("name") to it.optLong("bytes_up", 0L) }
                    val downRates = currentDown.mapValues { (name, value) ->
                        ((value - (previousDown[name] ?: value)).coerceAtLeast(0L) / elapsedSeconds)
                    }
                    val upRates = currentUp.mapValues { (name, value) ->
                        ((value - (previousUp[name] ?: value)).coerceAtLeast(0L) / elapsedSeconds)
                    }
                    previousDown = currentDown
                    previousUp = currentUp
                    previousSampleAt = sampledAt
                    val scheduling = telemetry.optJSONObject("scheduling")
                    mutableState.update { current ->
                        if (current.isBusy || engineSession !== session) return@update current
                        val links = current.adapters.map { adapter ->
                            adapter.copy(
                                downloadBps = downRates[adapter.name] ?: 0.0,
                                uploadBps = upRates[adapter.name] ?: 0.0
                            )
                        }
                        current.copy(
                            adapters = links,
                            totalDownloadBps = links.sumOf { it.downloadBps },
                            totalUploadBps = links.sumOf { it.uploadBps },
                            schedulingStrategy = scheduling?.optString("effective_tcp_strategy")
                                ?.takeIf { it.isNotBlank() } ?: current.schedulingStrategy,
                            schedulingState = scheduling?.optString("state")
                                ?.takeIf { it.isNotBlank() } ?: current.schedulingState
                        )
                    }
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    if (engineSession !== session || mutableState.value.isBusy) return@launch
                    HttpClients.setAggregationProxyEnabled(false)
                    engineSession = null
                    session.abort()
                    mutableState.update {
                        it.copy(
                            isRunning = false,
                            isBusy = false,
                            schedulingState = "",
                            error = "HypoMux 连接中断，YunX 已恢复原有网络设置。${failure.message.orEmpty()}"
                        )
                    }
                    Log.w(TAG, "聚合引擎连接中断：${failure.message}")
                    return@launch
                }
                delay(1000)
            }
        }
    }

    private fun scanAdapters(): List<NetworkAdapterOption> {
        val enumeration = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        return enumeration.toList().mapNotNull { networkInterface ->
            val name = networkInterface.name?.trim().orEmpty()
            if (name.isBlank() || name.contains("hypomux-tun", ignoreCase = true)) return@mapNotNull null
            if (!runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)) return@mapNotNull null
            val address = networkInterface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { ip ->
                    !ip.isAnyLocalAddress && !ip.isLoopbackAddress && !ip.isLinkLocalAddress && !ip.isMulticastAddress
                } ?: return@mapNotNull null
            val index = networkInterface.index
            if (index <= 0) return@mapNotNull null
            val display = networkInterface.displayName?.trim().takeUnless { it.isNullOrBlank() } ?: name
            val virtual = runCatching { networkInterface.isVirtual }.getOrDefault(false) ||
                VIRTUAL_NAME_HINTS.any { name.contains(it, ignoreCase = true) || display.contains(it, ignoreCase = true) }
            NetworkAdapterOption(
                id = "$name#$index",
                name = name,
                displayName = display,
                sourceIPv4 = address.hostAddress,
                interfaceIndex = index,
                selected = false,
                virtual = virtual
            )
        }.sortedWith(compareBy<NetworkAdapterOption>({ it.virtual }, { it.displayName.lowercase() }))
    }

    private fun readSelectedIds(): Set<String> {
        val raw = preferences.get(SELECTED_ADAPTERS_KEY, null) ?: return emptySet()
        return runCatching { JSONArray(raw).strings().toSet() }.getOrDefault(emptySet())
    }

    private fun saveSelectedIds(adapters: List<NetworkAdapterOption>) {
        runCatching {
            preferences.put(SELECTED_ADAPTERS_KEY, JSONArray().apply {
                adapters.filter { it.selected }.forEach { put(it.id) }
            }.toString())
        }.onFailure { Log.w(TAG, "保存网卡选择失败：${it.message}") }
    }

    private class HypoMuxSession private constructor(private val process: Process) {
        val isAlive: Boolean get() = process.isAlive
        private val requests = AtomicLong()
        private val lines = LinkedBlockingQueue<String>()
        private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)

        init {
            thread(isDaemon = true, name = "YunX-HypoMux-stdout") {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { stream ->
                    stream.forEach { lines.offer(it) }
                }
                lines.offer(OUTPUT_CLOSED)
            }
            thread(isDaemon = true, name = "YunX-HypoMux-stderr") {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { stream ->
                    stream.forEach { Log.d(TAG, it) }
                }
            }
        }

        @Synchronized
        fun request(method: String, params: JSONObject? = null): JSONObject {
            check(process.isAlive) { "HypoMux 引擎进程已退出。" }
            val id = "yunx-${requests.incrementAndGet()}"
            val request = JSONObject()
                .put("protocol", 1)
                .put("id", id)
                .put("method", method)
            if (params != null) request.put("params", params)
            writer.write(request.toString())
            writer.newLine()
            writer.flush()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw IOException("等待 HypoMux $method 响应超时。")
                val line = lines.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: throw IOException("等待 HypoMux $method 响应超时。")
                if (line == OUTPUT_CLOSED) throw IOException("HypoMux 引擎已关闭输出流。")
                val message = JSONObject(line)
                if (message.has("event")) continue
                if (message.optString("id") != id) continue
                val error = message.optJSONObject("error")
                if (error != null) {
                    val details = error.optJSONObject("details")?.optString("message").orEmpty()
                    val messageText = error.optString("message", "引擎请求失败")
                    throw IOException(listOf(error.optString("code"), messageText, details).filter { it.isNotBlank() }.distinct().joinToString("："))
                }
                return message.optJSONObject("result") ?: JSONObject()
            }
        }

        fun shutdown() {
            if (!process.isAlive) return
            runCatching { request("engine.stop") }
            runCatching { request("host.shutdown") }
            runCatching { writer.close() }
            if (!runCatching { process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)) {
                process.destroy()
                if (!runCatching { process.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)) process.destroyForcibly()
            }
        }

        fun abort() {
            runCatching { writer.close() }
            if (process.isAlive) process.destroyForcibly()
        }

        companion object {
            fun launch(): HypoMuxSession {
                val resource = NetworkAccelerationController::class.java.getResourceAsStream("/hypomux/hypomux-engine.exe")
                    ?: error("找不到内置 HypoMux 引擎，请重新构建 YunX。")
                val directory = AppContext.cacheDir.resolve("hypomux")
                check(directory.isDirectory || directory.mkdirs()) { "无法创建 HypoMux 引擎目录：${directory.absolutePath}" }
                val executable = File(directory, "hypomux-engine-$ENGINE_VERSION.exe")
                if (!executable.isFile || executable.length() == 0L) {
                    val temporary = File(directory, "${executable.name}.tmp")
                    resource.use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
                    if (!temporary.renameTo(executable)) {
                        temporary.copyTo(executable, overwrite = true)
                        temporary.delete()
                    }
                } else {
                    resource.close()
                }
                val process = ProcessBuilder(executable.absolutePath)
                    .directory(directory)
                    .start()
                return HypoMuxSession(process)
            }
        }
    }

    private fun JSONArray?.strings(): List<String> = this?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    }.orEmpty()

    private fun JSONArray?.objects(): List<JSONObject> = this?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
    }.orEmpty()

    private val VIRTUAL_NAME_HINTS = listOf("virtual", "vmware", "wintun", "tun", "vpn", "loopback", "vEthernet", "hyper-v", "docker", "wsl")
    private const val OUTPUT_CLOSED = "\u0000HYPOMUX_OUTPUT_CLOSED\u0000"
    private const val PLATFORM_HTTP_PORT = 10811
    private const val PLATFORM_SOCKS_PORT = 10810
}
