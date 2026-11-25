package ani.sanin.plugins.nuvio.runtime

import ani.sanin.plugins.nuvio.NuvioManifestScraper
import ani.sanin.plugins.nuvio.NuvioRuntimeResult
import ani.sanin.plugins.nuvio.NuvioScraper
import ani.sanin.plugins.nuvio.NuvioStorage
import ani.sanin.plugins.nuvio.NuvioSubtitleResult
import ani.sanin.plugins.nuvio.runtime.js.NuvioJsBindings
import ani.sanin.util.Logger
import android.os.Process
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.Executors

private const val NUVIO_TIMEOUT_MS = 60_000L
private const val NUVIO_MAX_CONCURRENT = 10

private val pluginDispatcher: CoroutineDispatcher =
    Executors.newFixedThreadPool(NUVIO_MAX_CONCURRENT) { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "nuvio-plugin-worker").apply { isDaemon = true }
    }.asCoroutineDispatcher()

private val semaphore = Semaphore(NUVIO_MAX_CONCURRENT)
private val json = Json { ignoreUnknownKeys = true }

internal object NuvioRuntime {
    suspend fun execute(
        scraper: NuvioScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): List<NuvioRuntimeResult> = semaphore.withPermit {
        withContext(pluginDispatcher) {
            withTimeout(NUVIO_TIMEOUT_MS) {
                executeInternal(scraper, tmdbId, mediaType, season, episode)
            }
        }
    }

    suspend fun settingsLayout(scraper: NuvioScraper): String = semaphore.withPermit {
        withContext(pluginDispatcher) {
            withTimeout(NUVIO_TIMEOUT_MS) {
                val deferred = CompletableDeferred<String>()
                val registry = NuvioHostApiRegistry.create(
                    scraperId = scraper.id,
                    settingsJson = "{}",
                    callArgsJson = "{}",
                    onResult = { deferred.complete(it) },
                    includeCallBindings = false,
                )
                try {
                    quickJs(pluginDispatcher) {
                        configure()
                        registry.registerAll(this)
                        evaluate<Any?>(NuvioJsBindings.staticPolyfillCode)
                        evaluate<Any?>(wrapModule(scraper.code))
                        evaluate<Any?>(NuvioJsBindings.staticSettingsCallCode)
                    }
                    runCatching { deferred.await() }.getOrDefault("[]")
                } catch (error: Throwable) {
                    Logger.log("NUVIO: settings layout failed for ${scraper.id}: ${error.message}")
                    "[]"
                }
            }
        }
    }

    private suspend fun executeInternal(
        scraper: NuvioScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): List<NuvioRuntimeResult> {
        val deferred = CompletableDeferred<String>()
        val settingsJson = NuvioStorage.loadSettings(scraper.id)
        val callArgs = JsonObject(
            mapOf(
                "tmdbId" to JsonPrimitive(tmdbId),
                "mediaType" to JsonPrimitive(mediaType),
                "season" to (season?.let(::JsonPrimitive) ?: JsonNull),
                "episode" to (episode?.let(::JsonPrimitive) ?: JsonNull),
            )
        ).toString()
        val registry = NuvioHostApiRegistry.create(
            scraperId = scraper.id,
            settingsJson = settingsJson,
            callArgsJson = callArgs,
            onResult = { deferred.complete(it) },
            includeCallBindings = true,
        )
        return try {
            quickJs(pluginDispatcher) {
                configure()
                registry.registerAll(this)
                evaluate<Any?>(NuvioJsBindings.staticPolyfillCode)
                evaluate<Any?>(wrapModule(scraper.code))
                evaluate<Any?>(NuvioJsBindings.staticCallCode)
            }
            parseResults(deferred.await())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Logger.log("NUVIO: runtime failed for ${scraper.id}: ${error.message}")
            emptyList()
        }
    }

    private fun QuickJs.configure() {
        evaluationTimeoutMillis = NUVIO_TIMEOUT_MS
    }

    private fun wrapModule(code: String): String = """
        var module = { exports: {} };
        var exports = module.exports;
        (function() {
            $code
        })();
    """.trimIndent()

    private fun parseResults(raw: String): List<NuvioRuntimeResult> = runCatching {
        val array = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = when (val value = item["url"]) {
                is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
                is JsonObject -> (value["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                else -> null
            } ?: return@mapNotNull null
            NuvioRuntimeResult(
                title = item.stringOrNull("title") ?: item.stringOrNull("name") ?: scraperFallbackTitle(item),
                name = item.stringOrNull("name"),
                url = url,
                quality = item.stringOrNull("quality"),
                size = item.stringOrNull("size"),
                language = item.stringOrNull("language"),
                provider = item.stringOrNull("provider"),
                type = item.stringOrNull("type"),
                seeders = item.intOrNull("seeders"),
                peers = item.intOrNull("peers"),
                infoHash = item.stringOrNull("infoHash"),
                headers = item.objectStrings("headers"),
                subtitles = (item["subtitles"] as? JsonArray)?.mapNotNull { sub ->
                    val subItem = sub as? JsonObject ?: return@mapNotNull null
                    val subUrl = subItem.stringOrNull("url") ?: return@mapNotNull null
                    NuvioSubtitleResult(
                        url = subUrl,
                        language = subItem.stringOrNull("language") ?: "Unknown",
                        name = subItem.stringOrNull("name"),
                        headers = subItem.objectStrings("headers"),
                    )
                }?.takeIf { it.isNotEmpty() },
            )
        }
    }.getOrElse { emptyList() }

    private fun scraperFallbackTitle(item: JsonObject): String =
        item.stringOrNull("provider") ?: item.stringOrNull("title") ?: "Nuvio"

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.let { value ->
            when (value) {
                is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() && !it.contains("[object") }
                is JsonNull -> null
                else -> null
            }
        }

    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.let { value ->
            when (value) {
                is JsonPrimitive -> value.intOrNull ?: value.contentOrNull?.toIntOrNull()
                else -> null
            }
        }

    private fun JsonObject.objectStrings(key: String): Map<String, String>? =
        (this[key] as? JsonObject)?.entries?.mapNotNull { (name, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { name to it }
        }?.toMap()?.takeIf { it.isNotEmpty() }
}

internal fun supportsNuvioType(scraper: NuvioManifestScraper, mediaType: String): Boolean {
    val normalized = mediaType.lowercase()
    return scraper.supportedTypes.any { it.lowercase() == normalized || it.lowercase() == "other" }
}
