package ani.sanin.subdub

import android.util.LruCache
import ani.sanin.util.Logger
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

object SubDubCache {

    private const val BASE_URL = "https://anivault-proxy.shemaus58.workers.dev"

    private val cache = LruCache<String, SubDubInfo>(300)

    fun get(title: String, scope: CoroutineScope, anilistId: Int? = null, onResult: (SubDubInfo?) -> Unit) {
        val key = anilistId?.toString() ?: title.lowercase().trim()
        if (key.isBlank()) { onResult(null); return }
        val cached = cache.get(key)
        if (cached != null) {
            onResult(cached)
            return
        }
        scope.launch(Dispatchers.IO) {
            val info = fetchFromApi(anilistId, title)
            if (info != null) cache.put(key, info)
            withContext(Dispatchers.Main) { onResult(info) }
        }
    }

    fun getCached(title: String): SubDubInfo? =
        cache.get(title.lowercase().trim())

    fun getCachedById(anilistId: Int): SubDubInfo? =
        cache.get(anilistId.toString())

    fun prefetch(
        items: List<Pair<String, Int?>>,
        scope: CoroutineScope,
        onDone: (() -> Unit)? = null
    ) {
        scope.launch(Dispatchers.IO) {
            items.forEach { (title, id) ->
                val key = id?.toString() ?: title.lowercase().trim()
                if (cache.get(key) == null) {
                    val info = fetchFromApi(id, title)
                    if (info != null) cache.put(key, info)
                }
            }
            onDone?.let { withContext(Dispatchers.Main) { it() } }
        }
    }

    fun invalidate(title: String) {
        cache.remove(title.lowercase().trim())
    }

    fun clear() { cache.evictAll() }

    private suspend fun fetchFromApi(anilistId: Int?, title: String): SubDubInfo? = withContext(Dispatchers.IO) {
        try {
            val urlStr = if (anilistId != null) {
                "$BASE_URL/?anilist_id=$anilistId"
            } else {
                val encoded = URLEncoder.encode(title, "UTF-8")
                "$BASE_URL/?q=$encoded"
            }
            val conn = URL(urlStr).openConnection()
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val body = conn.getInputStream().bufferedReader().readText()
            val json = JSONObject(body)
            val sub = json.optInt("sub", 0)
            val dub = json.optInt("dub", 0)
            val total = json.optInt("total", 0)
            if (sub == 0 && dub == 0 && total == 0) null else SubDubInfo(sub, dub, total)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("SubDubCache fetch error: ${e.message}")
            null
        }
    }
}
