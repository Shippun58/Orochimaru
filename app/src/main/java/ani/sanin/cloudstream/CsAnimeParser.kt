package ani.sanin.cloudstream

import android.content.Context
import ani.sanin.FileUrl
import ani.sanin.bridge.AnimeBridgeServerMapper
import ani.sanin.parsers.AnimeParser
import ani.sanin.parsers.Episode as AnimeEpisode
import ani.sanin.parsers.ShowResponse
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.tryWithSuspend
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.withTimeoutOrNull

@Suppress("UNUSED_PARAMETER")
class CsAnimeParser(
    private val context: Context,
    private val source: CsInstalledSource,
    val api: MainAPI,
    private val apiIndex: Int,
) : AnimeParser() {

    val sourceId: String get() = source.id

    override val name: String = if (api.name.isBlank() || api.name == source.name) {
        source.name
    } else {
        "${source.name} · ${api.name}"
    }

    override val saveName: String = "cs3_${CsRuntime.sourceKey(source.id, apiIndex)}"
    override val hostUrl: String = api.mainUrl.takeIf { it.isNotBlank() && it != "NONE" } ?: source.url
    override val language: String = if (source.lang.equals("en", ignoreCase = true)) "English" else source.lang
    override val iconUrl: String? = source.iconUrl

    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    override fun isDisplayOnlyServer(server: VideoServer): Boolean =
        AnimeBridgeServerMapper.isInfo(server)

    override suspend fun search(query: String): List<ShowResponse> {
        val paged = tryWithSuspend(true) { api.search(query, 1)?.items }
        val results = if (!paged.isNullOrEmpty()) {
            paged
        } else {
            tryWithSuspend(true) { api.search(query) }.orEmpty()
        }

        return results.mapNotNull { result ->
            val decoded = CsAnimeProtocol.decodeLink(result.url)
            ShowResponse(
                name = result.name,
                link = decoded.first,
                coverUrl = FileUrl(result.posterUrl.orEmpty(), result.posterHeaders.orEmpty()),
                otherNames = emptyList(),
                total = result.id,
                extra = decoded.second?.toMutableMap()
            )
        }
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: eu.kanade.tachiyomi.animesource.model.SAnime
    ): List<AnimeEpisode> {
        val decoded = CsAnimeProtocol.decodeLink(animeLink)
        val link = CsAnimeProtocol.encodeLink(decoded.first, extra ?: decoded.second)
        val response = tryWithSuspend(true) { api.load(link) } ?: return emptyList()
        val episodes = when (response) {
            is TvSeriesLoadResponse -> response.episodes
            is AnimeLoadResponse -> {
                val preferred = if (selectDub) DubStatus.Dubbed else DubStatus.Subbed
                val preferredEpisodes = response.episodes[preferred].orEmpty()
                if (preferredEpisodes.isNotEmpty()) {
                    preferredEpisodes
                } else {
                    response.episodes.values.flatten()
                }
            }
            else -> return emptyList()
        }

        return episodes.map { episode ->
            val episodeData = CsAnimeProtocol.decodeLink(episode.data)
            AnimeEpisode(
                number = (episode.episode ?: episode.season ?: 0).toString(),
                link = episodeData.first,
                title = episode.name,
                thumbnail = episode.posterUrl.orEmpty(),
                description = episode.description,
                extra = episodeData.second
            )
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: eu.kanade.tachiyomi.animesource.model.SEpisode
    ): List<VideoServer> {
        val decoded = CsAnimeProtocol.decodeLink(episodeLink)
        val link = CsAnimeProtocol.encodeLink(decoded.first, extra ?: decoded.second)
        val subtitles = mutableListOf<SubtitleFile>()
        val links = mutableListOf<ExtractorLink>()

        withTimeoutOrNull(api.loadLinksTimeoutMs ?: 45_000L) {
            runCatching {
                api.loadLinks(
                    link,
                    false,
                    { subtitle -> subtitles.add(subtitle) },
                    { extractorLink -> links.add(extractorLink) }
                )
            }
        }

        val subtitleData = CsAnimeProtocol.encodeSubtitles(subtitles.map { it.url to it.lang })
        val playable = TmdbStreamResolver.extractorLinksToPlayableLinks(links.filter { link ->
            link.type != ExtractorLinkType.TORRENT && link.type != ExtractorLinkType.MAGNET
        })
        val filtered = AnimeBridgeServerMapper.filterByAudio(playable, selectDub)
        return AnimeBridgeServerMapper.fromPlayableLinks(filtered).map { server ->
            val data = server.extraData?.toMutableMap() ?: mutableMapOf()
            if (subtitleData != null && !data.containsKey("subtitles")) {
                data["subtitles"] = subtitleData
            }
            server.copy(extraData = data)
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor? =
        if (AnimeBridgeServerMapper.isInfo(server)) null
        else AnimeBridgeServerMapper.extractor(server)
}
