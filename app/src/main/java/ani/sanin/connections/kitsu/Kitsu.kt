package ani.sanin.connections.kitsu

import ani.sanin.connections.anilist.api.FuzzyDate
import ani.sanin.media.Franchise
import ani.sanin.media.FranchiseType
import ani.sanin.util.Logger
import ani.sanin.media.FranchiseEntry
import ani.sanin.media.Media
import ani.sanin.media.anime.Anime
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.tryWithSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class CategoryPage(
    val media: List<Media>,
    val hasMore: Boolean,
    val failed: Boolean = false,
)

object Kitsu {

    private const val BASE = "https://kitsu.io/api/edge"
    private const val ANILIST_SITE = "anilist/anime"

    private const val PAGE_LIMIT = 20

    private val okHttpClient
        get() = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client

    private const val MAX_ENTRIES = 24

    private val franchiseCache = ConcurrentHashMap<Int, Franchise>()

    private val missingCache = ConcurrentHashMap<Int, Boolean>()

    @Volatile
    private var categoryCache: List<KitsuCategory>? = null

    private val categoryThumbnailCache = ConcurrentHashMap<String, List<String>>()

    private val franchiseIdCache = ConcurrentHashMap<String, String>()

    private val kitsuIdCache = ConcurrentHashMap<Int, String>()
    private val franchiseNameCache = ConcurrentHashMap<String, String>()

    private val installmentsCache = ConcurrentHashMap<String, List<String>>()

    private val mediaIdCache = ConcurrentHashMap<String, String>()

    private val animeCache = ConcurrentHashMap<String, KitsuResource>()

    private val anilistIdCache = ConcurrentHashMap<String, Int>()

    @Volatile
    private var positionsAvailable: Boolean? = null

    private suspend fun curatedOrder(installmentIds: List<String>): Boolean {
        if (!PrefManager.getVal<Boolean>(PrefName.KitsuCuratedOrder)) return false

        positionsAvailable?.let { return it }

        val probeId = installmentIds.firstOrNull() ?: return false
        val usable = get("/installments/$probeId")?.dataOne()
            ?.installmentAttributes()?.position != null
        positionsAvailable = usable
        return usable
    }

    private suspend fun curatedPositions(installmentIds: List<String>): Map<String, Int> {
        if (!curatedOrder(installmentIds)) return emptyMap()
        return installmentIds.mapNotNull { id ->
            get("/installments/$id")?.dataOne()
                ?.installmentAttributes()?.position?.let { id to it }
        }.toMap()
    }

    suspend fun categories(limit: Int = 60): List<KitsuCategory> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, 100)
        val found = ArrayList<KitsuCategory>()
        var offset = 0
        while (offset < safeLimit) {
            val pageSize = minOf(PAGE_LIMIT, safeLimit - offset)
            val document = get("/categories?page[limit]=$pageSize&page[offset]=$offset") ?: break
            val resources = document.dataList()
            if (resources.isEmpty()) break
            resources.forEach { resource ->
                val id = resource.id ?: return@forEach
                val attributes = resource.categoryAttributes() ?: return@forEach
                val slug = attributes.slug?.takeIf { it.isNotBlank() } ?: return@forEach
                val title = attributes.title?.takeIf { it.isNotBlank() } ?: slug
                found += KitsuCategory(id, slug, title, attributes.description, attributes.totalMediaCount)
            }
            offset += resources.size
            if (resources.size < pageSize) break
        }
        found.distinctBy { it.slug }.sortedByDescending { it.totalMediaCount }.take(safeLimit)
    }

    suspend fun categoryAnime(
        categorySlug: String,
        offset: Int = 0,
        limit: Int = PAGE_LIMIT,
    ): KitsuCategoryPage = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val document = get(
            "/anime?filter[categories]=$categorySlug&sort=-userCount" +
                "&page[limit]=$safeLimit&page[offset]=$safeOffset&include=mappings"
        ) ?: return@withContext KitsuCategoryPage(emptyList(), 0, false, false)

        val includedMappings = document.included.orEmpty().associateBy { it.id }
        val resources = document.dataList()
        val items = resources.mapNotNull { resource ->
            val id = resource.id ?: return@mapNotNull null
            val attributes = resource.animeAttributes() ?: return@mapNotNull null
            val title = attributes.titles?.en?.takeIf { it.isNotBlank() }
                ?: attributes.titles?.enJp?.takeIf { it.isNotBlank() }
                ?: attributes.titles?.jaJp?.takeIf { it.isNotBlank() }
                ?: attributes.canonicalTitle?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val anilistId = resource.relationshipIds("mappings")
                .asSequence()
                .mapNotNull { includedMappings[it]?.mappingAttributes() }
                .firstOrNull { it.externalSite == ANILIST_SITE }
                ?.externalId?.toIntOrNull()
            KitsuDiscoveryItem(
                id = id,
                title = title,
                synopsis = attributes.synopsis ?: attributes.description,
                year = attributes.startDate?.take(4)?.toIntOrNull() ?: attributes.seasonYear,
                userCount = attributes.userCount,
                score = attributes.averageRating?.toDoubleOrNull()?.div(10.0),
                posterUrl = attributes.posterImage?.large
                    ?: attributes.posterImage?.medium ?: attributes.posterImage?.small,
                anilistId = anilistId,
            )
        }
        KitsuCategoryPage(items, resources.size, resources.size >= safeLimit, true)
    }

    suspend fun discoverEditorial(limit: Int = 20): List<KitsuDiscoveryItem> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, PAGE_LIMIT)
        val document = get("/anime?sort=-userCount&page[limit]=$safeLimit&include=mappings")
            ?: return@withContext emptyList()
        val includedMappings = document.included.orEmpty().associateBy { it.id }
        document.dataList().mapNotNull { resource ->
            val id = resource.id ?: return@mapNotNull null
            val attributes = resource.animeAttributes() ?: return@mapNotNull null
            val anilistId = resource.relationshipIds("mappings")
                .asSequence()
                .mapNotNull { includedMappings[it]?.mappingAttributes() }
                .firstOrNull { it.externalSite == ANILIST_SITE }
                ?.externalId?.toIntOrNull()
            val title = attributes.titles?.en?.takeIf { it.isNotBlank() }
                ?: attributes.canonicalTitle?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            KitsuDiscoveryItem(
                id = id,
                title = title,
                synopsis = attributes.synopsis ?: attributes.description,
                year = attributes.startDate?.take(4)?.toIntOrNull() ?: attributes.seasonYear,
                userCount = attributes.userCount,
                score = attributes.averageRating?.toDoubleOrNull()?.div(10.0),
                posterUrl = attributes.posterImage?.medium ?: attributes.posterImage?.small,
                anilistId = anilistId,
            )
        }
    }

    suspend fun franchiseCard(anilistId: Int): Franchise? = withContext(Dispatchers.IO) {
        if (missingCache.containsKey(anilistId)) return@withContext null
        franchiseCache[anilistId]?.let { return@withContext it }
        val card = tryWithSuspend(snackbar = false) {
            buildCard(anilistId)
        }
        if (card == null) missingCache[anilistId] = true else franchiseCache[anilistId] = card
        card
    }

    private suspend fun buildCard(anilistId: Int): Franchise? {
        val kitsuId = kitsuAnimeId(anilistId) ?: run {
            Logger.log("Kitsu: anilist $anilistId has no kitsu mapping")
            return null
        }

        val franchiseId = franchiseIdOf(kitsuId) ?: run {
            Logger.log("Kitsu: kitsu $kitsuId belongs to no franchise")
            return null
        }
        val allInstallments = installmentsOf(franchiseId)
        if (allInstallments.isEmpty()) {
            Logger.log("Kitsu: franchise $franchiseId returned no installments")
            return null
        }

        val installmentIds = allInstallments.take(MAX_ENTRIES)

        val pairs = installmentIds.mapNotNull { installment ->
            mediaIdOf(installment)?.let { installment to it }
        }
        if (pairs.isEmpty()) {
            Logger.log("Kitsu: franchise $franchiseId had ${installmentIds.size} installments, none resolved to a media id")
            return null
        }

        val positions = curatedPositions(installmentIds)
        val animes = animeByIds(pairs.map { it.second }).ifEmpty {
            Logger.log("Kitsu: ${pairs.size} media ids of franchise $franchiseId all failed to load")
            return null
        }

        val entries = animes
            .mapNotNull { anime ->

                val resolvedId = anilistIdOf(anime.id())
                    ?: if (anime.id() == kitsuId) anilistId else null
                val mapped = anime.toEntry(resolvedId) ?: return@mapNotNull null

                val position = pairs.firstOrNull { it.second == anime.id() }?.first
                    ?.let { positions[it] }
                mapped to position
            }
            .sortedWith(

                compareByDescending<Pair<FranchiseEntry, Int?>> { it.second != null }
                    .thenBy { it.second ?: Int.MAX_VALUE }
                    .thenBy { it.first.sortYear }
            )
            .map { it.first }
            .ifEmpty { return null }

        val name = franchiseName(franchiseId)
            ?: animes.firstNotNullOfOrNull { it.animeAttributes()?.displayTitle() }
            ?: run {
                Logger.log("Kitsu: franchise $franchiseId had no name and no titled anime")
                return null
            }
        val card = Franchise(

            name = name,

            bannerUrl = entries.last().posterUrl,
            entries = entries,
        )
        Logger.log("Kitsu: built '$name' for anilist $anilistId with ${entries.size} entries")
        return card
    }

    private suspend fun franchiseName(franchiseId: String): String? {
        franchiseNameCache[franchiseId]?.let { return it }
        val title = get("/franchises/$franchiseId")?.dataOne()?.franchiseAttributes()
            ?.let { attrs ->
                attrs.titles?.en?.takeIf { it.isNotBlank() }
                    ?: attrs.canonicalTitle?.takeIf { it.isNotBlank() }
            } ?: return null
        return title.also { franchiseNameCache[franchiseId] = it }
    }

    suspend fun kitsuAnimeId(anilistId: Int): String? {
        kitsuIdCache[anilistId]?.let { return it }

        val ids = animeIdsForAnilist(listOf(anilistId))
        return ids[anilistId]?.also { kitsuIdCache[anilistId] = it }
    }

    private suspend fun animeIdsForAnilist(anilistIds: List<Int>): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (batch in anilistIds.distinct().chunked(PAGE_LIMIT)) {
            val ids = batch.joinToString(",")
            val doc = get(
                "/mappings?filter[externalSite]=$ANILIST_SITE&filter[externalId]=$ids" +
                    "&page[limit]=$PAGE_LIMIT&include=item"
            ) ?: continue

            val rows = doc.dataList()
            val items = doc.included.orEmpty()
            if (rows.size != items.size) continue

            for (i in rows.indices) {
                val externalId = rows[i].mappingAttributes()?.externalId
                    ?.toIntOrNull() ?: continue
                val animeId = items[i].id ?: continue
                out[externalId] = animeId
            }
        }
        return out
    }

    private suspend fun franchiseIdOf(animeId: String): String? {
        franchiseIdCache[animeId]?.let { return it }
        val installment = get("/anime/$animeId/relationships/installments")
            ?.dataOne()?.id ?: return null
        return get("/installments/$installment/relationships/franchise")
            ?.dataOne()?.id
            ?.also { franchiseIdCache[animeId] = it }
    }

    private suspend fun installmentsOf(franchiseId: String): List<String> {
        installmentsCache[franchiseId]?.let { return it }
        val doc = get("/franchises/$franchiseId/relationships/installments") ?: return emptyList()
        return doc.dataList().mapNotNull { it.id }
            .also { installmentsCache[franchiseId] = it }
    }

    private suspend fun mediaIdOf(installmentId: String): String? {
        mediaIdCache[installmentId]?.let { return it }
        return get("/installments/$installmentId/relationships/media")?.dataOne()?.id
            ?.also { mediaIdCache[installmentId] = it }
    }

    private suspend fun animeByIds(ids: List<String>): List<KitsuResource> {
        val missing = ids.distinct().filter { !animeCache.containsKey(it) }
        for (batch in missing.chunked(PAGE_LIMIT)) {
            val doc = get(
                "/anime?filter[id]=${batch.joinToString(",")}&page[limit]=$PAGE_LIMIT" +
                    "&include=mappings"
            ) ?: continue
            val mappingsById = (doc.included.orEmpty())
                .associateBy { it.id }
                .mapNotNull { (id, resource) ->
                    val anilistId = resource.mappingAttributes()
                        ?.takeIf { it.externalSite == ANILIST_SITE }
                        ?.externalId?.toIntOrNull()
                    anilistId?.let { id to it }
                }
                .toMap()
            for (anime in doc.dataList()) {
                val id = anime.id ?: continue
                animeCache[id] = anime

                anime.relationshipIds("mappings").forEach { mappingId ->
                    mappingsById[mappingId]?.let { anilistIdCache[id] = it }
                }
            }
        }

        return ids.distinct().mapNotNull { animeCache[it] }
    }

    private fun anilistIdOf(kitsuId: String): Int? = anilistIdCache[kitsuId]

    private fun KitsuResource.id() = id.orEmpty()

    private fun KitsuResource.toEntry(anilistId: Int?) = animeAttributes()?.toEntry(anilistId)

    private fun KitsuAnimeAttributes.displayTitle() =
        titles?.en?.takeIf { title -> title.isNotBlank() }
            ?: canonicalTitle?.takeIf { title -> title.isNotBlank() }

    private fun KitsuAnimeAttributes.toEntry(anilistId: Int?): FranchiseEntry? {
        val title = displayTitle() ?: return null
        val year = startDate?.take(4)?.toIntOrNull() ?: seasonYear
        return FranchiseEntry(
            year = year?.toString().orEmpty(),
            sortYear = year,
            posterUrl = posterImage?.medium ?: posterImage?.small ?: posterImage?.large,
            title = title,
            synopsis = synopsis ?: description,
            anilistId = anilistId,

            type = FranchiseType.fromFormat((showType ?: subtype)?.uppercase(java.util.Locale.US)),
        )
    }

    suspend fun allCategories(): List<KitsuCategory> {
        categoryCache?.let { return it }
        val out = LinkedHashMap<String, KitsuCategory>()
        var offset = 0
        var complete = false
        while (true) {
            val doc = get(
                "/categories?page[limit]=$PAGE_LIMIT&page[offset]=$offset" +
                    "&sort=title&include=parent"
            ) ?: break
            val rows = doc.dataList()
            if (rows.isEmpty()) {
                complete = true
                break
            }
            val parentsById = doc.included.orEmpty()
                .associate { resource -> resource.id to resource.categoryAttributes()?.title }
            for (resource in rows) {
                val attrs = resource.categoryAttributes() ?: continue
                val id = resource.id ?: continue
                val title = attrs.title?.takeIf { it.isNotBlank() } ?: continue
                val slug = attrs.slug?.takeIf { it.isNotBlank() } ?: continue
                val parentId = resource.relationshipIds("parent").firstOrNull()
                out[id] = KitsuCategory(
                    id = id,
                    title = title,
                    slug = slug,
                    description = attrs.description,
                    nsfw = attrs.nsfw,
                    childCount = attrs.childCount,
                    mediaCount = attrs.totalMediaCount,
                    parentId = parentId,
                    parentTitle = parentId?.let { parentsById[it] },
                )
            }
            offset += rows.size
            val total = doc.meta?.count ?: 0
            if (rows.size < PAGE_LIMIT || (total > 0 && offset >= total)) {
                complete = true
                break
            }
        }
        val categories = out.values.toList()

        if (!complete) {
            Logger.log("Kitsu: category catalogue fetch incomplete at offset=$offset; collected=${categories.size}")
            return emptyList()
        }
        if (categories.isNotEmpty()) categoryCache = categories
        return categories
    }

    suspend fun categoryThumbnails(slug: String): List<String> {
        if (slug.isBlank()) return emptyList()
        categoryThumbnailCache[slug]?.let { return it }
        val doc = get(
            "/anime?filter[categories]=$slug&sort=-averageRating&page[limit]=$PAGE_LIMIT" +
                "&page[offset]=0"
        ) ?: return emptyList()
        val posters = doc.dataList().asSequence().mapNotNull { resource ->
            val attrs = resource.animeAttributes() ?: return@mapNotNull null
            if (attrs.nsfw && !PrefManager.getVal<Boolean>(PrefName.IncludeAdultDiscovery)) return@mapNotNull null
            attrs.posterImage?.large
                ?: attrs.posterImage?.medium
                ?: attrs.posterImage?.original
                ?: attrs.posterImage?.small
        }.filter { it.isNotBlank() }.distinct().toList()
        if (posters.isNotEmpty()) categoryThumbnailCache.putIfAbsent(slug, posters)
        return categoryThumbnailCache[slug] ?: posters
    }

    suspend fun categoryThumbnail(slug: String, excludedPosters: Set<String> = emptySet()): String? {
        val posters = categoryThumbnails(slug)
        return posters.filterNot { it in excludedPosters }.randomOrNull() ?: posters.randomOrNull()
    }

    suspend fun animeByCategory(slug: String, page: Int = 1): CategoryPage {
        val offset = ((page - 1).coerceAtLeast(0)) * PAGE_LIMIT
        val doc = get(
            "/anime?filter[categories]=$slug&sort=-averageRating" +
                "&page[limit]=$PAGE_LIMIT&page[offset]=$offset&include=mappings"
        ) ?: return CategoryPage(emptyList(), hasMore = false, failed = true)
        val rows = doc.dataList()
        val mappingsById = doc.included.orEmpty().associateBy { it.id }
        val media = rows.mapNotNull { resource ->
            val attrs = resource.animeAttributes() ?: return@mapNotNull null
            if (attrs.nsfw && !PrefManager.getVal<Boolean>(PrefName.IncludeAdultDiscovery)) return@mapNotNull null
            val anilistId = resource.relationshipIds("mappings").firstNotNullOfOrNull { mappingId ->
                mappingsById[mappingId]?.mappingAttributes()
                    ?.takeIf { it.externalSite == ANILIST_SITE }
                    ?.externalId?.toIntOrNull()
            } ?: return@mapNotNull null
            resource.toMedia(anilistId, attrs)
        }

        val consumed = offset + rows.size
        val total = doc.meta?.count ?: 0
        val hasMore = if (total > 0) consumed < total else rows.size >= PAGE_LIMIT
        return CategoryPage(media = media, hasMore = hasMore)
    }

    suspend fun publicAnime(
        sort: String = "-userCount",
        status: String? = null,
        limit: Int = PAGE_LIMIT,
        offset: Int = 0,
    ): List<Media> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val statusFilter = status?.takeIf { it.isNotBlank() }?.let { "&filter[status]=$it" } ?: ""
        val doc = get(
            "/anime?sort=$sort$statusFilter&page[limit]=$safeLimit&page[offset]=$safeOffset&include=mappings"
        ) ?: return@withContext emptyList()
        val mappingsById = doc.included.orEmpty().associateBy { it.id }
        doc.dataList().mapNotNull { resource ->
            val attrs = resource.animeAttributes() ?: return@mapNotNull null
            if (attrs.nsfw && !PrefManager.getVal<Boolean>(PrefName.IncludeAdultDiscovery)) return@mapNotNull null
            val anilistId = resource.relationshipIds("mappings").firstNotNullOfOrNull { mappingId ->
                mappingsById[mappingId]?.mappingAttributes()
                    ?.takeIf { it.externalSite == ANILIST_SITE }
                    ?.externalId?.toIntOrNull()
            } ?: return@mapNotNull null
            resource.toMedia(anilistId, attrs)
        }
    }

    suspend fun search(query: String, limit: Int = 20): List<Media> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = get(
            "/anime?filter[text]=$encoded&page[limit]=$limit&include=mappings"
        ) ?: return@withContext emptyList()
        val mappingsById = doc.included.orEmpty().associateBy { it.id }
        doc.dataList().mapNotNull { resource ->
            val attrs = resource.animeAttributes() ?: return@mapNotNull null
            val anilistId = resource.relationshipIds("mappings").firstNotNullOfOrNull { mappingId ->
                mappingsById[mappingId]?.mappingAttributes()
                    ?.takeIf { it.externalSite == ANILIST_SITE }
                    ?.externalId?.toIntOrNull()
            } ?: return@mapNotNull null
            resource.toMedia(anilistId, attrs)
        }
    }

    private fun KitsuResource.toMedia(anilistId: Int, attrs: KitsuAnimeAttributes): Media? {
        val title = attrs.displayTitle() ?: return null
        return Media(
            id = anilistId,
            name = title,
            nameRomaji = attrs.titles?.enJp?.takeIf { it.isNotBlank() } ?: title,
            userPreferredName = title,
            cover = attrs.posterImage?.large
                ?: attrs.posterImage?.medium
                ?: attrs.posterImage?.original,
            banner = attrs.coverImage?.large ?: attrs.coverImage?.original,
            isAdult = attrs.nsfw,
            description = attrs.synopsis,
            format = attrs.subtype?.uppercase(Locale.ROOT)
                ?: attrs.showType?.uppercase(Locale.ROOT),
            startDate = attrs.startDate?.take(4)?.toIntOrNull()?.let { FuzzyDate(year = it) },
            anime = Anime(
                totalEpisodes = attrs.episodeCount,
                season = attrs.season,
                seasonYear = attrs.seasonYear,
            ),
            idKitsu = id,
        )
    }

    private suspend fun get(path: String): KitsuDocument? = tryWithSuspend(snackbar = false) {
        val request = Request.Builder()
            .url(BASE + path)
            .get()
            .addHeader("Accept", "application/vnd.api+json")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Logger.log("Kitsu HTTP ${response.code} for $path: ${response.message}")
                null
            } else {
                val body = response.body?.string()
                if (body == null) {
                    Logger.log("Kitsu empty body for $path")
                    null
                } else {
                    val decoded = kitsuJson.decodeFromString<KitsuDocument>(body)
                    Logger.log("Kitsu ${response.code} $path -> ${decoded.dataList().size} resources, ${body.length} bytes")
                    decoded
                }
            }
        }
    }
}
