package ani.sanin.bridge

import android.content.Context
import ani.sanin.FileUrl
import ani.sanin.cloudstream.TmdbStreamResolver
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.parsers.AnimeParser
import ani.sanin.parsers.Episode
import ani.sanin.parsers.PreloadedVideoExtractor
import ani.sanin.parsers.ShowResponse
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.parsers.encodeAnimeSubtitles
import ani.sanin.parsers.encodeAnimeTracks
import ani.sanin.plugins.nuvio.NuvioScraper
import ani.sanin.settings.StremioAddon
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode

private const val TMDB_LINK_PREFIX = "tmdb:"

object AnimeBridgeServerMapper {
    const val PRELOADED = "bridgePreloaded"
    const val SERVER_NAME = "bridgeServerName"

    fun fromPlayableLinks(links: List<TmdbStreamResolver.StreamResult.PlayableLink>): List<VideoServer> =
        TmdbStreamResolver.buildExtractors(links).mapNotNull { extractor ->
            val videos = extractor.videos.filter { it.file.url.isNotBlank() }
            if (videos.isEmpty()) return@mapNotNull null
            val data = extractor.server.extraData?.toMutableMap() ?: mutableMapOf()
            data[PRELOADED] = "1"
            data[SERVER_NAME] = extractor.server.name
            encodeAnimeSubtitles(extractor.subtitles)?.let { data["subtitles"] = it }
            encodeAnimeTracks(extractor.audioTracks)?.let { data["audioTracks"] = it }
            VideoServer(
                name = extractor.server.name,
                embed = extractor.server.embed,
                extraData = data,
                preloadedVideos = videos,
            )
        }

    fun extractor(server: VideoServer): VideoExtractor = PreloadedVideoExtractor(server)

    fun isPreloaded(server: VideoServer): Boolean =
        server.extraData?.get(PRELOADED) == "1"

    fun displayName(server: VideoServer): String? =
        server.extraData?.get(SERVER_NAME)?.takeIf { it.isNotBlank() }

    fun details(server: VideoServer): String? =
        server.extraData?.get("details")?.takeIf { it.isNotBlank() }
}

abstract class TmdbBridgedAnimeParser : AnimeParser() {
    override val hostUrl: String = "https://www.themoviedb.org/"
    override val language: String = "English"

    protected abstract suspend fun resolveStreams(
        tmdbId: Int,
        season: Int,
        episode: Int,
    ): TmdbStreamResolver.StreamResult

    override suspend fun search(query: String): List<ShowResponse> {
        return Tmdb.search(query)
            .filter { it.type == "tv" }
            .distinctBy { it.id }
            .map { media ->
                ShowResponse(
                    name = media.displayTitle,
                    link = "$TMDB_LINK_PREFIX${media.id}",
                    coverUrl = FileUrl(Tmdb.imageUrl(media.posterPath, 500).orEmpty()),
                    total = null,
                    extra = mutableMapOf("tmdb_id" to media.id.toString()),
                )
            }
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime,
    ): List<Episode> {
        val tmdbId = animeLink.removePrefix(TMDB_LINK_PREFIX).substringBefore(':').toIntOrNull()
            ?: return emptyList()
        val detail = Tmdb.detail("tv", tmdbId) ?: return emptyList()
        val seasons = detail.seasons
            .filter { it.seasonNumber > 0 }
            .sortedBy { it.seasonNumber }
        if (seasons.isEmpty()) return emptyList()

        var absoluteEpisode = 0
        return buildList {
            for (season in seasons) {
                val episodes = Tmdb.episodes("tv", tmdbId, season.seasonNumber)
                for (episode in episodes.filter { it.episodeNumber > 0 }) {
                    absoluteEpisode += 1
                    add(
                        Episode(
                            number = absoluteEpisode.toString(),
                            link = "$TMDB_LINK_PREFIX$tmdbId:${season.seasonNumber}:${episode.episodeNumber}",
                            title = episode.name ?: "Episode ${episode.episodeNumber}",
                            thumbnail = Tmdb.imageUrl(episode.stillPath, 780).orEmpty(),
                            description = episode.overview,
                            extra = mapOf(
                                "season" to season.seasonNumber.toString(),
                                "episode" to episode.episodeNumber.toString(),
                            ),
                        )
                    )
                }
            }
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode,
    ): List<VideoServer> {
        val parts = episodeLink.removePrefix(TMDB_LINK_PREFIX).split(':')
        if (parts.size < 3) return emptyList()
        val tmdbId = parts[0].toIntOrNull() ?: return emptyList()
        val season = parts[1].toIntOrNull() ?: return emptyList()
        val episode = parts[2].toIntOrNull() ?: return emptyList()
        return resolveStreams(tmdbId, season, episode).toVideoServers()
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor =
        AnimeBridgeServerMapper.extractor(server)

    private fun TmdbStreamResolver.StreamResult.toVideoServers(): List<VideoServer> =
        when (this) {
            is TmdbStreamResolver.StreamResult.Success -> AnimeBridgeServerMapper.fromPlayableLinks(links)
            is TmdbStreamResolver.StreamResult.Error -> emptyList()
        }
}

class BridgedNuvioAnimeParser(
    private val context: Context,
    private val scraper: NuvioScraper,
) : TmdbBridgedAnimeParser() {
    override val name: String = "${scraper.name} (Nuvio)"
    override val saveName: String = "bridge_nuvio_${stableKey(scraper.repositoryUrl + scraper.id)}"
    override val iconUrl: String? = scraper.logo

    override suspend fun resolveStreams(
        tmdbId: Int,
        season: Int,
        episode: Int,
    ): TmdbStreamResolver.StreamResult =
        TmdbStreamResolver.resolveNuvio(context, scraper, "tv", tmdbId, season, episode)
}

class BridgedStremioAnimeParser(
    private val addon: StremioAddon,
) : TmdbBridgedAnimeParser() {
    override val name: String = "${addon.manifest.name} (Stremio)"
    override val saveName: String = "bridge_stremio_${stableKey(addon.transportUrl)}"
    override val iconUrl: String? = addon.manifest.logo

    override suspend fun resolveStreams(
        tmdbId: Int,
        season: Int,
        episode: Int,
    ): TmdbStreamResolver.StreamResult {
        val detail = Tmdb.detail("tv", tmdbId)
            ?: return TmdbStreamResolver.StreamResult.Error("TMDB details not found")
        return TmdbStreamResolver.resolveStremio(
            addon = addon,
            mediaType = "tv",
            mediaId = tmdbId,
            detail = detail,
            season = season,
            episodeNumber = episode,
        )
    }
}

private fun stableKey(value: String): String = Integer.toHexString(value.hashCode())
