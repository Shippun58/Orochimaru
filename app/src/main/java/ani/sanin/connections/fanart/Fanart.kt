package ani.sanin.connections.fanart

import android.util.Log
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import kotlin.math.min

@Serializable
data class FanartImage(
    val url: String? = null,
    val lang: String? = null,
    @SerialName("likes") val likes: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("width") val width: String? = null,
    @SerialName("height") val height: String? = null,
)

@Serializable
data class FanartTv(
    val name: String? = null,
    @SerialName("hdtvlogo") val hdLogo: List<FanartImage> = emptyList(),
    @SerialName("clearlogo") val clearLogo: List<FanartImage> = emptyList(),
    @SerialName("hdtvclearart") val hdClearart: List<FanartImage> = emptyList(),
    @SerialName("clearart") val clearart: List<FanartImage> = emptyList(),
    @SerialName("show4kbackground") val background4k: List<FanartImage> = emptyList(),
    @SerialName("showbackground") val background: List<FanartImage> = emptyList(),
    @SerialName("tvposter") val poster: List<FanartImage> = emptyList(),
)

object Fanart {
    private const val BASE = "https://webservice.fanart.tv/v3.2"

    private const val MAX_CACHE = 64

    private const val MAX_429_RETRIES = 3

    private const val BASE_BACKOFF_MS = 500L
    private const val MAX_BACKOFF_MS = 8_000L

    private val cache = object : LinkedHashMap<Int, FanartTv?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FanartTv?>?): Boolean =
            size > MAX_CACHE
    }

    private val inFlight = HashMap<Int, CompletableDeferred<FanartTv?>>()

    val apiKey: String get() = PrefManager.getVal(PrefName.FanartApiKey)

suspend fun tv(tvdbId: Int): FanartTv? {
        synchronized(cache) {
            if (cache.containsKey(tvdbId)) return cache[tvdbId]
        }

        val mine = CompletableDeferred<FanartTv?>()
        val pending = synchronized(inFlight) { inFlight.putIfAbsent(tvdbId, mine) ?: mine }
        if (pending !== mine) return pending.await()

        val parsed = fetch(tvdbId)
        synchronized(inFlight) { inFlight.remove(tvdbId) }
        mine.complete(parsed)

        if (parsed != null) synchronized(cache) { cache[tvdbId] = parsed }
        return parsed
    }

    private suspend fun fetch(tvdbId: Int): FanartTv? {
        val key = apiKey
        if (key.isBlank()) {
            Logger.log(Log.WARN, "Fanart: no API key, skipping tv/$tvdbId")
            return null
        }

        var attempt = 0
        while (true) {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$BASE/tv/$tvdbId?api_key=$key")
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        when {

                            response.code == 404 -> Outcome.Missing
                            response.code == 429 -> Outcome.Throttled(response.header("Retry-After"))
                            !response.isSuccessful -> {
                                Logger.log(Log.WARN, "Fanart tv/$tvdbId HTTP ${response.code}")
                                Outcome.Failed
                            }
                            else -> Outcome.Body(response.body?.string().orEmpty())
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log(Log.WARN, "Fanart tv/$tvdbId failed: ${e.message}")
                return null
            }

            when (outcome) {

                is Outcome.Missing -> return FanartTv()
                is Outcome.Failed -> return null
                is Outcome.Body -> return runCatching {
                    Mapper.json.decodeFromString<FanartTv>(outcome.text)
                }.onFailure {
                    Logger.log(Log.WARN, "Fanart tv/$tvdbId decode failed: ${it.message}")
                }.getOrNull()
                is Outcome.Throttled -> {
                    if (attempt >= MAX_429_RETRIES) {
                        Logger.log(Log.WARN, "Fanart tv/$tvdbId still throttled after $attempt retries")
                        return null
                    }
                    val wait = outcome.retryAfterMs() ?: backoffMs(attempt)
                    Logger.log(Log.WARN, "Fanart tv/$tvdbId 429, waiting ${wait}ms")
                    delay(wait)
                    attempt++
                }
            }
        }
    }

    private sealed interface Outcome {
        data object Missing : Outcome
        data object Failed : Outcome
        data class Body(val text: String) : Outcome
        data class Throttled(val retryAfter: String?) : Outcome
    }

    private fun Outcome.Throttled.retryAfterMs(): Long? {
        val seconds = retryAfter?.trim()?.toLongOrNull() ?: return null
        if (seconds <= 0) return null
        return min(seconds * 1000L, MAX_BACKOFF_MS)
    }

    private fun backoffMs(attempt: Int): Long =
        min(BASE_BACKOFF_MS shl attempt, MAX_BACKOFF_MS)

private fun english(group: List<FanartImage>): FanartImage? =
group.firstOrNull { it.lang.equals("en", ignoreCase = true) && !it.url.isNullOrBlank() }

private fun anyLanguage(group: List<FanartImage>): FanartImage? =
group.firstOrNull { !it.url.isNullOrBlank() }

private fun urlOf(group: List<FanartImage>): String? = english(group)?.url

    suspend fun logoUrl(tvdbId: Int): String? =
        tv(tvdbId)?.let { urlOf(it.hdLogo) ?: urlOf(it.clearLogo) }

    suspend fun clearartOrLogoUrl(tvdbId: Int): String? =
        tv(tvdbId)?.let {
            urlOf(it.hdClearart) ?: urlOf(it.clearart) ?: urlOf(it.hdLogo) ?: urlOf(it.clearLogo)
        }

    suspend fun backgroundUrl(tvdbId: Int): String? =
        tv(tvdbId)?.let { anyLanguage(it.background4k)?.url ?: anyLanguage(it.background)?.url }

    suspend fun posterUrl(tvdbId: Int): String? = tv(tvdbId)?.let { anyLanguage(it.poster)?.url }
}