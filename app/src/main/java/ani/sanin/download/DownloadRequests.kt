package ani.sanin.download

import ani.sanin.App
import ani.sanin.media.Media
import ani.sanin.media.anime.Episode
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoExtractor
import ani.sanin.util.BatteryOptimizations

fun bestVideoOf(extractor: VideoExtractor): Video? =
    extractor.videos.firstOrNull { it.preferred }
        ?: extractor.videos.maxByOrNull { it.quality ?: 0 }
        ?: extractor.videos.firstOrNull()

fun DownloadManager.enqueue(
    media: Media,
    episode: Episode,
    extractor: VideoExtractor,
    video: Video,
    sourceName: String? = null,
): DownloadItem? {
    if (isDownloaded(media.id, episode.number)) return null
    val url = video.file.url
    if (url.isBlank()) return null

    val item = DownloadItem(
        id = "${media.id}:${episode.number}",
        mediaId = media.id,
        mediaName = media.userPreferredName.ifBlank { media.nameRomaji },
        cover = media.cover,
        thumbnail = episode.thumb?.url?.takeIf { it.isNotBlank() }
            ?: media.banner?.takeIf { it.isNotBlank() }
            ?: media.cover,
        episodeNumber = episode.number,
        episodeTitle = episode.title,
        sourceName = sourceName,
        serverName = extractor.server.name,
        url = url,
        headers = video.file.headers,
        videoType = video.format,
        quality = video.quality,
        fileName = "Episode ${episode.number}",
        synopsis = media.description,
        genres = media.genres.takeIf { it.isNotEmpty() },
    )
    enqueue(item)

    App.currentActivity()?.let { BatteryOptimizations.promptIfNeeded(it) }
    return item
}