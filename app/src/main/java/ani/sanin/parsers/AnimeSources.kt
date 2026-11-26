package ani.sanin.parsers

import ani.sanin.Lazier
import ani.sanin.bridge.AnimeBridgeStore
import ani.sanin.bridge.BridgedNuvioAnimeParser
import ani.sanin.bridge.BridgedStremioAnimeParser
import ani.sanin.cloudstream.CsAnimeParser
import ani.sanin.cloudstream.CsRepos
import ani.sanin.cloudstream.CsRuntime
import ani.sanin.currContext
import ani.sanin.plugins.nuvio.NuvioRepository
import ani.sanin.plugins.nuvio.NuvioSources
import ani.sanin.settings.StremioAddonManager
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
    private var pluginNames: Set<String> = emptySet()
    private var bridgeNames: Set<String> = emptySet()
    private var pluginEnglishSaveNames: Set<String> = emptySet()
    private var lastExtensions: List<AnimeExtension.Installed> = emptyList()

    val allNativeParsers by lazy {
        listOf(
            ReanimeProvider(),
            GojoLiveProvider(),
            AniWavesProvider(),
            AniZoneProvider(),
            ANVProvider(),
            Anime123Provider(),
            AnimeGGProvider(),
            AnimeJLProvider(),
            LatanimeProvider(),
            AnimeAV1Provider(),
            AniPmProvider(),
            MKissaProvider(),
        )
    }

    private val englishProviderNames = setOf(
        "animegg", "AniWaves", "Reanime", "GojoLive", "MKissa",
        "AniZone", "ANV", "123Anime", "AniPM"
    )

    fun isEnglishProvider(saveName: String): Boolean =
        saveName in englishProviderNames || saveName in pluginEnglishSaveNames

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
        val currentPluginNames = all.filter { it.name in pluginNames }.map { it.name }
        val currentBridgeNames = all.filter { it.name in bridgeNames }.map { it.name }
        val extNames = all.filter {
            it.name !in nativeNames && it.name !in pluginNames &&
                it.name !in bridgeNames && it.name != "Local"
        }.map { it.name }
        return buildList {
            if (nativeNames.isNotEmpty()) add("─── Built-in ───")
            addAll(nativeNames)
            if (currentPluginNames.isNotEmpty()) add("─── Plugins ───")
            addAll(currentPluginNames)
            if (currentBridgeNames.isNotEmpty()) add("─── Bridged ───")
            addAll(currentBridgeNames)
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
        lastExtensions = extensions
        val extParsers = createParsersFromExtensions(extensions)
        val (csParsers, csLaziers) = createParsersFromCsRepos()
        val (bridgeParsers, bridgeLaziers) = createParsersFromBridgedProviders()
        pluginNames = csLaziers.map { it.name }.toSet()
        bridgeNames = bridgeLaziers.map { it.name }.toSet()
        pluginEnglishSaveNames = (
            csParsers.filter { it.language == "English" }.map { it.saveName } +
                bridgeParsers.filter { it.language == "English" }.map { it.saveName }
            ).toSet()
        list = buildList {
            nativeParsers.forEach { add(Lazier({ it }, it.name)) }
            addAll(csLaziers)
            addAll(bridgeLaziers)
            addAll(extParsers)
            add(Lazier({ LocalAnimeParser() }, "Local"))
        }
    }

    fun rebuildNativeParsers() {
        val pluginParsers = list.filter { it.name in pluginNames }
        val bridgeParsers = list.filter { it.name in bridgeNames }
        val extParsers = list.filter {
            it.name !in nativeNames && it.name !in pluginNames &&
                it.name !in bridgeNames && it.name != "Local"
        }
        list = buildList {
            nativeParsers.forEach { add(Lazier({ it }, it.name)) }
            addAll(pluginParsers)
            addAll(bridgeParsers)
            addAll(extParsers)
            add(Lazier({ LocalAnimeParser() }, "Local"))
        }
    }

    fun refreshPluginSources() {
        if (!isInitialized) return
        rebuildList(lastExtensions)
    }

    fun performReorderAnimeSources() {
        val pluginParsers = list.filter { it.name in pluginNames }
        val bridgeParsers = list.filter { it.name in bridgeNames }
        val extParsers = list.filter {
            it.name !in nativeNames && it.name !in pluginNames &&
                it.name !in bridgeNames && it.name != "Local"
        }
        val sortedExt = sortPinnedAnimeSources(extParsers, pinnedAnimeSources)
        list = buildList {
            nativeParsers.forEach { add(Lazier({ it }, it.name)) }
            addAll(pluginParsers)
            addAll(bridgeParsers)
            addAll(sortedExt)
            add(Lazier({ LocalAnimeParser() }, "Local"))
        }
    }

    private fun createParsersFromCsRepos(): Pair<List<CsAnimeParser>, List<Lazier<BaseParser>>> {
        val context = currContext() ?: return emptyList<CsAnimeParser>() to emptyList()
        val parsers = CsRepos.installed(context)
            .filter { AnimeBridgeStore.isBridged(AnimeBridgeStore.CLOUDSTREAM, it.id) }
            .flatMap { source ->
                CsRuntime.apisFor(context, source).mapIndexed { index, api ->
                    CsAnimeParser(context, source, api, index)
                }
            }
        return parsers to parsers.map { parser -> Lazier({ parser }, parser.name) }
    }

    private fun createParsersFromBridgedProviders(): Pair<List<BaseParser>, List<Lazier<BaseParser>>> {
        val context = currContext() ?: return emptyList<BaseParser>() to emptyList()
        val nuvioParsers = NuvioRepository.installed(context)
            .filter {
                it.enabled && AnimeBridgeStore.isBridged(
                    AnimeBridgeStore.NUVIO,
                    NuvioSources.sourceId(it),
                )
            }
            .map { scraper -> BridgedNuvioAnimeParser(context, scraper) }
        val stremioParsers = StremioAddonManager.installed()
            .filter { AnimeBridgeStore.isBridged(AnimeBridgeStore.STREMIO, it.transportUrl) }
            .map { addon -> BridgedStremioAnimeParser(addon) }
        val parsers = nuvioParsers + stremioParsers
        return parsers to parsers.map { parser -> Lazier({ parser }, parser.name) }
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
