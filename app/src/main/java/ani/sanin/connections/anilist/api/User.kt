package ani.sanin.connections.anilist.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(

    @SerialName("id") var id: Int,

    @SerialName("name") var name: String?,

    @SerialName("avatar") var avatar: UserAvatar?,

    @SerialName("bannerImage") var bannerImage: String?,

    @SerialName("isFollowing") var isFollowing: Boolean?,

    @SerialName("isFollower") var isFollower: Boolean?,

    @SerialName("options") var options: UserOptions?,

    @SerialName("mediaListOptions") var mediaListOptions: MediaListOptions?,

    @SerialName("favourites") var favourites: Favourites?,

    @SerialName("statistics") var statistics: UserStatisticTypes?,

    @SerialName("unreadNotificationCount") var unreadNotificationCount: Int?,

) : java.io.Serializable

@Serializable
data class UserOptions(

    @SerialName("titleLanguage") var titleLanguage: UserTitleLanguage?,

    @SerialName("displayAdultContent") var displayAdultContent: Boolean?,

    @SerialName("airingNotifications") var airingNotifications: Boolean?,

    @SerialName("profileColor") var profileColor: String?,

    @SerialName("timezone") var timezone: String?,

    @SerialName("activityMergeTime") var activityMergeTime: Int?,

    @SerialName("staffNameLanguage") var staffNameLanguage: UserStaffNameLanguage?,

    @SerialName("restrictMessagesToFollowing") var restrictMessagesToFollowing: Boolean?,

)

@Serializable
data class UserAvatar(

    @SerialName("large") var large: String?,

    @SerialName("medium") var medium: String?,
) : java.io.Serializable

@Serializable
data class UserStatisticTypes(
    @SerialName("anime") var anime: UserStatistics?,
    @SerialName("manga") var manga: UserStatistics?
)

@Serializable
enum class UserTitleLanguage {
    @SerialName("ENGLISH")
    ENGLISH,

    @SerialName("ROMAJI")
    ROMAJI,

    @SerialName("NATIVE")
    NATIVE
}

@Serializable
enum class UserStaffNameLanguage {
    @SerialName("ROMAJI_WESTERN")
    ROMAJI_WESTERN,

    @SerialName("ROMAJI")
    ROMAJI,

    @SerialName("NATIVE")
    NATIVE
}

@Serializable
enum class ScoreFormat {
    @SerialName("POINT_100")
    POINT_100,

    @SerialName("POINT_10_DECIMAL")
    POINT_10_DECIMAL,

    @SerialName("POINT_10")
    POINT_10,

    @SerialName("POINT_5")
    POINT_5,

    @SerialName("POINT_3")
    POINT_3,
}

@Serializable
data class UserStatistics(

    @SerialName("count") var count: Int?,
    @SerialName("meanScore") var meanScore: Float?,
    @SerialName("standardDeviation") var standardDeviation: Float?,
    @SerialName("minutesWatched") var minutesWatched: Int?,
    @SerialName("episodesWatched") var episodesWatched: Int?,
    @SerialName("chaptersRead") var chaptersRead: Int?,
    @SerialName("volumesRead") var volumesRead: Int?,

)

@Serializable
data class Favourites(

    @SerialName("anime") var anime: MediaConnection?,

    @SerialName("manga") var manga: MediaConnection?,

    @SerialName("characters") var characters: CharacterConnection?,

    @SerialName("staff") var staff: StaffConnection?,

    @SerialName("studios") var studios: StudioConnection?,
)

@Serializable
data class MediaListOptions(

    @SerialName("scoreFormat") var scoreFormat: ScoreFormat?,

    @SerialName("rowOrder") var rowOrder: String?,

    @SerialName("animeList") var animeList: MediaListTypeOptions?,

    @SerialName("mangaList") var mangaList: MediaListTypeOptions?,
)

@Serializable
data class MediaListTypeOptions(

    @SerialName("sectionOrder") var sectionOrder: List<String>?,

    @SerialName("customLists") var customLists: List<String>?,

)

