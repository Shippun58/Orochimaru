package ani.sanin.connections.trakt

import ani.sanin.media.Franchise
import ani.sanin.util.Logger
import ani.sanin.media.FranchiseEntry
import ani.sanin.media.FranchiseType
import kotlin.math.roundToInt
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.tryWithSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

object Trakt {

    private const val BASE = "https://api.trakt.tv"

    private const val LIST_LIMIT = 20

    private const val MAX_ENTRIES = 24

    const val BATCH_CARDS = 29

    private val client
        get() = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client

    private val itemsCache = ConcurrentHashMap<String, List<TraktListItem>>()

    data class TraktBatch(val cards: List<Franchise>, val hasMore: Boolean)

    suspend fun franchiseCards(skip: Int = 0, take: Int = BATCH_CARDS): TraktBatch =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()

            val trending = lists("/lists/trending")
            val popular = lists("/lists/popular")
            val source = trending.mapIndexed { index, wrapper -> Ranked(wrapper, index + 1) } +
                popular.map { Ranked(it, null) }
            val window = source.drop(skip.coerceAtLeast(0)).take(take)
            Logger.log(
                "Trakt franchiseCards: resolving ${window.size} lists at offset $skip " +
                    "of ${source.size} available, started at ${System.currentTimeMillis() - started}ms"
            )
            val cards = cardsFrom(window)
            Logger.log(
                "Trakt franchiseCards: ${cards.size} cards from ${window.size} lists in " +
                    "${System.currentTimeMillis() - started}ms"
            )
            TraktBatch(cards, skip + window.size < source.size)
        }

    private data class Ranked(val wrapper: TraktListWrapper, val trendingRank: Int?)

    private suspend fun lists(path: String): List<TraktListWrapper> {
        val wrappers = getList<TraktListWrapper>("$path?limit=$LIST_LIMIT") ?: emptyList()
        val usable = wrappers.count { it.list != null }
        Logger.log("Trakt index $path -> $usable/${wrappers.size} wrappers carried a list object")
        return wrappers
    }

    private suspend fun cardsFrom(lists: List<Ranked>): List<Franchise> {
        var droppedNoList = 0
        var droppedNoSlug = 0
        var droppedNoName = 0
        var droppedNoUser = 0
        var droppedNoEntries = 0
        val cards = lists.mapNotNull { ranked ->
            val wrapper = ranked.wrapper
            val list = wrapper.list ?: run { droppedNoList++; return@mapNotNull null }
            val slug = list.ids?.slug ?: run { droppedNoSlug++; return@mapNotNull null }
            val name = list.name?.takeIf { it.isNotBlank() } ?: run {
                droppedNoName++; return@mapNotNull null
            }
            val username = list.user?.username ?: list.user?.ids?.slug ?: run {
                droppedNoUser++; return@mapNotNull null
            }

            val resolved = entriesOf(username, slug)
            if (resolved.entries.isEmpty()) {
                droppedNoEntries++
                Logger.log("Trakt list $username/$slug (\"$name\") resolved to 0 entries")
                return@mapNotNull null
            }

            Franchise(
                name = name,

                bannerUrl = resolved.entries.last().backdropUrl
                    ?: resolved.entries.last().posterUrl,
                logoUrl = resolved.logoUrl,
                entries = resolved.entries,
                likeCount = wrapper.likeCount ?: list.likes,
                trendingRank = ranked.trendingRank,
            )
        }

        val unique = cards.distinctBy { it.name.lowercase() }
        Logger.log(
            "Trakt cardsFrom -> ${unique.size} cards from ${lists.size} lists " +
                "(dropped: noList=$droppedNoList noSlug=$droppedNoSlug noName=$droppedNoName " +
                "noUser=$droppedNoUser noEntries=$droppedNoEntries dupes=${cards.size - unique.size})"
        )
        return unique
    }

    private data class ResolvedList(
        val entries: List<FranchiseEntry>,
        val logoUrl: String?,
    )

    private suspend fun entriesOf(username: String, slug: String): ResolvedList {
        val key = "$username/$slug"
        val cached = itemsCache[key] != null
        val items = itemsCache[key] ?: getList<TraktListItem>(

            "/users/$username/lists/$slug/items?limit=$MAX_ENTRIES&extended=full"
        )?.also { itemsCache[key] = it } ?: run {
            Logger.log("Trakt entriesOf $key: items request returned nothing")
            return ResolvedList(emptyList(), null)
        }
        Logger.log("Trakt entriesOf $key: ${items.size} items${if (cached) " (cached)" else ""}")

        val ordered = if (items.all { it.rank != null }) items.sortedBy { it.rank } else items

        val lastImages = ordered.lastOrNull()?.let { it.movie?.images ?: it.show?.images }
        val logo = lastImages?.urlOf(lastImages.logo, "medium", "full", "thumb")

        return ResolvedList(ordered.toEntries(), logo)
    }

    private fun List<TraktListItem>.toEntries() = mapNotNull { item ->

        val title = item.movie?.title ?: item.show?.title ?: return@mapNotNull null
        val year = item.movie?.year ?: item.show?.year

        val images = item.movie?.images ?: item.show?.images

        val airDate = item.movie?.released ?: item.show?.firstAired

        val rawType = item.type ?: item.movie?.type ?: item.show?.type

        FranchiseEntry(
            year = year?.toString().orEmpty(),
            sortYear = year,
            posterUrl = images?.urlOf(images.poster, "medium", "full", "thumb"),
            title = title,

            backdropUrl = images?.urlOf(images.screenshot, "medium", "full")
                ?: images?.urlOf(images.banner, "medium", "full"),
            airDate = airDate,
            airDateSortKey = airDate?.replace("-", "")?.toIntOrNull(),
            durationMinutes = item.movie?.runtime ?: item.show?.runtime,

            score = (item.movie?.rating ?: item.show?.rating)?.let { (it * 10).roundToInt() },
            synopsis = item.movie?.overview ?: item.show?.overview,
            type = if (rawType == "movie") FranchiseType.MOVIE else FranchiseType.SEQUENCE,
            tmdbId = (item.movie?.ids ?: item.show?.ids)?.tmdb,
            traktId = (item.movie?.ids ?: item.show?.ids)?.trakt,
        )
    }

    private suspend inline fun <reified T> getList(path: String): List<T>? {

        val key: String = PrefManager.getVal(PrefName.TraktClientId)
        Logger.log("Trakt GET $path (key ${if (key.isBlank()) "MISSING" else "present"})")
        val result = tryWithSuspend(snackbar = false) {
            val request = Request.Builder()
                .url("$BASE$path")
                .get()
                .addHeader("Content-Type", "application/json")
                .addHeader("trakt-api-version", "2")
                .addHeader("trakt-api-key", key)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.log("Trakt HTTP ${response.code} for $path: ${response.message}")
                    null
                } else {
                    val body = response.body?.string()
                    if (body == null) {
                        Logger.log("Trakt empty body for $path")
                        null
                    } else {
                        val decoded = traktJson.decodeFromString<List<T>>(body)
                        Logger.log("Trakt ${response.code} $path -> ${decoded.size} items, ${body.length} bytes")
                        decoded
                    }
                }
            }
        }
        if (result == null) Logger.log("Trakt FAILED (null) $path")
        return result
    }
}
