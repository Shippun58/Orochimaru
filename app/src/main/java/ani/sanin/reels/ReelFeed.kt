package ani.sanin.reels

import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anizip.AniZip
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbVideo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

object ReelFeed {

    data class AnimeRef(
        val anilistId: Int,
        val title: String,
        val synopsis: String? = null,
        val genres: List<String> = emptyList(),
        val cover: String? = null,
    )

    data class ReelItem(
        val key: String,
        val anilistId: Int,
        val animeTitle: String,
        val videoName: String,
        val synopsis: String? = null,
        val genres: List<String> = emptyList(),
        val cover: String? = null,

        val tmdbId: Int = 0,
        val mediaType: String? = null,
    )

    private const val MAX_CACHE = 160

    private const val MAX_PAGE = 50

    private const val POPULAR_BAND_PAGE = 12

    private val SORTS = listOf("POPULARITY_DESC", "SCORE_DESC", "TRENDING_DESC", "FAVOURITES_DESC")

    private const val RECENT_PAGE_MEMORY = 12
    private val recentPages = ArrayDeque<Int>()

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<Int, List<ReelItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<ReelItem>>?): Boolean =
            size > MAX_CACHE
    }

    private val gate = Semaphore(4)

    private val videoOrder = compareByDescending<TmdbVideo> { it.official }
        .thenBy { typeRank(it.type) }
        .thenByDescending { it.publishedAt ?: "" }

    suspend fun pool(page: Int, perPage: Int = 30, sort: String = "POPULARITY_DESC"): List<AnimeRef> {
        val media = runCatching {
            Anilist.query.discoverAnime(page = page, perPage = perPage, sort = sort)
        }
            .getOrNull()
            ?: return emptyList()
        return media.mapNotNull { item ->
            val title = (item.name ?: item.userPreferredName)
                .takeIf { it.isNotBlank() }
                ?: item.nameRomaji.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            AnimeRef(
                anilistId = item.id,
                title = title,
                synopsis = cleanSynopsis(item.description),
                genres = item.genres.toList(),
                cover = item.cover,
            )
        }
    }

    suspend fun draw(perPage: Int = 30): List<AnimeRef> =
        pool(page = nextPage(), perPage = perPage, sort = SORTS.random())

    suspend fun railPool(cardCount: Int): List<AnimeRef> {
        val merged = LinkedHashMap<Int, AnimeRef>()
        repeat(2) { draw().forEach { merged.putIfAbsent(it.anilistId, it) } }
        return merged.values.shuffled().take(cardCount)
    }

    private fun nextPage(): Int = synchronized(cacheLock) {
        var page: Int
        var guard = 0
        do {
            page = if (Random.nextBoolean()) {
                (1..POPULAR_BAND_PAGE).random()
            } else {
                (1..MAX_PAGE).random()
            }
            guard++
        } while (page in recentPages && guard < 8)
        recentPages.addLast(page)
        while (recentPages.size > RECENT_PAGE_MEMORY) recentPages.removeFirst()
        page
    }

    fun thumbnailUrl(key: String): String = "https://img.youtube.com/vi/$key/maxresdefault.jpg"

    fun thumbnailFallbackUrl(key: String): String = "https://img.youtube.com/vi/$key/mqdefault.jpg"

    suspend fun buildInterleaved(refs: List<AnimeRef>): List<ReelItem> {
        val distinct = refs.distinctBy { it.anilistId }
        if (distinct.isEmpty()) return emptyList()

        val pools = coroutineScope {
            distinct.map { ref -> async { videosFor(ref).toMutableList() } }.map { it.await() }
        }.filter { it.isNotEmpty() }
        if (pools.isEmpty()) return emptyList()

        val out = ArrayList<ReelItem>(pools.sumOf { it.size })
        while (true) {
            val active = pools.filter { it.isNotEmpty() }
            if (active.isEmpty()) break

            for (pool in active.shuffled()) {
                out += pool.removeAt(pool.indices.random())
            }
        }
        return out
    }

    suspend fun videosFor(ref: AnimeRef): List<ReelItem> {
        synchronized(cacheLock) { cache[ref.anilistId] }?.let { return it }
        val items = gate.withPermit { fetchVideos(ref) }
        synchronized(cacheLock) { cache[ref.anilistId] = items }
        return items
    }

    private suspend fun fetchVideos(ref: AnimeRef): List<ReelItem> {
        if (Tmdb.apiKey.isBlank()) return emptyList()

        val mappings = AniZip.getMappings(ref.anilistId) ?: return emptyList()
        val tmdbId = mappings.tmdbId?.toIntOrNull() ?: return emptyList()

        val movie = mappings.type?.equals("MOVIE", ignoreCase = true) == true
        val primary = if (movie) "movie" else "tv"
        val secondary = if (movie) "tv" else "movie"
        val videos = Tmdb.videos(primary, tmdbId).ifEmpty { Tmdb.videos(secondary, tmdbId) }

        val seen = HashSet<String>()
        return videos
            .asSequence()
            .filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
            .sortedWith(videoOrder)
            .mapNotNull { video ->
                val key = video.key ?: return@mapNotNull null
                if (!seen.add(key)) return@mapNotNull null
                ReelItem(
                    key = key,
                    anilistId = ref.anilistId,
                    animeTitle = ref.title,
                    videoName = video.name?.takeIf { it.isNotBlank() } ?: ref.title,
                    synopsis = ref.synopsis,
                    genres = ref.genres,
                    cover = ref.cover,
                )
            }
            .toList()
    }

    private fun cleanSynopsis(html: String?): String? = html
        ?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        ?.replace(Regex("<[^>]+>"), "")
        ?.replace("&quot;", "\"")
        ?.replace("&#039;", "'")
        ?.replace("&amp;", "&")
        ?.replace("&lt;", "<")
        ?.replace("&gt;", ">")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.ifBlank { null }

    private fun typeRank(type: String?): Int = when (type?.lowercase()) {
        "trailer" -> 0
        "teaser" -> 1
        "clip" -> 2
        "featurette" -> 3
        "behind the scenes" -> 4
        "bloopers" -> 5
        "opening credits" -> 6
        else -> 7
    }
}
