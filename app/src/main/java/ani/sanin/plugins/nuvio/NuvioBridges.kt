package ani.sanin.plugins.nuvio

import ani.sanin.plugins.nuvio.runtime.NuvioHostModule
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.select.Elements
import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

private const val NUVIO_FETCH_MAX_HEADER_CHARS = 8 * 1024
private const val NUVIO_FETCH_TRUNCATION_SUFFIX = "\n...[truncated]"
private const val NUVIO_TIMER_MAX_DELAY_MS = 60_000L

internal class NuvioHostFunctions(
    private val scraperId: String,
    private val scraperSettingsJson: String,
    private val callArgsJson: String,
    private val onResult: (String) -> Unit,
) : NuvioHostModule {
    override fun register(runtime: QuickJs) {
        runtime.asyncFunction("__plugin_sleep") { args ->
            val ms = (args.getOrNull(0) as? Number)?.toLong()?.coerceIn(0L, NUVIO_TIMER_MAX_DELAY_MS) ?: 0L
            delay(ms)
            null
        }
        runtime.define("console") {
            function("log") { args -> log("DEBUG", args); null }
            function("info") { args -> log("INFO", args); null }
            function("debug") { args -> log("DEBUG", args); null }
            function("warn") { args -> log("WARN", args); null }
            function("error") { args -> log("ERROR", args); null }
        }
        runtime.function("__get_scraper_id") { scraperId }
        runtime.function("__get_scraper_settings") { scraperSettingsJson }
        runtime.function("__get_tmdb_api_key") { PrefManager.getVal<String>(PrefName.TmdbApiKey) }
        runtime.function("__get_call_args") { callArgsJson }
        runtime.function("__capture_result") { args ->
            onResult(args.getOrNull(0)?.toString() ?: "[]")
            null
        }
    }

    private fun log(level: String, args: Array<Any?>) {
        val message = "NUVIO:$scraperId ${args.joinToString(" ") { it?.toString() ?: "null" }}"
        Logger.log(message)
    }
}

internal class NuvioFetchBridge : NuvioHostModule {
    private val json = Json { ignoreUnknownKeys = true }
    private val client get() = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client
        .newBuilder().cache(null).build()

    override fun register(runtime: QuickJs) {
        runtime.asyncFunction("__native_fetch") { args ->
            val url = args.getOrNull(0)?.toString().orEmpty()
            val method = args.getOrNull(1)?.toString()?.takeIf { it.isNotBlank() } ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val bodyKind = args.getOrNull(3)?.toString() ?: "none"
            val body = args.getOrNull(4)?.toString().orEmpty()
            val followRedirects = args.getOrNull(5) as? Boolean ?: true
            runCatching {
                performFetch(url, method, headersJson, bodyKind, body, followRedirects)
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                resultJson(false, 0, error.message ?: "Fetch failed", url, "", "", emptyMap())
            }
        }
    }

    private suspend fun performFetch(
        url: String,
        method: String,
        headersJson: String,
        bodyKind: String,
        body: String,
        followRedirects: Boolean,
    ): String {
        val headers = parseHeaders(headersJson).toMutableMap()
        headers.putIfAbsent("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        headers["Cache-Control"] = "no-store"
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }
        val requestBody = when (bodyKind) {
            "text" -> body.toRequestBody(null)
            "base64" -> decodeBase64(body).toRequestBody(null)
            else -> null
        }
        requestBuilder.method(method, requestBody)
        val localClient = if (followRedirects) client else client.newBuilder().followRedirects(false).build()
        localClient.newCall(requestBuilder.build()).execute().use { response ->
            val bytes: ByteArray = response.body?.bytes() ?: ByteArray(0)
            val responseHeaders = response.headers.toMap()
                .mapKeys { (key, _) -> key.lowercase() }
                .mapValues { (_, value) -> truncate(value) }
            return resultJson(
                ok = response.isSuccessful,
                status = response.code,
                statusText = response.message,
                url = response.request.url.toString(),
                body = bytes.decodeToString(),
                bodyBase64 = encodeBase64(bytes),
                headers = responseHeaders,
            )
        }
    }

    private fun parseHeaders(value: String): Map<String, String> = runCatching {
        val obj = json.parseToJsonElement(value) as? JsonObject ?: JsonObject(emptyMap())
        obj.entries.mapNotNull { (key, element) ->
            (element as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun truncate(value: String): String {
        if (value.length <= NUVIO_FETCH_MAX_HEADER_CHARS) return value
        val end = NUVIO_FETCH_MAX_HEADER_CHARS - NUVIO_FETCH_TRUNCATION_SUFFIX.length
        return if (end <= 0) NUVIO_FETCH_TRUNCATION_SUFFIX.take(NUVIO_FETCH_MAX_HEADER_CHARS)
        else value.substring(0, end) + NUVIO_FETCH_TRUNCATION_SUFFIX
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(value: String): ByteArray = Base64.decode(value)

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeBase64(value: ByteArray): String = Base64.encode(value)

    private fun resultJson(
        ok: Boolean,
        status: Int,
        statusText: String,
        url: String,
        body: String,
        bodyBase64: String,
        headers: Map<String, String>,
    ): String = JsonObject(
        mapOf(
            "ok" to JsonPrimitive(ok),
            "status" to JsonPrimitive(status),
            "statusText" to JsonPrimitive(statusText),
            "url" to JsonPrimitive(url),
            "body" to JsonPrimitive(body),
            "bodyBase64" to JsonPrimitive(bodyBase64),
            "headers" to JsonObject(headers.mapValues { JsonPrimitive(it.value) }),
        )
    ).toString()
}

internal class NuvioUrlBridge : NuvioHostModule {
    override fun register(runtime: QuickJs) {
        runtime.function("__parse_url") { args -> parseUrl(args.getOrNull(0)?.toString().orEmpty()) }
    }

    private fun parseUrl(value: String): String = runCatching {
        val parsed = Url(value)
        JsonObject(
            mapOf(
                "protocol" to JsonPrimitive("${parsed.protocol.name}:"),
                "host" to JsonPrimitive(
                    if (parsed.port != parsed.protocol.defaultPort) "${parsed.host}:${parsed.port}" else parsed.host
                ),
                "hostname" to JsonPrimitive(parsed.host),
                "port" to JsonPrimitive(if (parsed.port != parsed.protocol.defaultPort) parsed.port.toString() else ""),
                "pathname" to JsonPrimitive(parsed.encodedPath.ifBlank { "/" }),
                "search" to JsonPrimitive(parsed.encodedQuery?.let { "?$it" }.orEmpty()),
                "hash" to JsonPrimitive(parsed.encodedFragment?.let { "#$it" }.orEmpty()),
            )
        ).toString()
    }.getOrDefault(
        JsonObject(
            mapOf(
                "protocol" to JsonPrimitive(""),
                "host" to JsonPrimitive(""),
                "hostname" to JsonPrimitive(""),
                "port" to JsonPrimitive(""),
                "pathname" to JsonPrimitive("/"),
                "search" to JsonPrimitive(""),
                "hash" to JsonPrimitive(""),
            )
        ).toString()
    )
}

internal class NuvioDomBridge : NuvioHostModule {
    private val documents = mutableMapOf<String, Document>()
    private val elements = mutableMapOf<String, Element>()
    private val loaded = mutableListOf<String>()
    private var counter = 0
    private val containsRegex = Regex(""":contains\([\"']([^\"']+)[\"']\)""")

    override fun register(runtime: QuickJs) {
        runtime.function("__cheerio_load") { args ->
            val html = args.getOrNull(0)?.toString().orEmpty()
            val id = "doc_${counter++}_${Random.nextInt(0, Int.MAX_VALUE)}"
            documents[id] = Ksoup.parse(html)
            loaded.add(id)
            evict()
            id
        }
        runtime.function("__cheerio_select") { args ->
            val docId = args.getOrNull(0)?.toString().orEmpty()
            val doc = documents[docId] ?: return@function "[]"
            val selector = args.getOrNull(1)?.toString().orEmpty().replace(containsRegex, ":contains($1)")
            val ids = runCatching {
                if (selector.isBlank()) Elements() else doc.select(selector)
            }.getOrDefault(Elements()).mapIndexed { index, element ->
                val id = "$docId:$index:${element.hashCode()}"
                elements[id] = element
                id
            }
            ids.toJsonArray()
        }
        runtime.function("__cheerio_find") { args ->
            val docId = args.getOrNull(0)?.toString().orEmpty()
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            val element = elements[elementId] ?: return@function "[]"
            val selector = args.getOrNull(2)?.toString().orEmpty().replace(containsRegex, ":contains($1)")
            val ids = runCatching {
                element.select(selector)
            }.getOrDefault(Elements()).mapIndexed { index, child ->
                val id = "$docId:find:$index:${child.hashCode()}"
                elements[id] = child
                id
            }
            ids.toJsonArray()
        }
        runtime.function("__cheerio_text") { args ->
            args.getOrNull(1)?.toString().orEmpty().split(",")
                .filter { it.isNotEmpty() }
                .mapNotNull { elements[it]?.text() }
                .joinToString(" ")
        }
        runtime.function("__cheerio_html") { args ->
            val docId = args.getOrNull(0)?.toString().orEmpty()
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            if (elementId.isBlank()) documents[docId]?.html().orEmpty() else elements[elementId]?.html().orEmpty()
        }
        runtime.function("__cheerio_inner_html") { args ->
            elements[args.getOrNull(1)?.toString().orEmpty()]?.html().orEmpty()
        }
        runtime.function("__cheerio_attr") { args ->
            val element = elements[args.getOrNull(1)?.toString().orEmpty()]
            val name = args.getOrNull(2)?.toString().orEmpty()
            element?.attr(name)?.takeIf { it.isNotEmpty() } ?: "__UNDEFINED__"
        }
        runtime.function("__cheerio_next") { args ->
            siblingId(args, next = true)
        }
        runtime.function("__cheerio_prev") { args ->
            siblingId(args, next = false)
        }
    }

    fun clear() {
        documents.clear()
        elements.clear()
        loaded.clear()
    }

    private fun siblingId(args: Array<Any?>, next: Boolean): String {
        val docId = args.getOrNull(0)?.toString().orEmpty()
        val element = elements[args.getOrNull(1)?.toString().orEmpty()] ?: return "__NONE__"
        val sibling = (if (next) element.nextElementSibling() else element.previousElementSibling())
            ?: return "__NONE__"
        val id = "$docId:${if (next) "next" else "prev"}:${sibling.hashCode()}"
        elements[id] = sibling
        return id
    }

    private fun evict() {
        while (loaded.size > 8) {
            val id = loaded.removeAt(0)
            documents.remove(id)
            elements.keys.filter { it.startsWith("$id:") }.forEach { elements.remove(it) }
        }
    }

    private fun List<String>.toJsonArray(): String =
        "[" + joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
}

internal class NuvioCryptoBridge : NuvioHostModule {
    override fun register(runtime: QuickJs) {
        runtime.function("__crypto_get_random_values_hex") { args ->
            nuvioGetRandomValues((args.getOrNull(0) as? Number)?.toInt() ?: 0).toHex()
        }
        runtime.function("__crypto_digest_hex_raw") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val data = nuvioHexToByteArray(args.getOrNull(1)?.toString().orEmpty())
            runCatching { nuvioDigest(algorithm, data).toHex() }.getOrDefault("")
        }
        runtime.function("__crypto_hmac_hex_raw") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val key = nuvioHexToByteArray(args.getOrNull(1)?.toString().orEmpty())
            val data = nuvioHexToByteArray(args.getOrNull(2)?.toString().orEmpty())
            runCatching { nuvioHmac(algorithm, key, data).toHex() }.getOrDefault("")
        }
        runtime.function("__crypto_pbkdf2_hex") { args ->
            val password = nuvioHexToByteArray(args.getOrNull(0)?.toString().orEmpty())
            val salt = nuvioHexToByteArray(args.getOrNull(1)?.toString().orEmpty())
            val iterations = (args.getOrNull(2) as? Number)?.toInt() ?: 1000
            val bits = (args.getOrNull(3) as? Number)?.toInt() ?: 256
            val algorithm = args.getOrNull(4)?.toString() ?: "SHA256"
            runCatching { nuvioPbkdf2(password, salt, iterations, bits, algorithm).toHex() }.getOrDefault("")
        }
        runtime.function("__crypto_aes_encrypt_hex") { args ->
            val mode = args.getOrNull(0)?.toString() ?: "AES-CBC"
            val key = nuvioHexToByteArray(args.getOrNull(1)?.toString().orEmpty())
            val iv = nuvioHexToByteArray(args.getOrNull(2)?.toString().orEmpty())
            val data = nuvioHexToByteArray(args.getOrNull(3)?.toString().orEmpty())
            runCatching { nuvioAesEncrypt(mode, key, iv, data).toHex() }.getOrDefault("")
        }
        runtime.function("__crypto_aes_decrypt_hex") { args ->
            val mode = args.getOrNull(0)?.toString() ?: "AES-CBC"
            val key = nuvioHexToByteArray(args.getOrNull(1)?.toString().orEmpty())
            val iv = nuvioHexToByteArray(args.getOrNull(2)?.toString().orEmpty())
            val data = nuvioHexToByteArray(args.getOrNull(3)?.toString().orEmpty())
            runCatching { nuvioAesDecrypt(mode, key, iv, data).toHex() }.getOrDefault("")
        }
        runtime.function("__crypto_sign_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString().orEmpty()
            val key = nuvioHexToByteArray(args.getOrNull(1)?.toString().orEmpty())
            val data = nuvioHexToByteArray(args.getOrNull(2)?.toString().orEmpty())
            runCatching { nuvioSign(algorithm, key, data).toHex() }.getOrDefault("")
        }
        runtime.function("__crypto_verify_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString().orEmpty()
            val key = nuvioHexToByteArray(args.getOrNull(1)?.toString().orEmpty())
            val signature = nuvioHexToByteArray(args.getOrNull(2)?.toString().orEmpty())
            val data = nuvioHexToByteArray(args.getOrNull(3)?.toString().orEmpty())
            runCatching { nuvioVerify(algorithm, key, signature, data) }.getOrDefault(false)
        }
        runtime.function("__crypto_digest_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val data = args.getOrNull(1)?.toString().orEmpty()
            runCatching { nuvioDigest(algorithm, data.encodeToByteArray()).toHex() }.getOrDefault("")
        }
        runtime.function("__crypto_hmac_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val key = args.getOrNull(1)?.toString().orEmpty()
            val data = args.getOrNull(2)?.toString().orEmpty()
            runCatching { nuvioHmac(algorithm, key.encodeToByteArray(), data.encodeToByteArray()).toHex() }.getOrDefault("")
        }
        runtime.function("__crypto_base64_encode") { args ->
            runCatching { nuvioBase64Encode(args.getOrNull(0)?.toString().orEmpty()) }.getOrDefault("")
        }
        runtime.function("__crypto_base64_decode") { args ->
            runCatching { nuvioBase64Decode(args.getOrNull(0)?.toString().orEmpty()) }.getOrDefault("")
        }
        runtime.function("__crypto_utf8_to_hex") { args ->
            nuvioUtf8ToHex(args.getOrNull(0)?.toString().orEmpty())
        }
        runtime.function("__crypto_hex_to_utf8") { args ->
            runCatching { nuvioHexToUtf8(args.getOrNull(0)?.toString().orEmpty()) }.getOrDefault("")
        }
    }
}

internal class NuvioWasmBridge : NuvioHostModule {
    override fun register(runtime: QuickJs) = Unit
}
