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
    const val INFO = "bridgeInfo"
    const val STREAM = "bridgeStream"
    const val LABEL = "bridgeLabel"
    const val HOST = "bridgeHost"
    const val EXTERNAL = "bridgeExternalUrl"
    const val DETAILS = "bridgeDetails"

    private val DUB_RX = Regex("\\b(dub|dubbed|dublado|dublada)\\b", RegexOption.IGNORE_CASE)
    private val SUB_RX = Regex("\\b(sub|subbed|subtitled|legendado|legendada)\\b", RegexOption.IGNORE_CASE)

    fun fromPlayableLinks(links: List<TmdbStreamResolver.StreamResult.PlayableLink>): List<VideoServer> {
        val usedNames = HashSet<String>()
        val infos = links.mapNotNull { link ->
            if (link.url.isNotBlank()) return@mapNotNull null
            if (link.externalUrl.isNullOrBlank() && link.details.isNullOrBlank()) return@mapNotNull null
            val label = link.label.ifBlank { link.host.ifBlank { "Info" } }
            val data = buildMap {
                put(INFO, "1")
                put(LABEL, label)
                link.host.takeIf { it.isNotBlank() }?.let { put(HOST, it) }
                link.details?.takeIf { it.isNotBlank() }?.let { put(DETAILS, it) }
                link.rawDetails?.takeIf { it.isNotBlank() }?.let { put("rawDetails", it) }
                link.externalUrl?.takeIf { it.isNotBlank() }?.let { put(EXTERNAL, it) }
            }
            VideoServer(
                name = uniqueName(label, usedNames),
                embed = FileUrl(link.externalUrl.orEmpty()),
                extraData = data,
            )
        }

        val streams = links.mapNotNull { link ->
            if (link.url.isBlank()) return@mapNotNull null
            val extractor = TmdbStreamResolver.buildExtractors(listOf(link)).firstOrNull()
                ?: return@mapNotNull null
            val videos = extractor.videos.filter { it.file.url.isNotBlank() }
            if (videos.isEmpty()) return@mapNotNull null
            val label = link.label.ifBlank { link.host.ifBlank { "Stream" } }
            val data = buildMap {
                put(PRELOADED, "1")
                put(STREAM, "1")
                put(LABEL, label)
                put(SERVER_NAME, label)
                link.host.takeIf { it.isNotBlank() }?.let { put(HOST, it) }
                link.details?.takeIf { it.isNotBlank() }?.let { put(DETAILS, it) }
                encodeAnimeSubtitles(extractor.subtitles)?.let { put("subtitles", it) }
                encodeAnimeTracks(extractor.audioTracks)?.let { put("audioTracks", it) }
            }.toMutableMap()
            VideoServer(
                name = uniqueName(label, usedNames),
                embed = FileUrl(""),
                extraData = data,
                preloadedVideos = videos,
            )
        }

        return infos + streams
    }

    fun filterByAudio(
        links: List<TmdbStreamResolver.StreamResult.PlayableLink>,
        preferDub: Boolean,
    ): List<TmdbStreamResolver.StreamResult.PlayableLink> {
        if (links.isEmpty()) return links
        val tagged = links.map { it to audioTag(it) }
        val desired = tagged.filter { it.second == preferDub }
        if (desired.isEmpty()) return links
        return tagged.filter { it.second == null || it.second == preferDub }.map { it.first }
    }

    private fun audioTag(link: TmdbStreamResolver.StreamResult.PlayableLink): Boolean? {
        if (link.url.isBlank()) return null
        val hay = listOfNotNull(
            link.label,
            link.host,
            link.details,
            link.rawDetails,
            link.metadata.entries.joinToString(" ") { "${it.key} ${it.value}" },
        ).joinToString(" ")
        val dub = DUB_RX.containsMatchIn(hay)
        val sub = SUB_RX.containsMatchIn(hay)
        return when {
            dub && !sub -> true
            sub && !dub -> false
            else -> null
        }
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        val base = name.ifBlank { "Server" }
        if (used.add(base.lowercase())) return base
        var index = 2
        while (!used.add("$base $index".lowercase())) index++
        return "$base $index"
    }

    fun extractor(server: VideoServer): VideoExtractor = PreloadedVideoExtractor(server)

    fun isPreloaded(server: VideoServer): Boolean =
        server.extraData?.get(PRELOADED) == "1"

    fun isInfo(server: VideoServer): Boolean =
        server.extraData?.get(INFO) == "1"

    fun isStream(server: VideoServer): Boolean =
        server.extraData?.get(STREAM) == "1"

    fun displayName(server: VideoServer): String? =
        server.extraData?.get(SERVER_NAME)?.takeIf { it.isNotBlank() }

    fun label(server: VideoServer): String? =
        server.extraData?.get(LABEL)?.takeIf { it.isNotBlank() }

    fun streamHost(server: VideoServer): String? =
        server.extraData?.get(HOST)?.takeIf { it.isNotBlank() }

    fun details(server: VideoServer): String? =
        server.extraData?.get(DETAILS)?.takeIf { it.isNotBlank() }
            ?: server.extraData?.get("details")?.takeIf { it.isNotBlank() }

    fun externalUrl(server: VideoServer): String? =
        server.extraData?.get(EXTERNAL)?.takeIf { it.isNotBlank() }

    fun infoLink(server: VideoServer): TmdbStreamResolver.StreamResult.PlayableLink? {
        if (!isInfo(server)) return null
        val label = label(server) ?: server.name
        return TmdbStreamResolver.StreamResult.PlayableLink(
            label = label,
            url = "",
            host = streamHost(server) ?: "External",
            externalUrl = externalUrl(server),
            details = details(server),
        )
    }
}

abstract class TmdbBridgedAnimeParser : AnimeParser() {
    override val hostUrl: String = "https://www.themoviedb.org/"
    override val language: String = "English"

    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    override fun isDisplayOnlyServer(server: VideoServer): Boolean =
        AnimeBridgeServerMapper.isInfo(server)

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

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor? =
        if (AnimeBridgeServerMapper.isInfo(server)) null
        else AnimeBridgeServerMapper.extractor(server)

    private fun TmdbStreamResolver.StreamResult.toVideoServers(): List<VideoServer> =
        when (this) {
            is TmdbStreamResolver.StreamResult.Success ->
                AnimeBridgeServerMapper.fromPlayableLinks(
                    AnimeBridgeServerMapper.filterByAudio(links, selectDub)
                )
            is TmdbStreamResolver.StreamResult.Error -> emptyList()
        }
}

class BridgedNuvioAnimeParser(
    private val context: Context,
    private val scraper: NuvioScraper,
) : TmdbBridgedAnimeParser() {
    override val name: String = "${scraper.name} (nv)"
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
    override val name: String = "${addon.manifest.name} (st)"
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
