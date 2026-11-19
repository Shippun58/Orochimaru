package ani.sanin.home

import android.content.Context
import ani.sanin.cloudstream.CsRepos
import ani.sanin.cloudstream.CsRuntime
import ani.sanin.settings.StremioAddonClient
import ani.sanin.settings.StremioAddonManager
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HomeSectionEntry(
    val providerId: String,
    val providerName: String,
    val sectionKey: String,
    val title: String,
    val enabled: Boolean = true,
)

data class HomeSectionsEnumeration(
    val sections: List<HomeSectionEntry>,
    val validProviderIds: Set<String>,
)

enum class HomeSectionsMode { ANIME, MOVIE_TV }

object HomeSectionsConfig {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val animeSectionKeys = listOf("continue", "favorites", "planned", "missed", "recommended")

    private fun prefFor(mode: HomeSectionsMode): PrefName =
        if (mode == HomeSectionsMode.ANIME) PrefName.HomeSectionsAnime else PrefName.HomeSectionsMovieTv

    fun load(mode: HomeSectionsMode): MutableList<HomeSectionEntry> {
        val raw = PrefManager.getVal<String>(prefFor(mode))
        if (raw.isBlank()) return mutableListOf()
        return runCatching {
            json.decodeFromString<List<HomeSectionEntry>>(raw).toMutableList()
        }.getOrElse { mutableListOf() }
    }

    fun loadMovieTv(): List<HomeSectionEntry> = load(HomeSectionsMode.MOVIE_TV)

    fun save(mode: HomeSectionsMode, entries: List<HomeSectionEntry>) {
        PrefManager.setVal(prefFor(mode), json.encodeToString(entries))
        val versionPref = if (mode == HomeSectionsMode.ANIME) {
            PrefName.HomeSectionsAnimeVersion
        } else {
            PrefName.HomeSectionsMovieTvVersion
        }
        PrefManager.setVal(versionPref, PrefManager.getVal<Int>(versionPref) + 1)
        if (mode == HomeSectionsMode.ANIME) syncAnimeLegacy(entries)
    }

    fun animeEntries(): List<HomeSectionEntry> = listOf(
        HomeSectionEntry("anilist", "AniList", "continue", "Continue Watching"),
        HomeSectionEntry("anilist", "AniList", "favorites", "Favorite"),
        HomeSectionEntry("anilist", "AniList", "planned", "Planned"),
        HomeSectionEntry("anilist", "AniList", "missed", "Missed Sequels"),
        HomeSectionEntry("anilist", "AniList", "recommended", "Recommended"),
    )

    fun loadAnime(): MutableList<HomeSectionEntry> {
        val base = animeEntries()
        val stored = load(HomeSectionsMode.ANIME)
        if (stored.isEmpty()) {
            val legacyOrder = PrefManager.getVal<List<Int>>(PrefName.HomeLayoutOrder)
            val legacyShow = PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout)
            val seeded = base.mapIndexed { index, entry ->
                index to entry.copy(enabled = legacyShow.getOrElse(index) { true })
            }.sortedBy { (index, _) ->
                legacyOrder.indexOf(index).takeIf { it >= 0 } ?: (legacyOrder.size + index)
            }.map { it.second }.toMutableList()
            save(HomeSectionsMode.ANIME, seeded)
            return seeded
        }
        val merged = merge(stored, base, setOf("anilist"))
        save(HomeSectionsMode.ANIME, merged)
        return merged
    }

    suspend fun enumerate(mode: HomeSectionsMode, context: Context): HomeSectionsEnumeration =
        when (mode) {
            HomeSectionsMode.ANIME -> HomeSectionsEnumeration(animeEntries(), setOf("anilist"))
            HomeSectionsMode.MOVIE_TV -> enumerateMovieTv(context.applicationContext)
        }

    fun merge(
        stored: List<HomeSectionEntry>,
        fresh: List<HomeSectionEntry>,
        validProviderIds: Set<String>,
    ): MutableList<HomeSectionEntry> {
        val freshByKey = fresh.associateBy { it.providerId to it.sectionKey }
        val result = mutableListOf<HomeSectionEntry>()
        stored.forEach { old ->
            if (old.providerId !in validProviderIds) return@forEach
            val updated = freshByKey[old.providerId to old.sectionKey]
            result.add(
                old.copy(
                    providerName = updated?.providerName ?: old.providerName,
                    title = updated?.title ?: old.title,
                )
            )
        }
        fresh.forEach { entry ->
            if (result.none { it.providerId == entry.providerId && it.sectionKey == entry.sectionKey }) {
                result.add(entry)
            }
        }
        return result
    }

    private fun simklEntries(): List<HomeSectionEntry> = listOf(
        HomeSectionEntry("simkl", "Simkl", "continue", "Continue Watching"),
        HomeSectionEntry("simkl", "Simkl", "favorites", "Favourites"),
        HomeSectionEntry("simkl", "Simkl", "planned", "Planned"),
        HomeSectionEntry("simkl", "Simkl", "recommended", "Recommended"),
    )

    private suspend fun enumerateMovieTv(context: Context): HomeSectionsEnumeration {
        val valid = mutableSetOf("simkl")
        val sections = simklEntries().toMutableList()

        val installed = safeCall { withContext(Dispatchers.IO) { CsRepos.installed(context) } }.orEmpty()
        installed.forEach { source ->
            valid.add("cs:${source.id}")
            val apis = safeCall { withContext(Dispatchers.IO) { CsRuntime.apisFor(context, source) } }.orEmpty()
            apis.forEachIndexed { index, api ->
                val found = mutableListOf<Pair<String, String>>()
                val pages = api.mainPage.filter { it.data.isNotBlank() }
                if (pages.isNotEmpty()) {
                    pages.forEach { page ->
                        val response = safeCall {
                            withContext(Dispatchers.IO) {
                                api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
                            }
                        }
                        response?.items?.forEach { list ->
                            if (list.list.isNotEmpty() && !list.name.contains("Continue Watching", true)) {
                                found.add("$index:${list.name}" to list.name)
                            }
                        }
                    }
                } else {
                    val response = safeCall {
                        withContext(Dispatchers.IO) {
                            api.getMainPage(1, MainPageRequest("Home", "", false))
                        }
                    }
                    response?.items?.forEach { list ->
                        if (list.list.isNotEmpty() && !list.name.contains("Continue Watching", true)) {
                            found.add("$index:${list.name}" to list.name)
                        }
                    }
                }
                if (found.isEmpty()) {
                    val quick = safeCall { withContext(Dispatchers.IO) { api.quickSearch("") } }
                    if (!quick.isNullOrEmpty()) found.add("$index:Popular" to "Popular")
                }
                found.distinctBy { it.first }.forEach { (key, title) ->
                    sections.add(HomeSectionEntry("cs:${source.id}", source.name, key, title))
                }
            }
        }

        val addons = safeCall { withContext(Dispatchers.IO) { StremioAddonManager.homeSources() } }.orEmpty()
        addons.forEach { addon ->
            valid.add("stremio:${addon.transportUrl}")
            val shelves = safeCall {
                withContext(Dispatchers.IO) { StremioAddonClient.loadHomeCatalogShelves(addon) }
            }.orEmpty()
            shelves.forEach { shelf ->
                val key = shelf.name.trim().lowercase()
                sections.add(
                    HomeSectionEntry(
                        providerId = "stremio:${addon.transportUrl}",
                        providerName = addon.manifest.name,
                        sectionKey = key,
                        title = shelf.name,
                    )
                )
            }
        }

        return HomeSectionsEnumeration(sections.distinctBy { it.providerId to it.sectionKey }, valid)
    }

    private suspend fun <T> safeCall(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun syncAnimeLegacy(entries: List<HomeSectionEntry>) {
        val visibility = MutableList(animeSectionKeys.size) { false }
        val order = mutableListOf<Int>()
        entries.forEach { entry ->
            val index = animeSectionKeys.indexOf(entry.sectionKey)
            if (index >= 0) {
                visibility[index] = entry.enabled
                order.add(index)
            }
        }
        animeSectionKeys.indices.forEach { index ->
            if (index !in order) order.add(index)
        }
        PrefManager.setVal(PrefName.HomeLayoutOrder, order.distinct())
        PrefManager.setVal(PrefName.HomeLayout, visibility)
    }
}
