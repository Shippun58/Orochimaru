@file:Suppress("unused")

package ani.sanin.connections.anilist.api

import ani.sanin.R
import ani.sanin.currContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Media(

    @SerialName("id") var id: Int,

    @SerialName("idMal") var idMal: Int?,

    @SerialName("title") var title: MediaTitle?,

    @SerialName("type") var type: MediaType?,

    @SerialName("format") var format: MediaFormat?,

    @SerialName("status") var status: MediaStatus?,

    @SerialName("description") var description: String?,

    @SerialName("startDate") var startDate: FuzzyDate?,

    @SerialName("endDate") var endDate: FuzzyDate?,

    @SerialName("season") var season: MediaSeason?,

    @SerialName("seasonYear") var seasonYear: Int?,

    @SerialName("seasonInt") var seasonInt: Int?,

    @SerialName("episodes") var episodes: Int?,

    @SerialName("duration") var duration: Int?,

    @SerialName("chapters") var chapters: Int?,

    @SerialName("volumes") var volumes: Int?,

    @SerialName("countryOfOrigin") var countryOfOrigin: String?,

    @SerialName("isLicensed") var isLicensed: Boolean?,

    @SerialName("source") var source: MediaSource?,

    @SerialName("hashtag") var hashtag: String?,

    @SerialName("trailer") var trailer: MediaTrailer?,

    @SerialName("updatedAt") var updatedAt: Int?,

    @SerialName("coverImage") var coverImage: MediaCoverImage?,

    @SerialName("bannerImage") var bannerImage: String?,

    @SerialName("genres") var genres: List<String>?,

    @SerialName("synonyms") var synonyms: List<String>?,

    @SerialName("averageScore") var averageScore: Int?,

    @SerialName("meanScore") var meanScore: Int?,

    @SerialName("popularity") var popularity: Int?,

    @SerialName("isLocked") var isLocked: Boolean?,

    @SerialName("trending") var trending: Int?,

    @SerialName("favourites") var favourites: Int?,

    @SerialName("tags") var tags: List<MediaTag>?,

    @SerialName("relations") var relations: MediaConnection?,

    @SerialName("characters") var characters: CharacterConnection?,

    @SerialName("staffPreview") var staff: StaffConnection?,

    @SerialName("studios") var studios: StudioConnection?,

    @SerialName("producers") var producers: StudioConnection?,

    @SerialName("isFavourite") var isFavourite: Boolean?,

    @SerialName("isFavouriteBlocked") var isFavouriteBlocked: Boolean?,

    @SerialName("isAdult") var isAdult: Boolean?,

    @SerialName("nextAiringEpisode") var nextAiringEpisode: AiringSchedule?,

    @SerialName("externalLinks") var externalLinks: List<MediaExternalLink>?,

    @SerialName("streamingEpisodes") var streamingEpisodes: List<MediaStreamingEpisode>?,

    @SerialName("mediaListEntry") var mediaListEntry: MediaList?,

    @SerialName("reviews") var reviews: ReviewConnection?,

    @SerialName("recommendations") var recommendations: RecommendationConnection?,

    @SerialName("siteUrl") var siteUrl: String?,

    @SerialName("autoCreateForumThread") var autoCreateForumThread: Boolean?,

    @SerialName("isRecommendationBlocked") var isRecommendationBlocked: Boolean?,

    @SerialName("isReviewBlocked") var isReviewBlocked: Boolean?,

    @SerialName("modNotes") var modNotes: String?,
) : java.io.Serializable

@Serializable
data class MediaTitle(

    @SerialName("romaji") var romaji: String,

    @SerialName("english") var english: String?,

    @SerialName("native") var native: String?,

    @SerialName("userPreferred") var userPreferred: String,
) : java.io.Serializable

@Serializable
enum class MediaType {
    ANIME, MANGA;

    override fun toString(): String {
        return super.toString().replace("_", " ")
    }
}

@Serializable
enum class MediaStatus {
    FINISHED, RELEASING, NOT_YET_RELEASED, CANCELLED, HIATUS;

    override fun toString(): String {
        currContext()?.let {
            return when (super.toString()) {
                "FINISHED" -> it.getString(R.string.status_finished)
                "RELEASING" -> it.getString(R.string.status_releasing)
                "NOT_YET_RELEASED" -> it.getString(R.string.status_not_yet_released)
                "CANCELLED" -> it.getString(R.string.status_cancelled)
                "HIATUS" -> it.getString(R.string.status_hiatus)
                else -> ""
            }
        }
        return super.toString().replace("_", " ")
    }
}

@Serializable
data class AiringSchedule(

    @SerialName("id") var id: Int?,

    @SerialName("airingAt") var airingAt: Int?,

    @SerialName("timeUntilAiring") var timeUntilAiring: Int?,

    @SerialName("episode") var episode: Int?,

    @SerialName("mediaId") var mediaId: Int?,

    @SerialName("media") var media: Media?,
)

@Serializable
data class MediaStreamingEpisode(

    @SerialName("title") var title: String?,

    @SerialName("thumbnail") var thumbnail: String?,

    @SerialName("url") var url: String?,

    @SerialName("site") var site: String?,
) : java.io.Serializable

@Serializable
data class MediaCoverImage(

    @SerialName("extraLarge") var extraLarge: String?,

    @SerialName("large") var large: String?,

    @SerialName("medium") var medium: String?,

    @SerialName("color") var color: String?,
) : java.io.Serializable

@Serializable
data class MediaList(

    @SerialName("id") var id: Int?,

    @SerialName("userId") var userId: Int?,

    @SerialName("mediaId") var mediaId: Int?,

    @SerialName("status") var status: MediaListStatus?,

    @SerialName("score") var score: Float?,

    @SerialName("progress") var progress: Int?,

    @SerialName("progressVolumes") var progressVolumes: Int?,

    @SerialName("repeat") var repeat: Int?,

    @SerialName("priority") var priority: Int?,

    @SerialName("private") var private: Boolean?,

    @SerialName("notes") var notes: String?,

    @SerialName("hiddenFromStatusLists") var hiddenFromStatusLists: Boolean?,

    @SerialName("customLists") var customLists: Map<String, Boolean>?,

    @SerialName("startedAt") var startedAt: FuzzyDate?,

    @SerialName("completedAt") var completedAt: FuzzyDate?,

    @SerialName("updatedAt") var updatedAt: Int?,

    @SerialName("createdAt") var createdAt: Int?,

    @SerialName("media") var media: Media?,

    @SerialName("user") var user: User?
)

@Serializable
enum class MediaListStatus {
    CURRENT, PLANNING, COMPLETED, DROPPED, PAUSED, REPEATING;

    override fun toString(): String {
        return super.toString().replace("_", " ")
    }
}

@Serializable
enum class MediaSource {
    ORIGINAL, MANGA, LIGHT_NOVEL, VISUAL_NOVEL, VIDEO_GAME, OTHER, NOVEL, DOUJINSHI, ANIME, WEB_NOVEL, LIVE_ACTION, GAME, COMIC, MULTIMEDIA_PROJECT, PICTURE_BOOK;

    override fun toString(): String {
        return super.toString().replace("_", " ")
    }
}

@Serializable
enum class MediaFormat {
    TV, TV_SHORT, MOVIE, SPECIAL, OVA, ONA, MUSIC, MANGA, NOVEL, ONE_SHOT;

    override fun toString(): String {
        return super.toString().replace("_", " ")
    }
}

@Serializable
data class MediaTrailer(

    @SerialName("id") var id: String?,

    @SerialName("site") var site: String?,

    @SerialName("thumbnail") var thumbnail: String?,
)

@Serializable
data class MediaTagCollection(
    @SerialName("tags") var tags: List<MediaTag>?
)

@Serializable
data class MediaTag(

    @SerialName("id") var id: Int?,

    @SerialName("name") var name: String,

    @SerialName("description") var description: String?,

    @SerialName("category") var category: String?,

    @SerialName("rank") var rank: Int?,

    @SerialName("isGeneralSpoiler") var isGeneralSpoiler: Boolean?,

    @SerialName("isMediaSpoiler") var isMediaSpoiler: Boolean?,

    @SerialName("isAdult") var isAdult: Boolean?,

    @SerialName("userId") var userId: Int?,
)

@Serializable
data class MediaConnection(
    @SerialName("edges") var edges: List<MediaEdge>?,

    @SerialName("nodes") var nodes: List<Media>?,

    @SerialName("pageInfo") var pageInfo: PageInfo?,
)

@Serializable
data class MediaEdge(

    @SerialName("node") var node: Media?,

    @SerialName("id") var id: Int?,

    @SerialName("relationType") var relationType: MediaRelation?,

    @SerialName("isMainStudio") var isMainStudio: Boolean?,

    @SerialName("characters") var characters: List<Character>?,

    @SerialName("characterRole") var characterRole: String?,

    @SerialName("characterName") var characterName: String?,

    @SerialName("roleNotes") var roleNotes: String?,

    @SerialName("dubGroup") var dubGroup: String?,

    @SerialName("staffRole") var staffRole: String?,

    @SerialName("voiceActors") var voiceActors: List<Staff>?,

    @SerialName("favouriteOrder") var favouriteOrder: Int?,
)

@Serializable
enum class MediaRelation {
    ADAPTATION, PREQUEL, SEQUEL, PARENT, SIDE_STORY, CHARACTER, SUMMARY, ALTERNATIVE, SPIN_OFF, OTHER, SOURCE, COMPILATION, CONTAINS;

    override fun toString(): String {
        currContext()?.let {
            return when (super.toString()) {
                "ADAPTATION" -> it.getString(R.string.type_adaptation)
                "PARENT" -> it.getString(R.string.type_parent)
                "CHARACTER" -> it.getString(R.string.type_character)
                "SUMMARY" -> it.getString(R.string.type_summary)
                "ALTERNATIVE" -> it.getString(R.string.type_alternative)
                "OTHER" -> it.getString(R.string.type_other)
                "SOURCE" -> it.getString(R.string.type_source)
                "CONTAINS" -> it.getString(R.string.type_contains)
                else -> super.toString().replace("_", " ")
            }
        }
        return super.toString().replace("_", " ")
    }
}

@Serializable
enum class MediaSeason {
    WINTER, SPRING, SUMMER, FALL;
}

@Serializable
data class MediaExternalLink(

    @SerialName("id") var id: Int?,

    @SerialName("url") var url: String?,

    @SerialName("site") var site: String,

    @SerialName("siteId") var siteId: Int?,

    @SerialName("type") var type: ExternalLinkType?,

    @SerialName("language") var language: String?,

    @SerialName("color") var color: String?,

    @SerialName("icon") var icon: String?,

    @SerialName("notes") var notes: String?,
) : java.io.Serializable

@Serializable
enum class ExternalLinkType {
    INFO, STREAMING, SOCIAL;

    override fun toString(): String {
        return super.toString().replace("_", " ")
    }
}

@Serializable
data class MediaListCollection(

    @SerialName("lists") var lists: List<MediaListGroup>?,

    @SerialName("user") var user: User?,

    @SerialName("hasNextChunk") var hasNextChunk: Boolean?,

    ) : java.io.Serializable

@Serializable
data class FollowData(
    @SerialName("id") var id: Int,
    @SerialName("isFollowing") var isFollowing: Boolean,
) : java.io.Serializable

@Serializable
data class MediaListGroup(

    @SerialName("entries") var entries: List<MediaList>?,

    @SerialName("name") var name: String?,

    @SerialName("isCustomList") var isCustomList: Boolean?,

    @SerialName("isSplitCompletedList") var isSplitCompletedList: Boolean?,

    @SerialName("status") var status: MediaListStatus?,
) : java.io.Serializable

@Serializable
data class ReviewConnection(
    @SerialName("nodes") var nodes: List<Query.Review>?,
)