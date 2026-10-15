package com.yunx.app.data.hydra

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.yunx.app.AppContext
import com.yunx.app.data.network.NetworkAccelerationController
import com.yunx.app.data.network.SystemProxy
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Embedded HYDRA 1.1.0 transfer engine, accessed through its stable C ABI. */
object HydraEngine {
    private const val TAG = "YunX-Hydra"
    private const val PREFIX = "hydra:"
    private const val ABI_VERSION = 1
    private const val JOB_PAUSED = 4
    private const val JOB_COMPLETED = 6
    private const val JOB_FAILED = 7
    private const val JOB_CANCELLED = 8
    private const val ERR_INVALID_STATE = 3
    private const val EVENT_COMPLETED = 11
    private const val CANCEL_REMOVE_PARTIAL = 1
    private const val PROXY_HTTP = 1
    private const val CHECKSUM_NONE = 0

    private val lock = Any()
    private val currentProcessJobs = ConcurrentHashMap.newKeySet<Long>()
    private val restoredJobs = ConcurrentHashMap.newKeySet<Long>()

    @Volatile private var library: NativeLibrary? = null
    @Volatile private var engine: Pointer? = null
    @Volatile private var started = false
    @Volatile private var engineConfigSignature: String? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ shutdownQuietly() }, "YunX-Hydra-shutdown"))
    }

    data class Snapshot(
        val id: Long,
        val state: Int,
        val downloaded: Long,
        val total: Long,
        val speed: Long,
        val connections: Int,
        val sources: Int,
        val error: String,
        val outputPath: String
    )

    fun taskKey(id: Long): String = PREFIX + id

    fun parseTaskKey(key: String): Long? =
        if (key.startsWith(PREFIX)) key.removePrefix(PREFIX).toLongOrNull()?.takeIf { it > 0 } else null

    fun wasRestoredFromPreviousRun(id: Long): Boolean = id in restoredJobs && id !in currentProcessJobs

    fun isStarted(): Boolean = started && engine != null

    fun start(maxJobs: Int, speedLimitBps: Long) = synchronized(lock) {
        val handle = ensureEngineLocked()
        applyEngineSettingsLocked(handle, maxJobs, speedLimitBps)
        if (!started) {
            val restored = Memory(Native.POINTER_SIZE.toLong()).also { it.clear() }
            checkOk("hydra_engine_restore", callInt("hydra_engine_restore", handle, restored))
            restoredJobs.clear()
            listJobIds(handle).forEach(restoredJobs::add)
            started = true
            Log.i(TAG, "Hydra engine started; restored ${restoredJobs.size} jobs")
        }
        Log.i(TAG, "Hydra FFI ${nativeEngineVersion()} is ready")
    }

    fun applyRuntimeConfig(maxJobs: Int, speedLimitBps: Long) = synchronized(lock) {
        val handle = ensureEngineLocked()
        if (!started) start(maxJobs, speedLimitBps)
        applyEngineSettingsLocked(handle, maxJobs, speedLimitBps)
    }

    fun createTask(
        urls: List<String>,
        outputFile: File,
        headers: Map<String, String>,
        maxConnections: Int,
        maxRetries: Int,
        speedLimitBps: Long
    ): Long = synchronized(lock) {
        require(urls.isNotEmpty()) { "Hydra requires at least one download URL" }
        val handle = ensureEngineLocked()
        if (!started) start(maxJobs = 4, speedLimitBps = 0)

        val urlMemories = urls.map(::utf8)
        val urlArray = Memory((Native.POINTER_SIZE * urls.size).toLong())
        urlMemories.forEachIndexed { index, memory -> urlArray.setPointer((index * Native.POINTER_SIZE).toLong(), memory) }

        val headerMemories = headers.entries.map { utf8(it.key) to utf8(it.value) }
        val headerStructs = if (headerMemories.isEmpty()) emptyArray() else {
            @Suppress("UNCHECKED_CAST")
            (HydraHeader().toArray(headerMemories.size) as Array<HydraHeader>).also { items ->
                items.forEachIndexed { index, header ->
                    header.name = headerMemories[index].first
                    header.value = headerMemories[index].second
                    header.write()
                }
            }
        }
        val path = utf8(outputFile.absolutePath)
        outputFile.parentFile?.mkdirs()
        val proxyValue = proxyConfig()
        val proxyHost = proxyValue?.let { utf8(it.host) }
        val proxy = proxyValue?.let {
            HydraProxy().also { value ->
                value.kind = it.kind
                value.port = it.port.toShort()
                value.host = proxyHost
                value.write()
            }
        }
        val config = HydraJobConfig()
        checkOk("hydra_job_config_init", callInt("hydra_job_config_init", config.pointer, config.size()))
        // The initializer writes the ABI size/version into native memory. JNA
        // does not update the Kotlin fields automatically, so read them back
        // before write() copies the remaining options into the struct.
        config.read()
        config.urls = urlArray
        config.urlCount = urls.size.toLong()
        config.outputPath = path
        config.headers = headerStructs.firstOrNull()?.pointer
        config.headerCount = headerStructs.size.toLong()
        config.proxy = proxy?.pointer
        config.maxConnections = maxConnections.coerceIn(1, 512)
        config.maxRetries = maxRetries.coerceIn(0, 20)
        config.maxBytesPerSecond = speedLimitBps.coerceAtLeast(0)
        config.resume = 1
        config.adaptive = 1
        config.autoStart = 0
        config.write()

        val outId = Memory(8)
        checkOk("hydra_job_create", callInt("hydra_job_create", handle, config.pointer, outId))
        val id = outId.getLong(0)
        require(id > 0) { "Hydra returned an invalid job id" }
        checkOk("hydra_job_start", callInt("hydra_job_start", handle, id))
        currentProcessJobs += id
        Log.i(TAG, "Created Hydra job $id with ${urls.size} source(s), maxConnections=${config.maxConnections}")
        id
    }

    fun snapshot(id: Long): Snapshot? = synchronized(lock) {
        val handle = engine ?: return null
        if (!started) return null
        val value = HydraJobSnapshot()
        val code = callInt("hydra_job_get_snapshot", handle, id, value.pointer)
        if (code != 0) return null
        value.read()
        Snapshot(
            id = value.id,
            state = value.state,
            downloaded = value.progress.bytesDownloaded,
            total = value.progress.totalBytes,
            speed = value.progress.bytesPerSecond,
            connections = value.progress.activeConnections,
            sources = value.progress.activeSources,
            error = value.errorMessage.text(),
            outputPath = value.outputPath.text()
        ).also { callVoid("hydra_job_snapshot_free", value.pointer) }
    }

    fun startTask(id: Long) = synchronized(lock) {
        val handle = ensureEngineLocked()
        if (!started) start(maxJobs = 4, speedLimitBps = 0)
        checkOk("hydra_job_start", callInt("hydra_job_start", handle, id))
        currentProcessJobs += id
    }

    fun pauseTask(id: Long) = synchronized(lock) {
        engine?.let { handle -> checkOk("hydra_job_pause", callInt("hydra_job_pause", handle, id)) }
    }

    fun discardTask(id: Long, removePartial: Boolean = true) {
        synchronized(lock) {
            val handle = engine ?: return
            val mode = if (removePartial) CANCEL_REMOVE_PARTIAL else 0
            val cancel = callInt("hydra_job_cancel", handle, id, mode)
            if (cancel != 0) Log.w(TAG, "Hydra cancel job=$id returned ${errorName(cancel)}")
            // Cancellation of an active transfer is asynchronous.  Wait briefly for
            // the terminal state before removing its durable record; otherwise a
            // user deleting a task could leave a detached Hydra job running.
            var remove = callInt("hydra_job_remove", handle, id)
            var attempts = 0
            while (remove == ERR_INVALID_STATE && attempts++ < 50) {
                Thread.sleep(100)
                remove = callInt("hydra_job_remove", handle, id)
            }
            if (remove != 0) Log.w(TAG, "Hydra remove job=$id returned ${errorName(remove)}")
            currentProcessJobs.remove(id)
            restoredJobs.remove(id)
        }
    }

    fun removeCompletedTask(id: Long) {
        synchronized(lock) {
            val handle = engine ?: return
            val result = callInt("hydra_job_remove", handle, id)
            if (result != 0) Log.w(TAG, "Hydra completed job cleanup returned ${errorName(result)}")
            currentProcessJobs.remove(id)
            restoredJobs.remove(id)
        }
    }

    fun eventNext(): HydraEvent? = synchronized(lock) {
        val handle = engine ?: return null
        if (!started) return null
        val event = HydraEvent()
        if (callInt("hydra_event_next", handle, event.pointer) != 0) return null
        event.read()
        event
    }

    fun isCompletedEvent(event: HydraEvent, id: Long): Boolean =
        event.kind == EVENT_COMPLETED && event.jobId == id

    fun shutdownQuietly() {
        synchronized(lock) {
            val handle = engine ?: return
            runCatching { callInt("hydra_engine_shutdown", handle, 5_000) }
                .onFailure { Log.w(TAG, "Hydra shutdown failed: ${it.message}") }
            runCatching { callVoid("hydra_engine_destroy", handle) }
                .onFailure { Log.w(TAG, "Hydra destroy failed: ${it.message}") }
            engine = null
            started = false
            library = null
        }
    }

    fun proxyConfig(): HydraProxyConfig? {
        if (NetworkAccelerationController.state.value.isRunning) {
            return HydraProxyConfig(PROXY_HTTP, "127.0.0.1", 10801)
        }
        val settings = SettingsRepository()
        return when (settings.proxyMode) {
            SettingsRepository.PROXY_MODE_MANUAL -> settings.proxyHost.trim()
                .takeIf(String::isNotEmpty)?.let { HydraProxyConfig(PROXY_HTTP, it, settings.proxyPort) }
            SettingsRepository.PROXY_MODE_SYSTEM -> when (val proxy = SystemProxy.inspect()) {
                is SystemProxy.Inspect.Proxy -> HydraProxyConfig(PROXY_HTTP, proxy.host, proxy.port)
                else -> null
            }
            else -> null
        }
    }

    private fun ensureEngineLocked(): Pointer {
        engine?.let { return it }
        val target = File(AppContext.cacheDir, "hydra-1.1.0/hydra.dll")
        if (!target.isFile) {
            target.parentFile?.mkdirs()
            val resource = HydraEngine::class.java.getResourceAsStream("/hydra/hydra.dll")
                ?: error("Bundled Hydra transfer library is missing")
            resource.use { input -> target.outputStream().use(input::copyTo) }
        }
        val lib = NativeLibrary.getInstance(target.absolutePath)
        library = lib
        val abi = lib.getFunction("hydra_ffi_abi_version").invokeInt(emptyArray())
        check(abi == ABI_VERSION) { "Hydra ABI mismatch: expected $ABI_VERSION, got $abi" }

        val config = HydraEngineConfig()
        checkOk("hydra_engine_config_init", callInt("hydra_engine_config_init", config.pointer, config.size()))
        // hydra_engine_config_init populates size/version in native memory;
        // synchronize those fields before JNA writes the configured struct.
        config.read()
        config.maxJobs = 4
        config.maxConnections = 512
        config.maxRetries = 3
        config.progressIntervalMs = 500
        config.statePath = utf8(File(AppContext.dataDir, "hydra-state.json").absolutePath)
        config.userAgent = utf8("YunX-Desktop/1.2.9 (Hydra 1.1.0)")
        config.write()
        val handle = lib.getFunction("hydra_engine_create").invokePointer(arrayOf(config.pointer))
            ?: error("Failed to create Hydra engine: ${lastError()}")
        engine = handle
        return handle
    }

    private fun applyEngineSettingsLocked(handle: Pointer, maxJobs: Int, speedLimitBps: Long) {
        val signature = "${maxJobs.coerceIn(1, 64)}|${speedLimitBps.coerceAtLeast(0)}"
        if (signature == engineConfigSignature) return
        checkOk("hydra_engine_set_max_jobs", callInt("hydra_engine_set_max_jobs", handle, maxJobs.coerceIn(1, 64)))
        checkOk(
            "hydra_engine_set_max_bytes_per_second",
            callInt("hydra_engine_set_max_bytes_per_second", handle, speedLimitBps.coerceAtLeast(0))
        )
        engineConfigSignature = signature
    }

    private fun listJobIds(handle: Pointer): List<Long> {
        val array = HydraJobIdArray()
        checkOk("hydra_engine_list_jobs", callInt("hydra_engine_list_jobs", handle, array.pointer))
        array.read()
        val result = if (array.items == null || array.length <= 0) emptyList() else {
            List(array.length.coerceAtMost(100_000).toInt()) { index -> array.items!!.getLong(index * 8L) }
        }
        callVoid("hydra_job_id_array_free", array.pointer)
        return result
    }

    private fun nativeEngineVersion(): String = runCatching {
        val value = function("hydra_ffi_version_string").invokePointer(emptyArray())
        value?.getString(0, StandardCharsets.UTF_8.name()).orEmpty()
    }.getOrDefault("1.0.0")

    private fun errorName(code: Int): String = runCatching {
        function("hydra_error_name").invokePointer(arrayOf(code))
            ?.getString(0, StandardCharsets.UTF_8.name()).orEmpty()
    }.getOrDefault("Hydra error $code")

    private fun lastError(): String = runCatching {
        val value = HydraError()
        value.write()
        if (callInt("hydra_last_error", value.pointer) != 0) return@runCatching ""
        value.read()
        try {
            value.message.text()
        } finally {
            callVoid("hydra_error_free", value.pointer)
        }
    }.getOrDefault("")

    private fun checkOk(operation: String, result: Int) {
        if (result != 0) {
            // hydra_last_error is thread-local and every ABI call clears it, so capture
            // the detail before asking Hydra for the symbolic error name.
            val detail = lastError()
            val name = errorName(result)
            error("$operation failed: $name $detail".trim())
        }
    }

    private fun function(name: String): Function =
        (library ?: error("Hydra library is not loaded")).getFunction(name)

    private fun callInt(name: String, vararg args: Any): Int = function(name).invokeInt(args)
    private fun callVoid(name: String, vararg args: Any) = function(name).invokeVoid(args)

    private fun utf8(value: String): Memory = Memory(value.toByteArray(StandardCharsets.UTF_8).size + 1L).also {
        it.setString(0, value, StandardCharsets.UTF_8.name())
    }

    data class HydraProxyConfig(val kind: Int, val host: String, val port: Int)

    @Structure.FieldOrder("size", "version", "maxJobs", "maxConnections", "maxRetries", "progressIntervalMs", "eventQueueCapacity", "workerThreads", "maxBytesPerSecond", "adaptiveConcurrency", "rangeStealing", "allowInsecureTls", "reserved0", "networkPolicy", "powerMode", "statePath", "userAgent", "reserved")
    class HydraEngineConfig : Structure() {
        @JvmField var size = 0
        @JvmField var version = 0
        @JvmField var maxJobs = 0
        @JvmField var maxConnections = 0
        @JvmField var maxRetries = 0
        @JvmField var progressIntervalMs = 0
        @JvmField var eventQueueCapacity = 0
        @JvmField var workerThreads = 0
        @JvmField var maxBytesPerSecond = 0L
        @JvmField var adaptiveConcurrency: Byte = 0
        @JvmField var rangeStealing: Byte = 0
        @JvmField var allowInsecureTls: Byte = 0
        @JvmField var reserved0: Byte = 0
        @JvmField var networkPolicy = 0
        @JvmField var powerMode = 0
        @JvmField var statePath: Pointer? = null
        @JvmField var userAgent: Pointer? = null
        @JvmField var reserved = ByteArray(32)
    }

    @Structure.FieldOrder("name", "value")
    class HydraHeader : Structure() {
        @JvmField var name: Pointer? = null
        @JvmField var value: Pointer? = null
    }

    @Structure.FieldOrder("kind", "port", "reserved", "host", "username", "password")
    class HydraProxy : Structure() {
        @JvmField var kind = 0
        @JvmField var port: Short = 0
        @JvmField var reserved = ByteArray(2)
        @JvmField var host: Pointer? = null
        @JvmField var username: Pointer? = null
        @JvmField var password: Pointer? = null
    }

    @Structure.FieldOrder("algorithm", "reserved", "digest", "digestLen")
    class HydraChecksum : Structure() {
        @JvmField var algorithm = CHECKSUM_NONE
        @JvmField var reserved = 0
        @JvmField var digest: Pointer? = null
        @JvmField var digestLen = 0L
    }

    @Structure.FieldOrder("size", "version", "urls", "urlCount", "outputPath", "headers", "headerCount", "username", "password", "proxy", "checksum", "maxConnections", "maxRetries", "priority", "reserved0", "maxBytesPerSecond", "resume", "adaptive", "autoStart", "reserved1", "reserved")
    class HydraJobConfig : Structure() {
        @JvmField var size = 0
        @JvmField var version = 0
        @JvmField var urls: Pointer? = null
        @JvmField var urlCount = 0L
        @JvmField var outputPath: Pointer? = null
        @JvmField var headers: Pointer? = null
        @JvmField var headerCount = 0L
        @JvmField var username: Pointer? = null
        @JvmField var password: Pointer? = null
        @JvmField var proxy: Pointer? = null
        @JvmField var checksum = HydraChecksum()
        @JvmField var maxConnections = 0
        @JvmField var maxRetries = 0
        @JvmField var priority = 1
        @JvmField var reserved0 = 0
        @JvmField var maxBytesPerSecond = 0L
        @JvmField var resume: Byte = 1
        @JvmField var adaptive: Byte = 1
        @JvmField var autoStart: Byte = 0
        @JvmField var reserved1: Byte = 0
        @JvmField var reserved = ByteArray(32)
    }

    @Structure.FieldOrder("bytesDownloaded", "totalBytes", "bytesPerSecond", "averageBytesPerSecond", "etaSeconds", "activeConnections", "activeSources", "completedRanges", "totalRanges", "retryCount", "stallCount")
    class HydraProgress : Structure() {
        @JvmField var bytesDownloaded = 0L
        @JvmField var totalBytes = 0L
        @JvmField var bytesPerSecond = 0L
        @JvmField var averageBytesPerSecond = 0L
        @JvmField var etaSeconds = 0L
        @JvmField var activeConnections = 0
        @JvmField var activeSources = 0
        @JvmField var completedRanges = 0
        @JvmField var totalRanges = 0
        @JvmField var retryCount = 0L
        @JvmField var stallCount = 0L
    }

    @Structure.FieldOrder("kind", "state", "jobId", "progress", "error", "httpStatus", "osError", "reserved", "timestampMs", "droppedEvents")
    class HydraEvent : Structure() {
        @JvmField var kind = 0
        @JvmField var state = 0
        @JvmField var jobId = 0L
        @JvmField var progress = HydraProgress()
        @JvmField var error = 0
        @JvmField var httpStatus = 0
        @JvmField var osError = 0
        @JvmField var reserved = 0
        @JvmField var timestampMs = 0L
        @JvmField var droppedEvents = 0L
    }

    @Structure.FieldOrder("id", "state", "errorCode", "progress", "url", "outputPath", "fileName", "errorMessage", "createdAtMs", "startedAtMs", "finishedAtMs")
    class HydraJobSnapshot : Structure() {
        @JvmField var id = 0L
        @JvmField var state = 0
        @JvmField var errorCode = 0
        @JvmField var progress = HydraProgress()
        @JvmField var url = HydraString()
        @JvmField var outputPath = HydraString()
        @JvmField var fileName = HydraString()
        @JvmField var errorMessage = HydraString()
        @JvmField var createdAtMs = 0L
        @JvmField var startedAtMs = 0L
        @JvmField var finishedAtMs = 0L
    }

    @Structure.FieldOrder("data", "length")
    class HydraString : Structure() {
        @JvmField var data: Pointer? = null
        @JvmField var length = 0L
        fun text(): String = data?.getString(0, StandardCharsets.UTF_8.name()).orEmpty()
    }

    @Structure.FieldOrder("code", "osError", "httpStatus", "message")
    class HydraError : Structure() {
        @JvmField var code = 0
        @JvmField var osError = 0
        @JvmField var httpStatus = 0
        @JvmField var message = HydraString()
    }

    @Structure.FieldOrder("items", "length")
    class HydraJobIdArray : Structure() {
        @JvmField var items: Pointer? = null
        @JvmField var length = 0L
    }
}
