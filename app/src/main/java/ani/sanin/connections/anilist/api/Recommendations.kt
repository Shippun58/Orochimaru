package ani.sanin.connections.anilist.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Recommendation(

    @SerialName("id") var id: Int?,

    @SerialName("rating") var rating: Int?,

    @SerialName("media") var media: Media?,

    @SerialName("mediaRecommendation") var mediaRecommendation: Media?,

    @SerialName("user") var user: User?,
)

@Serializable
data class RecommendationConnection(

    @SerialName("nodes") var nodes: List<Recommendation>?,

)