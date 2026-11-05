package ani.sanin.connections.anilist

import ani.sanin.util.Logger
import ani.sanin.connections.anilist.api.Media as AnilistMedia
import ani.sanin.connections.anilist.api.MediaTitle
import ani.sanin.connections.anilist.api.Query
import ani.sanin.media.Franchise
import ani.sanin.media.FranchiseEntry
import ani.sanin.media.FranchiseType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object AnilistFranchiseRanks {

    class Ranks(private val positions: Map<String, Int>) {

        fun rankOf(card: Franchise): Int? =

            positions[normalize(card.name)]
                ?: card.entries.mapNotNull { positionOf(it) }.minOrNull()

        private fun positionOf(entry: FranchiseEntry): Int? =
            entry.anilistId?.let { positions["id:$it"] } ?: positions[normalize(entry.title)]

        val size: Int get() = positions.size

        internal fun raw(): Map<String, Int> = positions
    }

    private const val PAGE_SIZE = 50

    private var popularity: Ranks? = null
    private var trending: Ranks? = null

    private val lock = Mutex()

    suspend fun popular(): Ranks = lock.withLock {
        popularity ?: query("POPULARITY_DESC").also { popularity = it }
    }

    suspend fun trending(): Ranks = lock.withLock {
        trending ?: query("TRENDING_DESC").also { trending = it }
    }

    private suspend fun query(sort: String): Ranks = try {
        val media = Injekt.get<Anilist>()
            .executeQuery<Query.Page>(
                """{ Page(perPage: $PAGE_SIZE){media(sort: $sort, type: ANIME, isAdult: false) {id title{romaji english userPreferred} } } }""",
                useToken = false,
            )
            ?.data?.page?.media

        if (media.isNullOrEmpty()) Ranks(emptyMap()) else Ranks(
            buildMap {
                media.forEachIndexed { index, m ->
                    val position = index + 1

                    putIfAbsent("id:${m.id}", position)
                    titlesOf(m.title)?.forEach { putIfAbsent(normalize(it), position) }
                }
            }
        )
    } catch (e: Exception) {
        Logger.log(e)

        Ranks(emptyMap())
    }

    private fun titlesOf(title: MediaTitle?): List<String> =
        listOfNotNull(title?.romaji, title?.english, title?.userPreferred)

    private fun normalize(title: String): String =
        title.lowercase()
            .filter { it.isLetterOrDigit() }
            .trim()

    fun applyTrending(cards: List<Franchise>, ranks: Ranks): List<Franchise> =
        cards.map { card ->
            card.copy(trendingRank = ranks.rankOf(card) ?: card.trendingRank)
        }

    fun applyPopular(cards: List<Franchise>, ranks: Ranks): List<Franchise> =
        cards.map { card ->
            val position = ranks.rankOf(card) ?: return@map card
            card.copy(likeCount = ranks.size - position + 1)
        }

    suspend fun enrich(cards: List<Franchise>): List<Franchise> {
        val wanted = cards.flatMap { it.entries }.mapNotNull { it.anilistId }.distinct()
        if (wanted.isEmpty()) return cards

        val details = try {
            fetchDetails(wanted)
        } catch (e: Exception) {
            Logger.log(e)
            return cards
        }
        if (details.isEmpty()) return cards

        val byId = details.associateBy { it.id }
        val byTitle = buildMap<String, AnilistMedia?> {
            details.forEach { m ->
                titlesOf(m.title).forEach { putIfAbsent(normalize(it), m) }
            }
        }

        return cards.map { card ->
            card.copy(entries = card.entries.map { entry ->
                val detail = entry.anilistId?.let { byId[it] }
                    ?: byTitle[normalize(entry.title)]
                if (detail == null) entry else entry.withDetail(detail)
            })
        }
    }

    private suspend fun fetchDetails(ids: List<Int>): List<AnilistMedia> =
        ids.chunked(MAX_IDS_PER_QUERY).flatMap { chunk ->
            Injekt.get<Anilist>()
                .executeQuery<Query.Page>(
                    """{ Page(page:1, perPage:${chunk.size}){media(id_in:[${chunk.joinToString(",")}], type:ANIME, isAdult:false){id format averageScore duration description startDate{year month day} title{romaji english userPreferred} mediaListEntry{status progress score(format:POINT_100)} } } }""",
                    useToken = false,
                )
                ?.data?.page?.media.orEmpty()
        }

    private fun FranchiseEntry.withDetail(detail: AnilistMedia): FranchiseEntry {
        val date = detail.startDate

        val year = date?.year
        val month = date?.month
        val dateKey = if (year == null || month == null) {
            null
        } else {
            year * 10000 + month * 100 + (date.day ?: 1)
        }
        return copy(
            airDate = airDate ?: date?.let { it.toStringOrEmpty() }.orEmpty().ifBlank { null },
            airDateSortKey = airDateSortKey ?: dateKey,
            durationMinutes = durationMinutes ?: detail.duration,
            score = score ?: detail.averageScore,

            type = type ?: FranchiseType.fromFormat(detail.format?.toString()),
            synopsis = synopsis ?: stripHtml(detail.description),

            listStatus = listStatus ?: detail.mediaListEntry?.status?.toString(),
        )
    }

    fun stripHtml(html: String?): String? = html
        ?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        ?.replace(Regex("<[^>]+>"), "")
        ?.replace("&quot;", "\"")
        ?.replace("&#039;", "'")
        ?.replace("&amp;", "&")
        ?.replace("&lt;", "<")
        ?.replace("&gt;", ">")
        ?.replace(Regex("[ \\t]+"), " ")
        ?.replace(Regex("\\n{3,}"), "\n\n")
        ?.trim()
        ?.ifBlank { null }

    fun clear() = synchronized(this) {
        popularity = null
        trending = null
    }

    suspend fun franchiseListStatuses(ids: List<Int>): Map<Int, String>? {
        if (ids.isEmpty()) return emptyMap()
        return try {
            ids.distinct().chunked(MAX_IDS_PER_QUERY).flatMap { chunk ->
                Injekt.get<Anilist>()
                    .executeQuery<Query.Page>(
                        """{ Page(page:1, perPage:${chunk.size}){media(id_in:[${chunk.joinToString(",")}], type:ANIME, isAdult:false){id mediaListEntry{status} } } }""",
                        useToken = true,
                        cache = 0,
                    )
                    ?.data?.page?.media.orEmpty()
            }.mapNotNull { media ->
                val status = media.mediaListEntry?.status?.toString()
                if (status.isNullOrBlank()) null else media.id to status
            }.toMap()
        } catch (e: Exception) {
            Logger.log(e)
            null
        }
    }

    private const val MAX_IDS_PER_QUERY = 50
}