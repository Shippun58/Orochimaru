package ani.sanin.parsers

import android.content.Context
import ani.sanin.FileUrl
import ani.sanin.currContext
import ani.sanin.media.MediaNameAdapter
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.NO_HOSTER_LIST
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.copyFrom
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassException
import eu.kanade.tachiyomi.source.anime.getPreferenceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.UnsupportedEncodingException
import java.net.MalformedURLException
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

class DynamicAnimeParser(extension: AnimeExtension.Installed) : AnimeParser() {
    val extension: AnimeExtension.Installed
    var sourceLanguage = 0

    init {
        this.extension = extension
    }

    override val name = extension.name
    override val saveName = extension.name
    override val hostUrl =
        (extension.sources.first() as? AnimeHttpSource)?.baseUrl ?: extension.sources.first().name
    override val isNSFW = extension.isNsfw
    override val icon = extension.icon

    override var selectDub: Boolean
        get() = getDub()
        set(value) {
            setDub(value)
        }

    private fun getDub(): Boolean {
        if (sourceLanguage >= extension.sources.size) {
            sourceLanguage = extension.sources.size - 1
        }
        val configurableSource = extension.sources[sourceLanguage] as? ConfigurableAnimeSource
            ?: return false
        currContext()?.let { context ->
            val sharedPreferences =
                context.getSharedPreferences(
                    configurableSource.getPreferenceKey(),
                    Context.MODE_PRIVATE
                )
            sharedPreferences.all.filterValues { MediaNameAdapter.getSubDub(it.toString()) != MediaNameAdapter.SubDubType.NULL }
                .forEach { value ->
                    return when (MediaNameAdapter.getSubDub(value.value.toString())) {
                        MediaNameAdapter.SubDubType.SUB -> false
                        MediaNameAdapter.SubDubType.DUB -> true
                        MediaNameAdapter.SubDubType.NULL -> false
                    }
                }
        }
        return false
    }

    private fun setDub(setDub: Boolean) {
        if (sourceLanguage >= extension.sources.size) {
            sourceLanguage = extension.sources.size - 1
        }
        val configurableSource = extension.sources[sourceLanguage] as? ConfigurableAnimeSource
            ?: return
        val type = when (setDub) {
            true -> MediaNameAdapter.SubDubType.DUB
            false -> MediaNameAdapter.SubDubType.SUB
        }
        currContext()?.let { context ->
            val sharedPreferences =
                context.getSharedPreferences(
                    configurableSource.getPreferenceKey(),
                    Context.MODE_PRIVATE
                )
            sharedPreferences.all.filterValues { MediaNameAdapter.getSubDub(it.toString()) != MediaNameAdapter.SubDubType.NULL }
                .forEach { value ->
                    val setValue = MediaNameAdapter.setSubDub(value.value.toString(), type)
                    if (setValue != null) {
                        sharedPreferences.edit().putString(value.key, setValue).apply()
                    }
                }
        }
    }

    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean {
        val configurableSource = extension.sources[sourceLanguage] as? ConfigurableAnimeSource
            ?: return false
        currContext()?.let { context ->
            Logger.log("isDubAvailableSeparately: ${configurableSource.getPreferenceKey()}")
            val sharedPreferences =
                context.getSharedPreferences(
                    configurableSource.getPreferenceKey(),
                    Context.MODE_PRIVATE
                )
            sharedPreferences.all.filterValues {
                MediaNameAdapter.setSubDub(
                    it.toString(),
                    MediaNameAdapter.SubDubType.NULL
                ) != null
            }
                .forEach { _ -> return true }
        }
        return false
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> {
        val source = try {
            extension.sources[sourceLanguage]
        } catch (e: Exception) {
            sourceLanguage = 0
            extension.sources[sourceLanguage]
        } as? AnimeHttpSource ?: (extension.sources[sourceLanguage] as? AnimeCatalogueSource
            ?: return emptyList())
        try {

            runCatching { source.getAnimeDetails(sAnime) }.getOrNull()?.let { detailed ->
                runCatching { sAnime.copyFrom(detailed) }
                sAnime.initialized = true
            }
            val res = loadEpisodeListSafe(source, sAnime)
            if (res.isEmpty()) {
                Logger.log("loadEpisodes: extension returned no episodes for '${sAnime.title}'")
                return emptyList()
            }

            val sortedEpisodes = if (res[0].episode_number == -1f) {

                val sortedByStringNumber = res.sortedBy {
                    val matchResult = MediaNameAdapter.findEpisodeNumber(it.name)
                    val number = matchResult ?: Float.MAX_VALUE
                    it.episode_number = number
                    number
                }

                var incrementingNumber = 1f
                sortedByStringNumber.map {
                    if (it.episode_number == Float.MAX_VALUE) {
                        it.episode_number =
                            incrementingNumber++
                    }
                    it
                }
            } else if (episodesAreIncrementing(res)) {
                res.sortedBy { it.episode_number }
            } else {
                var episodeCounter = 1f

                val seasonGroups =
                    res.groupBy { MediaNameAdapter.findSeasonNumber(it.name) ?: 0 }
                seasonGroups.keys.sortedBy { it }
                    .flatMap { season ->
                        seasonGroups[season]?.sortedBy { it.episode_number }?.map { episode ->
                            if (episode.episode_number != 0f) {
                                val potentialNumber =
                                    MediaNameAdapter.findEpisodeNumber(episode.name)
                                if (potentialNumber != null) {
                                    episode.episode_number = potentialNumber
                                } else {
                                    episode.episode_number = episodeCounter
                                }
                                episodeCounter++
                            }
                            episode
                        } ?: emptyList()
                    }
            }
            return sortedEpisodes.map { sEpisodeToEpisode(it) }
        } catch (e: Exception) {
            Logger.log("Exception: $e")
        }
        return emptyList()
    }

    private suspend fun loadEpisodeListSafe(source: AnimeSource, sAnime: SAnime): List<SEpisode> {
        return try {
            source.getEpisodeList(sAnime)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: LinkageError) {
            Logger.log("loadEpisodes: linkage error (${e::class.java.simpleName}), retrying once: ${e.message}")
            runCatching { source.getEpisodeList(sAnime) }.getOrDefault(emptyList())
        } catch (e: NotImplementedError) {
            Logger.log("loadEpisodes: NotImplementedError, retrying once: ${e.message}")
            runCatching { source.getEpisodeList(sAnime) }.getOrDefault(emptyList())
        }
    }

    private fun episodesAreIncrementing(episodes: List<SEpisode>): Boolean {
        val sortedEpisodes = episodes.sortedBy { it.episode_number }
        val takenNumbers = mutableListOf<Float>()
        sortedEpisodes.forEach {
            if (it.episode_number !in takenNumbers) {
                takenNumbers.add(it.episode_number)
            } else {
                return false
            }
        }
        return true
    }
    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> {
        val source = try {
            extension.sources[sourceLanguage]
        } catch (e: Exception) {
            sourceLanguage = 0
            extension.sources[sourceLanguage]
        } as? AnimeHttpSource ?: return emptyList()

        return try {
            val videos = getVideoList(source, sEpisode)

            if (!PrefManager.getVal<Boolean>(PrefName.GroupServersByHostAndLanguageTags)) {
                val usedNames = HashSet<String>()
                return videos.mapIndexed { index, video ->
                    val name = video.videoTitle.ifBlank { video.quality.ifBlank { "Server ${index + 1}" } }
                    var uniqueName = name
                    var suffix = 2
                    while (!usedNames.add(uniqueName.lowercase(Locale.ROOT))) {
                        uniqueName = "$name ${suffix++}"
                    }
                    VideoServer(
                        name = uniqueName,
                        embed = FileUrl(video.url),
                        extraData = null,
                        video = video,
                        extraVideos = null
                    )
                }
            }

            val dub = selectDub
            videos.groupBy { video ->
                val (base, tag) = extensionVideoGroup(video)
                extensionGroupKey(base, tag)
            }
                .map { (_, group) ->
                    val (base, tag) = extensionVideoGroup(group.first())
                    GroupedVideoServer(extensionGroupName(base, tag), group, tag)
                }
                .filter { !dub || it.audio != MediaNameAdapter.SubDubType.SUB }
                .sortedWith(
                    compareBy(
                        { it.audio.sortRank() },
                        { if (it.videos.any { v -> v.preferred }) 0 else 1 },
                    )
                )
                .map { group ->

                    val ordered = group.videos.sortedByDescending { it.preferred }
                    val first = ordered.first()
                    VideoServer(
                        name = group.host,
                        embed = FileUrl(first.url),
                        extraData = group.audio.takeUnless { it == MediaNameAdapter.SubDubType.NULL }
                            ?.let { mapOf("audio" to it.name) },
                        video = first,
                        extraVideos = ordered.takeIf { it.size > 1 }
                    )
                }
        } catch (e: Exception) {
            Logger.log("Exception occurred: ${e.message}")
            emptyList()
        }
    }

    private data class GroupedVideoServer(
        val host: String,
        val videos: List<Video>,
        val audio: MediaNameAdapter.SubDubType
    )

    private fun videoHost(video: Video): String {
        val title = video.videoTitle.ifBlank { video.quality }.trim()
        val sep = HOST_SEPARATOR.find(title) ?: return title
        val host = title.substring(0, sep.range.first).trim()
        return host.ifBlank { title }
    }

    private val HOST_AUDIO_TOKEN = Regex("""(?i)\s+[\[(]?(?:dub|dubbed|sub|subbed)s?[)\]]?\s*$""")

    private fun extensionVideoGroup(video: Video): Pair<String, MediaNameAdapter.SubDubType> {
        val host = videoHost(video)
        val tag = audioTagOf(listOf(video))
        val base = host.replace(HOST_AUDIO_TOKEN, "").trim()
        return (base.ifBlank { host }) to tag
    }

    private fun extensionGroupKey(base: String, tag: MediaNameAdapter.SubDubType): String =
        base.lowercase(Locale.ROOT) +
            if (tag != MediaNameAdapter.SubDubType.NULL) "_${tag.name.lowercase(Locale.ROOT)}" else ""

    private fun extensionGroupName(base: String, tag: MediaNameAdapter.SubDubType): String =
        if (tag != MediaNameAdapter.SubDubType.NULL) "$base ${tag.name}" else base

    private fun audioTagOf(videos: List<Video>): MediaNameAdapter.SubDubType {
        val hasDub = videos.any { EXT_AUDIO_DUB.containsMatchIn(it.videoTitle.ifBlank { it.quality }) }
        val hasSub = videos.any { EXT_AUDIO_SUB.containsMatchIn(it.videoTitle.ifBlank { it.quality }) }
        return when {
            hasDub && !hasSub -> MediaNameAdapter.SubDubType.DUB
            hasSub && !hasDub -> MediaNameAdapter.SubDubType.SUB
            else -> MediaNameAdapter.SubDubType.NULL
        }
    }
    suspend fun getVideoList(
        source: AnimeHttpSource,
        episode: SEpisode
    ): List<Video> {

        val hasHosters = checkHasHosters(source)

        val directVideos = if (!hasHosters) {
            runCatching {
                source.getVideoList(episode)
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        val hosterVideos = if (hasHosters) {
            val hosters = runCatching {
                source.getHosterList(episode)
            }.getOrElse { emptyList() }

            coroutineScope {
                hosters.map { hoster ->
                    async(Dispatchers.IO) {

                        val videos = when {
                            !hoster.videoList.isNullOrEmpty() -> hoster.videoList
                            else -> runCatching {
                                source.getVideoList(hoster)
                            }.getOrElse { emptyList() }
                        }

                        videos.map { video ->
                            val resolved = resolveVideo(source, video)

                            val title = if (
                                hoster.hosterName.isBlank() ||
                                hoster.hosterName == NO_HOSTER_LIST
                            ) {
                                resolved.videoTitle
                            } else {
                                "${hoster.hosterName} - ${resolved.videoTitle}"
                            }

                            resolved.copy(
                                videoTitle = title,
                                initialized = true
                            )
                        }
                    }
                }.awaitAll().flatten()
            }
        } else {
            emptyList()
        }

        val resolvedDirect = coroutineScope {
            directVideos.map {
                async(Dispatchers.IO) {
                    resolveVideo(source, it)
                }
            }.awaitAll()
        }

        return source.run {
            (resolvedDirect + hosterVideos)
                .distinctBy { it.videoUrl }
                .filter { it.videoUrl.isNotEmpty() && it.videoUrl != "null" }
                .sortVideos()
        }
    }

    private fun checkHasHosters(source: AnimeHttpSource): Boolean {
        var current: Class<in AnimeHttpSource> = source.javaClass

        while (true) {
            if (current == ParsedAnimeHttpSource::class.java ||
                current == AnimeHttpSource::class.java ||
                current == AnimeSource::class.java
            ) {
                return false
            }

            if (current.declaredMethods.any {
                    it.name in listOf(
                        "getHosterList",
                        "hosterListRequest",
                        "hosterListParse"
                    )
                }
            ) {
                return true
            }

            current = current.superclass ?: return false
        }
    }

    private suspend fun resolveVideo(
        source: AnimeHttpSource,
        video: Video
    ): Video {
        if (video.initialized && video.videoUrl.isNotEmpty() && video.videoUrl != "null") {
            return video
        }

        val resolved = runCatching {
            source.resolveVideo(video)
        }.getOrNull()

        if (resolved != null) return resolved

        if (video.videoUrl == "null" || video.videoUrl.isEmpty()) {
            val newUrl = runCatching {
                source.getVideoUrl(video)
            }.getOrNull()

            return video.copy(videoUrl = newUrl ?: video.videoUrl)
        }

        return video
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor {
        return VideoServerPassthrough(server)
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val source = try {
            extension.sources[sourceLanguage]
        } catch (e: Exception) {
            sourceLanguage = 0
            extension.sources[sourceLanguage]
        } as? AnimeHttpSource ?: (extension.sources[sourceLanguage] as? AnimeCatalogueSource
            ?: return emptyList())
        return try {
            val res = source.getSearchAnime(1, query, source.getFilterList())
            Logger.log("query: $query")
            convertAnimesPageToShowResponse(res)
        } catch (e: CloudflareBypassException) {
            Logger.log("Exception in search: $e")
            Logger.log(e)
            withContext(Dispatchers.Main) {
                snackString("Failed to bypass Cloudflare")
            }
            emptyList()
        } catch (e: Exception) {
            Logger.log("General exception in search: $e")
            Logger.log(e)
            emptyList()
        }
    }

    private fun convertAnimesPageToShowResponse(animesPage: AnimesPage): List<ShowResponse> {
        return animesPage.animes.map { sAnime ->

            val name = sAnime.title
            val link = sAnime.url
            val coverUrl = sAnime.thumbnail_url ?: ""

            ShowResponse(name, link, coverUrl, sAnime)
        }
    }

    private fun sEpisodeToEpisode(sEpisode: SEpisode): Episode {

        val episodeNumberInt =
            if (sEpisode.episode_number % 1 == 0f) {
                sEpisode.episode_number.toInt()
            } else {
                sEpisode.episode_number
            }
        return Episode(
            if (episodeNumberInt.toInt() != -1) {
                if (sEpisode.episode_number % 1 == 0f) {
                    episodeNumberInt.toInt().toString()
                } else {
                    sEpisode.episode_number.toString()
                }
            } else {
                sEpisode.name
            },
            sEpisode.url,
            sEpisode.name,
            null,
            null,
            false,
            null,
            sEpisode
        )
    }

}

private val HOST_SEPARATOR = Regex("""\s*-\s*""")

private val EXT_AUDIO_DUB = Regex("""(?i)\b(?:dub|dubbed|eng\s*dub|english\s*dub)\b""")
private val EXT_AUDIO_SUB = Regex("""(?i)\b(?:sub|subbed|softsub|subtitled|subtitle|eng\s*sub|english\s*sub)\b""")

private fun MediaNameAdapter.SubDubType.sortRank(): Int = when (this) {
    MediaNameAdapter.SubDubType.SUB -> 0
    MediaNameAdapter.SubDubType.NULL -> 1
    MediaNameAdapter.SubDubType.DUB -> 2
}

class VideoServerPassthrough(private val videoServer: VideoServer) : VideoExtractor() {

    override val server: VideoServer
        get() = videoServer

    override suspend fun extract(): VideoContainer {
        val aniVideos = videoServer.extraVideos ?: listOfNotNull(videoServer.video)
        val vidList = coroutineScope {
            aniVideos.map { v ->
                async { runCatching { aniVideoToSaiVideo(v) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
        val subList = aniVideos.flatMap { it.subtitleTracks.map { track -> trackToSubtitle(track) } }
        val audioList = aniVideos.flatMap { it.audioTracks }

        return if (vidList.isNotEmpty()) {
            VideoContainer(
                vidList,
                subList,
                audioList,

                timestamps = aniVideos.flatMap { it.timestamps },
            )
        } else {
            throw Exception("No videos found")
        }
    }

    private suspend fun aniVideoToSaiVideo(aniVideo: Video): ani.sanin.parsers.Video {

        val number = aniVideo.resolution?.takeIf { it in 100..9999 }
            ?: Regex("""(?i)\b(\d{3,4})\s*p\b""").find(aniVideo.quality)?.groupValues?.get(1)?.toInt()

        val videoUrl = aniVideo.videoUrl ?: throw Exception("Video URL is null")

        var format: VideoType?

        try {
            val urlObj = URL(videoUrl)
            val path = urlObj.path
            val query = urlObj.query

            format = getVideoType(path)

            if (format == null && query != null) {
                val queryPairs: List<Pair<String, String>> = query.split("&").mapNotNull {
                    val idx = it.indexOf("=")
                    if (idx != -1) {
                        val key = runCatching { URLDecoder.decode(it.substring(0, idx), "UTF-8") }.getOrNull() ?: ""
                        val value = runCatching { URLDecoder.decode(it.substring(idx + 1), "UTF-8") }.getOrNull() ?: ""
                        Pair(key, value)
                    } else null
                }

                val targetParam = queryPairs.find { it.first == "url" || it.first == "file" }?.second ?: ""
                if (targetParam.isNotBlank()) {
                    format = getVideoType(targetParam)
                }
            }

            if (format == null) {
                
                
                
                
                
                
                format = withContext(Dispatchers.IO) {
                    headRequest(videoUrl, Injekt.get<NetworkHelper>())
                } ?: run {
                    Logger.log("Unknown video format: $videoUrl")
                    VideoType.CONTAINER
                }
            }
        } catch (malformed: MalformedURLException) {
            throw malformed
        }
        val headersMap: Map<String, String> =
            aniVideo.headers?.toMultimap()?.mapValues { it.value.joinToString() } ?: mapOf()

        return Video(
            number,
            format!!,
            FileUrl(videoUrl, headersMap),
            null,
            preferred = aniVideo.preferred,
        )
    }

    private fun getVideoType(fileName: String): VideoType? {
        val type = when {
            fileName.endsWith(".mp4", ignoreCase = true) || fileName.endsWith(
                ".mkv",
                ignoreCase = true
            ) || fileName.endsWith(
                ".webm",
                ignoreCase = true
            ) -> VideoType.CONTAINER

            fileName.endsWith(".m3u8", ignoreCase = true) ||
                    fileName.contains(".m3u8", ignoreCase = true) ||
                    fileName.contains("/m3u8", ignoreCase = true) ||
                    fileName.equals("m3u8", ignoreCase = true) -> VideoType.M3U8
            fileName.endsWith(".mpd", ignoreCase = true) ||
                    fileName.contains(".mpd", ignoreCase = true) ||
                    fileName.contains("/mpd", ignoreCase = true) -> VideoType.DASH
            else -> null
        }

        return type
    }

    private fun headRequest(fileName: String, networkHelper: NetworkHelper): VideoType? {
        return try {
            Logger.log("attempting head request for $fileName")
            val request = Request.Builder()
                .url(fileName)
                .head()
                .build()

            networkHelper.client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type")
                val contentDisposition = response.header("Content-Disposition")

                if (contentType != null) {
                    when {
                        contentType.contains("mpegurl", ignoreCase = true) -> VideoType.M3U8
                        contentType.contains("dash", ignoreCase = true) -> VideoType.DASH
                        contentType.contains("mp4", ignoreCase = true) -> VideoType.CONTAINER
                        else -> null
                    }
                } else if (contentDisposition != null) {
                    when {
                        contentDisposition.contains("mpegurl", ignoreCase = true) -> VideoType.M3U8
                        contentDisposition.contains("dash", ignoreCase = true) -> VideoType.DASH
                        contentDisposition.contains("mp4", ignoreCase = true) -> VideoType.CONTAINER
                        else -> null
                    }
                } else {
                    Logger.log("failed head request for $fileName")
                    null
                }

            }
        } catch (e: Exception) {
            Logger.log("Exception in headRequest: $e")
            null
        }

    }

    private fun trackToSubtitle(track: Track): Subtitle {
        val type = runBlocking { findSubtitleType(track.url) }
        if (type == SubtitleType.UNKNOWN) {
            Logger.log("Warning: subtitle type unresolved for '${track.url}', defaulting to SRT")
        }
        return Subtitle(track.lang, track.url, type.takeUnless { it == SubtitleType.UNKNOWN } ?: SubtitleType.SRT)
    }

    private suspend fun findSubtitleType(url: String): SubtitleType {
        val typeFromUrl = findSubtitleTypeFromUrl(url)
        if (typeFromUrl != SubtitleType.UNKNOWN) return typeFromUrl
        return SubtitleType.UNKNOWN
    }

    private fun findSubtitleTypeFromUrl(url: String): SubtitleType {
        val normalizedUrl = url.substringBefore('#').substringBefore('?')
        val fromPath = extensionToSubtitleType(normalizedUrl)
        if (fromPath != SubtitleType.UNKNOWN) return fromPath

        val encodedCandidate =
            try {
                val query = URL(url).query ?: return SubtitleType.UNKNOWN
                query
                    .split('&')
                    .asSequence()
                    .mapNotNull { part ->
                        val idx = part.indexOf('=')
                        if (idx == -1) null else URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                    }.firstOrNull { value ->
                        extensionToSubtitleType(value) != SubtitleType.UNKNOWN
                    }
            } catch (_: Exception) {
                null
            }

        return extensionToSubtitleType(encodedCandidate ?: "")
    }

    private fun extensionToSubtitleType(value: String): SubtitleType {
        val lower = value.lowercase(Locale.ROOT)
        return when {
            hasExtensionMarker(lower, ".vtt") -> SubtitleType.VTT
            hasExtensionMarker(lower, ".ass", ".ssa") -> SubtitleType.ASS
            hasExtensionMarker(lower, ".srt") -> SubtitleType.SRT
            else -> SubtitleType.UNKNOWN
        }
    }

    private fun hasExtensionMarker(value: String, vararg extensions: String): Boolean {
        val base = value.substringBefore('#').substringBefore('?').substringBefore('&')
        return extensions.any { ext ->
            base.endsWith(ext)
        }
    }
}
