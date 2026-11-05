package ani.sanin.parsers

import android.util.Base64
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.net.URI
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EmbedResult(
    val urls: List<String>,
    val subtitles: List<SubData> = emptyList(),
    val intro: Pair<Double, Double>? = null,
    val outro: Pair<Double, Double>? = null,
    val headers: Map<String, String> = emptyMap()
)

data class SubData(
    val url: String,
    val language: String = "en",
    val type: String = "vtt"
)

object EmbedRouter {
    suspend fun resolve(url: String, referer: String? = null): EmbedResult =
        withContext(Dispatchers.IO) {
            try {
                val result = when {
                    FlixcloudExtractor.matches(url)  -> FlixcloudExtractor.extract(url, referer)
                    MegaPlayExtractor.matches(url)    -> MegaPlayExtractor.extract(url, referer)
                    VidplayExtractor.matches(url)     -> VidplayExtractor.extract(url, referer)
                    VidmolyExtractor.matches(url)     -> VidmolyExtractor.extract(url, referer)
                    NovaExtractor.matches(url)        -> NovaExtractor.extract(url, referer)
                    ByseExtractor.matches(url)        -> ByseExtractor.extract(url, referer)
                    BabaStreamExtractor.matches(url)  -> BabaStreamExtractor.extract(url, referer)
                    DataSvExtractor.matches(url)      -> DataSvExtractor.extract(url, referer)
                    AnimeSaltExtractor.matches(url)   -> AnimeSaltExtractor.extract(url, referer)
                    else -> {

                        Logger.log("EmbedRouter: no extractor for $url")
                        EmbedResult(urls = emptyList())
                    }
                }
                Logger.log("EmbedRouter: ${result.urls.size} url(s) for $url")
                result
            } catch (e: Exception) {
                Logger.log("EmbedRouter failed $url : ${e.message}")
                emptyEmbedResult()
            }
        }
}

private fun emptyEmbedResult(): EmbedResult = EmbedResult(urls = emptyList())

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private fun rawGet(url: String, headers: Map<String, String> = mapOf("User-Agent" to UA)): String {
    val req = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.get().build()
    return okHttpClient.newCall(req).execute().use { it.body?.string().orEmpty() }
}

private fun rawGetBytes(url: String, headers: Map<String, String> = mapOf("User-Agent" to UA)): ByteArray {
    val req = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.get().build()
    return okHttpClient.newCall(req).execute().use { it.body?.bytes() ?: ByteArray(0) }
}

private fun rawPostJson(url: String, body: String, headers: Map<String, String> = mapOf("User-Agent" to UA)): String {
    val req = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    return okHttpClient.newCall(req).execute().use { it.body?.string().orEmpty() }
}

private fun rawPostEmpty(url: String, headers: Map<String, String> = mapOf("User-Agent" to UA)): String {
    val req = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType())).build()
    return okHttpClient.newCall(req).execute().use { it.body?.string().orEmpty() }
}

private fun rawPostForm(url: String, form: Map<String, String>, headers: Map<String, String> = mapOf("User-Agent" to UA)): String {
    val formBody = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
    val req = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.post(formBody).build()
    return okHttpClient.newCall(req).execute().use { it.body?.string().orEmpty() }
}

private fun originOf(url: String): String = runCatching {
    URI(url).let { "${it.scheme}://${it.authority}" }
}.getOrDefault(url.substringBefore('/', ""))

private fun b64d(input: String): ByteArray = Base64.decode(input, Base64.DEFAULT)
private fun b64ud(input: String): ByteArray = Base64.decode(input, Base64.URL_SAFE or Base64.NO_WRAP)
private fun b64e(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
private fun b64ue(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)

private fun sha256hex(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun sha256bytes(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

private fun hmacSha256(key: ByteArray, value: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(value.toByteArray(Charsets.UTF_8))
}

private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int = 32): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(password, "HmacSHA256"))
    val hashLen = 32
    val blocks = (dkLen + hashLen - 1) / hashLen
    val out = ByteArray(dkLen)
    var pos = 0
    for (block in 1..blocks) {
        val int = byteArrayOf(
            (block ushr 24).toByte(), (block ushr 16).toByte(),
            (block ushr 8).toByte(), block.toByte()
        )
        var u = mac.doFinal(salt + int)
        val t = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        val n = minOf(hashLen, dkLen - pos)
        for (i in 0 until n) out[pos + i] = t[i]
        pos += n
    }
    return out
}

private fun jsGet(obj: Map<*, *>?, key: String): Any? = obj?.get(key)

private fun asString(value: Any?): String? = value as? String

private fun asMap(value: Any?): Map<*, *>? = value as? Map<*, *>

private fun asList(value: Any?): List<*>? = value as? List<*>

private class JsLiteralParser(private val source: String) {
    private var index = 0
    private val len = source.length

    fun parse(): Any? {
        whitespace()
        return when {
            index >= len -> null
            source[index] == '{' -> parseObject()
            source[index] == '[' -> parseArray()
            source[index] == '"' -> parseDoubleString()
            source[index] == '\'' -> parseSingleString()
            source.startsWith("true", index) -> { index += 4; true }
            source.startsWith("false", index) -> { index += 5; false }
            source.startsWith("null", index) -> { index += 4; null }
            source.startsWith("undefined", index) -> { index += 9; null }
            source.startsWith("!0", index) -> { index += 2; true }
            source.startsWith("!1", index) -> { index += 2; false }
            else -> parseNumber()
        }
    }

    private fun whitespace() { while (index < len && source[index] in " \t\r\n") index++ }

    private fun parseObject(): Map<*, *> {
        index++; whitespace()
        val map = linkedMapOf<String, Any?>()
        while (index < len && source[index] != '}') {
            if (source[index] == ',') { index++; whitespace(); continue }
            val key = parseKey()
            whitespace()
            if (index < len && source[index] == ':') index++
            map[key] = parse()
            whitespace()
        }
        if (index < len) index++
        return map
    }

    private fun parseArray(): List<*> {
        index++; whitespace()
        val list = mutableListOf<Any?>()
        while (index < len && source[index] != ']') {
            if (source[index] == ',') { index++; whitespace(); continue }
            list.add(parse())
            whitespace()
        }
        if (index < len) index++
        return list
    }

    private fun parseKey(): String {
        whitespace()
        return when {
            index < len && source[index] == '"' -> (parseDoubleString() ?: "")
            index < len && source[index] == '\'' -> (parseSingleString() ?: "")
            else -> {
                val m = Regex("^[a-zA-Z_$][a-zA-Z0-9_$]*").find(source.substring(index))
                    ?: return ""
                index += m.value.length
                m.value
            }
        }
    }

    private fun parseDoubleString(): String? {
        index++; val sb = StringBuilder()
        while (index < len && source[index] != '"') {
            if (source[index] == '\\' && index + 1 < len) {
                index++
                sb.append(when (source[index]) {
                    'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '"' -> '"'; '\\' -> '\\'; else -> source[index]
                })
            } else sb.append(source[index])
            index++
        }
        if (index < len) index++
        return sb.toString()
    }

    private fun parseSingleString(): String? {
        index++; val sb = StringBuilder()
        while (index < len && source[index] != '\'') {
            if (source[index] == '\\' && index + 1 < len) {
                index++
                sb.append(when (source[index]) {
                    'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '\'' -> '\''; '\\' -> '\\'; else -> source[index]
                })
            } else sb.append(source[index])
            index++
        }
        if (index < len) index++
        return sb.toString()
    }

    private fun parseNumber(): Number {
        val m = Regex("^-?[0-9.]+([eE][+-]?[0-9]+)?").find(source.substring(index))
            ?: return 0
        index += m.value.length
        return m.value.toDoubleOrNull() ?: 0.0
    }
}

private class WasmTransform(val step: Int, private val code: ByteArray) {
    fun transform(inputByte: Int): Int {
        var local6 = inputByte and 0xFF
        var i = 0
        val stack = mutableListOf<Int>()
        while (i < code.size) {
            val op = code[i++].toInt() and 0xFF
            when (op) {
                32 -> {
                    val (idx, next) = leb(code, i); i = next
                    stack.add(if (idx == 6) local6 else 0)
                }
                33 -> {
                    val (idx, next) = leb(code, i); i = next
                    val v = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    if (idx == 6) local6 = v and 0xFF
                }
                65 -> {
                    val (v, next) = leb(code, i); i = next
                    stack.add(v)
                }
                106 -> {
                    val r = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    val l = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    stack.add((l + r) and 0xFF)
                }
                107 -> {
                    val r = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    val l = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    stack.add((l - r + 256) and 0xFF)
                }
                113 -> {
                    val r = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    val l = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    stack.add((l and r) and 0xFF)
                }
                114 -> {
                    val r = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    val l = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    stack.add((l or r) and 0xFF)
                }
                115 -> {
                    val r = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    val l = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    stack.add((l xor r) and 0xFF)
                }
                116 -> {
                    val r = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    val l = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    stack.add((l shl (r and 7)) and 0xFF)
                }
                118 -> {
                    val r = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    val l = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else 0
                    stack.add((l ushr (r and 7)) and 0xFF)
                }
            }
        }
        return local6
    }

    private fun leb(arr: ByteArray, index: Int): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var i = index
        var next: Int
        do {
            next = arr[i++].toInt() and 0xFF
            value = value or ((next and 0x7F) shl shift)
            shift += 7
        } while (next and 0x80 != 0)
        return value to i
    }
}

private fun parseWasmDecrypt(bytes: ByteArray): WasmTransform {
    var position = 8
    while (position < bytes.size) {
        val section = bytes[position++].toInt() and 0xFF
        var size = 0
        var shift = 0
        var next: Int
        do {
            next = bytes[position++].toInt() and 0xFF
            size = size or ((next and 0x7F) shl shift)
            shift += 7
        } while (next and 0x80 != 0)

        if (section == 10) {

            position++
            var bodySize = 0
            var bodyShift = 0
            do {
                next = bytes[position++].toInt() and 0xFF
                bodySize = bodySize or ((next and 0x7F) shl bodyShift)
                bodyShift += 7
            } while (next and 0x80 != 0)
            position += bodySize
            break
        }
        position += size
    }

    var fSize = 0
    var fShift = 0
    var next: Int
    do {
        next = bytes[position++].toInt() and 0xFF
        fSize = fSize or ((next and 0x7F) shl fShift)
        fShift += 7
    } while (next and 0x80 != 0)
    val body = bytes.copyOfRange(position, position + fSize)

    val xorEnd = intArrayOf(32, 2, 32, 5, 106, 45, 0, 0, 115, 33, 6)
    var transformStart = -1
    outer@ for (i in 0..body.size - xorEnd.size) {
        for (j in xorEnd.indices) if ((body[i + j].toInt() and 0xFF) != xorEnd[j]) continue@outer
        transformStart = i + xorEnd.size
        break
    }
    if (transformStart < 0) throw IllegalStateException("WASM transform start not found")
    var transformEnd = -1
    var step = 36
    var i = transformStart
    while (i < body.size - 4) {
        if ((body[i].toInt() and 0xFF) == 32 && (body[i + 1].toInt() and 0xFF) == 5 && (body[i + 2].toInt() and 0xFF) == 65) {
            var j = i + 3
            var value = 0
            var shift = 0
            var n: Int
            do {
                n = body[j++].toInt() and 0xFF
                value = value or ((n and 0x7F) shl shift)
                shift += 7
            } while (n and 0x80 != 0)
            if ((body[j].toInt() and 0xFF) == 108) {
                transformEnd = i
                step = value
                break
            }
        }
        i++
    }
    if (transformEnd < 0) throw IllegalStateException("WASM keystream not found")
    val code = body.copyOfRange(transformStart, transformEnd)
    return WasmTransform(step, code)
}

private fun runDecrypt(wasmBytes: ByteArray, fragment: ByteArray, keyFragment: ByteArray, token: ByteArray, seed: Long): ByteArray {
    val t = parseWasmDecrypt(wasmBytes)
    val out = ByteArray(fragment.size)
    for (i in fragment.indices) {
        val value = (fragment[i].toInt() xor keyFragment[i].toInt()) xor (token[i and (token.size - 1)].toInt() and 0xFF)
        val tv = t.transform(value)
        out[i] = (tv.toLong() xor ((i.toLong() * t.step + seed) and 0xFFL)).toInt().toByte()
    }
    return out
}

object FlixcloudExtractor {
    private const val FLIX = "https://flixcloud.cc"

    fun matches(url: String): Boolean = url.contains("flixcloud", ignoreCase = true) ||
        url.contains("flix", ignoreCase = true)

    suspend fun extract(embedUrl: String, referer: String?): EmbedResult = withContext(Dispatchers.IO) {
        val html = rawGet(embedUrl, mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,*/*",
            "Referer" to (referer ?: "https://reanime.to/")
        ))
        val data = JsLiteralParser(extractSsrObj(html)).parse() as? Map<*, *>
            ?: throw IllegalStateException("flixcloud ssr parse failed")
        val seed = asString(jsGet(data, "obfuscation_seed"))
            ?: throw IllegalStateException("obfuscation_seed missing")
        val fields = deriveFields(seed)
        val cryptoData = asMap(jsGet(data, "obfuscated_crypto_data"))
            ?: throw IllegalStateException("obfuscated_crypto_data missing")
        val container = asMap(jsGet(cryptoData, fields.containerName))
            ?: throw IllegalStateException("container missing")
        val array = asList(jsGet(container, fields.arrayName))
            ?: throw IllegalStateException("array missing")
        val arr0 = array.firstOrNull()?.let { asMap(it) }
            ?: throw IllegalStateException("arr0 missing")
        val obj = asMap(jsGet(arr0, fields.objectName))
            ?: throw IllegalStateException("object missing")
        val fragment = asString(jsGet(obj, fields.keyField))?.let(::b64d)
            ?: throw IllegalStateException("keyField missing")
        val iv = asString(jsGet(obj, fields.ivField))?.let(::b64d)
            ?: throw IllegalStateException("ivField missing")
        val keyFragmentRaw = asString(jsGet(data, fields.keyFrag2Field))
            ?: throw IllegalStateException("kf2 missing")
        val keyFragment = b64d(keyFragmentRaw)
        val token = asString(jsGet(data, fields.tokenField))
            ?: throw IllegalStateException("token missing")

        val tokenJson = rawGet("$FLIX/api/m3u8/$token", mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json,*/*"
        ))
        val tokenData = Mapper.json.parseToJsonElement(tokenJson) as? JsonObject
            ?: throw IllegalStateException("token api bad json")
        val videoKey = sha256hex(token + "vid").substring(0, 10)
        val tokenKey = sha256hex(token + "key").substring(0, 10)
        val videoBytes = (tokenData[videoKey] as? JsonPrimitive)?.contentOrNull?.let(::b64d)
            ?: throw IllegalStateException("token vid missing")
        val tokenBytes = (tokenData[tokenKey] as? JsonPrimitive)?.contentOrNull?.let(::b64d)
            ?: throw IllegalStateException("token key missing")

        val seedNumber = seed.substring(0, 8).toLong(16)
        val wasmPayload = asString(jsGet(data, "w_payload"))?.let(::b64d)
            ?: throw IllegalStateException("w_payload missing")

        val wasmOut = runDecrypt(wasmPayload, fragment, keyFragment, tokenBytes, seedNumber)
        val derived = pbkdf2Sha256(wasmOut, seed.toByteArray(Charsets.UTF_8), 1000, 32)
        for (i in 0 until 32) {
            derived[i] = (derived[i].toInt() xor seed[i % seed.length].code).toByte()
        }
        val aesKey = sha256bytes(derived)
        val plain = runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            cipher.doFinal(videoBytes)
        }.getOrNull() ?: throw IllegalStateException("flixcloud decrypt failed")
        val url = String(plain, Charsets.UTF_8).trim().trimEnd('\u0000')
        if (!url.startsWith("http")) throw IllegalStateException("bad decrypted url")

        val playlistKey = runCatching { flixPlaylistKey(wasmPayload) }.getOrNull()
            ?: throw IllegalStateException("flixcloud playlist key missing")
        val segmentMask = flixSegmentMask(html)

        val proxied = StreamLocalProxy.proxyUrl(
            original = url,
            referer = "$FLIX/",
            origin = FLIX,
            pk = b64e(playlistKey),
            mask = b64e(segmentMask)
        )

        val subs = mutableListOf<SubData>()
        ((asList(jsGet(data, "subtitles")) ?: emptyList<Any?>()) as List<*>).forEach { s ->
            val m = asMap(s) ?: return@forEach
            val u = asString(jsGet(m, "url")) ?: asString(jsGet(m, "file")) ?: return@forEach
            val lang = asString(jsGet(m, "label")) ?: asString(jsGet(m, "language")) ?: asString(jsGet(m, "lang")) ?: "en"
            val type = flixSubtitleType(asString(jsGet(m, "format")) ?: asString(jsGet(m, "type")), u)
            subs.add(SubData(StreamLocalProxy.proxyUrl(u, "$FLIX/", FLIX, subtitle = true), lang, type))
        }
        Logger.log(
            "Flixcloud: ${subs.size} subtitle(s) " +
                subs.joinToString { "${it.language} -> ${it.type}" }
        )
        EmbedResult(
            urls = listOf(proxied),
            subtitles = subs,
            headers = mapOf("Referer" to "$FLIX/", "Origin" to FLIX, "User-Agent" to UA)
        )
    }

    private fun flixSubtitleType(declared: String?, url: String): String {
        when (declared?.lowercase()?.substringBefore(';')?.trim()) {
            "ass", "ssa" -> return "ass"
            "srt" -> return "srt"
            "vtt", "webvtt" -> return "vtt"
        }
        val path = runCatching { URI(url).path ?: "" }
            .getOrDefault(url.substringBefore('?'))
            .lowercase()
        return when {
            path.endsWith(".ass") || path.endsWith(".ssa") -> "ass"
            path.endsWith(".srt") -> "srt"
            else -> "vtt"
        }
    }

    private fun flixPlaylistKey(wasm: ByteArray): ByteArray {
        var pos = 8
        while (pos < wasm.size) {
            val section = wasm[pos++].toInt() and 0xFF
            var size = 0
            var shift = 0
            var next: Int
            do {
                next = wasm[pos++].toInt() and 0xFF
                size = size or ((next and 0x7F) shl shift)
                shift += 7
            } while (next and 0x80 != 0)
            val end = pos + size
            if (section == 11) {
                var i = pos
                var count = 0
                var cShift = 0
                do {
                    next = wasm[i++].toInt() and 0xFF
                    count = count or ((next and 0x7F) shl cShift)
                    cShift += 7
                } while (next and 0x80 != 0)
                if (count < 1) throw IllegalStateException("no data segment")
                i++
                if (wasm[i++].toInt() and 0xFF != 0x41) throw IllegalStateException("bad offset expr")
                var v = 0
                var vShift = 0
                do {
                    next = wasm[i++].toInt() and 0xFF
                    v = v or ((next and 0x7F) shl vShift)
                    vShift += 7
                } while (next and 0x80 != 0)
                if (wasm[i++].toInt() and 0xFF != 0x0b) throw IllegalStateException("bad offset end")
                var segLen = 0
                var sShift = 0
                do {
                    next = wasm[i++].toInt() and 0xFF
                    segLen = segLen or ((next and 0x7F) shl sShift)
                    sShift += 7
                } while (next and 0x80 != 0)
                val seg = wasm.copyOfRange(i, i + segLen)
                val pk = ByteArray(32)
                for (k in 0 until 32) {
                    val a = if (k < seg.size) seg[k].toInt() and 0xFF else 0
                    val b = if (k + 32 < seg.size) seg[k + 32].toInt() and 0xFF else 0
                    pk[k] = (a xor b).toByte()
                }
                return pk
            }
            pos = end
        }
        throw IllegalStateException("wasm data section not found")
    }

    private fun flixSegmentMask(html: String): ByteArray {
        val fallback = byteArrayOf(
            (-99).toByte(), 42, (-15).toByte(), 71, (-77).toByte(), (-114).toByte(), 92, 112,
            (-90).toByte(), 25, (-28).toByte(), 59, (-40).toByte(), 98, 15, (-59).toByte()
        )
        return runCatching {
            val scriptPath = Regex("""href="([^"]*hls\.js[^"]*)""").find(html)?.groupValues?.get(1)
                ?: return fallback
            val scriptUrl = if (scriptPath.startsWith("http")) scriptPath else "$FLIX$scriptPath"
            val js = rawGet(scriptUrl, mapOf("User-Agent" to UA, "Referer" to "$FLIX/"))
            Regex("""for\(var f=\[(\d{1,3}(?:,\d{1,3}){15})]""").find(js)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim().toInt().toByte() }?.toByteArray() ?: fallback
        }.getOrDefault(fallback)
    }

    private fun extractSsrObj(html: String): String {
        val marker = Regex("""\{type:"data",data:\{""").find(html)
            ?: throw IllegalStateException("ssr block not found")
        val start = marker.range.last
        var depth = 0
        for (k in start until html.length) {
            when (html[k]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, k + 1)
                }
            }
        }
        throw IllegalStateException("ssr brace matching failed")
    }

    private fun deriveFields(seed: String): FlixcloudFields {
        var first = seed
        for (i in 0..2) first = sha256hex(first + i)
        var second = first
        for (i in 0..2) second = sha256hex(second + i)
        return FlixcloudFields(
            keyField = "kf_" + first.substring(8, 16),
            ivField = "ivf_" + first.substring(16, 24),
            containerName = "cd_" + first.substring(24, 32),
            arrayName = "ad_" + first.substring(32, 40),
            objectName = "od_" + first.substring(40, 48),
            tokenField = first.substring(48, 64) + "_" + first.substring(56, 64),
            keyFrag2Field = second.substring(0, 16) + "_" + second.substring(16, 24)
        )
    }

    private data class FlixcloudFields(
        val keyField: String,
        val ivField: String,
        val containerName: String,
        val arrayName: String,
        val objectName: String,
        val tokenField: String,
        val keyFrag2Field: String
    )
}

object MegaPlayExtractor {
    fun matches(url: String): Boolean = Regex("megaplay\\.[^/]+/stream/", RegexOption.IGNORE_CASE).containsMatchIn(url)

    suspend fun extract(embedUrl: String, referer: String?): EmbedResult = withContext(Dispatchers.IO) {
        val pageUrl = URI(embedUrl)
        val origin = originOf(embedUrl)
        val pageHtml = rawGet(embedUrl, mapOf("User-Agent" to UA, "Referer" to (referer ?: "$origin/")))
        val fileId = Regex("""data-id=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(pageHtml)
            ?.groupValues?.get(1) ?: throw IllegalStateException("megaplay file id not found")

        val scriptUrls = Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(pageHtml).map { runCatching { java.net.URL(java.net.URL(embedUrl), it.groupValues[1]).toString() }.getOrNull() }
            .filterNotNull().toList()

        val orderedScriptUrls = scriptUrls.sortedBy { url ->
            when {
                url.contains("newclient", ignoreCase = true) -> 0
                url.contains("app.main", ignoreCase = true) -> 1
                else -> 2
            }
        }
        var targetScript: String? = null
        for (scriptUrl in orderedScriptUrls) {
            val body = runCatching { rawGet(scriptUrl, mapOf("User-Agent" to UA, "Referer" to embedUrl)) }.getOrNull()
                ?: continue
            if (body.contains("getSources", ignoreCase = true) && body.contains("AES-CBC", ignoreCase = true)) {
                Logger.log("MegaPlay: matched script ${scriptUrl.substringAfterLast('/')} (${body.length} chars)")
                targetScript = body
                break
            }
        }
        val script = targetScript ?: throw IllegalStateException("megaplay script not found")

        val route = getRoute(script)
        val srcHeaders = mapOf("User-Agent" to UA, "Referer" to embedUrl, "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json,*/*")
        val srcUrl = "$origin/$route?id=$fileId&id=$fileId"
        val srcJson = runCatching { rawGet(srcUrl, srcHeaders) }.let { runCatching { Mapper.json.parseToJsonElement(it.getOrThrow()) as? JsonObject }.getOrNull() }

        var resolvedUrl: String? = srcJson?.let { ((it["sources"] as? JsonObject)?.get("file") as? JsonPrimitive)?.contentOrNull }

        if (resolvedUrl == null) {
            val enc = srcJson?.let { (it["enc"] as? JsonPrimitive)?.contentOrNull }
            if (enc != null) {
                resolvedUrl = decryptSource(enc, script)
            }
        }

        val url = resolvedUrl ?: throw IllegalStateException("megaplay no source found")
        val metadata = srcJson
        val intro = parseSkip(metadata?.get("intro"))
        val outro = parseSkip(metadata?.get("outro"))
        val tracks = metadata?.get("tracks") as? JsonArray
        val subs = tracks?.mapNotNull { t ->
            val m = t as? JsonObject ?: return@mapNotNull null
            val u = (m["file"] as? JsonPrimitive)?.contentOrNull ?: (m["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val lang = (m["label"] as? JsonPrimitive)?.contentOrNull ?: (m["kind"] as? JsonPrimitive)?.contentOrNull ?: "en"
            SubData(u, lang)
        } ?: emptyList()

        val cdnHost = runCatching { java.net.URI(url).host }.getOrNull()
        val embedHost = runCatching { java.net.URI(embedUrl).host }.getOrNull()
        val needWarm = cdnHost != null && cdnHost != embedHost
        val proxied = StreamLocalProxy.proxyUrl(url, "$origin/", origin, warm = needWarm)
        val proxiedSubs = subs.map { it.copy(url = StreamLocalProxy.proxyUrl(it.url, "$origin/", origin, subtitle = true)) }
        EmbedResult(urls = listOf(proxied), subtitles = proxiedSubs, intro = intro, outro = outro,
            headers = mapOf("User-Agent" to UA, "Referer" to "$origin/"))
    }

    private fun getRoute(script: String): String {
        val routes = Regex("""stream/getSources[\w/-]*""", RegexOption.IGNORE_CASE).findAll(script)
            .map { it.value }.sortedBy { it.length }.toList()
        val legacy = routes.firstOrNull() ?: "stream/getSources"
        val modern = routes.find { it != legacy && it.startsWith(legacy) }
        return modern ?: legacy
    }

    private fun decryptSource(enc: String, script: String): String? {
        val encrypted = try { b64ud(enc) } catch (_: Exception) { return null }
        if (encrypted.isEmpty() || encrypted.size % 16 != 0) return null
        val strings = extractScriptStrings(script)
        val keys = strings.filter { it.toByteArray(Charsets.UTF_8).size in 1..32 }
        val ivs = keys.filter { it.toByteArray(Charsets.UTF_8).size == 16 }
        for (kv in keys) {
            val keyBytes = ByteArray(32)
            val kvBytes = kv.toByteArray(Charsets.UTF_8)
            System.arraycopy(kvBytes, 0, keyBytes, 0, kvBytes.size)
            for (ivStr in ivs) {
                try {
                    val iv = ivStr.toByteArray(Charsets.UTF_8)
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                    val plain = cipher.doFinal(encrypted)
                    val obj = Mapper.json.parseToJsonElement(String(plain, Charsets.UTF_8)) as? JsonObject
                    val source = (obj?.get("file") as? JsonPrimitive)?.contentOrNull
                        ?: (obj?.get("url") as? JsonPrimitive)?.contentOrNull
                    if (!source.isNullOrBlank()) return source
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun parseSkip(v: Any?): Pair<Double, Double>? {
        val a = v as? JsonArray ?: return null
        if (a.size < 2) return null
        fun num(el: JsonElement?): Double? = when (el) {
            is JsonPrimitive -> el.doubleOrNull ?: el.contentOrNull?.toDoubleOrNull()
            else -> null
        }
        val s = num(a.getOrNull(0)) ?: return null
        val e = num(a.getOrNull(1)) ?: return null
        return if (e > s) s to e else null
    }

    private fun extractScriptStrings(script: String): List<String> {
        val strings = mutableListOf<String>()
        var i = 0
        var prev = ""
        val len = script.length
        while (i < len) {
            val c = script[i]
            if (c == '/' && i + 1 < len && script[i + 1] == '/') {
                i = script.indexOf('\n', i + 2); if (i < 0) break; continue
            }
            if (c == '/' && i + 1 < len && script[i + 1] == '*') {
                i = script.indexOf("*/", i + 2); if (i < 0) break; i += 2; continue
            }
            if (c == '/' && prev.isNotEmpty() && prev[0] in "=(:[!&|?{};") {
                i++; var inClass = false
                while (i < len) {
                    if (script[i] == '\\') { i += 2; continue }
                    if (script[i] == '[') inClass = true
                    if (script[i] == ']') inClass = false
                    if (script[i] == '/' && !inClass) { i++; while (i < len && script[i].isLetter()) i++; break }
                    i++
                }
                continue
            }
            if (c == '"' || c == '\'') {
                val q = c; i++; val sb = StringBuilder()
                while (i < len && script[i] != q) {
                    if (script[i] == '\\' && i + 1 < len) { i++; sb.append(script[i]) } else sb.append(script[i])
                    i++
                }
                if (i < len) i++
                strings.add(sb.toString().replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r"))
                prev = if (strings.isNotEmpty()) strings.last().lastOrNull()?.toString() ?: "" else ""
                continue
            }
            if (!c.isWhitespace()) prev = c.toString()
            i++
        }
        return strings.distinct()
    }
}

object VidplayExtractor {
    fun matches(url: String): Boolean = Regex("play\\.(?:echovideo\\.ru|vhscdn\\.to)/embed-[01]/", RegexOption.IGNORE_CASE).containsMatchIn(url)

    suspend fun extract(embedUrl: String, referer: String?): EmbedResult = withContext(Dispatchers.IO) {
        val url = java.net.URL(embedUrl)
        val match = Regex("/(embed-[01])/([^/]+)", RegexOption.IGNORE_CASE).find(url.path)
            ?: throw IllegalStateException("vidplay id not found")
        val type = match.groupValues[1]
        val id = match.groupValues[2]
        val endpoint = "${originOf(embedUrl)}/$type/getSources?id=$id"
        val json = rawGet(endpoint, mapOf("User-Agent" to UA, "Referer" to embedUrl, "X-Requested-With" to "XMLHttpRequest"))
        val obj = Mapper.json.parseToJsonElement(json) as? JsonObject ?: throw IllegalStateException("vidplay bad json")
        val sources = obj["sources"]
        val urls = when {
            sources is JsonArray -> sources.mapNotNull { s ->
                when (s) {
                    is JsonPrimitive -> s.contentOrNull
                    is JsonObject -> s["file"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: s["url"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                    else -> null
                }
            }.filter { it.isNotBlank() }
            sources is JsonPrimitive -> listOf(sources.contentOrNull ?: "").filter { it.isNotBlank() }
            else -> emptyList()
        }
        if (urls.isEmpty()) throw IllegalStateException("vidplay no sources")
        EmbedResult(urls = urls)
    }
}

object VidmolyExtractor {
    fun matches(url: String): Boolean = Regex("vidmoly\\.(net|biz|to)", RegexOption.IGNORE_CASE).containsMatchIn(url)

    suspend fun extract(embedUrl: String, referer: String?): EmbedResult = withContext(Dispatchers.IO) {
        val fixedUrl = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
        val html = rawGet(fixedUrl, mapOf("User-Agent" to UA, "Referer" to (referer ?: "https://animenosub.to/")))
        val m = Regex("""sources:\s*\[\s*\{\s*file:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""", RegexOption.IGNORE_CASE).find(html)
            ?: throw IllegalStateException("vidmoly m3u8 not found")
        EmbedResult(urls = listOf(m.groupValues[1]))
    }
}

object NovaExtractor {
    private val KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
    private val IV = "1234567890oiuytr".toByteArray(Charsets.UTF_8)

    fun matches(url: String): Boolean = url.contains("upn.one", ignoreCase = true)

    suspend fun extract(embedUrl: String, referer: String?): EmbedResult = withContext(Dispatchers.IO) {
        val id = Regex("/#([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(embedUrl)
            ?.groupValues?.get(1) ?: throw IllegalStateException("nova id not found")
        val json = rawGet("https://nova.upn.one/api/v1/video?id=$id&w=1920&h=1080&r=",
            mapOf("User-Agent" to UA, "Referer" to "https://nova.upn.one/"))
        val hex = json.trim()
        val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        val plain = cipher.doFinal(bytes)
        val obj = Mapper.json.parseToJsonElement(String(plain, Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalStateException("nova bad json")
        val url = (obj["cf"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["source"] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("nova no url")
        EmbedResult(urls = listOf(url))
    }
}

object ByseExtractor {
    fun matches(url: String): Boolean = Regex("(?:bysesayeveum\\.com|gn1r5n\\.org|mfw09\\.org|playmogo\\.com)/e/", RegexOption.IGNORE_CASE).containsMatchIn(url)

    suspend fun extract(embedUrl: String, referer: String?): EmbedResult = withContext(Dispatchers.IO) {
        val code = Regex("/e/([a-z0-9]+)", RegexOption.IGNORE_CASE).find(embedUrl)?.groupValues?.get(1)
            ?: throw IllegalStateException("byse code not found")
        val embedOrigin = runCatching { originOf(embedUrl) }.getOrElse { "" }
        val parentUrl = referer ?: embedUrl
        val parentHost = runCatching { java.net.URL(parentUrl).host }.getOrElse { "" }
        val embedHeaders = mapOf(
            "X-Embed-Origin" to parentHost,
            "X-Embed-Referer" to parentUrl,
            "X-Embed-Parent" to embedUrl,
            "User-Agent" to UA,
            "Referer" to embedUrl
        )
        val detailsJson = rawGet("$embedOrigin/api/videos/$code/embed/details", embedHeaders)
        val details = Mapper.json.parseToJsonElement(detailsJson) as? JsonObject
        val frameUrl = (details?.get("embed_frame_url") as? JsonPrimitive)?.contentOrNull ?: embedUrl
        val frameBase = runCatching { originOf(frameUrl) }.getOrElse { "" }

        val challengeJson = rawPostEmpty(
            "$frameBase/api/videos/access/challenge",
            mapOf("Origin" to frameBase, "Referer" to frameUrl, "User-Agent" to UA)
        )
        val challenge = Mapper.json.parseToJsonElement(challengeJson) as? JsonObject
            ?: throw IllegalStateException("byse challenge bad json")
        val nonce = (challenge["nonce"] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("byse nonce missing")
        val challengeId = (challenge["challenge_id"] as? JsonPrimitive)?.contentOrNull ?: ""

        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub = kp.public as java.security.interfaces.ECPublicKey
        val px = pub.w.affineX.toByteArray().normalize32()
        val py = pub.w.affineY.toByteArray().normalize32()
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(kp.private)
        sig.update(nonce.toByteArray(Charsets.UTF_8))
        val derSig = sig.sign()
        val rawSig = derToRaw(derSig)

        val attestJson = rawPostJson(
            "$frameBase/api/videos/access/attest",
            """{"nonce":"$nonce","challenge_id":"$challengeId","public_key":{"kty":"EC","crv":"P-256","x":"${b64ue(px)}","y":"${b64ue(py)}"},"signature":"${b64ue(rawSig)}"}""",
            mapOf("Content-Type" to "application/json", "Origin" to frameBase, "Referer" to frameUrl, "User-Agent" to UA)
        )
        val attest = Mapper.json.parseToJsonElement(attestJson) as? JsonObject
            ?: throw IllegalStateException("byse attest bad json")
        val viewerId = (attest["viewer_id"] as? JsonPrimitive)?.contentOrNull ?: ""
        val deviceId = (attest["device_id"] as? JsonPrimitive)?.contentOrNull ?: ""
        val token = (attest["token"] as? JsonPrimitive)?.contentOrNull ?: ""
        val confidence = (attest["confidence"] as? JsonPrimitive)?.contentOrNull ?: "0"
        val cookie = "byse_viewer_id=$viewerId; byse_device_id=$deviceId"
        val fingerprint = """{"token":"$token","viewer_id":"$viewerId","device_id":"$deviceId","confidence":"$confidence"}"""

        val captchaJson = rawPostJson(
            "$frameBase/api/videos/$code/embed/captcha",
            """{"fingerprint":$fingerprint}""",
            mapOf("Content-Type" to "application/json", "Origin" to frameBase, "Referer" to frameUrl, "User-Agent" to UA, "Cookie" to cookie) + embedHeaders
        )
        val captcha = Mapper.json.parseToJsonElement(captchaJson) as? JsonObject
            ?: throw IllegalStateException("byse captcha bad json")
        val powToken = (captcha["pow_token"] as? JsonPrimitive)?.contentOrNull ?: ""
        val nonceStr = (captcha["pow_nonce"] as? JsonPrimitive)?.contentOrNull ?: ""
        val difficulty = (captcha["pow_difficulty"] as? JsonPrimitive)?.intOrNull ?: 0
        val solution = solvePoW(nonceStr, difficulty)

        val verifyJson = rawPostJson(
            "$frameBase/api/videos/$code/embed/captcha/verify",
            """{"pow_token":"$powToken","solution":"$solution","fingerprint":$fingerprint}""",
            mapOf("Content-Type" to "application/json", "Origin" to frameBase, "Referer" to frameUrl, "User-Agent" to UA, "Cookie" to cookie) + embedHeaders
        )
        val verification = Mapper.json.parseToJsonElement(verifyJson) as? JsonObject
            ?: throw IllegalStateException("byse verify bad json")
        val verifyToken = (verification["token"] as? JsonPrimitive)?.contentOrNull ?: ""

        val playbackJson = rawPostJson(
            "$frameBase/api/videos/$code/embed/playback",
            """{"fingerprint":$fingerprint}""",
            mapOf("Content-Type" to "application/json", "Origin" to frameBase, "Referer" to frameUrl, "User-Agent" to UA, "Cookie" to cookie, "X-Captcha-Token" to verifyToken) + embedHeaders
        )
        val playbackData = Mapper.json.parseToJsonElement(playbackJson) as? JsonObject
            ?: throw IllegalStateException("byse playback bad json")
        val playback = playbackData["playback"] as? JsonObject
            ?: throw IllegalStateException("byse playback missing")
        val keyParts = playback["key_parts"] as? JsonArray ?: JsonArray(emptyList())
        val keyBytes = keyParts.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .mapNotNull { runCatching { b64ud(it) }.getOrNull() }
            .filter { it.size == 16 }
            .flatMap { it.toList() }.toByteArray()
        val iv = ((playback["iv"] as? JsonPrimitive)?.contentOrNull)?.let(::b64ud)
            ?: throw IllegalStateException("byse iv missing")
        val payload = ((playback["payload"] as? JsonPrimitive)?.contentOrNull)?.let(::b64ud)
            ?: throw IllegalStateException("byse payload missing")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(payload)
        val sources = Mapper.json.parseToJsonElement(String(plain, Charsets.UTF_8)) as? JsonObject
        val urls = sources?.get("sources")?.let { arr ->
            (arr as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("url")?.let { v -> (v as? JsonPrimitive)?.contentOrNull } }?.filterNotNull()
        } ?: emptyList()
        if (urls.isEmpty()) throw IllegalStateException("byse no sources")
        EmbedResult(urls = urls)
    }

    private fun ByteArray.normalize32(): ByteArray {
        val clean = if (size > 1 && this[0].toInt() == 0) copyOfRange(1, size) else this
        if (clean.size == 32) return clean
        if (clean.size > 32) return clean.copyOfRange(clean.size - 32, clean.size)
        return ByteArray(32 - clean.size) + clean
    }

    private fun derToRaw(sig: ByteArray): ByteArray {

        var i = 0
        var r: ByteArray
        var s: ByteArray
        fun readTlv(): ByteArray {
            i++
            var len = 0
            var lenBytes = 0
            var b = sig[i].toInt() and 0xFF
            if (b and 0x80 != 0) { lenBytes = b and 0x7F; i++ }
            else lenBytes = 0
            if (lenBytes == 0) { len = b; i++ }
            else {
                for (k in 0 until lenBytes) { len = (len shl 8) or (sig[i].toInt() and 0xFF); i++ }
            }
            val out = sig.copyOfRange(i, i + len)
            i += len
            return out
        }
        i += 1

        var seqLen = 0
        var seqLenBytes = 0
        var b = sig[i].toInt() and 0xFF
        if (b and 0x80 != 0) { seqLenBytes = b and 0x7F; i++ }
        else seqLenBytes = 0
        if (seqLenBytes == 0) { seqLen = b; i++ }
        else for (k in 0 until seqLenBytes) { seqLen = (seqLen shl 8) or (sig[i].toInt() and 0xFF); i++ }
        r = readTlv()
        s = readTlv()
        return r.normalize32() + s.normalize32()
    }

    private fun solvePoW(nonce: String, difficulty: Int): String {
        var counter = 0L
        while (true) {
            val input = "$nonce:$counter"
            val bytes = ByteArray(input.length) { input[it].code.toByte() }
            val hash = byseHash(bytes)
            if (leadingZeros(hash) >= difficulty) return counter.toString()
            counter++
        }
    }

    private fun leadingZeros(words: UIntArray): Int {
        var total = 0
        for (w in words) {
            if (w == 0u) { total += 32; continue }
            return total + w.countLeadingZeroBits()
        }
        return total
    }

    private val MASK = 511u
    private fun rot(v: UInt, s: Int): UInt = (v shl s) or (v shr (32 - s))

    private fun byseHash(bytes: ByteArray): UIntArray {
        var state = uintArrayOf(1779033703u, 3144134277u, 1013904242u, 2773480762u)
        fun mix() {
            state[0] = state[0] + state[1]
            state[3] = rot(state[3] xor state[0], 16)
            state[2] = state[2] + state[3]
            state[1] = rot(state[1] xor state[2], 12)
            state[0] = state[0] + state[1]
            state[3] = rot(state[3] xor state[0], 8)
            state[2] = state[2] + state[3]
            state[1] = rot(state[1] xor state[2], 7)
        }
        for (byte in bytes) {
            state[0] = state[0] + (byte.toInt() and 0xFF).toUInt()
            state[0] = rot(state[0], 7)
            mix()
        }
        repeat(8) { mix() }
        val table = UIntArray(512)
        for (i in 0 until 512) { mix(); table[i] = state[0] xor state[2] }
        repeat(2) {
            for (index in 0 until 512) {
                val tableIndex = table[index] and MASK
                var value = table[index] + table[tableIndex.toInt()]
                value = rot(value, 13)
                value = value xor (table[(index + 1) and MASK.toInt()] * 2654435761u)
                table[index] = value
                state[0] = state[0] xor value
                mix()
            }
        }
        val out = UIntArray(8)
        val width = 512 / 8
        for (i in 0 until 8) {
            mix()
            var value = state[0]
            val offset = i * width
            for (idx in 0 until width) {
                val tv = table[offset + idx]
                value = value + tv
                value = rot(value, 5)
                value = value xor (tv * 2246822519u)
            }
            out[i] = value xor state[2]
        }
        return out
    }
}

object BabaStreamExtractor {
    fun matches(url: String): Boolean = Regex("babastream\\.[^/]+/embed/", RegexOption.IGNORE_CASE).containsMatchIn(url)

    suspend fun extract(embedUrl: String, referer: String?): EmbedResult = withContext(Dispatchers.IO) {
        val pageUrl = java.net.URL(embedUrl)
        val origin = originOf(embedUrl)
        val html = rawGet(embedUrl, mapOf("User-Agent" to UA, "Referer" to (referer ?: "$origin/")))
        val config = parseConfig(html) ?: throw IllegalStateException("babastream config not found")
        val key = b64d(config["pk"] ?: "")
        val cryptoOptions = getCryptoOptions(html, key.size) ?: throw IllegalStateException("babastream crypto options not found")
        var resolve = Regex("""fetch\(\s*["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }
            .find { it.contains("resolve", ignoreCase = true) }
        var verify = Regex("""fetch\(\s*["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }
            .find { it.contains("verify", ignoreCase = true) }
        if (resolve == null || verify == null) throw IllegalStateException("babastream routes not found")

        fun encReq(route: String, body: String): JsonObject? {
            val enc = encrypt(body, key, cryptoOptions)
            val resp = rawPostJson(
                java.net.URL(java.net.URL(embedUrl), route).toString(),
                """{"s":"${config["sid"]}","d":"$enc"}""",
                mapOf("User-Agent" to UA, "Referer" to embedUrl, "Content-Type" to "application/json")
            )
            val obj = Mapper.json.parseToJsonElement(resp) as? JsonObject ?: return null
            val d = (obj["d"] as? JsonPrimitive)?.contentOrNull ?: return null
            val plain = decrypt(d, key, cryptoOptions) ?: return null
            return Mapper.json.parseToJsonElement(plain) as? JsonObject
        }

        var resolved = encReq(resolve, """{"ts":${System.currentTimeMillis()}}""")
        val errMsg = (resolved?.get("m") as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (resolved?.get("t")?.toString() == "\"error\"" || ((resolved?.get("t") as? JsonPrimitive)?.contentOrNull == "error")) {
            if (errMsg?.contains("verify") == true) {
                val capScripts = Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(html).map { runCatching { java.net.URL(java.net.URL(embedUrl), it.groupValues[1]).toString() }.getOrNull() }
                    .filterNotNull().toList()
                val widget = capScripts.mapNotNull { url ->
                    runCatching { rawGet(url, mapOf("User-Agent" to UA, "Referer" to embedUrl)) }.getOrNull()
                }.find { it.contains("challenge", ignoreCase = true) && it.contains("redeem", ignoreCase = true) && it.contains("SHA-256", ignoreCase = true) }
                    ?: throw IllegalStateException("babastream challenge client not found")
                val tmplSuffixes = mutableListOf<String>()
                var scan = 0
                while (true) {
                    val open = widget.indexOf('$', scan)
                    if (open < 0 || open + 1 >= widget.length) break
                    if (widget[open + 1] != '{') { scan = open + 1; continue }
                    val close = widget.indexOf('}', open + 2)
                    if (close < 0) break
                    var end = close + 1
                    while (end < widget.length && widget[end] != '`') end++
                    if (end > close + 1) tmplSuffixes.add(widget.substring(close + 1, end))
                    scan = end
                }
                val strings = extractScriptStrings(widget) + tmplSuffixes
                val challengePath = strings.find { it == "challenge" || it == "/challenge" || it.endsWith("/challenge") }
                    ?: throw IllegalStateException("challenge path not found")
                val redeemPath = strings.find { it == "redeem" || it == "/redeem" || it.endsWith("/redeem") }
                    ?: throw IllegalStateException("redeem path not found")
                val capBase = config["cap"] ?: origin
                val challengeResp = rawPostJson(
                    java.net.URL(java.net.URL(capBase), challengePath).toString(),
                    "",
                    mapOf("User-Agent" to UA, "Referer" to embedUrl, "Content-Type" to "application/json")
                )
                val challenge = Mapper.json.parseToJsonElement(challengeResp) as? JsonObject
                    ?: throw IllegalStateException("babastream challenge bad json")
                val solved = solveChallenge(challenge)
                val challengeToken = (challenge["token"] as? JsonPrimitive)?.contentOrNull ?: ""
                val redeemResp = rawPostJson(
                    java.net.URL(java.net.URL(capBase), redeemPath).toString(),
                    """{"token":"$challengeToken","solutions":$solved}""",
                    mapOf("User-Agent" to UA, "Referer" to embedUrl, "Content-Type" to "application/json")
                )
                val redeemed = Mapper.json.parseToJsonElement(redeemResp) as? JsonObject
                val redeemedToken = (redeemed?.get("token") as? JsonPrimitive)?.contentOrNull
                if ((redeemed?.get("success") as? JsonPrimitive)?.booleanOrNull != true || redeemedToken == null)
                    throw IllegalStateException("babastream cap verification failed")
                encReq(verify, """{"ts":${System.currentTimeMillis()},"token":"$redeemedToken","mode":"invisible"}""")
                resolved = encReq(resolve, """{"ts":${System.currentTimeMillis()}}""")
            }
        }
        val u = (resolved?.get("u") as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("babastream no stream")
        EmbedResult(urls = listOf(u), headers = mapOf("User-Agent" to UA, "Referer" to "$origin/"))
    }

    private fun parseConfig(html: String): Map<String, String>? {
        val re = Regex("""\b(?:var|let|const)\s+\w+\s*=\s*(\{[^;]+\});""")
        for (m in re.findAll(html)) {
            val cfg = runCatching {
                val obj = Mapper.json.parseToJsonElement(m.groupValues[1]) as? JsonObject ?: return@runCatching null
                val sid = (obj["sid"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
                val pk = (obj["pk"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
                mutableMapOf("sid" to sid, "pk" to pk).apply {
                    (obj["cap"] as? JsonPrimitive)?.contentOrNull?.let { put("cap", it) }
                }
            }.getOrNull()
            if (cfg != null) return cfg
        }
        return null
    }

    private data class CryptoOptions(val cipher: String, val ivLength: Int, val tagLength: Int)

    private fun getCryptoOptions(html: String, keyLen: Int): CryptoOptions? {
        val algorithm = Regex("""importKey\([^,]+,[^,]+,\s*\{\s*name\s*:\s*["'](AES-[A-Z]+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: return null
        val ivLength = Regex("""getRandomValues\(new Uint8Array\((\d+)\)\)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val tagLength = Regex("""tagLength\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 128
        if (ivLength < 1) return null
        val cipher = "AES/GCM/NoPadding"
        return CryptoOptions(cipher, ivLength, tagLength / 8)
    }

    private fun encrypt(value: String, key: ByteArray, opts: CryptoOptions): String {
        val iv = ByteArray(opts.ivLength).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(opts.cipher)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(opts.tagLength * 8, iv))
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return b64e(iv + ct)
    }

    private fun decrypt(value: String, key: ByteArray, opts: CryptoOptions): String? = try {
        val raw = b64d(value)
        val tagStart = raw.size - opts.tagLength
        if (tagStart <= opts.ivLength) return null
        val cipher = Cipher.getInstance(opts.cipher)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(opts.tagLength * 8, raw.copyOfRange(0, opts.ivLength)))
        cipher.update(raw.copyOfRange(opts.ivLength, tagStart))
        String(cipher.doFinal(), Charsets.UTF_8)
    } catch (_: Exception) { null }

    private fun extractScriptStrings(script: String): List<String> {
        val strings = mutableListOf<String>()
        var i = 0
        var prev = ""
        val len = script.length
        while (i < len) {
            val c = script[i]
            if (c == '/' && i + 1 < len && script[i + 1] == '/') { i = script.indexOf('\n', i + 2); if (i < 0) break; continue }
            if (c == '/' && i + 1 < len && script[i + 1] == '*') { i = script.indexOf("*/", i + 2); if (i < 0) break; i += 2; continue }
            if (c == '/' && prev.isNotEmpty() && prev[0] in "=(:[!&|?{};") {
                i++; var inClass = false
                while (i < len) {
                    if (script[i] == '\\') { i += 2; continue }
                    if (script[i] == '[') inClass = true
                    if (script[i] == ']') inClass = false
                    if (script[i] == '/' && !inClass) { i++; while (i < len && script[i].isLetter()) i++; break }
                    i++
                }
                continue
            }
            if (c == '"' || c == '\'') {
                val q = c; i++; val sb = StringBuilder()
                while (i < len && script[i] != q) {
                    if (script[i] == '\\' && i + 1 < len) { i++; sb.append(script[i]) } else sb.append(script[i])
                    i++
                }
                if (i < len) i++
                strings.add(sb.toString().replace("\\n", "\n").replace("\\t", "\t"))
                prev = ""
                continue
            }
            if (!c.isWhitespace()) prev = c.toString()
            i++
        }
        return strings.distinct()
    }

    private fun solveProof(salt: String, target: String): Long {
        var nonce = 0L
        while (true) {
            val h = sha256hex(salt + nonce)
            if (h.startsWith(target)) return nonce
            nonce++
        }
    }

    private fun seededHex(value: String, length: Int): String {
        var state = 2166136261L
        for (ch in value) {
            state = state xor ch.code.toLong()
            state = (state + (state shl 1) + (state shl 4) + (state shl 7) + (state shl 8) + (state shl 24)) and 0xFFFFFFFFL
        }
        var out = StringBuilder()
        while (out.length < length) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            state = state and 0xFFFFFFFFL
            out.append(java.lang.Long.toHexString(state).padStart(8, '0'))
        }
        return out.toString().substring(0, length)
    }

    private fun solveChallenge(challenge: JsonObject): String {
        val challenges = challenge["challenges"] as? JsonArray
        if (challenges != null) {
            val proofs = challenges.mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                val protocol = (o["protocol"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (protocol != "sha256-pow") return@mapNotNull null
                val payload = o["payload"] as? JsonObject ?: return@mapNotNull null
                val salt = (payload["salt"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val target = (payload["target"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                """{"nonce":"${solveProof(salt, target)}"}"""
            }
            return "[${proofs.joinToString(",")}]"
        }
        val token = (challenge["token"] as? JsonPrimitive)?.contentOrNull ?: ""
        val challengeObj = challenge["challenge"] as? JsonObject ?: return "[]"
        val c = (challengeObj["c"] as? JsonPrimitive)?.intOrNull ?: 0
        val s = (challengeObj["s"] as? JsonPrimitive)?.intOrNull ?: 0
        val d = (challengeObj["d"] as? JsonPrimitive)?.intOrNull ?: 0
        val proofs = (1..c).map { counter ->
            val salt = seededHex("$token$counter", s)
            val target = seededHex("${token}${counter}d", d)
            solveProof(salt, target)
        }
        return "[${proofs.joinToString(",")}]"
    }
}

object DataSvExtractor {
    fun matches(url: String): Boolean = Regex("play\\.echovideo\\.ru/embed-20/", RegexOption.IGNORE_CASE).containsMatchIn(url)

    suspend fun extract(embedUrl: String, referer: String?): EmbedResult = withContext(Dispatchers.IO) {
        val url = java.net.URL(embedUrl)
        val id = Regex("/embed-20/([^/]+)", RegexOption.IGNORE_CASE).find(url.path)?.groupValues?.get(1)
            ?: throw IllegalStateException("datasv id not found")
        val endpoint = "${originOf(embedUrl)}/embed-20/getSources?id=$id"
        val json = rawGet(endpoint, mapOf("User-Agent" to UA, "Referer" to embedUrl, "X-Requested-With" to "XMLHttpRequest"))
        val obj = Mapper.json.parseToJsonElement(json) as? JsonObject ?: throw IllegalStateException("datasv bad json")
        val sources = mutableListOf<Pair<String, String>>()
        (obj["sources"] as? JsonObject)?.forEach { (quality, v) ->
            val list = when (v) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(v.contentOrNull)
                else -> emptyList()
            }
            list.forEach { if (it.isNotBlank()) sources.add(it to quality) }
        }
        val valid = sources.filter { (u, _) ->
            runCatching {
                val req = Request.Builder().url(u).method("HEAD", null)
                    .header("User-Agent", UA).header("Referer", "${originOf(embedUrl)}/").build()
                okHttpClient.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }.map { it.first }
        if (valid.isEmpty()) throw IllegalStateException("datasv no available sources")
        EmbedResult(urls = valid)
    }
}

object AnimeSaltExtractor {
    fun matches(url: String): Boolean {
        val low = url.lowercase()
        return low.contains("animesalt") || low.contains("as-cdn") || low.contains("acdn.top")
    }

    suspend fun extract(url: String, referer: String?): EmbedResult = withContext(Dispatchers.IO) {
        val ref = referer ?: "https://animesalt.cx/"
        var targetPageUrl = url
        var videoOrigin = ""
        var videoHash = ""
        var iframeUrl = ""

        val direct = Regex("""https?://(?:as-cdn\d*|acdn)\.top/video/([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE).find(url)
        if (direct != null) {
            iframeUrl = url
            videoHash = direct.groupValues[1]
            videoOrigin = runCatching { originOf(url) }.getOrDefault("")
        } else {
            val html = rawGet(url, mapOf("User-Agent" to UA, "Referer" to ref))
            val m = Regex("""src=["'](https?://(?:as-cdn\d*|acdn)\.top/video/([a-zA-Z0-9_-]+))["']""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""data-src=["'](https?://(?:as-cdn\d*|acdn)\.top/video/([a-zA-Z0-9_-]+))["']""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""(https?://(?:as-cdn\d*|acdn)\.top/video/([a-zA-Z0-9_-]+))""", RegexOption.IGNORE_CASE).find(html)
            if (m != null) {
                iframeUrl = m.groupValues[1]
                videoHash = m.groupValues[2]
                videoOrigin = runCatching { originOf(iframeUrl) }.getOrDefault("")
            }
        }
        if (videoHash.isEmpty() || videoOrigin.isEmpty()) throw IllegalStateException("animesalt player not found")
        val postUrl = "$videoOrigin/player/index.php?data=$videoHash&do=getVideo"
        val resp = rawPostForm(
            postUrl,
            mapOf("hash" to videoHash, "r" to targetPageUrl),
            mapOf("User-Agent" to UA, "Referer" to (iframeUrl.ifEmpty { "$videoOrigin/video/$videoHash" }), "X-Requested-With" to "XMLHttpRequest", "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8")
        )
        val obj = runCatching { Mapper.json.parseToJsonElement(resp) as? JsonObject }.getOrNull()
        val videoSource = (obj?.get("videoSource") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("securedLink") as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("animesalt no source")
        EmbedResult(urls = listOf(videoSource), headers = mapOf("Referer" to "$videoOrigin/", "User-Agent" to UA, "Origin" to videoOrigin))
    }
}
