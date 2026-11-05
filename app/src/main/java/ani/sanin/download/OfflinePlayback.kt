package ani.sanin.download

import android.net.Uri
import ani.sanin.FileUrl
import ani.sanin.media.Media
import ani.sanin.media.Selected
import ani.sanin.media.anime.Anime
import ani.sanin.media.anime.Episode
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoContainer
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.parsers.VideoType
import java.io.File

const val DOWNLOADED_SERVER_NAME = "Downloaded"

fun DownloadedItem.playbackUri(): String {
    if (path.startsWith(DownloadHlsCache.LOCATOR_PREFIX)) {
        return playbackUrl ?: sourceUrl
            ?: throw IllegalStateException("Downloaded adaptive source URL is missing")
    }
    val p = path
    return if (p.startsWith("content://") || p.startsWith("file://")) p
    else Uri.fromFile(File(p)).toString()
}

fun DownloadedItem.toOfflineExtractor(): VideoExtractor {
    val isAdaptiveCache = path.startsWith(DownloadHlsCache.LOCATOR_PREFIX)
    val video = Video(
        quality = null,
        format = if (isAdaptiveCache) (videoType ?: VideoType.M3U8) else (videoType ?: VideoType.CONTAINER),
        file = ani.sanin.FileUrl(playbackUri(), headers.orEmpty()),
        size = sizeBytes.takeIf { it > 0 }?.toDouble(),
    )
    return object : VideoExtractor() {
        override val server = VideoServer(DOWNLOADED_SERVER_NAME, true, null)
        override suspend fun extract(): VideoContainer = VideoContainer(listOf(video))
    }.apply { videos = listOf(video) }
}

fun DownloadedItem.toOfflineMedia(): Media {
    val extractor = toOfflineExtractor()
    val ep = Episode(
        number = episodeNumber,
        title = episodeTitle,
        thumb = cover?.let { FileUrl(it) },
    ).apply {
        extractors = mutableListOf(extractor)
        extractorsSource = 0
        selectedExtractor = extractor.server.name
        selectedVideo = 0
    }
    val anime = Anime(
        totalEpisodes = 1,
        selectedEpisode = episodeNumber,
        episodes = mutableMapOf(episodeNumber to ep),
    )
    return Media(
        anime = anime,
        id = mediaId,
        name = mediaName,
        nameRomaji = mediaName,
        userPreferredName = mediaName,
        cover = cover,
        isAdult = false,
        format = "LOCAL",
        selected = Selected(sourceIndex = 0, server = DOWNLOADED_SERVER_NAME, video = 0),

        description = synopsis,
        genres = genres?.let { ArrayList(it) } ?: arrayListOf(),
        logoUrl = logoPath?.let { Uri.fromFile(File(it)).toString() },
    )
}