package ani.sanin.bridge

import android.content.Context
import ani.sanin.FileUrl
import ani.sanin.cloudstream.TmdbStreamResolver
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.parsers.AnimeParser
import ani.sanin.parsers.Episode
import ani.sanin.parsers.NativeVideoExtractor
import ani.sanin.parsers.ShowResponse
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.plugins.nuvio.NuvioScraper
import ani.sanin.settings.StremioAddon
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode

private const val TMDB_LINK_PREFIX = "tmdb:"

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
        NativeVideoExtractor(server)

    private fun TmdbStreamResolver.StreamResult.toVideoServers(): List<VideoServer> =
        when (this) {
            is TmdbStreamResolver.StreamResult.Success -> links.mapIndexedNotNull { index, link ->
                val url = link.url.trim()
                if (url.isBlank()) return@mapIndexedNotNull null
                val name = link.label.ifBlank { "Server ${index + 1}" }
                val headers = link.headers.toMutableMap().apply {
                    link.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                }
                val data = buildMap {
                    put("quality", name)
                    when {
                        url.contains(".m3u8", ignoreCase = true) -> put("videoType", "m3u8")
                        url.contains(".mpd", ignoreCase = true) -> put("videoType", "dash")
                    }
                }
                VideoServer(name, FileUrl(url, headers), data)
            }

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
