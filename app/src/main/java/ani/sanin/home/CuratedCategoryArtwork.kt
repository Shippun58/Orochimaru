package ani.sanin.home

import ani.sanin.connections.anilist.Anilist
import ani.sanin.media.Media
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object CuratedCategoryArtwork {
    private val poolMutex = Mutex()
    @Volatile private var cachedPool: List<Media>? = null

    private data class RankedMedia(val media: Media, val relevance: Int)

    private val aliases = mapOf(
        "swordplay" to listOf("sword fighting", "swordplay"),
        "assassin" to listOf("assassins", "assassin"),
        "coming-of-age" to listOf("coming of age", "coming-of-age"),
        "parental-abandonment" to listOf("parental abandonment", "absent parents"),
        "unrequited-love" to listOf("unrequited love", "one-sided love"),
        "dark-fantasy" to listOf("dark fantasy"),
        "demon" to listOf("demons", "demon"),
        "elf" to listOf("elves", "elf"),
        "super-power" to listOf("super power", "superpowers"),
        "love-polygon" to listOf("love triangle", "love polygon", "polyamorous"),
        "slow-when-it-comes-to-love" to listOf("slow burn romance", "slow romance", "slow-burn"),
        "high-school" to listOf("high school", "school"),
        "student-government" to listOf("student council", "student government"),
        "street-racing" to listOf("street racing"),
        "card-games" to listOf("card battle", "card games"),
        "motorsport" to listOf("motorsports", "motorsport", "racing"),
        "formula-racing" to listOf("formula racing", "formula one"),
        "proxy-battles" to listOf("proxy battle", "proxy battles"),
        "musical-band" to listOf("band", "music"),
        "shinsengumi" to listOf("shinsengumi"),
        "plot-continuity" to listOf("non-linear storytelling", "plot continuity"),
        "tone-changes" to listOf("sudden turn", "tone changes"),
        "bishounen" to listOf("bishounen", "beautiful male characters"),
        "yaoi" to listOf("boys love", "yaoi"),
        "yuri" to listOf("girls love", "yuri"),
    )

    suspend fun pickFor(
        categories: List<CuratedDiscoveryCategory>,
        excludedPosterUrls: Set<String> = emptySet(),
    ): Map<String, String?> {
        if (categories.isEmpty()) return emptyMap()
        val pool = loadPool()
        if (pool.isEmpty()) return categories.associate { it.kitsuSlug to null }

        val usedIds = HashSet<Int>()
        val usedUrls = excludedPosterUrls.toMutableSet()
        val result = LinkedHashMap<String, String?>()

        categories.shuffled().forEach { category ->
            val terms = buildList {
                add(normalize(category.title))
                add(normalize(category.kitsuSlug.replace('-', ' ')))
                aliases[category.kitsuSlug].orEmpty().mapTo(this) { normalize(it) }
            }.filter { it.isNotBlank() }.distinct()

            val ranked = pool.mapNotNull { media ->
                if (media.cover.isNullOrBlank()) return@mapNotNull null
                RankedMedia(media, relevance(media, terms))
            }.sortedWith(
                compareByDescending<RankedMedia> { it.relevance }
                    .thenByDescending { it.media.meanScore ?: 0 }
                    .thenByDescending { it.media.popularity ?: 0 }
            )

            val relevant = ranked.filter {
                it.relevance > 0 && it.media.id !in usedIds &&
                    it.media.cover?.let { url -> url !in usedUrls } == true
            }
            val fallback = ranked.filter {
                it.media.id !in usedIds &&
                    it.media.cover?.let { url -> url !in usedUrls } == true
            }.take(16)
            val candidates = relevant.take(8).ifEmpty { fallback }
            val picked = candidates.randomOrNull()?.media
                ?: ranked.firstOrNull { it.media.cover?.let { url -> url !in usedUrls } == true }?.media
                ?: ranked.firstOrNull()?.media

            if (picked != null) {
                usedIds += picked.id
                picked.cover?.let { usedUrls += it }
            }
            result[category.kitsuSlug] = picked?.cover
        }
        return result
    }

    private suspend fun loadPool(): List<Media> {
        cachedPool?.let { return it }
        return poolMutex.withLock {
            cachedPool?.let { return@withLock it }
            val found = ArrayList<Media>(100)

            for (page in 1..2) {
                val batch = runCatching {
                    Anilist.query.discoverAnime(page = page, perPage = 50)
                }.getOrNull().orEmpty()
                found += batch.filter { !it.isAdult && !it.cover.isNullOrBlank() }
                if (batch.size < 50) break
            }
            val usable = found.distinctBy { it.id }
            if (usable.isNotEmpty()) cachedPool = usable
            usable
        }
    }

    private fun relevance(media: Media, terms: List<String>): Int {
        val tags = media.tags.map { normalize(it.substringBefore(':')) }
        val genres = media.genres.map(::normalize)
        var best = 0
        for (term in terms) {
            if (tags.any { it == term }) best = maxOf(best, 100)
            else if (genres.any { it == term }) best = maxOf(best, 90)
            else if (term.length >= 4 && tags.any { it.contains(term) || term.contains(it) }) {
                best = maxOf(best, 65)
            } else if (term.length >= 4 && genres.any { it.contains(term) || term.contains(it) }) {
                best = maxOf(best, 55)
            }
        }
        return best
    }

    private fun normalize(value: String): String =
        value.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")
}
