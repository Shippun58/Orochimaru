package ani.sanin.download

import ani.sanin.parsers.VideoType
import java.io.Serializable

data class DownloadItem(
    val id: String,
    val mediaId: Int,
    val mediaName: String,
    val cover: String? = null,
    val thumbnail: String? = null,
    val episodeNumber: String,
    val episodeTitle: String? = null,
    val sourceName: String? = null,
    val serverName: String? = null,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val videoType: VideoType = VideoType.CONTAINER,

    var offlinePlaybackUrl: String? = null,
    val quality: Int? = null,
    val fileName: String,
    var status: DownloadStatus = DownloadStatus.QUEUED,
    var progress: Float = 0f,
    var downloadedBytes: Long = 0L,
    var totalBytes: Long = 0L,
    var speed: Long = 0L,
    var error: String? = null,

    var synopsis: String? = null,
    var genres: List<String>? = null,
    var thumbPath: String? = null,
    var logoPath: String? = null,
) : Serializable {

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED ||
            status == DownloadStatus.PREPARING ||
            status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.RETRYING
}

enum class DownloadStatus {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    RETRYING,
    PAUSED,
    FINISHED,
    ERROR,
}

data class DownloadedItem(
    val id: String,
    val mediaId: Int,
    val mediaName: String,
    val cover: String? = null,
    val thumbnail: String? = null,
    val episodeNumber: String,
    val episodeTitle: String? = null,
    val path: String,
    val sizeBytes: Long = 0L,
    val timestamp: Long = 0L,
    val synopsis: String? = null,
    val genres: List<String>? = null,
    val thumbPath: String? = null,
    val logoPath: String? = null,

    val videoType: VideoType? = null,
    val sourceUrl: String? = null,
    val playbackUrl: String? = null,
    val headers: Map<String, String>? = emptyMap(),
) : Serializable

enum class DownloadStorage {

    APP,

    SHARED,
}