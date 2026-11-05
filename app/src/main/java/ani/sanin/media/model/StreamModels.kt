package ani.sanin.media.model

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnore
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromLanguageToTagIETF
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class ExtractorUri(
    val uri: Uri,
    val name: String,

    val basePath: String? = null,
    val relativePath: String? = null,
    val displayName: String? = null,

    val id: Int? = null,
    val parentId: Int? = null,
    val episode: Int? = null,
    val season: Int? = null,
    val headerName: String? = null,
    val tvType: TvType? = null,
)

enum class SubtitleOrigin {
    URL,
    DOWNLOADED_FILE,
    EMBEDDED_IN_VIDEO
}

@Serializable
data class SubtitleData(
    @SerialName("originalName") val originalName: String,
    @SerialName("nameSuffix") val nameSuffix: String,
    @SerialName("url") val url: String,
    @SerialName("origin") val origin: SubtitleOrigin,
    @SerialName("mimeType") val mimeType: String,
    @SerialName("headers") val headers: Map<String, String>,
    @SerialName("languageCode") val languageCode: String?,
    @SerialName("source") val source: String? = null,
) {

    @JsonIgnore
    fun getId(): String {
        return if (origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) url
        else "$url|$name"
    }

    fun matchesLanguageCode(langCode: String): Boolean {
        return getIETF_tag() == langCode
    }

    @JsonIgnore
    fun getIETF_tag(): String? {
        return fromLanguageToTagIETF(this.languageCode)
            ?: fromLanguageToTagIETF(this.originalName, halfMatch = true)
    }

    @SerialName("name") val name = "$originalName $nameSuffix"

    @JsonIgnore
    fun getFixedUrl(): String {

        val fixedSubUrl = if (this.url.startsWith("//")) {
            "https:${this.url}"
        } else this.url
        return fixedSubUrl
    }
}

enum class VideoWatchState {

    None,
    Watched
}

@Serializable
data class ResultEpisode(
    @SerialName("headerName") val headerName: String,
    @SerialName("name") val name: String?,
    @SerialName("poster") val poster: String?,
    @SerialName("episode") val episode: Int,
    @SerialName("seasonIndex") val seasonIndex: Int?,
    @SerialName("season") val season: Int?,
    @SerialName("data") val data: String,
    @SerialName("apiName") val apiName: String,
    @SerialName("id") val id: Int,
    @SerialName("index") val index: Int,
    @SerialName("position") val position: Long,
    @SerialName("duration") val duration: Long,
    @SerialName("score") val score: Score?,
    @SerialName("description") val description: String?,
    @SerialName("isFiller") val isFiller: Boolean?,
    @SerialName("tvType") val tvType: TvType,
    @SerialName("parentId") val parentId: Int,

    @SerialName("videoWatchState") val videoWatchState: VideoWatchState,

    @SerialName("totalEpisodeIndex") val totalEpisodeIndex: Int? = null,
    @SerialName("airDate") val airDate: Long? = null,
    @SerialName("runTime") val runTime: Int? = null,
    @SerialName("seasonData") val seasonData: SeasonData? = null,
)
