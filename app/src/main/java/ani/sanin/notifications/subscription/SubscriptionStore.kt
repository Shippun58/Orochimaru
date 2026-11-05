package ani.sanin.notifications.subscription

import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionStore(
    val title: String,
    val content: String,
    val mediaId: Int,
    val type: String = "SUBSCRIPTION",
    val time: Long = System.currentTimeMillis(),
    val image: String? = "",
    val banner: String? = "",

    val tmdbType: String? = null,

    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val thumbnail: String? = null,
    val durationMinutes: Int? = null,
    val airDate: String? = null,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 2L
    }
}
