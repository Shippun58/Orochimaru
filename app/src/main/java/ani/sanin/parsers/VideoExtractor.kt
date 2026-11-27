package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.Serializable

abstract class VideoExtractor : Serializable {
    abstract val server: VideoServer
    var videos: List<Video> = listOf()
    var subtitles: List<Subtitle> = listOf()
    var audioTracks: List<Track> = listOf()
    var timestamps: List<TimeStamp> = listOf()

    val isPreferred: Boolean
        get() = server.video?.preferred == true ||
            videos.any { it.preferred } ||
            server.extraVideos?.any { it.preferred } == true ||
            server.preloadedVideos?.any { it.preferred } == true

    abstract suspend fun extract(): VideoContainer

    open suspend fun load(): VideoExtractor {
        extract().also {
            videos = it.videos
            subtitles = it.subtitles
            audioTracks = it.audioTracks
            timestamps = it.timestamps
            return this
        }
    }

    open suspend fun onVideoPlayed(video: Video?) {}

    open suspend fun onVideoStopped(video: Video?) {}
}

class PreloadedVideoExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer {
        val videos = server.preloadedVideos.orEmpty()
        val data = server.extraData.orEmpty()
        return VideoContainer(
            videos = videos,
            subtitles = parseAnimeSubtitles(data["subtitles"]),
            audioTracks = parseAnimeTracks(data["audioTracks"]),
        )
    }
}

internal fun encodeAnimeSubtitles(subtitles: List<Subtitle>): String? {
    if (subtitles.isEmpty()) return null
    return buildJsonArray {
        subtitles.forEach { subtitle ->
            add(buildJsonObject {
                put("url", JsonPrimitive(subtitle.file.url))
                put("language", JsonPrimitive(subtitle.language))
                put("type", JsonPrimitive(when (subtitle.type) {
                    SubtitleType.ASS -> "ass"
                    SubtitleType.SRT -> "srt"
                    else -> "vtt"
                }))
                if (subtitle.file.headers.isNotEmpty()) {
                    put("headers", buildJsonObject {
                        subtitle.file.headers.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                    })
                }
            })
        }
    }.toString()
}

internal fun parseAnimeSubtitles(jsonStr: String?): List<Subtitle> {
    if (jsonStr.isNullOrBlank()) return emptyList()
    return try {
        val array = Mapper.json.parseToJsonElement(jsonStr) as? JsonArray ?: return emptyList()
        array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val language = (obj["language"] as? JsonPrimitive)?.contentOrNull ?: "Unknown"
            val type = when ((obj["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                "ass", "ssa" -> SubtitleType.ASS
                "srt" -> SubtitleType.SRT
                else -> SubtitleType.VTT
            }
            val headers = (obj["headers"] as? JsonObject)?.entries?.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }?.toMap().orEmpty()
            Subtitle(language = language, file = FileUrl(url, headers), type = type)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun encodeAnimeTracks(tracks: List<Track>): String? {
    if (tracks.isEmpty()) return null
    return buildJsonArray {
        tracks.forEach { track ->
            add(buildJsonObject {
                put("url", JsonPrimitive(track.url))
                put("lang", JsonPrimitive(track.lang))
            })
        }
    }.toString()
}

internal fun parseAnimeTracks(jsonStr: String?): List<Track> {
    if (jsonStr.isNullOrBlank()) return emptyList()
    return try {
        val array = Mapper.json.parseToJsonElement(jsonStr) as? JsonArray ?: return emptyList()
        array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val lang = (obj["lang"] as? JsonPrimitive)?.contentOrNull ?: "Unknown"
            Track(url = url, lang = lang)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

data class VideoServer(
    val name: String,
    val embed: FileUrl,
    val extraData: Map<String, String>? = null,
    val video: eu.kanade.tachiyomi.animesource.model.Video? = null,
    val offline: Boolean = false,
    val extraVideos: List<eu.kanade.tachiyomi.animesource.model.Video>? = null,
    val preloadedVideos: List<Video>? = null
) : Serializable {

    val isPreferred: Boolean
        get() = video?.preferred == true || extraVideos?.any { it.preferred } == true ||
            preloadedVideos?.any { it.preferred } == true

    constructor(name: String, embedUrl: String, extraData: Map<String, String>? = null)
            : this(name, FileUrl(embedUrl), extraData)

    constructor(name: String, offline: Boolean, extraData: Map<String, String>?)
            : this(name, FileUrl(""), extraData, null, offline)

    constructor(
        name: String,
        embedUrl: String,
        extraData: Map<String, String>? = null,
        video: eu.kanade.tachiyomi.animesource.model.Video?
    )
            : this(name, FileUrl(embedUrl), extraData, video)
}

data class VideoContainer(
    val videos: List<Video>,
    val subtitles: List<Subtitle> = listOf(),
    val audioTracks: List<Track> = listOf(),
    val timestamps: List<TimeStamp> = listOf(),
) : Serializable

data class DrmInfo(
    val licenseUrl: String? = null,
    val uuid: java.util.UUID? = null,
    val keyRequestParameters: HashMap<String, String> = hashMapOf(),
    val kid: String? = null,
    val key: String? = null,
    val kty: String? = null,
) : Serializable

data class Video(

    val quality: Int?,

    val format: VideoType,

    val file: FileUrl,

    val size: Double? = null,

    val extraNote: String? = null,
    val drm: DrmInfo? = null,
    val preferred: Boolean = false,
) : Serializable {

    constructor(
        quality: Int? = null,
        videoType: VideoType,
        url: String,
        size: Double?,
        extraNote: String? = null,
        drm: DrmInfo? = null,
    )
            : this(quality, videoType, FileUrl(url), size, extraNote, drm)

    constructor(quality: Int? = null, videoType: VideoType, url: String, size: Double?)
            : this(quality, videoType, FileUrl(url), size)

    constructor(quality: Int? = null, videoType: VideoType, url: String)
            : this(quality, videoType, FileUrl(url))
}

data class Subtitle(

    val language: String,

    val file: FileUrl,

    val type: SubtitleType = SubtitleType.VTT,
) : Serializable {
    constructor(language: String, url: String, type: SubtitleType = SubtitleType.VTT) : this(
        language,
        FileUrl(url),
        type
    )
}

enum class VideoType {
    CONTAINER, M3U8, DASH
}

enum class SubtitleType {
    VTT, ASS, SRT, UNKNOWN
}