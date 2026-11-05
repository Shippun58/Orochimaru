package ani.sanin.parsers

import android.util.Base64
import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.DoodstreamCom
import com.lagradost.cloudstream3.extractors.Streamlare
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

private const val HOST_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private fun mkHostFetch(
    url: String,
    referer: String? = null,
    headers: Map<String, String> = emptyMap()
): String {
    val builder = Request.Builder().url(url)
        .header("Accept", "*/*")
        .header("Accept-Language", "en-US,en;q=0.9")
    referer?.let { builder.header("Referer", it) }
    headers.forEach { (k, v) -> builder.header(k, v) }
    return okHttpClient.newCall(builder.get().build()).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
        body
    }
}

private fun mkHostPost(
    url: String,
    body: String,
    referer: String? = null,
    headers: Map<String, String> = emptyMap()
): String {
    val builder = Request.Builder().url(url)
        .header("Accept", "*/*")
        .header("Accept-Language", "en-US,en;q=0.9")
        .post(body.toRequestBody("application/json".toMediaType()))
    referer?.let { builder.header("Referer", it) }
    headers.forEach { (k, v) -> builder.header(k, v) }
    return okHttpClient.newCall(builder.build()).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url: ${text.take(160)}")
        text
    }
}

private fun mkHostSubtitleType(url: String): SubtitleType = when {
    url.contains(".ass", true) || url.contains(".ssa", true) -> SubtitleType.ASS
    url.contains(".srt", true) -> SubtitleType.SRT
    else -> SubtitleType.VTT
}

private fun mkHostQuality(text: String?): Int? =
    text?.let { Regex("(\\d{3,4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

private fun mkHostHlsVariants(
    master: String,
    masterUrl: String,
    headers: Map<String, String>
): List<Video> {
    val base = runCatching { URI(masterUrl) }.getOrNull() ?: return emptyList()
    return Regex(
        "#EXT-X-STREAM-INF:[^\\r\\n]*?RESOLUTION=(\\d+)x(\\d+)[^\\r\\n]*\\r?\\n([^\\r\\n]+)",
        RegexOption.IGNORE_CASE
    ).findAll(master).mapNotNull { m ->
        val height = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        val child = m.groupValues[3].trim()
        val url = if (child.startsWith("http")) child else base.resolve(child).toString()
        Video(height, VideoType.M3U8, FileUrl(url, headers))
    }.distinctBy { it.file.url }.toList()
}

private fun mkHostPlaylist(
    url: String,
    headers: Map<String, String>,
    referer: String? = null,
    fallbackQuality: Int? = null
): List<Video> {
    val body = runCatching { mkHostFetch(url, referer, headers) }.getOrNull() ?: return emptyList()
    val variants = mkHostHlsVariants(body, url, headers)
    if (variants.isNotEmpty()) return variants
    if (body.trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")) {
        return listOf(Video(fallbackQuality, VideoType.M3U8, FileUrl(url, headers)))
    }
    return emptyList()
}

private fun JsonObject.hostStr(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

class MKissaOkruExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val embed = server.embed.url.replace("/video/", "/videoembed/")
            Logger.log("MKissaOkru: fetching $embed")
            val raw = try {
                mkHostFetch(
                    embed,
                    null,
                    emptyMap()
                )
            } catch (e: Exception) {
                Logger.log("MKissaOkru: HTTP error: ${e.message}")
                return@withContext VideoContainer(emptyList())
            }
            Logger.log(
                "MKissaOkru: response=${raw.length} chars " +
                    "dataOptions=${raw.contains("data-options")} " +
                    "videos=${raw.contains("\"videos\"") || raw.contains("&quot;videos&quot;")}"
            )

            val decoded = raw.replace("&quot;", "\"").replace("&amp;", "&")
            val doc = Jsoup.parse(raw, embed)
            val attrVideos = doc.select("div[data-options]")
                .map { it.attr("data-options") }
                .firstOrNull { it.contains("videos") }
            val regexVideos = Regex("data-options=\"([^\"]*)\"")
                .findAll(decoded)
                .map { it.groupValues[1] }
                .firstOrNull { it.contains("videos") }
            val options = attrVideos ?: regexVideos
            Logger.log(
                "MKissaOkru: data-options videos found via " +
                    if (attrVideos != null) "jsoup" else if (regexVideos != null) "regex" else "none"
            )
            if (options.isNullOrBlank()) {
                Logger.log(
                    "MKissaOkru: no videos in data-options; decoded length=${decoded.length} " +
                        "body head: ${decoded.take(300)}"
                )
                return@withContext VideoContainer(emptyList())
            }
            val decodedOptions = options
            val root = runCatching {
                Mapper.json.parseToJsonElement(decodedOptions) as? JsonObject
            }.getOrNull()
            fun JsonObject?.child(key: String): JsonObject? {
                val v = this?.get(key) ?: return null
                return v as? JsonObject
                    ?: (v as? JsonPrimitive)?.contentOrNull?.let {
                        runCatching { Mapper.json.parseToJsonElement(it) as? JsonObject }.getOrNull()
                    }
            }
            fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray
            val flashvars = root?.child("flashvars")
            Logger.log(
                "MKissaOkru: json ok=${root != null} keys=${root?.keys?.joinToString(",")} " +
                    "flashvars=${flashvars != null} flashvarsKeys=${flashvars?.keys?.joinToString(",")}"
            )

            val hlsUrl = root?.hostStr("ondemandHls") ?: flashvars?.hostStr("ondemandHls")
            val dashUrl = root?.hostStr("ondemandDash") ?: flashvars?.hostStr("ondemandDash")
            Logger.log("MKissaOkru: ondemandHls=${hlsUrl ?: "absent"} ondemandDash=${dashUrl ?: "absent"}")
            if (!hlsUrl.isNullOrBlank()) {
                val videos = mkHostPlaylist(hlsUrl, emptyMap(), null)
                if (videos.isNotEmpty()) {
                    Logger.log("MKissaOkru: ${videos.size} hls variant(s) from $hlsUrl")
                    return@withContext VideoContainer(videos)
                }
                Logger.log("MKissaOkru: hls playlist empty for $hlsUrl")
            }
            if (!dashUrl.isNullOrBlank()) {
                Logger.log("MKissaOkru: using dash $dashUrl")
                return@withContext VideoContainer(
                    listOf(Video(null, VideoType.DASH, FileUrl(dashUrl)))
                )
            }

            var arr = root?.arr("videos") ?: flashvars?.arr("videos")
            if (arr.isNullOrEmpty()) {
                val rawVideos = Regex("\"videos\"\\s*:\\s*(\\[[^\\]]*\\])")
                    .find(decodedOptions)?.groupValues?.get(1)
                arr = rawVideos?.let {
                    runCatching { Mapper.json.parseToJsonElement(it) as? JsonArray }.getOrNull()
                }
                Logger.log("MKissaOkru: raw-regex videos=${arr?.size}")
            }
            if (arr.isNullOrEmpty()) {
                Logger.log(
                    "MKissaOkru: videos array missing; options length=${decodedOptions.length} " +
                        "hasFlashvarsVideos=${flashvars?.arr("videos") != null} head=${decodedOptions.take(600)}"
                )
                return@withContext VideoContainer(emptyList())
            }
            val names = arr.mapNotNull { el ->
                (el as? JsonObject)?.hostStr("name")
            }
            Logger.log("MKissaOkru: raw names=${names.joinToString(",") { it ?: "?" }} " +
                "disallowed=${arr.mapNotNull { (it as? JsonObject)?.hostStr("disallowed") }}")
            val videos = arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val u = obj.hostStr("url") ?: return@mapNotNull null
                val name = obj.hostStr("name").orEmpty()
                val url = if (u.startsWith("//")) "https:$u" else u
                Logger.log("MKissaOkru: source name=$name url=${url.take(140)}")
                Video(okruQuality(name), VideoType.CONTAINER, FileUrl(url))
            }.distinctBy { it.file.url }
            Logger.log(
                "MKissaOkru: ${videos.size} stream(s) from $embed " +
                    "qualities=${videos.map { it.quality ?: -1 }.sortedDescending()}"
            )
            VideoContainer(videos)
        } catch (e: Exception) {
            Logger.log("MKissaOkru extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }

    private fun okruQuality(name: String): Int? = when (name.uppercase()) {
        "MOBILE" -> 144
        "LOWEST" -> 240
        "LOW" -> 360
        "SD" -> 480
        "HD" -> 720
        "FULL" -> 1080
        "QUAD" -> 1440
        "ULTRA" -> 2160
        else -> mkHostQuality(name)
    }
}

class MKissaMp4UploadExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val url = server.embed.url
            val id = Regex("mp4upload\\.com/(?:embed-)?([A-Za-z0-9]+)")
                .find(url)?.groupValues?.get(1)
            val realUrl = if (id != null) "https://www.mp4upload.com/embed-$id.html" else url
            val html = mkHostFetch(realUrl, "https://mp4upload.com/")
            val unpacked = getAndUnpack(html)
            val quality = Regex("(?i)height=(\\d+)")
                .find(unpacked)?.groupValues?.get(1)?.toIntOrNull()
            val link = Regex("player\\.src\\(\"(.*?)\"").find(unpacked)?.groupValues?.get(1)
                ?: Regex("player\\.src\\([\\w\\W]*?src:\\s*\"(.*?)\"")
                    .find(unpacked)?.groupValues?.get(1)
            if (link.isNullOrBlank()) {
                Logger.log("MKissaMp4Upload: no player.src in $realUrl")
                return@withContext VideoContainer(emptyList())
            }
            val headers = mapOf("Referer" to "https://mp4upload.com/")
            Logger.log("MKissaMp4Upload: ${quality ?: "?"}p stream from $realUrl")
            VideoContainer(listOf(Video(quality, VideoType.CONTAINER, FileUrl(link, headers))))
        } catch (e: Exception) {
            Logger.log("MKissaMp4Upload extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class MKissaUniExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val playerUrl = URI(server.embed.url)
            val origin = URI(playerUrl.scheme, playerUrl.authority, "/", null).toString()
            val file1 = playerUrl.fragment?.substringBefore("&")?.takeIf { it.isNotEmpty() }
            if (file1 == null) {
                Logger.log("MKissaUni: no file id in ${server.embed.url}")
                return@withContext VideoContainer(emptyList())
            }
            Logger.log("MKissaUni: resolving file1=$file1 origin=$origin")
            val headers = mapOf("Referer" to origin)

            
            val resolveApi = "$origin" + "api/public/resolve?file1=$file1"
            val resolveBody = try {
                mkHostFetch(resolveApi, null)
            } catch (e: Exception) {
                Logger.log("MKissaUni: resolve HTTP error: ${e.message}")
                null
            }
            if (resolveBody != null) {
                Logger.log("MKissaUni: resolve body=${resolveBody.take(240)}")
                val obj = runCatching {
                    Mapper.json.parseToJsonElement(resolveBody) as? JsonObject
                }.getOrNull()
                val resolved = obj?.hostStr("url")
                if (!resolved.isNullOrBlank()) {
                    return@withContext playResolved(resolved, origin, headers)
                }
                Logger.log(
                    "MKissaUni: resolve gave no url (ok=${obj?.hostStr("ok")} error=${obj?.hostStr("error")})"
                )
            }

            
            val oldApi = "$origin" + "api/v1/video?id=$file1&w=1920&h=1080&r=mkissa.to"
            Logger.log("MKissaUni: trying legacy api $oldApi")
            val oldBody = try {
                mkHostFetch(oldApi, origin)
            } catch (e: Exception) {
                Logger.log("MKissaUni: legacy HTTP error: ${e.message}")
                null
            }
            if (oldBody != null) {
                Logger.log("MKissaUni: legacy body len=${oldBody.length} head=${oldBody.trim().take(120)}")
                val decrypted = runCatching { uniDecrypt(oldBody.trim()) }.getOrNull()
                if (decrypted != null) {
                    Logger.log("MKissaUni: legacy decrypted=${decrypted.take(240)}")
                    val streams = Mapper.json.parseToJsonElement(decrypted) as? JsonObject
                    if (streams != null) {
                        val candidates = listOf(
                            streams.hostStr("hlsVideoGoogle"),
                            streams.hostStr("cfNative"),
                            streams.hostStr("source"),
                            streams.hostStr("hlsVideoTiktok"),
                        ).filterNotNull().filter { it.isNotBlank() }
                        for (path in candidates) {
                            val resolved = runCatching { URI(origin).resolve(path).toString() }.getOrNull()
                                ?: continue
                            val videos = mkHostPlaylist(resolved, headers, origin)
                            if (videos.isNotEmpty()) {
                                Logger.log("MKissaUni: legacy ${videos.size} variant(s) from $resolved")
                                return@withContext VideoContainer(videos)
                            }
                        }
                        Logger.log("MKissaUni: legacy candidates gave no playable playlist (${candidates.size} tried)")
                    }
                } else {
                    Logger.log("MKissaUni: legacy hex decrypt failed")
                }
            }
            VideoContainer(emptyList())
        } catch (e: Exception) {
            Logger.log("MKissaUni extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }

    private suspend fun playResolved(
        resolved: String,
        origin: String,
        headers: Map<String, String>
    ): VideoContainer {
        val finalUrl = if (resolved.startsWith("http")) resolved
        else runCatching { URI(origin).resolve(resolved).toString() }.getOrNull()
        if (finalUrl == null) {
            Logger.log("MKissaUni: could not resolve $resolved against $origin")
            return VideoContainer(emptyList())
        }
        Logger.log("MKissaUni: resolved url=$finalUrl")
        val variants = mkHostPlaylist(finalUrl, headers, origin)
        if (variants.isNotEmpty()) {
            Logger.log("MKissaUni: ${variants.size} variant(s) from $finalUrl")
            return VideoContainer(variants)
        }
        val type = when {
            finalUrl.contains(".m3u8", true) -> VideoType.M3U8
            finalUrl.contains(".mpd", true) -> VideoType.DASH
            else -> VideoType.CONTAINER
        }
        Logger.log("MKissaUni: direct ${type.name} stream $finalUrl")
        return VideoContainer(listOf(Video(null, type, FileUrl(finalUrl, headers))))
    }

    private fun uniDecrypt(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(UNI_KEY.toByteArray(), "AES"),
            IvParameterSpec(UNI_IV.toByteArray())
        )
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    companion object {
        private const val UNI_KEY = "kiemtienmua911ca"
        private const val UNI_IV = "1234567890oiuytr"
    }
}

class MKissaFilemoonExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val url = server.embed.url
            val httpUrl = URI(url)
            val host = httpUrl.host ?: return@withContext VideoContainer(emptyList())
            val mediaId = extractMediaId(httpUrl)
            if (mediaId.isBlank()) {
                Logger.log("MKissaFilemoon: no media id in $url")
                return@withContext VideoContainer(emptyList())
            }
            val baseHeaders = mapOf(
                "Accept-Language" to "en-US,en;q=0.9",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
            )
            val embedUrl = getEmbedUrl(host, mediaId, baseHeaders)
            if (embedUrl.isNullOrBlank()) {
                Logger.log("MKissaFilemoon: no embed url for $host/$mediaId")
                return@withContext VideoContainer(emptyList())
            }
            val embedHost = runCatching { URI(embedUrl).host }.getOrNull() ?: host
            val pageReferer = url
            val origin = "https://$embedHost"
            val apiHeaders = baseHeaders + mapOf("Referer" to pageReferer, "Origin" to origin)
            val data = challengeFlow(mediaId, apiHeaders, origin, url)
            if (data == null) {
                Logger.log("MKissaFilemoon: challenge flow failed for $url")
                return@withContext VideoContainer(emptyList())
            }
            val sources = extractSources(data)
            if (sources.isNullOrEmpty()) {
                Logger.log("MKissaFilemoon: no sources in playback response for $url")
                return@withContext VideoContainer(emptyList())
            }
            val videoHeaders = baseHeaders + mapOf("Referer" to origin)
            val videos = sources.flatMap { source ->
                val streamUrl = source.url ?: source.file ?: return@flatMap emptyList<Video>()
                val quality = mkHostQuality(source.label)
                mkHostPlaylist(streamUrl, videoHeaders, origin, quality).ifEmpty {
                    listOf(
                        Video(
                            quality,
                            if (streamUrl.contains(".m3u8", true)) VideoType.M3U8 else VideoType.CONTAINER,
                            FileUrl(streamUrl, videoHeaders)
                        )
                    )
                }
            }.distinctBy { it.file.url }
            Logger.log("MKissaFilemoon: ${videos.size} stream(s) from $url")
            VideoContainer(videos)
        } catch (e: Exception) {
            Logger.log("MKissaFilemoon extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }

    private fun extractMediaId(httpUrl: URI): String {
        val segments = httpUrl.path?.split('/')?.filter { it.isNotEmpty() } ?: return ""
        if (segments.size > 1 && (segments[0] == "e" || segments[0] == "eyi" ||
                segments[0] == "d" || segments[0] == "download" || segments[0].startsWith("j"))
        ) {
            return segments[1]
        }
        return segments.lastOrNull() ?: ""
    }

    private fun getEmbedUrl(host: String, mediaId: String, headers: Map<String, String>): String? {
        val detailsUrl = "https://$host/api/videos/$mediaId/embed/details"
        return try {
            val body = mkHostFetch(detailsUrl, null, headers)
            val embed = body.substringAfter("embed_frame_url", "")
                .substringAfter(":").substringAfter('"').substringBefore('"')
            embed.ifBlank { null }
        } catch (e: Exception) {
            Logger.log("MKissaFilemoon details error: ${e.message}")
            null
        }
    }

    private fun challengeFlow(
        mediaId: String,
        apiHeaders: Map<String, String>,
        origin: String,
        pageUrl: String,
    ): PlaybackResponse? {
        return try {
            val challenge = Mapper.json.decodeFromString<ChallengeResponse>(
                mkHostPost("$origin/api/videos/access/challenge", "{}", null, apiHeaders)
            )
            val (privateKey, publicKeyJwk) = generateEcKeyPair()
            val signature = signNonce(privateKey, challenge.nonce)
            val client = generateClientFingerprint()
            val attestPayload = AttestRequest(
                viewerId = "",
                deviceId = "",
                challengeId = challenge.challengeId,
                nonce = challenge.nonce,
                signature = signature,
                publicKey = publicKeyJwk,
                client = client,
                storage = emptyMap(),
                attributes = mapOf("entropy" to "low"),
            )
            val attest = Mapper.json.decodeFromString<AttestResponse>(
                mkHostPost(
                    "$origin/api/videos/access/attest",
                    Mapper.json.encodeToString(attestPayload),
                    null,
                    apiHeaders
                )
            )
            val fingerprint = FingerprintData(
                token = attest.token,
                viewerId = attest.viewerId,
                deviceId = attest.deviceId,
                confidence = attest.confidence,
            )
            val fingerprintPayload = FingerprintPayload(fingerprint)
            val refererHost = runCatching { URI(apiHeaders["Referer"] ?: pageUrl).host }
                .getOrNull() ?: ""
            val embedHeaders = apiHeaders + mapOf(
                "X-Embed-Origin" to refererHost,
                "X-Embed-Referer" to "https://$refererHost/",
                "X-Embed-Parent" to pageUrl,
                "Cookie" to "byse_viewer_id=${attest.viewerId}; byse_device_id=${attest.deviceId}",
            )
            val captcha = Mapper.json.decodeFromString<CaptchaResponse>(
                mkHostPost(
                    "$origin/api/videos/$mediaId/embed/captcha",
                    Mapper.json.encodeToString(fingerprintPayload),
                    null,
                    embedHeaders
                )
            )
            val solution = solvePow(captcha.powNonce, captcha.powDifficulty)
            val verify = Mapper.json.decodeFromString<VerifyResponse>(
                mkHostPost(
                    "$origin/api/videos/$mediaId/embed/captcha/verify",
                    Mapper.json.encodeToString(
                        VerifyRequest(captcha.powToken, solution, fingerprint)
                    ),
                    null,
                    embedHeaders
                )
            )
            val token = verify.token
            if (verify.status != "ok" || token.isNullOrBlank()) {
                Logger.log("MKissaFilemoon: captcha verify failed (${verify.status})")
                return null
            }
            val playbackHeaders = embedHeaders + mapOf("X-Captcha-Token" to token)
            Mapper.json.decodeFromString<PlaybackResponse>(
                mkHostPost(
                    "$origin/api/videos/$mediaId/embed/playback",
                    Mapper.json.encodeToString(fingerprintPayload),
                    null,
                    playbackHeaders
                )
            )
        } catch (e: Exception) {
            Logger.log("MKissaFilemoon challenge error: ${e.message}")
            null
        }
    }

    private fun extractSources(data: PlaybackResponse): List<VideoSource>? {
        if (!data.sources.isNullOrEmpty()) return data.sources
        val playback = data.playback ?: return null
        return try {
            Mapper.json.decodeFromString<PlaybackResponse>(decrypt(playback)).sources
        } catch (e: Exception) {
            Logger.log("MKissaFilemoon decrypt error: ${e.message}")
            null
        }
    }

    private fun decrypt(input: PlaybackData): String {
        val keyBytes = when (input.version) {
            null -> input.keyParts.map { decodeBase64Url(it) }
                .fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            else -> {
                val v = input.version.toIntOrNull() ?: 1
                val parts = input.keyParts
                if (parts.size >= v) {
                    listOf(parts[v - 1], parts[parts.size - v])
                        .map { decodeBase64Url(it) }
                        .fold(ByteArray(0)) { acc, bytes -> acc + bytes }
                } else {
                    parts.map { decodeBase64Url(it) }
                        .fold(ByteArray(0)) { acc, bytes -> acc + bytes }
                }
            }
        }
        val ivBytes = decodeBase64Url(input.iv)
        val payloadBytes = decodeBase64Url(input.payload)
        if (payloadBytes.size < 16) {
            throw IOException("payload too short (${payloadBytes.size})")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(128, ivBytes)
        )
        return String(cipher.doFinal(payloadBytes), StandardCharsets.UTF_8)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val base64 = input.replace('-', '+').replace('_', '/')
        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.decode(base64 + padding, Base64.DEFAULT)
    }

    private fun encodeBase64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun generateEcKeyPair(): Pair<java.security.PrivateKey, EcJwk> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = generator.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val x = publicKey.w.affineX.toByteArray()
        val y = publicKey.w.affineY.toByteArray()
        val xPadded = if (x.size < 32) ByteArray(32 - x.size) + x
        else x.copyOfRange(max(0, x.size - 32), x.size)
        val yPadded = if (y.size < 32) ByteArray(32 - y.size) + y
        else y.copyOfRange(max(0, y.size - 32), y.size)
        val jwk = EcJwk(
            alg = "ES256",
            crv = "P-256",
            ext = true,
            keyOps = listOf("verify"),
            kty = "EC",
            x = encodeBase64Url(xPadded),
            y = encodeBase64Url(yPadded),
        )
        return keyPair.private to jwk
    }

    private fun signNonce(privateKey: java.security.PrivateKey, nonce: String): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(nonce.toByteArray(StandardCharsets.UTF_8))
        return encodeBase64Url(signature.sign())
    }

    private fun generateClientFingerprint(): ClientFingerprint {
        val random = SecureRandom()
        fun randHash(): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return encodeBase64Url(bytes)
        }
        return ClientFingerprint(
            userAgent = HOST_UA,
            pixelRatio = 1,
            screenWidth = 1920,
            screenHeight = 1080,
            colorDepth = 24,
            languages = listOf("en-US", "en"),
            timezone = "America/New_York",
            hardwareConcurrency = 8,
            touchPoints = 0,
            webglVendor = "Google Inc. (Intel)",
            webglRenderer = "ANGLE (Intel, Intel(R) UHD Graphics 630, OpenGL 4.5)",
            canvasHash = randHash(),
            audioHash = randHash(),
            webglParamsHash = randHash(),
            fontsHash = randHash(),
            codecsHash = randHash(),
            mediaDevices = "ai0ao0vi0",
            pointerType = "fine,hover",
            extra = mapOf("vendor" to "", "appVersion" to "5.0 (X11)"),
        )
    }

    private fun solvePow(nonce: String, difficulty: Int, maxIterations: Int = 200000): String {
        val prefix = ("$nonce:").toByteArray(Charsets.ISO_8859_1)
        val bufferSize = 512
        val bufferMask = 511
        val initConst = 2654435761L
        val finalConst = 2246822519L
        val mask32 = 0xFFFFFFFFL

        fun rotl(value: Long, shift: Int): Long =
            ((value shl shift) or (value ushr (32 - shift))) and mask32

        val buf = LongArray(bufferSize)
        for (counter in 0..maxIterations) {
            val counterBytes = counter.toString().toByteArray(Charsets.ISO_8859_1)
            val input = prefix + counterBytes

            var s0 = 1779033703L
            var s1 = 3144134277L
            var s2 = 1013904242L
            var s3 = 2773480762L

            for (b in input) {
                s0 = (s0 + (b.toInt() and 0xFF)) and mask32
                s0 = rotl(s0, 7)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 7)
            }

            repeat(8) {
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 7)
            }

            for (i in 0 until bufferSize) {
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 7)
                buf[i] = (s0 xor s2) and mask32
            }

            repeat(2) {
                for (si in 0 until bufferSize) {
                    val a = (buf[si] and bufferMask.toLong()).toInt()
                    var c = (buf[si] + buf[a]) and mask32
                    c = rotl(c, 13)
                    c = (c xor ((buf[(si + 1) and bufferMask] * initConst) and mask32)) and mask32
                    buf[si] = c
                    s0 = (s0 xor c) and mask32
                    s0 = (s0 + s1) and mask32
                    s3 = rotl(s3 xor s0, 16)
                    s2 = (s2 + s3) and mask32
                    s1 = rotl(s1 xor s2, 12)
                    s0 = (s0 + s1) and mask32
                    s3 = rotl(s3 xor s0, 8)
                    s2 = (s2 + s3) and mask32
                    s1 = rotl(s1 xor s2, 7)
                }
            }

            s0 = (s0 + s1) and mask32
            s3 = rotl(s3 xor s0, 16)
            s2 = (s2 + s3) and mask32
            s1 = rotl(s1 xor s2, 12)
            s0 = (s0 + s1) and mask32
            s3 = rotl(s3 xor s0, 8)
            s2 = (s2 + s3) and mask32
            s1 = rotl(s1 xor s2, 7)

            var outVal = s0
            for (ci in 0 until 64) {
                val d = buf[ci]
                outVal = (outVal + d) and mask32
                outVal = rotl(outVal, 5)
                outVal = (outVal xor ((d * finalConst) and mask32)) and mask32
            }
            outVal = (outVal xor s2) and mask32

            if (outVal.toInt().countLeadingZeroBits() >= difficulty) {
                return counter.toString()
            }
        }
        throw IOException("PoW solver found no solution in $maxIterations iterations")
    }

    @Serializable
    private data class ChallengeResponse(
        @SerialName("challenge_id") val challengeId: String,
        val nonce: String,
    )

    @Serializable
    private data class AttestRequest(
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("challenge_id") val challengeId: String,
        val nonce: String,
        val signature: String,
        @SerialName("public_key") val publicKey: EcJwk,
        val client: ClientFingerprint,
        val storage: Map<String, String>,
        val attributes: Map<String, String>,
    )

    @Serializable
    private data class AttestResponse(
        val token: String,
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        val confidence: Double,
    )

    @Serializable
    private data class EcJwk(
        val alg: String,
        val crv: String,
        val ext: Boolean,
        @SerialName("key_ops") val keyOps: List<String>,
        val kty: String,
        val x: String,
        val y: String,
    )

    @Serializable
    private data class ClientFingerprint(
        @SerialName("user_agent") val userAgent: String,
        @SerialName("pixel_ratio") val pixelRatio: Int,
        @SerialName("screen_width") val screenWidth: Int,
        @SerialName("screen_height") val screenHeight: Int,
        @SerialName("color_depth") val colorDepth: Int,
        val languages: List<String>,
        val timezone: String,
        @SerialName("hardware_concurrency") val hardwareConcurrency: Int,
        @SerialName("touch_points") val touchPoints: Int,
        @SerialName("webgl_vendor") val webglVendor: String,
        @SerialName("webgl_renderer") val webglRenderer: String,
        @SerialName("canvas_hash") val canvasHash: String,
        @SerialName("audio_hash") val audioHash: String,
        @SerialName("webgl_params_hash") val webglParamsHash: String,
        @SerialName("fonts_hash") val fontsHash: String,
        @SerialName("codecs_hash") val codecsHash: String,
        @SerialName("media_devices") val mediaDevices: String,
        @SerialName("pointer_type") val pointerType: String,
        val extra: Map<String, String>,
    )

    @Serializable
    private data class FingerprintPayload(
        val fingerprint: FingerprintData,
    )

    @Serializable
    private data class FingerprintData(
        val token: String,
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        val confidence: Double,
    )

    @Serializable
    private data class CaptchaResponse(
        @SerialName("pow_nonce") val powNonce: String,
        @SerialName("pow_difficulty") val powDifficulty: Int,
        @SerialName("pow_token") val powToken: String,
    )

    @Serializable
    private data class VerifyRequest(
        @SerialName("pow_token") val powToken: String,
        val solution: String,
        val fingerprint: FingerprintData,
    )

    @Serializable
    private data class VerifyResponse(
        val status: String,
        val token: String? = null,
    )

    @Serializable
    private data class PlaybackResponse(
        val sources: List<VideoSource>? = null,
        val playback: PlaybackData? = null,
    )

    @Serializable
    private data class PlaybackData(
        val iv: String,
        @SerialName("key_parts") val keyParts: List<String>,
        val payload: String,
        val version: String? = null,
    )

    @Serializable
    private data class VideoSource(
        val file: String? = null,
        val url: String? = null,
        val label: String? = "Default",
    )
}

class MKissaCsBridgeExtractor(
    override val server: VideoServer,
    private val kind: String
) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        val api: ExtractorApi = when (kind) {
            "streamwish" -> StreamWishExtractor()
            "dood" -> DoodstreamCom()
            "streamlare" -> Streamlare()
            else -> return@withContext VideoContainer(emptyList())
        }
        val videos = mutableListOf<Video>()
        val subtitles = mutableListOf<Subtitle>()
        try {
            api.getUrl(
                server.embed.url,
                null,
                { sub: SubtitleFile ->
                    if (sub.url.isNotBlank()) {
                        subtitles.add(
                            Subtitle(
                                sub.lang.ifBlank { "Unknown" },
                                sub.url,
                                mkHostSubtitleType(sub.url)
                            )
                        )
                    }
                },
                { link ->
                    val type = when (link.type) {
                        ExtractorLinkType.M3U8 -> VideoType.M3U8
                        ExtractorLinkType.DASH -> VideoType.DASH
                        else -> VideoType.CONTAINER
                    }
                    if (link.url.isNotBlank()) {
                        videos.add(
                            Video(
                                link.quality.takeIf { it > 0 },
                                type,
                                FileUrl(link.url, link.getAllHeaders())
                            )
                        )
                    }
                },
            )
            Logger.log("MKissa bridge ($kind): ${videos.size} stream(s)")
        } catch (e: Exception) {
            Logger.log("MKissa bridge ($kind) error: ${e.message}")
        }
        VideoContainer(videos.distinctBy { it.file.url }, subtitles)
    }
}
