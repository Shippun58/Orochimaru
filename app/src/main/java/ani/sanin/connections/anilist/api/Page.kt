package ani.sanin.connections.anilist.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Page(

    @SerialName("pageInfo") var pageInfo: PageInfo?,

    @SerialName("users") var users: List<User>?,

    @SerialName("media") var media: List<Media>?,

    @SerialName("characters") var characters: List<Character>?,

    @SerialName("staff") var staff: List<Staff>?,

    @SerialName("studios") var studios: List<Studio>?,

    @SerialName("mediaList") var mediaList: List<MediaList>?,

    @SerialName("airingSchedules") var airingSchedules: List<AiringSchedule>?,

    @SerialName("followers") var followers: List<User>?,

    @SerialName("following") var following: List<User>?,

    @SerialName("recommendations") var recommendations: List<Recommendation>?,

    @SerialName("likes") var likes: List<User>?,
)

@Serializable
data class PageInfo(

    @SerialName("total") var total: Int?,

    @SerialName("perPage") var perPage: Int?,

    @SerialName("currentPage") var currentPage: Int?,

    @SerialName("lastPage") var lastPage: Int?,

    @SerialName("hasNextPage") var hasNextPage: Boolean?,
)