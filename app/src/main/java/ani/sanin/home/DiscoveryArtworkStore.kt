package ani.sanin.home

import android.content.Context
import org.json.JSONObject

internal object DiscoveryArtworkStore {

    private const val PREFS_NAME = "discovery_artwork"
    private const val KEY_ANIME = "anime"
    private const val KEY_TMDB = "tmdb"
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private val anime = HashMap<String, String>()
    private val tmdb = HashMap<String, String>()
    private var loaded = false

    @Synchronized
    fun animePosters(context: Context): Map<String, String> {
        ensureLoaded(context)
        return HashMap(anime)
    }

    @Synchronized
    fun putAnime(context: Context, slug: String, url: String?) {
        if (url.isNullOrBlank()) return
        ensureLoaded(context)
        if (anime[slug] == url) return
        anime[slug] = url
        persist(context, KEY_ANIME, anime)
    }

    @Synchronized
    fun tmdbPosters(context: Context): Map<String, String> {
        ensureLoaded(context)
        return HashMap(tmdb)
    }

    @Synchronized
    fun putTmdb(context: Context, id: String, url: String?) {
        if (url.isNullOrBlank()) return
        ensureLoaded(context)
        if (tmdb[id] == url) return
        tmdb[id] = url
        persist(context, KEY_TMDB, tmdb)
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        read(context, KEY_ANIME, anime)
        read(context, KEY_TMDB, tmdb)
    }

    private fun read(context: Context, key: String, target: MutableMap<String, String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong("${key}_saved_at", 0L)
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > MAX_AGE_MS) return
        val raw = prefs.getString(key, null) ?: return
        runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { name ->
                json.optString(name).takeIf { it.isNotBlank() }?.let { target[name] = it }
            }
        }
    }

    private fun persist(context: Context, key: String, source: Map<String, String>) {
        runCatching {
            val json = JSONObject()
            source.forEach { (name, url) -> json.put(name, url) }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(key, json.toString())
                .putLong("${key}_saved_at", System.currentTimeMillis())
                .apply()
        }
    }
}
