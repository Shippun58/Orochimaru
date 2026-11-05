package ani.sanin.connections.anizip

import ani.sanin.Mapper
import ani.sanin.client
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import android.util.Log
import ani.sanin.util.Logger
import kotlinx.coroutines.CompletableDeferred

@Serializable
data class AniZipImage(
    val coverType: String? = null,
    val url: String? = null,
)

@Serializable
data class AniZipEpisode(
    val airDate: String? = null,
    val airDateUtc: String? = null,
    val airdate: String? = null,
    val runtime: Int? = null,
    val length: Int? = null,
    val image: String? = null,
    @SerialName("episodeNumber") val episodeNumber: Int? = null,
    @SerialName("absoluteEpisodeNumber") val absoluteEpisodeNumber: Int? = null,
)

@Serializable
data class AniZipMappingIds(
    @SerialName("kitsu_id") val kitsuId: Int? = null,
    @SerialName("themoviedb_id") val tmdbId: String? = null,
    @SerialName("thetvdb_id") val tvdbId: Int? = null,

    @SerialName("type") val type: String? = null,
)

@Serializable
data class AniZipMappings(
    val images: List<AniZipImage>? = null,

    val mappings: AniZipMappingIds? = null,
    @SerialName("episodeCount") val episodeCount: Int? = null,
    val episodes: Map<String, AniZipEpisode>? = null,
) {
    val kitsuId: Int? get() = mappings?.kitsuId
    val tmdbId: String? get() = mappings?.tmdbId
    val tvdbId: Int? get() = mappings?.tvdbId
    val type: String? get() = mappings?.type

    fun episode(number: Int?): AniZipEpisode? {
        if (number == null) return null
        return episodes?.get(number.toString())
    }
}

data class AniZipImages(
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
)

object AniZip {
    private const val BASE_URL = "https://api.ani.zip"
    private const val MAX_CACHE = 64

    private val mappingsCache = object : LinkedHashMap<Int, AniZipMappings?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, AniZipMappings?>?): Boolean =
            size > MAX_CACHE
    }

    private val inFlight = HashMap<Int, CompletableDeferred<AniZipMappings?>>()

    suspend fun getMappings(anilistId: Int): AniZipMappings? {
        synchronized(mappingsCache) {
            if (mappingsCache.containsKey(anilistId)) return mappingsCache[anilistId]
        }

        val mine = CompletableDeferred<AniZipMappings?>()
        val pending = synchronized(inFlight) {
            inFlight.putIfAbsent(anilistId, mine) ?: mine
        }
        if (pending !== mine) return pending.await()

        val parsed = fetchMappings(anilistId)
        synchronized(inFlight) { inFlight.remove(anilistId) }
        mine.complete(parsed)

        if (parsed != null) {
            synchronized(mappingsCache) { mappingsCache[anilistId] = parsed }
        }
        return parsed
    }

    private suspend fun fetchMappings(anilistId: Int): AniZipMappings? = try {
        val response = client.get("$BASE_URL/mappings?anilist_id=$anilistId")
        Mapper.json.decodeFromString<AniZipMappings>(response.text)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.log(Log.WARN, "AniZip getMappings $anilistId failed: ${e.message}")
        null
    }

    suspend fun getImages(anilistId: Int): AniZipImages {
        val mappings = getMappings(anilistId) ?: return AniZipImages()
        val images = mappings.images.orEmpty()
        return AniZipImages(
            backdropUrl = images.firstOrNull { it.coverType == "Fanart" }?.url
                ?: images.firstOrNull { it.coverType == "Banner" }?.url,
            posterUrl = images.firstOrNull { it.coverType == "Poster" }?.url,
        )
    }

    suspend fun getImagesBatch(ids: List<Int>): Map<Int, AniZipImages> = coroutineScope {
        ids.map { id -> async { id to getImages(id) } }.associate { it.await() }
    }

    suspend fun getBackdropUrl(anilistId: Int): String? {
        return getImages(anilistId).backdropUrl
    }

    suspend fun getPosterUrl(anilistId: Int): String? {
        return getImages(anilistId).posterUrl
    }

    suspend fun getBackdropUrlWithTmdbFallback(
        anilistId: Int,
        title: String?
    ): String? {
        val anizip = getBackdropUrl(anilistId)
        if (!anizip.isNullOrBlank()) return anizip
        if (title.isNullOrBlank()) return null
        return try {
            val results = ani.sanin.connections.tmdb.Tmdb.search(title)
            results.firstOrNull()?.backdropPath?.let { ani.sanin.connections.tmdb.Tmdb.imageUrl(it, 1280) }
        } catch (_: Exception) {
            null
        }
    }
}
