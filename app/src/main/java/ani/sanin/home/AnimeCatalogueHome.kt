package ani.sanin.home

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.media.anime.Anime
import ani.sanin.media.Media
import ani.sanin.media.Selected
import ani.sanin.parsers.AnimeSources
import ani.sanin.parsers.DynamicAnimeParser
import ani.sanin.parsers.NativeAnimeParser
import ani.sanin.parsers.ShowResponse
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import ani.sanin.Lazier
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class CatalogueItem(
    val response: ShowResponse,
    val media: Media? = null,
)

data class CatalogueRow(val title: String, val items: List<CatalogueItem>)

object AnimeCatalogueHome {

    private const val PER_SECTION = 20
    private val SECTION_ORDER = listOf(
        "Ongoing",
        "Latest Episodes",
        "Trending",
        "Popular",
        "Upcoming",
        "Recommended",
    )

    fun isCatalogueSource(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        if (id.equals("anilist", true) || id.equals("mal", true)) return false
        if (id.startsWith("native:", true)) {
            val saveName = id.substringAfter(':')
            return AnimeSources.nativeParsers.any { it.saveName == saveName }
        }
        return true
    }

    fun savedSourceId(): String = PrefManager.getVal(PrefName.AnimeCatalogueSource)

    suspend fun loadRows(sourceId: String): List<CatalogueRow> {
        Logger.log("Catalogue: loadRows('$sourceId')")
        if (!isCatalogueSource(sourceId)) {
            Logger.log("Catalogue: '$sourceId' is not a catalogue source")
            return emptyList()
        }
        if (sourceId.startsWith("ext:", true)) {
            val name = sourceId.substringAfter(':')
            val parser = resolveExtension(name)
            if (parser == null) {
                Logger.log(
                    "Catalogue: extension '$name' not found " +
                        "(known sources=${AnimeSources.list.size})"
                )
                return emptyList()
            }
            Logger.log("Catalogue: extension '$name' resolved to '${parser.name}'")
            val nativeRows = loadExtensionRows(parser)
            val rows = ordered(withTmdbFallback(nativeRows) { title ->
                searchExtension(parser, title)
            })
            Logger.log("Catalogue: extension '$name' returned ${rows.size} rows")
            return rows
        }
        if (sourceId.startsWith("native:", true)) {
            val saveName = sourceId.substringAfter(':')
            val parser = AnimeSources.allNativeParsers.firstOrNull { it.saveName == saveName }
            if (parser == null) {
                Logger.log(
                    "Catalogue: native provider '$saveName' not found " +
                        "(known=${AnimeSources.allNativeParsers.size})"
                )
                return emptyList()
            }
            Logger.log("Catalogue: native '$saveName' resolved to '${parser.name}'")
            val rows = ordered(loadNativeRows(parser))
            Logger.log("Catalogue: native '$saveName' returned ${rows.size} rows")
            return rows
        }
        Logger.log("Catalogue: unsupported source id '$sourceId'")
        return emptyList()
    }

    private fun ordered(rows: List<CatalogueRow>): List<CatalogueRow> {
        rows.forEach { Logger.log("Catalogue: section '${it.title}' -> ${it.items.size} items") }
        val byTitle = rows.filter { it.items.isNotEmpty() }.associateBy { it.title }
        val ordered = (
            SECTION_ORDER.mapNotNull { byTitle[it] } +
                rows.filter { it.items.isNotEmpty() && it.title !in SECTION_ORDER }
            ).take(10)
        if (ordered.isEmpty()) Logger.log("Catalogue: every section was empty after ordering")
        return ordered
    }

    private fun resolveExtension(name: String): DynamicAnimeParser? {
        val source = AnimeSources.list.firstOrNull { it.name == name }
        if (source == null) {
            Logger.log("Catalogue: no source named '$name' in the source list")
            return null
        }
        val parser = runCatching { source.get?.value }.getOrElse {
            Logger.log("Catalogue: failed to instantiate extension '$name'")
            Logger.log(it)
            null
        }
        if (parser !is DynamicAnimeParser) {
            Logger.log(
                "Catalogue: source '$name' is " +
                    "${parser?.javaClass?.simpleName ?: "null"}, not a dynamic extension"
            )
        }
        return parser as? DynamicAnimeParser
    }

    private fun catalogueSourceOf(parser: DynamicAnimeParser): AnimeCatalogueSource? {
        val sources = runCatching { parser.extension.sources }.getOrElse {
            Logger.log("Catalogue: '${parser.name}' exposed no source list")
            Logger.log(it)
            return null
        }
        val chosen = sources.getOrNull(parser.sourceLanguage) as? AnimeCatalogueSource
            ?: sources.filterIsInstance<AnimeCatalogueSource>().firstOrNull()
        Logger.log(
            "Catalogue: '${parser.name}' sources=${sources.size} " +
                "lang=${parser.sourceLanguage} chosen=${chosen?.name ?: "none"}"
        )
        return chosen
    }

    private suspend fun loadExtensionRows(parser: DynamicAnimeParser): List<CatalogueRow> =
        withContext(Dispatchers.IO) {
            val source = catalogueSourceOf(parser) ?: run {
                Logger.log("Catalogue: '${parser.name}' has no catalogue source")
                return@withContext emptyList()
            }
            Logger.log("Catalogue: loading extension '${parser.name}' via '${source.name}'")

            val popular1 = page(source) { it.getPopularAnime(1) }
            val popular2 = page(source) { it.getPopularAnime(2) }
            val latest = if (source.supportsLatest) {
                page(source) { it.getLatestUpdates(1) }
            } else {
                Logger.log("Catalogue: '${source.name}' does not support latest")
                emptyList()
            }
            Logger.log(
                "Catalogue: '${source.name}' popular1=${popular1.size} " +
                    "popular2=${popular2.size} latest=${latest.size}"
            )

            val ongoingByStatus = (popular1 + latest)
                .filter { it.status == SAnime.ONGOING }
                .distinctBy { it.url }
            val ongoing = ongoingByStatus.ifEmpty {
                searchWithStatus(
                    source,
                    listOf("airing", "ongoing", "releasing", "emision", "en emisión", "en emision"),
                )
            }
            val upcoming = searchWithStatus(
                source,
                listOf("upcoming", "not yet aired", "unreleased", "próximamente", "proximamente", "not yet"),
            )

            val trending = popular2.ifEmpty { popular1.reversed() }
            val basePool = (popular1 + popular2 + latest + ongoing + upcoming)
                .distinctBy { it.url }
            val recommended = basePool.shuffled().take(PER_SECTION)
            val extraRows = loadFilterRows(source, existingTitles = SECTION_ORDER.toSet())
            Logger.log(
                "Catalogue: '${source.name}' ongoing=${ongoing.size} " +
                    "upcoming=${upcoming.size} trending=${trending.size} " +
                    "recommended=${recommended.size}"
            )

            listOf(
                CatalogueRow("Ongoing", ongoing.take(PER_SECTION).map { it.toItem() }),
                CatalogueRow("Latest Episodes", latest.take(PER_SECTION).map { it.toItem() }),
                CatalogueRow("Trending", trending.take(PER_SECTION).map { it.toItem() }),
                CatalogueRow("Popular", popular1.take(PER_SECTION).map { it.toItem() }),
                CatalogueRow("Upcoming", upcoming.take(PER_SECTION).map { it.toItem() }),
                CatalogueRow("Recommended", recommended.map { it.toItem() }),
            ) + extraRows
        }

    private suspend fun page(
        source: AnimeCatalogueSource,
        block: suspend (AnimeCatalogueSource) -> AnimesPage,
    ): List<SAnime> = runCatching { block(source).animes }.getOrElse {
        Logger.log(
            "Catalogue: section fetch failed on '${source.name}': " +
                "${it.javaClass.simpleName}: ${it.message}"
        )
        Logger.log(it)
        emptyList()
    }

    private suspend fun searchWithStatus(
        source: AnimeCatalogueSource,
        keywords: List<String>,
    ): List<SAnime> {
        val filters = runCatching { source.getFilterList() }.getOrNull() ?: return emptyList()
        var applied = false
        for (filter in filters) {
            if (filter !is AnimeFilter.Select<*>) continue
            val filterName = filter.name.lowercase()
            if (!(filterName.contains("status") || filterName.contains("estado") || filterName.contains("state"))) continue
            val index = filter.values.indexOfFirst { value ->
                val text = value.toString().lowercase()
                keywords.any { text.contains(it) }
            }
            if (index >= 0) {
                filter.state = index
                applied = true
            }
        }
        if (!applied) return emptyList()
        return runCatching {
            source.getSearchAnime(1, "", AnimeFilterList(filters.toList())).animes
        }.getOrElse {
            Logger.log(
                "Catalogue: status search failed on '${source.name}': " +
                    "${it.javaClass.simpleName}: ${it.message}"
            )
            Logger.log(it)
            emptyList()
        }
    }

    private fun SAnime.toItem(): CatalogueItem = CatalogueItem(
        response = ShowResponse(
            name = title,
            link = url,
            coverUrl = thumbnail_url ?: "",
            sAnime = this,
        )
    )

    private suspend fun loadNativeRows(parser: NativeAnimeParser): List<CatalogueRow> =
        withContext(Dispatchers.IO) {
            Logger.log("Catalogue: loading native '${parser.name}' from TMDB")
            val resolve: suspend (String) -> CatalogueItem? = { title ->
                val wanted = title.lowercase().filter { ch -> ch.isLetterOrDigit() }
                runCatching {
                    parser.search(title).firstOrNull {
                        it.name.lowercase().filter { ch -> ch.isLetterOrDigit() } == wanted
                    }?.let { CatalogueItem(response = it) }
                }
                    .onFailure { Logger.log("Catalogue: native search failed for '$title': ${it.message}") }
                    .getOrNull()
            }
            val rows = tmdbRows(resolve)
            if (rows.isNotEmpty()) {
                Logger.log("Catalogue: native '${parser.name}' returned ${rows.size} TMDB-backed rows")
                rows
            } else {
                val grid = tmdbGrid(resolve)
                if (grid.isEmpty()) emptyList() else listOf(CatalogueRow("", grid.take(100)))
            }
        }

    private suspend fun withTmdbFallback(
        rows: List<CatalogueRow>,
        resolve: suspend (String) -> CatalogueItem?,
    ): List<CatalogueRow> {
        val existing = rows.associateBy { it.title }
        val needsFallback = SECTION_ORDER.filter { existing[it]?.items.isNullOrEmpty() }
        if (needsFallback.isEmpty()) return rows.take(10)
        val fallback = tmdbRows(resolve, needsFallback.toSet())
        return (rows.filter { it.items.isNotEmpty() } + fallback)
            .distinctBy { it.title }
            .take(10)
    }

    private suspend fun tmdbRows(
        resolve: suspend (String) -> CatalogueItem?,
        only: Set<String> = SECTION_ORDER.toSet(),
    ): List<CatalogueRow> = withContext(Dispatchers.IO) {

        val trending = runCatching { Tmdb.trending("tv", "week") }.getOrDefault(emptyList())
        val popular = runCatching { Tmdb.popular(1) }.getOrDefault(emptyList())
        val upcoming = runCatching {
            Tmdb.exploreRow(Tmdb.ExploreRow.UPCOMING, "tv")
        }.getOrDefault(emptyList())
        val ongoing = runCatching { Tmdb.onTheAir() }.getOrDefault(emptyList())
        val candidates = linkedMapOf(
            "Ongoing" to ongoing,
            "Trending" to trending,
            "Popular" to popular,
            "Upcoming" to upcoming,
        )
        val keyOf: (String) -> String = { it.lowercase().filter { ch -> ch.isLetterOrDigit() } }
        val titlesBySection = only.mapNotNull { section ->
            val source = candidates[section] ?: return@mapNotNull null
            val titles = source.asSequence()
                .filter { it.type == "tv" || it.type == "movie" }
                .distinctBy { "${it.type}:${it.id}" }
                .map { it.displayTitle }
                .filter { it.isNotBlank() }
                .distinct()
                .take(PER_SECTION)
                .toList()
            if (titles.isEmpty()) null else section to titles
        }.toMap()

        val uniqueTitles = titlesBySection.values.flatten().distinctBy(keyOf)
        val gate = Semaphore(4)
        val resolvedByTitle = coroutineScope {
            uniqueTitles.map { title ->
                async(Dispatchers.IO) {
                    val item = try {
                        gate.withPermit { resolve(title) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.log("Catalogue: TMDB fallback resolution failed for '$title': ${e.message}")
                        null
                    }
                    keyOf(title) to item
                }
            }.awaitAll().toMap()
        }
        titlesBySection.mapNotNull { (section, titles) ->
            val items = titles.mapNotNull { resolvedByTitle[keyOf(it)] }
                .distinctBy { it.response.link }
                .take(PER_SECTION)
            if (items.isEmpty()) null else CatalogueRow(section, items)
        }
    }

    private suspend fun loadFilterRows(
        source: AnimeCatalogueSource,
        existingTitles: Set<String>,
    ): List<CatalogueRow> = withContext(Dispatchers.IO) {
        val filters = runCatching { source.getFilterList() }.getOrNull() ?: return@withContext emptyList()
        val categoryFilter = filters.firstOrNull { filter ->
            filter is AnimeFilter.Select<*> &&
                listOf("genre", "category", "type").any { filter.name.lowercase().contains(it) }
        } as? AnimeFilter.Select<*> ?: return@withContext emptyList()
        val values = categoryFilter.values.mapIndexedNotNull { index, value ->
            if (index == categoryFilter.state || value.toString().isBlank() ||
                value.toString().contains("all", true) || value.toString().contains("select", true)
            ) null else index to value.toString()
        }.take(4)
        values.mapNotNull { (index, label) ->
            val copy = runCatching { source.getFilterList() }.getOrNull() ?: return@mapNotNull null
            val target = copy.firstOrNull { it.name == categoryFilter.name } as? AnimeFilter.Select<*>
                ?: return@mapNotNull null
            target.state = index
            val items = runCatching {
                source.getSearchAnime(1, "", AnimeFilterList(copy.toList())).animes
                    .distinctBy { it.url }.take(PER_SECTION).map { it.toItem() }
            }.getOrDefault(emptyList())
            val title = label.trim().replaceFirstChar { it.uppercase() }
            if (items.isEmpty() || title in existingTitles) null else CatalogueRow(title, items)
        }
    }

    private suspend fun loadExtensionGrid(parser: DynamicAnimeParser): List<CatalogueItem> =
        withContext(Dispatchers.IO) {
            val source = catalogueSourceOf(parser) ?: return@withContext emptyList()
            val items = (page(source) { it.getPopularAnime(1) } +
                page(source) { it.getPopularAnime(2) } +
                if (source.supportsLatest) page(source) { it.getLatestUpdates(1) } else emptyList())
                .distinctBy { it.url }.take(100).map { it.toItem() }
            items
        }

    private suspend fun tmdbGrid(
        resolve: suspend (String) -> CatalogueItem?,
    ): List<CatalogueItem> = withContext(Dispatchers.IO) {
        val candidates = (runCatching { Tmdb.popular(1) }.getOrDefault(emptyList()) +
            runCatching { Tmdb.trending("tv", "week") }.getOrDefault(emptyList()))
            .distinctBy { "${it.type}:${it.id}" }
        val result = mutableListOf<CatalogueItem>()
        for (candidate in candidates) {
            if (result.size >= 100) break
            val title = candidate.displayTitle
            if (title.isBlank()) continue
            runCatching { resolve(title) }.getOrNull()?.let(result::add)
        }
        result.distinctBy { it.response.link }.take(100)
    }

    private suspend fun searchExtension(
        parser: DynamicAnimeParser,
        title: String,
    ): CatalogueItem? {
        val source = catalogueSourceOf(parser) ?: return null
        val results = runCatching {
            source.getSearchAnime(1, title, AnimeFilterList()).animes
        }.getOrDefault(emptyList())
        val normalize: (String) -> String = { it.lowercase().filter { ch -> ch.isLetterOrDigit() } }
        val wanted = normalize(title)
        val result = results.firstOrNull { normalize(it.title) == wanted } ?: return null
        return result.toItem()
    }

    fun watchSourceIndex(sourceId: String): Int {
        val name = when {
            sourceId.startsWith("native:", true) ->
                AnimeSources.allNativeParsers
                    .firstOrNull { it.saveName == sourceId.substringAfter(':') }?.name
            sourceId.startsWith("ext:", true) -> sourceId.substringAfter(':')
            else -> null
        } ?: return -1

        val existing = AnimeSources.list.indexOfFirst { it.name == name }
        if (existing >= 0) return existing

        val parser = AnimeSources.allNativeParsers.firstOrNull { it.name == name } ?: return -1

        val enabled = PrefManager.getVal<Set<String>>(PrefName.EnabledProviders).toMutableSet()
        if (enabled.add(parser.saveName)) {
            PrefManager.setVal(PrefName.EnabledProviders, enabled)
            AnimeSources.rebuildNativeParsers()
            val rebuilt = AnimeSources.list.indexOfFirst { it.name == name }
            if (rebuilt >= 0) return rebuilt
        }
        val list = AnimeSources.list.toMutableList()
        val localIndex = list.indexOfFirst { it.name == "Local" }
        val insertion = if (localIndex >= 0) localIndex else list.size
        list.add(insertion, Lazier<ani.sanin.parsers.BaseParser>({ parser }, parser.name))
        AnimeSources.list = list
        return insertion
    }

    fun displayMedia(sourceId: String, item: CatalogueItem): Media =
        item.media ?: item.response.toSyntheticMedia(sourceId)

    fun openItem(
        activity: AppCompatActivity,
        sourceId: String,
        item: CatalogueItem,
        onFailed: () -> Unit = {},
        onResolved: (Media) -> Unit,
    ) {
        val index = watchSourceIndex(sourceId)
        if (index < 0) {
            Logger.log("Catalogue: no watch source for '$sourceId'")
            onFailed()
            return
        }
        activity.lifecycleScope.launch {

            val media = item.media ?: withContext(Dispatchers.IO) {
                item.response.toSyntheticMedia(sourceId)
            }

            withContext(Dispatchers.IO) {
                runCatching {
                    AnimeSources.saveResponse(index, media.id, item.response)
                    val selected = Selected(
                        sourceIndex = index,
                        preferDub = PrefManager.getVal(PrefName.PreferDub),
                    )
                    media.selected = selected
                    PrefManager.setCustomVal("Selected-${media.id}", selected)
                }.onFailure { Logger.log("Catalogue: failed saving source for '${media.name}': ${it.message}") }
            }
            onResolved(media)
        }
    }

    private fun ShowResponse.toSyntheticMedia(sourceId: String): Media {
        val id = syntheticId(sourceId, link)
        return Media(
            anime = Anime(totalEpisodes = total),
            id = id,
            name = name,
            nameRomaji = name,
            userPreferredName = name,
            isAdult = false,
            cover = coverUrl.url.ifBlank { null },
            format = "TV",
        )
    }

    private fun syntheticId(sourceId: String, link: String): Int {
        val hash = "$sourceId|$link".hashCode()
        return if (hash == Int.MIN_VALUE) -1 else -abs(hash) - 1
    }
}
