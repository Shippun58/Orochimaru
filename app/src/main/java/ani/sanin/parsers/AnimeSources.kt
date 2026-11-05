package ani.sanin.parsers

import ani.sanin.Lazier
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

object AnimeSources : WatchSources() {
    override var list: List<Lazier<BaseParser>> = emptyList()
    var pinnedAnimeSources: List<String> = emptyList()
    var providersOrder: List<String> = emptyList()
    var isInitialized = false

    val allNativeParsers by lazy {
        listOf(
            ReanimeProvider(),
            GojoLiveProvider(),
            AniWavesProvider(),
            AnimeGGProvider(),
            AnimeJLProvider(),
            LatanimeProvider(),
            AnimeAV1Provider(),
            MKissaProvider(),
        )
    }

    private val englishProviderNames = setOf("animegg", "AniWaves", "Reanime", "GojoLive", "MKissa")

    fun isEnglishProvider(saveName: String): Boolean = saveName in englishProviderNames

    val orderedNativeParsers: List<NativeAnimeParser>
        get() = allNativeParsers.sortedBy { parser ->
            providersOrder.indexOf(parser.saveName).let { if (it < 0) Int.MAX_VALUE else it }
        }

    val nativeParsers: List<NativeAnimeParser> get() {
        val enabled = PrefManager.getVal<Set<String>>(PrefName.EnabledProviders)
        return orderedNativeParsers.filter { it.saveName in enabled }
    }

    fun reorderProviders(names: List<String>) {
        PrefManager.setVal(PrefName.ProvidersOrder, names)
        providersOrder = names
        rebuildNativeParsers()
    }

    override val nativeNames: List<String> get() = nativeParsers.map { it.name }

    override val displayNames: List<String> get() {
        val all = list
        val extNames = all.filter { it.name !in nativeNames && it.name != "Local" }
            .map { it.name }
        return buildList {
            if (nativeNames.isNotEmpty()) add("─── Built-in ───")
            addAll(nativeNames)
            if (extNames.isNotEmpty()) add("─── Extensions ───")
            addAll(extNames)
        }
    }

    suspend fun init(fromExtensions: StateFlow<List<AnimeExtension.Installed>>) {
        pinnedAnimeSources =
            PrefManager.getNullableVal<List<String>>(PrefName.AnimeSourcesOrder, null)
                ?: emptyList()
        providersOrder =
            PrefManager.getNullableVal<List<String>>(PrefName.ProvidersOrder, null)
                ?: emptyList()

        val initialExtensions = fromExtensions.first()
        rebuildList(initialExtensions)
        isInitialized = true

        fromExtensions.collect { extensions ->
            rebuildList(extensions)
        }
    }

    private fun rebuildList(extensions: List<AnimeExtension.Installed>) {
        val extParsers = createParsersFromExtensions(extensions)
        list = buildList {
            nativeParsers.forEach { add(Lazier({ it }, it.name)) }
            addAll(extParsers)
            add(Lazier({ LocalAnimeParser() }, "Local"))
        }
    }

    fun rebuildNativeParsers() {
        val extParsers = list.filter { it.name !in nativeNames && it.name != "Local" }
        list = buildList {
            nativeParsers.forEach { add(Lazier({ it }, it.name)) }
            addAll(extParsers)
            add(Lazier({ LocalAnimeParser() }, "Local"))
        }
    }

    fun performReorderAnimeSources() {
        val extParsers = list.filter { it.name !in nativeNames && it.name != "Local" }
        val sortedExt = sortPinnedAnimeSources(extParsers, pinnedAnimeSources)
        list = buildList {
            nativeParsers.forEach { add(Lazier({ it }, it.name)) }
            addAll(sortedExt)
            add(Lazier({ LocalAnimeParser() }, "Local"))
        }
    }

    private fun createParsersFromExtensions(extensions: List<AnimeExtension.Installed>): List<Lazier<BaseParser>> {
        return extensions.map { extension ->
            Lazier({ DynamicAnimeParser(extension) }, extension.name)
        }
    }

    private fun sortPinnedAnimeSources(
        sources: List<Lazier<BaseParser>>,
        pinnedAnimeSources: List<String>
    ): List<Lazier<BaseParser>> {
        val pinnedSourcesMap = sources.filter { pinnedAnimeSources.contains(it.name) }
            .associateBy { it.name }
        val orderedPinnedSources = pinnedAnimeSources.mapNotNull { name ->
            pinnedSourcesMap[name]
        }
        val unpinnedSources = sources.filterNot { pinnedAnimeSources.contains(it.name) }
        return orderedPinnedSources + unpinnedSources
    }
}

object HAnimeSources : WatchSources() {
    override val list: List<Lazier<BaseParser>> get() = AnimeSources.list
    override val nativeNames: List<String> get() = AnimeSources.nativeNames
}
