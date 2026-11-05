package ani.sanin.connections

import android.content.Context
import ani.sanin.Mapper
import ani.sanin.connections.anizip.AniZip
import ani.sanin.connections.fanart.Fanart
import ani.sanin.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

object LogoApi {

    private const val ROLE_LOGO = "logo"
    private const val ROLE_CLEARART = "clearart"
    private const val ROLE_BACKGROUND = "bg"
    private const val ROLE_POSTER = "poster"

    private const val CACHE_FILE = "logos_fanart_v1.json"

    private var cacheDir: File? = null

    private val memoryCache = HashMap<String, String>()
    private var diskLoaded = false

    fun init(context: Context) {
        if (cacheDir != null) return
        cacheDir = File(context.cacheDir, "logo_cache").also { it.mkdirs() }
    }

    private fun getCacheFile(): File? =
        cacheDir?.let { File(it, CACHE_FILE) }

    private fun loadFromDisk() {
        if (diskLoaded) return
        diskLoaded = true
        try {
            val file = getCacheFile() ?: return
            if (!file.exists()) return
            val map = Mapper.json.decodeFromString<Map<String, String>>(file.readText())
            memoryCache.putAll(map)
            Logger.log("LogoApi: loaded ${map.size} cached entries")
        } catch (e: Exception) {
            Logger.log("LogoApi: disk load error - ${e.message}")
        }
    }

    private fun saveToDisk() {
        try {
            val file = getCacheFile() ?: return
            file.writeText(Mapper.json.encodeToString(memoryCache))
        } catch (e: Exception) {
            Logger.log("LogoApi: disk save error - ${e.message}")
        }
    }

    suspend fun getLogoUrl(anilistId: Int): String? = resolve(anilistId, ROLE_LOGO) { tvdbId ->
        Fanart.logoUrl(tvdbId)
    }

    suspend fun getClearartUrl(anilistId: Int): String? = resolve(anilistId, ROLE_CLEARART) { tvdbId ->
        Fanart.clearartOrLogoUrl(tvdbId)
    }

    suspend fun getBackgroundUrl(anilistId: Int): String? = resolve(anilistId, ROLE_BACKGROUND) { tvdbId ->
        Fanart.backgroundUrl(tvdbId)
    }

    suspend fun getPosterUrl(anilistId: Int): String? = resolve(anilistId, ROLE_POSTER) { tvdbId ->
        Fanart.posterUrl(tvdbId)
    }

    suspend fun getLogosBatch(anilistIds: List<Int>): Map<Int, String> {
        if (anilistIds.isEmpty()) return emptyMap()
        if (!diskLoaded) withContext(Dispatchers.IO) { loadFromDisk() }

        val pending = anilistIds.filter { !memoryCache.containsKey(key(it, ROLE_LOGO)) }
        if (pending.isNotEmpty()) {

            val resolved = coroutineScope {
                pending.map { id -> async { id to getLogoUrl(id) } }.awaitAll()
            }
            for ((id, url) in resolved) {
                if (url != null) memoryCache[key(id, ROLE_LOGO)] = url
            }
            withContext(Dispatchers.IO) { saveToDisk() }
        }

        return anilistIds.mapNotNull { id ->
            memoryCache[key(id, ROLE_LOGO)]?.takeIf { it.isNotBlank() }?.let { id to it }
        }.toMap()
    }

    private fun key(anilistId: Int, role: String) = "$anilistId:$role"

    private suspend fun resolve(
        anilistId: Int,
        role: String,
        fetch: suspend (tvdbId: Int) -> String?,
    ): String? {
        if (!diskLoaded) withContext(Dispatchers.IO) { loadFromDisk() }
        memoryCache[key(anilistId, role)]?.let { return it.ifEmpty { null } }

        return withContext(Dispatchers.IO) {
            val tvdbId = AniZip.getMappings(anilistId)?.tvdbId
            if (tvdbId == null) {
                Logger.log("LogoApi: $anilistId has no TheTVDB id, skipping Fanart")
                return@withContext null
            }

            val url = fetch(tvdbId)
            if (url != null) {
                memoryCache[key(anilistId, role)] = url
                saveToDisk()
                Logger.log("LogoApi: $anilistId/$role -> $url")
            }
            url
        }
    }

    fun clearCache() {
        memoryCache.clear()
        diskLoaded = false
        getCacheFile()?.delete()
    }
}