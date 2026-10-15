package com.yunx.app.data.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/** Serves a loopback PAC file that sends only selected SteamTools domains to YunX's proxy. */
object PlatformPacServer {
    const val PORT = 10812
    private const val HTTP_PROXY_PORT = 10811

    @Volatile
    private var listener: ServerSocket? = null

    @Volatile
    private var pacScript: String = buildPacScript(emptyList())

    @Synchronized
    fun start(domains: Collection<String>) {
        pacScript = buildPacScript(domains)
        if (listener?.isBound == true && listener?.isClosed == false) return

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
        listener = socket
        thread(isDaemon = true, name = "YunX-Platform-PAC") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "YunX-Platform-PAC-client") { serve(client) }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { listener?.close() }
        listener = null
        pacScript = buildPacScript(emptyList())
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                val requestLine = reader.readLine().orEmpty()
                while (reader.readLine()?.isNotEmpty() == true) Unit
                val isPacRequest = requestLine.startsWith("GET /proxy.pac ") || requestLine.startsWith("GET / ")
                val body = if (isPacRequest) pacScript else "Not Found"
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                val status = if (isPacRequest) "200 OK" else "404 Not Found"
                val contentType = if (isPacRequest) "application/x-ns-proxy-autoconfig; charset=utf-8" else "text/plain; charset=utf-8"
                val headers = "HTTP/1.1 $status\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(headers.toByteArray(StandardCharsets.ISO_8859_1))
                    write(bytes)
                    flush()
                }
            }
        }
    }

    private fun buildPacScript(domains: Collection<String>): String {
        val safeDomains = domains.asSequence()
            .map { it.trim().trimStart('.') }
            .filter { it.matches(Regex("[A-Za-z0-9.-]+")) && it.contains('.') }
            .map(String::lowercase)
            .distinct()
            .sorted()
            .joinToString(",\n        ") { "\"$it\"" }
        return """
            function FindProxyForURL(url, host) {
                host = host.toLowerCase().replace(/\.$/, "");
                var yunxDomains = [
                    $safeDomains
                ];
                for (var i = 0; i < yunxDomains.length; i++) {
                    var domain = yunxDomains[i];
                    if (host === domain || dnsDomainIs(host, "." + domain)) {
                        return "PROXY 127.0.0.1:$HTTP_PROXY_PORT; DIRECT";
                    }
                }
                return "DIRECT";
            }
        """.trimIndent()
    }
}
