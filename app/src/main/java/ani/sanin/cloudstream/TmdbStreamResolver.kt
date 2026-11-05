package ani.sanin.cloudstream

import android.content.Context
import android.content.Intent
import android.util.Log
import ani.sanin.FileUrl
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.connections.tmdb.TmdbEpisode
import ani.sanin.media.Media
import ani.sanin.media.Selected
import ani.sanin.media.anime.Anime
import ani.sanin.media.anime.ExoplayerView
import ani.sanin.media.anime.Episode
import ani.sanin.parsers.Video
import eu.kanade.tachiyomi.animesource.model.Track
import ani.sanin.parsers.VideoContainer
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.parsers.DrmInfo
import ani.sanin.parsers.VideoType
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_DRM_UUID
import com.lagradost.cloudstream3.utils.Qualities
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object TmdbStreamResolver {

    sealed class StreamResult {
        data class PlayableLink(
            val label: String,
            val url: String,
            val referer: String? = null,
            val headers: Map<String, String> = emptyMap(),
            val drm: DrmInfo? = null,
            val audioTracks: List<AudioFile> = emptyList(),
            val host: String = "",
        )

        data class Success(val links: List<PlayableLink>, val matchName: String? = null) : StreamResult()
        data class Error(val message: String) : StreamResult()
    }

    fun syntheticId(mediaId: Int): Int = -1000000000 - (mediaId % 1000000)

    suspend fun resolveAuto(
        context: Context,
        sources: List<CsInstalledSource>,
        d: TmdbDetail,
        season: Int?,
        episodeNumber: Int?
    ): Pair<CsInstalledSource?, StreamResult> {
        for (source in sources) {
            val result = resolveStreams(context, source, d, season, episodeNumber)
            if (result is StreamResult.Success) return source to result
        }
        return null to StreamResult.Error("No playable streams found on any source")
    }

    suspend fun resolveStreams(
        context: Context,
        source: CsInstalledSource,
        d: TmdbDetail,
        season: Int?,
        episodeNumber: Int?
    ): StreamResult {
        val apis = CsRuntime.apisFor(context, source)
        if (apis.isEmpty()) {
            val why = CsRuntime.lastError?.let { "\n$it" } ?: ""
            return StreamResult.Error("Failed to load ${source.name} — is it a .cs3 plugin?$why")
        }

        for (api in apis) {
            val cachedLoad = loadResponseCache["${d.id}|${api.name}"]
            if (cachedLoad != null) {
                Logger.log("TMDB_PLAY: ${api.name} using cached LoadResponse for '${d.displayTitle}', skipping search+load")
                Log.i("TmdbDetails", "${api.name}: cached load, calling resolvePluginStreams directly")
                return resolvePluginStreams(context, source, cachedLoad, season, episodeNumber)
            }
        }
        val failures = mutableListOf<String>()
        for (api in apis) {
            Logger.log(
                "TMDB_PLAY: trying provider ${api.name} for '${d.displayTitle}' " +
                    "(season=$season ep=$episodeNumber)"
            )
            Log.i("TmdbDetails", "Trying provider ${api.name} for '${d.displayTitle}' (season=$season ep=$episodeNumber)")
            try {

                val (streams, reason, matchName) = withTimeout(60_000) {
                    resolveFromApi(api, d, season, episodeNumber)
                }
                Logger.log(
                    "TMDB_PLAY: ${api.name} returned ${streams.size} playable links " +
                        "reason=${if (reason.isNotBlank()) reason else "ok"}"
                )
                Log.i("TmdbDetails", "${api.name}: returned ${streams.size} playable links")
                if (streams.isNotEmpty()) return StreamResult.Success(streams, matchName)
                if (reason.isNotBlank()) failures += reason
            } catch (t: TimeoutCancellationException) {
                val detail = "${api.name}: timed out after 60s"
                Logger.log(Log.ERROR, "TMDB_PLAY: provider ${api.name} timed out")
                failures += detail
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                val detail = "${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
                Logger.log(Log.ERROR, "TMDB_PLAY: provider ${api.name} threw $detail")
                Logger.log(t)
                Log.e("TmdbDetails", "Provider ${api.name} failed", t)
                failures += "${api.name}: $detail"
            }
        }
        val why = failures.lastOrNull()?.let { " — $it" } ?: ""
        return StreamResult.Error("No playable streams found on ${source.name}$why")
    }

    suspend fun resolvePluginStreams(
        context: Context,
        source: CsInstalledSource,
        load: LoadResponse,
        season: Int?,
        episodeNumber: Int?
    ): StreamResult {
        val apis = CsRuntime.apisFor(context, source)
        if (apis.isEmpty()) {
            val why = CsRuntime.lastError?.let { "\n$it" } ?: ""
            return StreamResult.Error("Failed to load ${source.name} — is it a .cs3 plugin?$why")
        }
        val api = apis.firstOrNull { it.name == load.apiName } ?: apis.firstOrNull()
            ?: return StreamResult.Error("No provider registered in ${source.name}")
        val dataUrl: String = when (load) {
            is TvSeriesLoadResponse -> {
                val episode = when {
                    episodeNumber != null && season != null ->
                        load.episodes.firstOrNull {
                            it.episode == episodeNumber && (it.season == null || it.season == season)
                        } ?: load.episodes.firstOrNull { it.episode == episodeNumber }
                    episodeNumber != null -> load.episodes.firstOrNull { it.episode == episodeNumber }
                    season != null -> load.episodes.firstOrNull { it.season == null || it.season == season }
                    else -> load.episodes.firstOrNull()
                }
                if (episode == null) {
                    return StreamResult.Error(
                        "${source.name}: no episode found (season=$season ep=$episodeNumber) in load response"
                    )
                }
                episode.data
            }
            is MovieLoadResponse -> load.dataUrl
            is LiveStreamLoadResponse -> load.dataUrl
            else -> load.url
        }
        if (dataUrl.isBlank()) {
            return StreamResult.Error("${source.name}: blank dataUrl after load")
        }
        Logger.log(
            "TMDB_PLAY: ${api.name} plugin-direct loadLinks dataUrl=${dataUrl.take(120)} " +
                "(season=$season ep=$episodeNumber)"
        )
        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()
        val ok = try {
            withTimeout(60_000) {
                api.loadLinks(
                    dataUrl,
                    isCasting = false,
                    subtitleCallback = { subs.add(it) },
                    callback = { links.add(it) }
                )
            }
        } catch (t: TimeoutCancellationException) {
            return StreamResult.Error("${api.name}: timed out after 60s")
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            val detail = "${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
            Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} loadLinks threw $detail")
            Logger.log(t)
            return StreamResult.Error("${api.name}: $detail")
        }
        Logger.log("TMDB_PLAY: ${api.name} loadLinks ok=$ok links=${links.size} (plugin direct)")
        if (!ok || links.isEmpty()) {
            return StreamResult.Error("${api.name}: loadLinks ok=$ok links=${links.size}")
        }
        val seen = HashSet<String>()
        val playable = links.mapNotNull { link ->
            val url = link.url
            if (url.isBlank() || !seen.add(url)) return@mapNotNull null
            val quality = Qualities.getStringByInt(link.quality).ifBlank { link.name }
            val drmInfo = drmForLink(link)
            StreamResult.PlayableLink(
                label = quality,
                url = url,
                referer = link.referer.takeIf { it.isNotBlank() },
                headers = link.headers,
                drm = drmInfo,
                audioTracks = link.audioTracks,
                host = link.name,
            )
        }
        return StreamResult.Success(playable, load.name)
    }

    private data class ApiResolve(
        val links: List<StreamResult.PlayableLink>,
        val reason: String,
        val matchName: String? = null
    )

    private suspend fun resolveFromApi(
        api: MainAPI,
        d: TmdbDetail,
        season: Int?,
        episodeNumber: Int?
    ): ApiResolve {
        val wantMovie = d.displayTitle.isNotBlank() && (d.releaseDate != null || d.title != null)

        var searchError: String? = null
        val results = runCatching { api.search(d.displayTitle, 1)?.items }
            .getOrElse { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                searchError = "search threw ${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
                Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} $searchError")
                Logger.log(t)
                Log.e("TmdbDetails", "${api.name}: search(query,1) failed", t)
                null
            }?.takeIf { it.isNotEmpty() }
            ?: runCatching { api.quickSearch(d.displayTitle) }
                .getOrElse { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    searchError = "quickSearch threw ${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
                    Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} $searchError")
                    Logger.log(t)
                    Log.e("TmdbDetails", "${api.name}: quickSearch failed", t)
                    null
                } ?: emptyList()
        Logger.log(
            "TMDB_PLAY: ${api.name} search '${d.displayTitle}' -> ${results.size} results " +
                (searchError ?: "")
        )
        Log.i("TmdbDetails", "${api.name}: search '${d.displayTitle}' -> ${results.size} results")
        var match = bestSearchMatch(results, d.displayTitle, wantMovie)
        Log.i("TmdbDetails", "${api.name}: best match = ${match?.url ?: "NONE"}")
        if (match == null && results.isEmpty()) {

            val homeItems = homePageItems(api)
            Logger.log("TMDB_PLAY: ${api.name} home fallback -> ${homeItems.size} catalog items")
            Log.i("TmdbDetails", "${api.name}: home fallback -> ${homeItems.size} catalog items")
            match = bestSearchMatch(homeItems, d.displayTitle, wantMovie)
            if (match != null) {
                Logger.log("TMDB_PLAY: ${api.name} matched '${d.displayTitle}' from home page: '${match.name}'")
            }
        }
        if (match == null) {
            return ApiResolve(emptyList(), "${api.name}: no search result matched '${d.displayTitle}' (${results.size} results)")
        }

        val response = try {
            api.load(match.url)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val detail = "${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
            Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} load threw $detail")
            Logger.log(t)
            Log.e("TmdbDetails", "${api.name}: load failed", t)
            return ApiResolve(emptyList(), "${api.name}: load threw $detail")
        }
        Logger.log(
            "TMDB_PLAY: ${api.name} load '${match.url}' -> " +
                (response?.javaClass?.simpleName ?: "null response")
        )
        Log.i("TmdbDetails", "${api.name}: load ${match.url} -> ${response?.javaClass?.simpleName ?: "null"}")
        if (response == null) {
            return ApiResolve(emptyList(), "${api.name}: load returned null for ${match.url}")
        }

        loadResponseCache["${d.id}|${api.name}"] = response
        val dataUrl: String = when {
            response is TvSeriesLoadResponse -> {
                val episode = when {
                    episodeNumber != null && season != null ->
                        response.episodes.firstOrNull {
                            it.episode == episodeNumber && (it.season == null || it.season == season)
                        } ?: response.episodes.firstOrNull { it.episode == episodeNumber }
                    episodeNumber != null ->
                        response.episodes.firstOrNull { it.episode == episodeNumber }
                    season != null ->
                        response.episodes.firstOrNull { it.season == null || it.season == season }
                    else -> response.episodes.firstOrNull()
                }
                if (episode == null) {
                    return ApiResolve(
                        emptyList(),
                        "${api.name}: no episode found (season=$season ep=$episodeNumber) in load response"
                    )
                }
                if (episodeNumber == null) {
                    Logger.log(
                        "TMDB_PLAY: ${api.name} no episode selected — using " +
                            "S${episode.season ?: season}E${episode.episode} data"
                    )
                }
                episode.data
            }
            response is MovieLoadResponse -> response.dataUrl
            response is LiveStreamLoadResponse -> response.dataUrl
            else -> response.url
        }
        Log.i("TmdbDetails", "${api.name}: dataUrl = ${dataUrl.take(160).ifBlank { "<blank>" }}")
        if (dataUrl.isBlank()) {
            return ApiResolve(emptyList(), "${api.name}: blank dataUrl after load")
        }

        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()
        val ok = try {
            api.loadLinks(dataUrl, isCasting = false, subtitleCallback = { subs.add(it) }, callback = { links.add(it) })
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val detail = "${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
            Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} loadLinks threw $detail")
            Logger.log(t)
            Log.e("TmdbDetails", "${api.name}: loadLinks failed", t)
            return ApiResolve(emptyList(), "${api.name}: loadLinks threw $detail")
        }
        Logger.log(
            "TMDB_PLAY: ${api.name} loadLinks ok=$ok links=${links.size} subs=${subs.size} " +
                "dataUrl=${dataUrl.take(120)}"
        )
        Log.i("TmdbDetails", "${api.name}: loadLinks ok=$ok links=${links.size} subs=${subs.size}")
        if (!ok || links.isEmpty()) {
            return ApiResolve(emptyList(), "${api.name}: loadLinks ok=$ok links=${links.size}")
        }

        val seen = HashSet<String>()
        val playable = links.mapNotNull { link ->
            val url = link.url
            if (url.isBlank() || !seen.add(url)) return@mapNotNull null
            val quality = Qualities.getStringByInt(link.quality).ifBlank { link.name }
            val drmInfo = drmForLink(link)
            StreamResult.PlayableLink(
                label = quality,
                url = url,
                referer = link.referer.takeIf { it.isNotBlank() },
                headers = link.headers,
                drm = drmInfo,
                audioTracks = link.audioTracks,
                host = link.name,
            )
        }
        return ApiResolve(playable, "ok", match?.name)
    }

    fun bestSearchMatch(
        results: List<SearchResponse>,
        title: String,
        wantMovie: Boolean
    ): SearchResponse? {
        val exact = results.firstOrNull {
            (!wantMovie || it.type?.isMovieType() == true) && looseMatch(it.name, title)
        }
        if (exact != null) return exact
        return results.firstOrNull { (!wantMovie || it.type?.isMovieType() == true) }
            ?: results.firstOrNull()
    }

    fun looseMatch(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim()
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
    return x.contains(y) || y.contains(x)
    }

    private suspend fun homePageItems(api: MainAPI): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        val pages = api.mainPage.filter { it.data.isNotBlank() }
        if (pages.isEmpty()) {

            val resp = runCatching {
                api.getMainPage(1, MainPageRequest("Home", "", false))
            }.getOrElse { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                null
            }
            resp?.items?.forEach { items.addAll(it.list) }
        } else {
            for (page in pages) {
                val resp = runCatching {
                    api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
                }.getOrElse { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    null
                }
                resp?.items?.forEach { items.addAll(it.list) }
                if (items.isNotEmpty()) break
            }
        }
        return items
    }

    private fun drmForLink(link: ExtractorLink): DrmInfo? {
        val result = when (link) {
            is DrmExtractorLink -> {
                val license = link.licenseUrl?.takeIf { it.isNotBlank() }
                val hasKidKey = !link.kid.isNullOrBlank() && !link.key.isNullOrBlank()
                when {
                    license != null -> {
                        DrmInfo(
                            licenseUrl = license,
                            uuid = link.uuid.toJavaUuid(),
                            keyRequestParameters = link.keyRequestParameters,
                            kid = link.kid,
                            key = link.key,
                            kty = link.kty,
                        )
                    }
                    hasKidKey -> {
                        DrmInfo(
                            uuid = CLEARKEY_DRM_UUID.toJavaUuid(),
                            keyRequestParameters = link.keyRequestParameters,
                            kid = link.kid,
                            key = link.key,
                            kty = link.kty ?: "oct",
                        )
                    }
                    else -> null
                }
            }
            else -> null
        }
        Log.i("TmdbDetails", "drmForLink: result uuid=${result?.uuid} license=${result?.licenseUrl?.take(80) ?: "<none>"}")
        return result
    }

    data class SyntheticSession(
        val mediaId: Int,
        val mediaType: String,

        val detail: TmdbDetail,
        val source: CsInstalledSource,
        val load: LoadResponse? = null,
        val isLive: Boolean = load is LiveStreamLoadResponse
    )

    private val episodeShells = mutableMapOf<String, Map<String, Episode>>()

    private val linksCache = mutableMapOf<String, StreamResult.Success>()

    private val loadResponseCache = mutableMapOf<String, LoadResponse>()

    private val sessions = mutableMapOf<Int, SyntheticSession>()

    private fun cacheKey(mediaId: Int, sourceName: String, season: Int?, ep: Int?) =
        "$mediaId|$sourceName|$season|$ep"

    fun cachedLinks(mediaId: Int, sourceName: String, season: Int?, ep: Int?): StreamResult.Success? =
        linksCache[cacheKey(mediaId, sourceName, season, ep)]

    fun cacheLinks(mediaId: Int, sourceName: String, season: Int?, ep: Int?, result: StreamResult.Success) {
        linksCache[cacheKey(mediaId, sourceName, season, ep)] = result
    }

    fun syntheticSourceName(syntheticId: Int): String? = sessions[syntheticId]?.source?.name

    fun sessionFor(syntheticId: Int): SyntheticSession? = sessions[syntheticId]

    fun invalidateLinks(mediaId: Int) {
        linksCache.keys.removeAll { it.startsWith("$mediaId|") }
        loadResponseCache.keys.removeAll { it.startsWith("$mediaId|") }
    }

    private fun qualityFromLabel(label: String): Int? =
        Regex("\\d{3,4}").find(label)?.value?.toIntOrNull()

    private fun videoTypeFor(url: String): VideoType = when {
        url.contains(".m3u8", ignoreCase = true) -> VideoType.M3U8
        url.contains(".mpd", ignoreCase = true) -> VideoType.DASH
        else -> VideoType.CONTAINER
    }

    private fun audioLanguage(audio: AudioFile): String {
        val hay = audio.url.lowercase()
        val rules = listOf(
            "english" to "English", "eng" to "English",
            "japanese" to "Japanese", "jpn" to "Japanese", "日本語" to "Japanese",
            "hindi" to "Hindi", "hin" to "Hindi",
            "korean" to "Korean", "kor" to "Korean",
            "mandarin" to "Chinese", "cantonese" to "Chinese",
            "chinese" to "Chinese", "zho" to "Chinese", "chi" to "Chinese",
            "spanish" to "Spanish", "spa" to "Spanish",
            "french" to "French", "francais" to "French", "fra" to "French",
            "german" to "German", "deu" to "German",
            "italian" to "Italian", "ita" to "Italian",
            "portuguese" to "Portuguese", "por" to "Portuguese",
            "arabic" to "Arabic", "ara" to "Arabic",
            "russian" to "Russian", "rus" to "Russian",
            "tamil" to "Tamil", "tam" to "Tamil",
            "telugu" to "Telugu", "tel" to "Telugu",
            "malayalam" to "Malayalam", "mal" to "Malayalam",
            "thai" to "Thai", "tha" to "Thai",
            "vietnamese" to "Vietnamese", "vie" to "Vietnamese",
            "indonesian" to "Indonesian", "ind" to "Indonesian",
            "turkish" to "Turkish", "tur" to "Turkish",
            "polish" to "Polish", "pol" to "Polish",
            "ukrainian" to "Ukrainian", "ukr" to "Ukrainian",
            "portugues" to "Portuguese",
        )
        for ((needle, name) in rules) {
            if (Regex("(^|[^a-z0-9])$needle([^a-z0-9]|$)").containsMatchIn(hay)) return name
        }
        return "Unknown"
    }

    fun buildExtractors(links: List<StreamResult.PlayableLink>): List<VideoExtractor> {
        if (!PrefManager.getVal<Boolean>(PrefName.GroupServersByHostAndLanguageTags)) {
            val usedNames = HashSet<String>()
            return links.mapIndexedNotNull { index, link ->
                if (link.url.isBlank()) return@mapIndexedNotNull null
                var name = link.label.ifBlank { "Server ${index + 1}" }
                if (!usedNames.add(name.lowercase())) name = "$name ${usedNames.size + 1}"
                val headers = HashMap(link.headers).apply {
                    link.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                }
                val video = Video(
                    quality = qualityFromLabel(link.label),
                    format = videoTypeFor(link.url),
                    file = FileUrl(link.url, headers),
                    drm = link.drm,
                )
                val audioTracks = link.audioTracks.map { Track(url = it.url, lang = audioLanguage(it)) }
                object : VideoExtractor() {
                    override val server = VideoServer(name, "", mapOf("quality" to name))
                    override suspend fun extract() =
                        VideoContainer(videos = listOf(video), audioTracks = audioTracks)
                }.apply {
                    videos = listOf(video)
                    this.audioTracks = audioTracks
                }
            }
        }

        val usedNames = HashSet<String>()
        return links.filter { it.url.isNotBlank() }
            .groupBy { it.host.ifBlank { it.label }.ifBlank { "Server" } }
            .map { (_, groupLinks) ->
                var name = groupLinks.first().host
                    .ifBlank { groupLinks.first().label }
                    .ifBlank { "Server" }
                if (!usedNames.add(name.lowercase())) name = "$name ${usedNames.size + 1}"
                val videos = groupLinks.mapNotNull { link ->
                    if (link.url.isBlank()) return@mapNotNull null
                    val headers = HashMap(link.headers).apply {
                        link.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                    }
                    Video(
                        quality = qualityFromLabel(link.label),
                        format = videoTypeFor(link.url),
                        file = FileUrl(link.url, headers),
                        drm = link.drm,
                    )
                }
                val audioTracks = groupLinks.flatMap { link ->
                    link.audioTracks.map { Track(url = it.url, lang = audioLanguage(it)) }
                }
                val qualityMeta = groupLinks.mapNotNull { qualityFromLabel(it.label) }
                    .distinct().sortedDescending().joinToString(" · ") { "${it}p" }
                object : VideoExtractor() {
                    override val server = VideoServer(
                        name, "", mapOf("quality" to qualityMeta.ifBlank { name })
                    )
                    override suspend fun extract() =
                        VideoContainer(videos = videos, audioTracks = audioTracks)
                }.apply {
                    this.videos = videos
                    this.audioTracks = audioTracks
                }
            }
    }

    private fun episodeKey(season: Int, ep: Int) = "S${season}E${ep}"

    private fun tmdbEpisode(mediaId: Int, season: Int, ep: TmdbEpisode): Episode = Episode(
        number = episodeKey(season, ep.episodeNumber),
        title = ep.name ?: "Episode ${ep.episodeNumber}",
        desc = ep.overview,
        thumb = Tmdb.imageUrl(ep.stillPath, 780)?.let { FileUrl(it) },
        date = ep.airDate,
        rating = ep.voteAverage.takeIf { it > 0 }
            ?.let { String.format(java.util.Locale.US, "%.1f", it) },
        selectedSubtitle = -1,
        extra = mapOf(
            "season" to season.toString(),
            "episode" to ep.episodeNumber.toString(),
            "tmdbId" to mediaId.toString()
        )
    )

    private suspend fun episodesFor(
        mediaId: Int,
        mediaType: String,
        d: TmdbDetail,
        sourceName: String
    ): Map<String, Episode> {
        val key = "$mediaId|$sourceName"
        episodeShells[key]?.let { return it }
        val map = linkedMapOf<String, Episode>()
        if (mediaType == "tv") {
            for (season in Tmdb.seasons(mediaType, mediaId)) {
                for (ep in Tmdb.episodes(mediaType, mediaId, season.seasonNumber)) {
                    map[episodeKey(season.seasonNumber, ep.episodeNumber)] =
                        tmdbEpisode(mediaId, season.seasonNumber, ep)
                }
            }
        } else {
            map["1"] = Episode(
                number = "1",
                title = d.displayTitle,
                desc = d.overview,
                thumb = Tmdb.imageUrl(d.backdropPath ?: d.posterPath, 780)?.let { FileUrl(it) },
                date = d.releaseDate,
                rating = d.voteAverage.takeIf { it > 0 }
                    ?.let { String.format(java.util.Locale.US, "%.1f", it) },
                selectedSubtitle = -1,
                extra = mapOf("tmdbId" to mediaId.toString())
            )
        }
        episodeShells[key] = map
        Logger.log("TMDB_PLAY: built ${map.size} episode shells for '$mediaType' id=$mediaId source=$sourceName")
        return map
    }

    fun pluginEpisodeShells(
        mediaId: Int,
        seasonEpisodes: Map<Int, List<TmdbEpisode>>
    ): Map<String, Episode> {
        val map = linkedMapOf<String, Episode>()
        seasonEpisodes.forEach { (season, eps) ->
            eps.forEach { ep ->
                map[episodeKey(season, ep.episodeNumber)] = tmdbEpisode(mediaId, season, ep)
            }
        }
        return map
    }

    fun pluginMovieShell(mediaId: Int, d: TmdbDetail): Map<String, Episode> =
        linkedMapOf(
            "1" to Episode(
                number = "1",
                title = d.displayTitle,
                desc = d.overview,
                thumb = Tmdb.imageUrl(d.backdropPath ?: d.posterPath, 780)?.let { FileUrl(it) },
                date = d.releaseDate,
                rating = d.voteAverage.takeIf { it > 0 }
                    ?.let { String.format(java.util.Locale.US, "%.1f", it) },
                selectedSubtitle = -1,
                extra = mapOf("tmdbId" to mediaId.toString())
            )
        )

    suspend fun populateSyntheticEpisode(context: Context, media: Media, ep: Episode): Boolean =
        withContext(Dispatchers.IO) {
            if (!ep.extractors.isNullOrEmpty() && ep.allStreams) return@withContext true
            val session = sessions[media.id] ?: return@withContext false
            val season = ep.extra?.get("season")?.toIntOrNull()
            val episodeNum = ep.extra?.get("episode")?.toIntOrNull()
            val cached = cachedLinks(session.mediaId, session.source.name, season, episodeNum)
            val result = cached
                ?: if (session.load != null) {
                    (resolvePluginStreams(context, session.source, session.load, season, episodeNum)
                        as? StreamResult.Success)
                        .also { if (it != null) cacheLinks(session.mediaId, session.source.name, season, episodeNum, it) }
                } else {
                    (resolveStreams(context, session.source, session.detail, season, episodeNum)
                        as? StreamResult.Success)
                        .also { if (it != null) cacheLinks(session.mediaId, session.source.name, season, episodeNum, it) }
                }
                ?: return@withContext false
            val extractors = buildExtractors(result.links)
            if (extractors.isEmpty()) return@withContext false
            ep.extractors = extractors.toMutableList()
            ep.extractorsSource = 0
            ep.allStreams = true
            Logger.log(
                "TMDB_PLAY: populated synthetic ep '${ep.number}' with ${extractors.size} " +
                    "servers via ${session.source.name}"
            )
            true
        }

    suspend fun launchPlayer(
        context: Context,
        mediaId: Int,
        mediaType: String,
        d: TmdbDetail,
        source: CsInstalledSource,
        season: Int?,
        epNumber: Int?,
        links: List<StreamResult.PlayableLink>,
        pickedLabel: String,
        episodesOverride: Map<String, Episode>? = null,
        load: LoadResponse? = null
    ) {
        val id = syntheticId(mediaId)
        sessions[id] = SyntheticSession(mediaId, mediaType, d, source, load)
        if (sessions.size > 24) {
            sessions.entries.take(sessions.size - 24).forEach { sessions.remove(it.key) }
        }
        val episodes = episodesOverride ?: episodesFor(mediaId, mediaType, d, source.name)
        val currentKey = if (mediaType == "tv") {
            episodeKey(season ?: 1, epNumber ?: 1)
        } else "1"
        val current = episodes[currentKey] ?: episodes.values.firstOrNull() ?: return
        val extractors = buildExtractors(links)
        if (extractors.isEmpty()) return
        current.extractors = extractors.toMutableList()
        current.extractorsSource = 0
        current.allStreams = true

        val groupingEnabled = PrefManager.getVal<Boolean>(PrefName.GroupServersByHostAndLanguageTags)
        val pickedHost = links.firstOrNull { it.label == pickedLabel }?.host
            ?.takeIf { it.isNotBlank() }
            ?: pickedLabel
        val pickedExtractor = extractors.firstOrNull {
            it.server.name == if (groupingEnabled) pickedHost else pickedLabel
        } ?: extractors.first()
        val pickedVideoIdx = if (groupingEnabled) {
            links.filter { (it.host.ifBlank { it.label }) == pickedHost }
                .indexOfFirst { it.label == pickedLabel }
                .coerceAtLeast(0)
        } else 0
        current.selectedExtractor = pickedExtractor.server.name

        current.selectedVideo = pickedVideoIdx.coerceIn(
            0,
            (pickedExtractor.videos.size - 1).coerceAtLeast(0),
        )
        val title = d.displayTitle
        val media = Media(
            anime = Anime(
                totalEpisodes = episodes.size,
                selectedEpisode = currentKey,
                episodes = episodes.toMutableMap()
            ),
            id = id,
            name = title,
            nameRomaji = title,
            userPreferredName = title,
            description = d.overview,
            genres = ArrayList(d.genres.map { it.name }),
            meanScore = (d.voteAverage * 10).toInt().coerceIn(0, 100),
            cover = Tmdb.imageUrl(d.posterPath, 500),
            banner = Tmdb.imageUrl(d.backdropPath, 780),
            logoUrl = Tmdb.logoUrl(d),
            format = if (mediaType == "tv") "TV" else "MOVIE",
            idIMDB = d.externalIds?.imdbId,
            isAdult = false,
            status = d.status?.uppercase()
        )

        val selected = Selected(sourceIndex = 0, server = pickedExtractor.server.name, video = pickedVideoIdx)
        media.selected = selected
        PrefManager.setCustomVal("Selected-$id", selected)
        Logger.log(
            "TMDB_PLAY: launching ExoplayerView for ${mediaType} id=$id title='$title' " +
                "eps=${episodes.size} current='$currentKey' picked='$pickedLabel'"
        )
        ExoplayerView.media = media
        ExoplayerView.initialized = true
        context.startActivity(
            Intent(context, ExoplayerView::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
}
