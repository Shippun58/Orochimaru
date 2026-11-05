package ani.sanin.connections.trakt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TraktListWrapper(
    @SerialName("like_count") val likeCount: Int? = null,
    @SerialName("list") val list: TraktList? = null,
)

@Serializable
data class TraktList(
    val name: String? = null,
    val description: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("sort_by") val sortBy: String? = null,
    @SerialName("sort_how") val sortHow: String? = null,
    val likes: Int? = null,
    val ids: TraktListIds? = null,
    val user: TraktUser? = null,
)

@Serializable
data class TraktListIds(
    val slug: String? = null,
    val trakt: Long? = null,
)

@Serializable
data class TraktUser(
    val username: String? = null,
    val ids: TraktUserIds? = null,
)

@Serializable
data class TraktUserIds(
    val slug: String? = null,
)

@Serializable
data class TraktListItem(
    val rank: Int? = null,
    val type: String? = null,
    val movie: TraktMovie? = null,
    val show: TraktShow? = null,
)

@Serializable
data class TraktMovie(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIds? = null,
    val images: TraktImages? = null,

    val overview: String? = null,

    val released: String? = null,

    val runtime: Int? = null,

    val rating: Double? = null,

    val type: String? = null,
)

@Serializable
data class TraktShow(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIds? = null,
    val images: TraktImages? = null,
    val overview: String? = null,

    @SerialName("first_aired") val firstAired: String? = null,
    val runtime: Int? = null,
    val rating: Double? = null,
    val type: String? = null,
)

@Serializable
data class TraktIds(
    @SerialName("slug") val slug: String? = null,
    @SerialName("imdb") val imdb: String? = null,
    @SerialName("tmdb") val tmdb: Int? = null,
    @SerialName("trakt") val trakt: Int? = null,
)

@Serializable
data class TraktImages(
    @SerialName("poster") val poster: List<String> = emptyList(),
    @SerialName("screenshot") val screenshot: List<String> = emptyList(),
    @SerialName("banner") val banner: List<String> = emptyList(),
    @SerialName("logo") val logo: List<String> = emptyList(),
    @SerialName("clearart") val clearart: List<String> = emptyList(),
) {

    fun urlOf(group: List<String>, vararg prefer: String): String? {
        if (group.isEmpty()) return null
        val pick = prefer.firstNotNullOfOrNull { tag ->
            group.firstOrNull { it.contains("/$tag/") }
        } ?: group.first()

        return when {
            pick.startsWith("http") -> pick
            pick.startsWith(HOST) -> "https://$pick"
            else -> "$IMAGE_BASE$pick"
        }
    }
}

private const val IMAGE_BASE = "https://media.trakt.tv/"

private const val HOST = "media.trakt.tv/"

internal val traktJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
