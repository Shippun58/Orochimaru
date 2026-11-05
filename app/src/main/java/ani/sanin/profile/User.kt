package ani.sanin.profile

import ani.sanin.connections.anilist.api.Activity
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: Int,
    val name: String,
    val pfp: String?,
    val banner: String?,

    val status: String? = null,
    val score: Float? = null,
    val progress: Int? = null,
    val totalEpisodes: Int? = null,
    val nextAiringEpisode: Int? = null,
    val activity: List<Activity> = mutableListOf(),

    var isFollowing: Boolean? = null,
    var isFollower: Boolean? = null,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = 1
    }
}