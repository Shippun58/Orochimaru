package ani.sanin.parsers

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import ani.sanin.App
import ani.sanin.defaultHeaders
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object StreamLocalProxy {

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "stream-local-proxy").apply { isDaemon = true }
    }

    private val warmedHosts = ConcurrentHashMap.newKeySet<String>()

    private val dnsFailedHosts = ConcurrentHashMap<String, Long>()
    private const val DNS_FAIL_TTL_MS = 30_000L
    private const val DNS_PROBE_TTL_MS = 60_000L

    private val acceptThread: Thread by lazy {
        Thread({
            val sock = server
            if (sock == null || sock.isClosed) return@Thread
            Logger.log(Log.WARN, "StreamLocalProxy: listening on 127.0.0.1:${sock.localPort}")
            while (true) {
                try {
                    val client = sock.accept()
                    client.soTimeout = 60_000
                    executor.execute { handle(client) }
                } catch (e: Exception) {
                    if (sock.isClosed) break
                }
            }
        }, "stream-local-proxy-accept").apply { isDaemon = true }
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var port: Int = -1

    private val cfClient: OkHttpClient get() = okHttpClient

    private val streamClient: OkHttpClient by lazy {
        okhttp3.ConnectionPool(30, 2, TimeUnit.MINUTES).let { pool ->
            OkHttpClient.Builder()
                .cookieJar(okHttpClient.cookieJar)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectionPool(pool)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private fun extractHost(url: String): String? =
        runCatching { URI(url).host }.getOrNull()

    private fun isHostReachable(url: String): Boolean {
        val host = extractHost(url) ?: return true
        val failTime = dnsFailedHosts[host] ?: return true
        val elapsed = System.currentTimeMillis() - failTime
        if (elapsed > DNS_FAIL_TTL_MS) {
            dnsFailedHosts.remove(host)
            return true
        }
        return false
    }

    private fun markHostDnsFailed(host: String) {
        dnsFailedHosts[host] = System.currentTimeMillis()
        Logger.log(Log.WARN, "StreamLocalProxy: DNS failure cached for host=$host (ttl=${DNS_FAIL_TTL_MS / 1000}s)")
    }

    private fun ensureStarted() {
        synchronized(this) {
            if (server == null || server!!.isClosed) {
                val sock = ServerSocket(0, 64, java.net.InetAddress.getByName("127.0.0.1"))
                server = sock
                port = sock.localPort
                acceptThread.start()
            }
        }
    }

    private fun baseUrl(): String {
        ensureStarted()
        return "http://127.0.0.1:$port/stream"
    }

    fun proxyUrl(
        original: String,
        referer: String,
        origin: String = referer.removeSuffix("/"),
        pk: String? = null,
        mask: String? = null,
        em: Boolean = false,
        js: Boolean = false,
        subtitle: Boolean = false,
        warm: Boolean = false,
    ): String {
        val params = mutableListOf(
            "url=" + URLEncoder.encode(original, "UTF-8"),
            "ref=" + URLEncoder.encode(referer, "UTF-8"),
        )
        if (origin.isNotBlank()) params += "origin=" + URLEncoder.encode(origin, "UTF-8")
        pk?.let { params += "pk=" + URLEncoder.encode(it, "UTF-8") }
        mask?.let { params += "mask=" + URLEncoder.encode(it, "UTF-8") }
        if (em) params += "em=1"
        if (js) params += "js=1"
        if (subtitle) params += "sub=1"
        if (warm) params += "warm=1"
        return baseUrl() + "?" + params.joinToString("&")
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    writeResponse(s, "400 Bad Request", "text/plain", "Bad request", null)
                    return
                }

                var len = 0
                while (len < 32 * 1024) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    len += line.length
                }

                val path = parts[1].substringBefore("?")
                val query = parts[1].substringAfter("?", "")
                if (path != "/stream") {
                    writeResponse(s, "404 Not Found", "text/plain", "Not found", null)
                    return
                }
                val params = parseQuery(query)
                val url = params["url"] ?: run {
                    writeResponse(s, "400 Bad Request", "text/plain", "Missing url", null)
                    return
                }
                val referer = params["ref"] ?: ""
                val origin = params["origin"] ?: ""
                val pk = params["pk"]?.takeIf { it.isNotBlank() }
                val mask = params["mask"]?.takeIf { it.isNotBlank() }
                val em = params["em"] == "1"
                val js = params["js"] == "1"
                val subtitle = params["sub"] == "1"
                val warm = params["warm"] == "1"

                val pkBytes = pk?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }
                val maskBytes = mask?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }

                if (warm) warmHost(url, referer)

                if (subtitle || !isManifest(url, em)) {
                    serveStream(s, url, referer, origin, maskBytes, js)
                } else {
                    serveManifest(s, url, referer, origin, pkBytes, maskBytes, em, js)
                }
            }
        } catch (e: UnknownHostException) {
            Logger.log(Log.WARN, "StreamLocalProxy handle: DNS failure: ${e.message}")
        } catch (e: Exception) {
            Logger.log(Log.WARN, "StreamLocalProxy handle: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun isManifest(url: String, em: Boolean): Boolean =
        url.contains(".m3u8", ignoreCase = true) ||
            (em && (url.contains("master.txt", ignoreCase = true) || url.contains("playlist.txt", ignoreCase = true)))

    private fun serveManifest(
        socket: Socket,
        url: String,
        referer: String,
        origin: String,
        pk: ByteArray?,
        mask: ByteArray?,
        em: Boolean,
        js: Boolean,
    ) {

        if (js) {
            val bodyBytes = fetchViaWebView(url, referer)
            if (bodyBytes == null) {
                writeResponse(socket, "502 Bad Gateway", "text/plain", "WebView fetch failed", null)
                return
            }
            writeManifest(socket, url, referer, origin, pk, mask, em, js, bodyBytes)
            return
        }
        val request = Request.Builder().url(url)
            .header("User-Agent", ua())
            .header("Accept", "*/*")
            .apply {
                if (referer.isNotBlank()) header("Referer", referer)
                if (origin.isNotBlank()) header("Origin", origin)
                header("Sec-Fetch-Dest", "empty")
                header("Sec-Fetch-Mode", "cors")
                header("Sec-Fetch-Site", "same-site")
            }
            .get()
            .build()

        if (!isHostReachable(url)) {
            Logger.log(Log.WARN, "StreamLocalProxy: manifest skip — host known-bad for $url")
            writeResponse(socket, "502 Bad Gateway", "text/plain", "Host unreachable (DNS cached)", null)
            return
        }

        val owner = if (referer.contains("flixcloud", true)) "flixcloud" else "hls"

        var lastException: Exception? = null
        for (attempt in 1..3) {
        try {
        cfClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = runCatching { resp.peekBody(512).string() }.getOrDefault("")
                Logger.log(
                    Log.WARN,
                    "StreamLocalProxy: $owner manifest ${resp.code} for $url body=${body.take(300)} " +
                        "srv=${resp.header("Server")} ray=${resp.header("cf-ray")}"
                )
                writeResponse(socket, "${resp.code}", "text/plain", body.ifBlank { "${resp.code}" }, null)
                return
            }
            val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
            writeManifest(socket, url, referer, origin, pk, mask, em, js, bodyBytes)
        }
        return
        } catch (e: java.net.SocketTimeoutException) {
            lastException = e
            Logger.log(Log.WARN, "StreamLocalProxy: $owner manifest timeout attempt $attempt/3 for $url")
            if (attempt == 3) {
                Logger.log(Log.WARN, "StreamLocalProxy: $owner manifest all 3 attempts failed for $url")
                writeResponse(socket, "504 Gateway Timeout", "text/plain", "Manifest fetch timed out", null)
                return
            }
        } catch (e: UnknownHostException) {
            extractHost(url)?.let { markHostDnsFailed(it) }
            Logger.log(Log.WARN, "StreamLocalProxy: $owner manifest DNS failure for $url: ${e.message}")
            writeResponse(socket, "502 Bad Gateway", "text/plain", "DNS resolution failed", null)
            return
        } catch (e: Exception) {
            Logger.log(Log.WARN, "StreamLocalProxy: $owner manifest error: ${e.message}")
            writeResponse(socket, "502 Bad Gateway", "text/plain", "Manifest fetch error: ${e.message}", null)
            return
        }
        }
    }

    private const val EM3U8_PREFIX = "EM3U8v1:"

    private val em3u8Key: ByteArray = run {
        val ur = intArrayOf(
            226, 24, 149, 40, 170, 108, 184, 157, 168, 18, 90, 64, 186, 69, 66, 110,
            109, 169, 203, 138, 29, 188, 78, 25, 203, 185, 211, 252, 76, 126, 134, 42,
        )
        val pr = intArrayOf(
            140, 250, 231, 59, 141, 129, 254, 6, 30, 203, 96, 249, 13, 237, 122, 106,
            60, 57, 126, 48, 152, 101, 128, 186, 122, 88, 171, 249, 187, 202, 40, 220,
        )
        ByteArray(32) { (ur[it] xor pr[it]).toByte() }
    }

    private fun decryptEm3u8(raw: String): String? = runCatching {
        val payload = android.util.Base64.decode(raw.substringAfter(EM3U8_PREFIX).trim(), android.util.Base64.DEFAULT)
        if (payload.size <= 12) error("EM3U8v1 payload too short")
        val iv = payload.copyOfRange(0, 12)
        val ct = payload.copyOfRange(12, payload.size)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(em3u8Key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv),
        )
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    private fun writeManifest(
        socket: Socket,
        url: String,
        referer: String,
        origin: String,
        pk: ByteArray?,
        mask: ByteArray?,
        em: Boolean,
        js: Boolean,
        rawBytes: ByteArray,
    ) {
        val raw = String(rawBytes, Charsets.UTF_8)
        val bodyText = decryptPlaylistIfNeeded(raw, pk, em)
        if (bodyText == null || !bodyText.trimStart().startsWith("#EXTM3U", ignoreCase = true)) {
            Logger.log(Log.WARN, "StreamLocalProxy: manifest decrypt failed url=$url pk=${pk != null} em=$em size=${raw.length}")
            writeResponse(socket, "502 Bad Gateway", "text/plain", "Manifest decrypt failed", null)
            return
        }
        val base = runCatching { URI(url) }.getOrNull()
        val rewritten = rewritePlaylist(bodyText, base, referer, origin, upstreamUrl = url, pkByteArray = pk, maskByteArray = mask, emFlag = em, jsFlag = js)
        writeResponse(socket, "200 OK", "application/vnd.apple.mpegurl", rewritten, rewritten.toByteArray().size.toLong())
    }

    private fun decryptPlaylistIfNeeded(raw: String, pk: ByteArray?, em: Boolean): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("#EXTM3U", ignoreCase = true)) return raw
        if (em && trimmed.startsWith(EM3U8_PREFIX, ignoreCase = true)) {
            val plain = decryptEm3u8(trimmed)
            if (plain != null) return plain
        }
        if (pk == null || pk.isEmpty()) return null
        return runCatching {
            val cipher = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
            val plain = ByteArray(cipher.size)
            for (i in cipher.indices) plain[i] = (cipher[i].toInt() xor pk[i % pk.size].toInt()).toByte()
            String(plain, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun rewritePlaylist(
        body: String,
        base: URI?,
        referer: String,
        origin: String,
        upstreamUrl: String,
        pkByteArray: ByteArray?,
        maskByteArray: ByteArray?,
        emFlag: Boolean,
        jsFlag: Boolean,
    ): String {
        val pkB64 = pkByteArray?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        val maskB64 = maskByteArray?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        val bwRegex = Regex("""(?<![A-Z-])BANDWIDTH=(\d+)""")
        val avgBwRegex = Regex("""AVERAGE-BANDWIDTH=(\d+)""")
        val out = StringBuilder(body.length + 512)
        for (line in body.lines()) {
            var trimmed = line.trim()
            if (trimmed.isEmpty()) {
                out.append('\n')
                continue
            }

            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                val bwMatch = bwRegex.find(trimmed)
                val avgMatch = avgBwRegex.find(trimmed)
                if (bwMatch != null) {
                    val peakBw = bwMatch.groupValues[1].toLongOrNull() ?: 0L
                    val avgBw = avgMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    if (peakBw in 1 until 100_000) {
                        val finalBw = if (avgBw >= 100_000) avgBw else peakBw * 1000L
                        trimmed = trimmed.replace(bwRegex, "BANDWIDTH=$finalBw")
                    }
                }
            }
            if (trimmed.startsWith("#")) {
                val uriMatch = Regex("""URI="([^"]*)"""").find(trimmed)
                if (uriMatch != null) {
                    val resolved = resolveUrl(base, uriMatch.groupValues[1], referer)
                    val proxied = proxyUrl(
                        ensureToken(resolved, upstreamUrl), referer, origin,
                        pk = pkB64, mask = maskB64, em = emFlag, js = jsFlag
                    )
                    out.append(trimmed.replace(uriMatch.groupValues[1], proxied)).append('\n')
                } else {
                    out.append(trimmed).append('\n')
                }
            } else {
                val resolved = resolveUrl(base, trimmed, referer)
                out.append(proxyUrl(
                    ensureToken(resolved, upstreamUrl), referer, origin,
                    pk = pkB64, mask = maskB64, em = emFlag, js = jsFlag
                )).append('\n')
            }
        }

        out.toString().lines().filter { it.startsWith("#EXT-X-STREAM-INF") }.forEach { line ->
            Logger.log("StreamLocalProxy: rewritten $line")
        }
        return out.toString()
    }

    private fun resolveUrl(base: URI?, value: String, referer: String): String {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        base?.let {
            return runCatching { base.resolve(value).toString() }.getOrDefault(value)
        }
        return runCatching { URI(referer).resolve(value).toString() }.getOrDefault(value)
    }

    private fun ensureToken(url: String, parentParam: String): String {
        if (url.contains("token=")) return url
        val parent = firstUrlWithToken(parentParam) ?: return url
        val token = parseQuery(parent.substringAfter("?", "")).getFirst("token") ?: return url
        val sep = if (url.contains("?")) "&" else "?"
        return url + sep + "token=" + token
    }

    private fun firstUrlWithToken(param: String): String? {

        val tryUrls = buildList {
            add(param)
            val q = param.substringAfter("?", "")
            if (q.isNotEmpty()) {
                val u = parseQuery(q).getFirst("url")
                if (u != null) add(u)
            }
        }
        return tryUrls.firstOrNull { parseQuery(it.substringAfter("?", "")).getFirst("token") != null }
    }

    private fun serveStream(
        socket: Socket,
        url: String,
        referer: String,
        origin: String,
        mask: ByteArray?,
        js: Boolean,
    ) {
        if (!isHostReachable(url)) {
            Logger.log(Log.WARN, "StreamLocalProxy: segment skip — host known-bad for $url")
            writeResponse(socket, "502 Bad Gateway", "text/plain", "Host unreachable (DNS cached)", null)
            return
        }

        if (js) {
            val bytes = fetchViaWebView(url, referer)
            if (bytes == null) {
                writeResponse(socket, "502 Bad Gateway", "text/plain", "WebView fetch failed", null)
                return
            }
            val contentType = when {
                url.endsWith(".ass", true) -> "text/x-ass"
                url.endsWith(".srt", true) -> "application/x-subrip"
                url.endsWith(".vtt", true) -> "text/vtt"
                url.contains("/subtitles/", true) -> "text/vtt"
                else -> "video/mp2t"
            }
            writeResponse(socket, "200 OK", contentType, null, bytes.size.toLong())
            socket.getOutputStream().use { out -> out.write(bytes) }
            return
        }

        val request = Request.Builder().url(url)
            .header("User-Agent", ua())
            .header("Accept", "*/*")
            .apply {
                if (referer.isNotBlank()) header("Referer", referer)
                if (origin.isNotBlank()) header("Origin", origin)
            }
            .get()
            .build()

        streamClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = runCatching { resp.peekBody(512).string() }.getOrDefault("")
                Logger.log(
                    Log.WARN,
                    "StreamLocalProxy: segment/subtitle ${resp.code} for $url body=${body.take(200)} " +
                        "srv=${resp.header("Server")} ray=${resp.header("cf-ray")}"
                )
                writeResponse(socket, "${resp.code}", "text/plain", body.ifBlank { "${resp.code}" }, null)
                return
            }

            val contentType = when {
                url.endsWith(".ass", true) -> "text/x-ass"
                url.endsWith(".srt", true) -> "application/x-subrip"
                url.endsWith(".vtt", true) -> "text/vtt"
                url.contains("/subtitles/", true) -> "text/vtt"
                else -> "video/mp2t"
            }

            val upstream = resp.body?.byteStream() ?: return
            val isText = contentType.startsWith("text/") || contentType.contains("subrip")
            if (isText || mask == null) {
                val bytes = upstream.readBytes()
                if (mask == null && isText) {
                    Logger.log(
                        "StreamLocalProxy: sub served ${bytes.size}B ct=$contentType head=" +
                            bytes.take(40).toByteArray().decodeToString(throwOnInvalidSequence = false).replace('\n', ' ')
                    )
                }
                writeResponse(socket, "200 OK", contentType, null, bytes.size.toLong())
                socket.getOutputStream().use { out -> out.write(bytes) }
                return
            }

            val head = ByteArray(13)
            var got = 0
            while (got < head.size) {
                val n = upstream.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
            val headerSize = detectFakeHeader(head, got)
            val shouldXor = headerSize > 0 && got > headerSize && (head[headerSize].toInt() and 0xFF) != 0x47
            if (shouldXor) {
                val first = head[headerSize].toInt() and 0xFF
                val dec = first xor (mask[0].toInt() and 0xFF)
                if (dec != 0x47) {
                    Logger.log(Log.WARN, "StreamLocalProxy: segment XOR mask mismatch (first=$first dec=$dec)")
                }
            }

            val out = socket.getOutputStream()
            writeHead(out, "200 OK", contentType, null, chunked = true)
            val xorSource = XorInputStream(upstream, head, got, headerSize, if (shouldXor) mask else null)
            streamChunked(out, xorSource)
        }
    }

    private fun detectFakeHeader(head: ByteArray, len: Int): Int = when {
        len >= 12 &&
            head[0] == 0x52.toByte() && head[1] == 0x49.toByte() &&
            head[2] == 0x46.toByte() && head[3] == 0x46.toByte() &&
            head[8] == 0x57.toByte() && head[9] == 0x45.toByte() &&
            head[10] == 0x42.toByte() && head[11] == 0x50.toByte() -> 12
        len >= 8 &&
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() &&
            head[4] == 0x0D.toByte() && head[5] == 0x0A.toByte() &&
            head[6] == 0x1A.toByte() && head[7] == 0x0A.toByte() -> 8
        else -> 0
    }

    private fun writeResponse(
        socket: Socket,
        status: String,
        contentType: String,
        body: String?,
        contentLength: Long?,
    ) {
        val out = socket.getOutputStream()
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Connection: close\r\n")
        if (contentLength != null) sb.append("Content-Length: ").append(contentLength).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        body?.let { out.write(it.toByteArray(Charsets.UTF_8)) }
        out.flush()
    }

    private fun writeHead(out: java.io.OutputStream, status: String, contentType: String, contentLength: Long?, chunked: Boolean) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Connection: close\r\n")
        if (contentLength != null) sb.append("Content-Length: ").append(contentLength).append("\r\n")
        if (chunked) sb.append("Transfer-Encoding: chunked\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun streamChunked(out: java.io.OutputStream, input: InputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    out.write(Integer.toHexString(n).toByteArray(Charsets.US_ASCII))
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                    out.write(buf, 0, n)
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                }
            }
            out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        } finally {
            out.flush()
            runCatching { input.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var prev = -1
        while (buf.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (prev == '\r'.code && b == '\n'.code) {
                val bytes = buf.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.UTF_8)
            }
            buf.write(b)
            prev = b
        }
        return buf.toString("UTF-8")
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.isEmpty() || kv[0].isBlank()) null
            else kv[0] to runCatching { URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8") }.getOrDefault(kv.getOrElse(1) { "" })
        }.toMap()
    }

    private fun Map<String, String>.getFirst(name: String): String? = this[name]

    private fun ua(): String = defaultHeaders["User-Agent"] ?: FALLBACK_UA

    private fun warmHost(url: String, referer: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        if (!warmedHosts.add(host)) return
        val hasClearance = runCatching {
            okHttpClient.cookieJar.loadForRequest(url.toHttpUrl())
                .any { it.name.equals("cf_clearance", true) || it.name.equals("__cf_bm", true) }
        }.getOrDefault(false)
        if (hasClearance) return
        warmHostLocked(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun warmHostLocked(url: String) {
        val ctx = App.context ?: return
        val latch = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            var webView: WebView? = null
            try {
                webView = WebView(ctx.applicationContext)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    userAgentString = ua()
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        latch.countDown()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        latch.countDown()
                    }
                }
                webView.loadUrl(url)
            } catch (e: Exception) {
                Logger.log(Log.WARN, "StreamLocalProxy: webview warm failed: ${e.message}")
                latch.countDown()
            }

            mainHandler.postDelayed({
                runCatching { webView?.stopLoading() }
                runCatching { webView?.destroy() }
            }, 1_500)
        }
    }

    private val webFetchLock = Any()

    @Volatile private var fetchWv: WebView? = null
    @Volatile private var fetchWvReady = false

    fun fetchViaWebView(url: String, referer: String): ByteArray? = synchronized(webFetchLock) {
        App.context ?: return null
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            try {
                val wv = obtainFetchWebView()
                if (wv == null) {
                    latch.countDown()
                    return@post
                }
                waitForWvReady(wv, mainHandler, 0) {
                    runFetchInWebView(wv, url, referer, mainHandler, holder, latch)
                }
            } catch (e: Exception) {
                Logger.log(Log.WARN, "StreamLocalProxy: webview fetch setup failed: ${e.message}")
                latch.countDown()
            }
        }
        try {
            latch.await(60, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        val b64 = holder[0] ?: return null
        runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainFetchWebView(): WebView? {

        fetchWv?.let { if (it.url != null) return it }
        val ctx = App.context ?: return null
        val wv = WebView(ctx.applicationContext)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = ua()
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                fetchWvReady = true
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                fetchWvReady = true
            }
        }
        wv.loadUrl("https://senshi.to/")
        fetchWv = wv
        return wv
    }

    private fun waitForWvReady(wv: WebView, handler: Handler, tries: Int, next: () -> Unit) {
        if (fetchWvReady || tries > 200) {
            next()
            return
        }
        handler.postDelayed({ waitForWvReady(wv, handler, tries + 1, next) }, 50)
    }

    private fun runFetchInWebView(
        wv: WebView,
        url: String,
        referer: String,
        handler: Handler,
        holder: Array<String?>,
        latch: CountDownLatch,
    ) {
        val jsUrl = org.json.JSONObject.quote(url)
        val jsRef = org.json.JSONObject.quote(referer)
        val script = """
            window.__animeResp = null;
            window.__animeErr = null;
            fetch($jsUrl, {headers:{'Referer':$jsRef,'Accept':'*/*','Sec-Fetch-Dest':'video','Sec-Fetch-Mode':'cors','Sec-Fetch-Site':'cross-site'}})
              .then(async r => {
                if (!r.ok) { throw new Error('HTTP ' + r.status); }
                const b = new Uint8Array(await r.arrayBuffer());
                let bin = '';
                const CH = 32768;
                for (let i = 0; i < b.length; i += CH) {
                  bin += String.fromCharCode.apply(null, b.subarray(i, i + CH));
                }
                window.__animeResp = btoa(bin);
              })
              .catch(e => { window.__animeErr = String(e); });
        """.trimIndent()
        wv.evaluateJavascript(script, null)
        pollFetchResult(wv, handler, 0, holder, latch)
    }

    private fun pollFetchResult(
        wv: WebView,
        handler: Handler,
        tries: Int,
        holder: Array<String?>,
        latch: CountDownLatch,
    ) {
        if (tries > 600) {
            latch.countDown()
            return
        }
        wv.evaluateJavascript(
            "(window.__animeResp != null) ? window.__animeResp : ((window.__animeErr != null) ? 'ERR:' + window.__animeErr : '')"
        ) { value ->
            val v = value?.trim()
            when {
                v == null || v == "\"\"" || v == "null" || v.isEmpty() ->
                    handler.postDelayed({ pollFetchResult(wv, handler, tries + 1, holder, latch) }, 100)

                v.startsWith("\"ERR:", ignoreCase = true) -> latch.countDown()

                v.startsWith("\"") && v.length > 1 -> {
                    holder[0] = v.substring(1, v.length - 1)
                    latch.countDown()
                }

                else ->
                    handler.postDelayed({ pollFetchResult(wv, handler, tries + 1, holder, latch) }, 100)
            }
        }
    }
}

private class XorInputStream(
    private val upstream: InputStream,
    private val head: ByteArray,
    private val headLen: Int,
    private val skipBytes: Int,
    private val mask: ByteArray?,
) : InputStream() {

    private var headPos = 0
    private var xorIndex = 0

    private fun transformByte(v: Int): Int =
        if (mask == null) v else (v xor (mask[xorIndex % mask.size].toInt() and 0xFF)) and 0xFF

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (headPos < headLen) {
            if (headPos >= skipBytes) {
                b[off] = transformByte(head[headPos].toInt() and 0xFF).toByte()
                headPos++
                xorIndex++
                return 1
            }
            headPos++
        }
        val n = upstream.read(b, off, len)
        if (n < 0) return -1
        for (i in 0 until n) {
            b[off + i] = transformByte(b[off + i].toInt() and 0xFF).toByte()
            xorIndex++
        }
        return n
    }
}
