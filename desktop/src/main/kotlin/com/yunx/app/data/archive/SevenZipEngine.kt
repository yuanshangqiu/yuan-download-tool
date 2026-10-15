package com.yunx.app.data.archive

import com.yunx.app.AppContext
import com.yunx.app.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 7-Zip 26.04 command-line engine bundled with YunX.
 *
 * The UI deliberately talks to the stable 7za command line instead of
 * reimplementing archive formats in Kotlin. The executable is extracted from
 * the application resources on first use and kept in the user cache.
 */
object SevenZipEngine {
    private const val TAG = "YunX-7Zip"
    private const val VERSION = "26.04"
    private const val RESOURCE = "/7zip/7za.exe"

    data class Result(val exitCode: Int, val output: String) {
        val succeeded: Boolean get() = exitCode == 0
    }

    fun version(): String = VERSION

    fun compress(input: File, archive: File, format: String = "7z"): Result {
        require(input.exists()) { "找不到待压缩文件：${input.absolutePath}" }
        archive.parentFile?.mkdirs()
        return run(
            listOf(
                ensureExecutable().absolutePath,
                "a",
                "-y",
                "-t$format",
                archive.absolutePath,
                input.absolutePath
            )
        )
    }

    fun extract(archive: File, outputDirectory: File): Result {
        require(archive.isFile) { "找不到压缩文件：${archive.absolutePath}" }
        outputDirectory.mkdirs()
        return run(
            listOf(
                ensureExecutable().absolutePath,
                "x",
                "-y",
                archive.absolutePath,
                "-o${outputDirectory.absolutePath}"
            )
        )
    }

    fun list(archive: File): Result {
        require(archive.isFile) { "找不到压缩文件：${archive.absolutePath}" }
        return run(listOf(ensureExecutable().absolutePath, "l", archive.absolutePath))
    }

    private fun ensureExecutable(): File {
        val directory = File(AppContext.cacheDir, "7zip-$VERSION")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建 7-Zip 缓存目录：${directory.absolutePath}" }
        val executable = File(directory, "7za.exe")
        if (executable.isFile && executable.length() > 0L) return executable

        val resource = SevenZipEngine::class.java.getResourceAsStream(RESOURCE)
            ?: error("找不到内置 7-Zip 引擎，请重新构建 YunX。")
        val temporary = File(directory, "${executable.name}.tmp")
        resource.use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
        if (!temporary.renameTo(executable)) {
            temporary.copyTo(executable, overwrite = true)
            temporary.delete()
        }
        Log.i(TAG, "已准备 7-Zip $VERSION：${executable.absolutePath}")
        return executable
    }

    private fun run(command: List<String>): Result {
        val process = ProcessBuilder(command)
            .directory(AppContext.cacheDir)
            .redirectErrorStream(true)
            .start()
        val bytes = process.inputStream.readBytes()
        val finished = process.waitFor(10, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            return Result(-1, "7-Zip 执行超时。")
        }
        val output = bytes.toString(StandardCharsets.UTF_8).trim()
        val result = Result(process.exitValue(), output)
        if (!result.succeeded) Log.w(TAG, "7-Zip 命令失败（${result.exitCode}）：${output.take(500)}")
        return result
    }
}
