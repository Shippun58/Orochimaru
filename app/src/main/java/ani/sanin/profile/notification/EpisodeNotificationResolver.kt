package ani.sanin.profile.notification

import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.connections.anizip.AniZip
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.util.Logger
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request

data class EpisodeExtra(
    val thumbnailUrl: String? = null,
    val title: String? = null,
    val durationMinutes: Int? = null,
    val airDate: String? = null,
    val backdropUrl: String? = null,
) {
    val isEmpty: Boolean
        get() = thumbnailUrl == null && title == null && durationMinutes == null &&
            airDate == null && backdropUrl == null
}

object EpisodeNotificationResolver {
    private const val MAX_CACHE = 400

    private val cache = object : LinkedHashMap<String, EpisodeExtra>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpisodeExtra>?): Boolean =
            size > MAX_CACHE
    }

    private data class ShowLayout(val seasonNumbers: List<Int>, val ranges: List<IntRange>)

    private val tmdbLayouts = HashMap<Int, ShowLayout>()

    suspend fun resolve(
        anilistId: Int,
        episode: Int?,
        fallbackTitle: String? = null
    ): EpisodeExtra {
        val key = "$anilistId-${episode ?: -1}"
        synchronized(cache) {
            cache[key]?.let { return it }
        }

        val extra = resolveUncached(anilistId, episode, fallbackTitle)
        if (!extra.isEmpty) {
            synchronized(cache) { cache[key] = extra }
        }
        return extra
    }

    private suspend fun resolveUncached(
        anilistId: Int,
        episode: Int?,
        fallbackTitle: String?
    ): EpisodeExtra {
        val mappings = AniZip.getMappings(anilistId) ?: return EpisodeExtra()
        val entry = mappings.episode(episode)
        val backdrop = mappings.images?.firstOrNull { it.coverType == "Fanart" }?.url
            ?: mappings.images?.firstOrNull { it.coverType == "Banner" }?.url

var thumbnail = entry?.image

var title = fallbackTitle
        var duration = entry?.runtime ?: entry?.length
        var airDate = entry?.airDateUtc?.take(10)
            ?: entry?.airDate
            ?: entry?.airdate

        if (thumbnail == null && episode != null) {
            val tmdbId = mappings.tmdbId?.toIntOrNull()
            if (tmdbId != null) {
                val meta = tmdbEpisodeMeta(tmdbId, episode)
                if (meta != null) {
                    thumbnail = thumbnail ?: meta.third?.let { Tmdb.imageUrl(it, 500) }
                    title = title ?: meta.first
                    airDate = airDate ?: meta.second
                }
            }
        }

        val kitsuId = mappings.kitsuId
        if (kitsuId != null && (thumbnail == null || title == null || duration == null || airDate == null)) {
            val k = kitsuEpisode(kitsuId, episode)
            if (k != null) {
                thumbnail = thumbnail ?: k.first
                title = title ?: k.second
                duration = duration ?: k.third
                airDate = airDate ?: k.fourth
            }
        }

        Logger.log(Log.INFO, "EpisodeResolver [$anilistId/$episode] thumb=${thumbnail != null} title=$title dur=$duration air=$airDate")
        return EpisodeExtra(thumbnail, title, duration, airDate, backdrop)
    }

    private suspend fun tmdbEpisodeMeta(tmdbId: Int, episode: Int): Triple<String?, String?, String?>? {
        return withContext(Dispatchers.IO) {
            try {
                val layout = tmdbLayouts.getOrPut(tmdbId) {
                    val seasons = Tmdb.seasons("tv", tmdbId)
                    val ranges = mutableListOf<IntRange>()
                    var start = 1
                    seasons.forEach { s ->
                        ranges += (start..(start + s.episodeCount - 1))
                        start += s.episodeCount
                    }
                    ShowLayout(seasons.map { it.seasonNumber }, ranges)
                }
                if (layout.ranges.isEmpty()) return@withContext null

                val seasonIdx = if (layout.seasonNumbers.size == 1) 0
                else layout.ranges.indexOfFirst { episode in it }.takeIf { it >= 0 } ?: return@withContext null
                val season = layout.seasonNumbers[seasonIdx]
                val cumStart = layout.ranges[seasonIdx].first
                val eps = Tmdb.episodes("tv", tmdbId, season)
                val target = eps.firstOrNull { it.episodeNumber == episode }
                    ?: eps.getOrNull(episode - cumStart)
                    ?: return@withContext null
                Triple(target.name, target.airDate, target.stillPath)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    @Serializable
    private data class KitsuResponse(val data: List<KitsuEpisodeData> = emptyList())

    @Serializable
    private data class KitsuEpisodeData(val attributes: KitsuAttrs = KitsuAttrs())

    @Serializable
    private data class KitsuAttrs(
        val canonicalTitle: String? = null,
        val airdate: String? = null,
        val length: Int? = null,
        val titles: Map<String, String>? = null,
        val thumbnail: KitsuThumb? = null,
    )

    @Serializable
    private data class KitsuThumb(val original: String? = null)

    private suspend fun kitsuEpisode(
        kitsuId: Int,
        episode: Int?
    ): Quad<String?, String?, Int?, String?>? {
        if (episode == null) return null
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://kitsu.io/api/edge/anime/$kitsuId/episodes?filter%5Bnumber%5D=$episode")
                    .header("Accept", "application/vnd.api+json")
                    .build()
                val body = okHttpClient.newCall(request).execute().use { it.body?.string() } ?: return@withContext null
                val data = Mapper.json.decodeFromString<KitsuResponse>(body).data.firstOrNull() ?: return@withContext null
                val a = data.attributes
                Quad(
                    a.thumbnail?.original,
                    a.canonicalTitle ?: a.titles?.get("en_us"),
                    a.length,
                    a.airdate
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A?, val second: B?, val third: C?, val fourth: D?)
