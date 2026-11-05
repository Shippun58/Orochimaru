package ani.sanin.connections.anilist.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Character(

    @SerialName("id") var id: Int,

    @SerialName("name") var name: CharacterName?,

    @SerialName("image") var image: CharacterImage?,

    @SerialName("description") var description: String?,

    @SerialName("gender") var gender: String?,

    @SerialName("dateOfBirth") var dateOfBirth: FuzzyDate?,

    @SerialName("age") var age: String?,

    @SerialName("bloodType") var bloodType: String?,

    @SerialName("isFavourite") var isFavourite: Boolean?,

    @SerialName("isFavouriteBlocked") var isFavouriteBlocked: Boolean?,

    @SerialName("siteUrl") var siteUrl: String?,

    @SerialName("media") var media: MediaConnection?,

    @SerialName("favourites") var favourites: Int?,

    @SerialName("modNotes") var modNotes: String?,
) : java.io.Serializable

@Serializable
data class CharacterConnection(
    @SerialName("edges") var edges: List<CharacterEdge>?,

    @SerialName("nodes") var nodes: List<Character>?,

    @SerialName("pageInfo") var pageInfo: PageInfo?,
) : java.io.Serializable

@Serializable
data class CharacterEdge(
    @SerialName("node") var node: Character?,

    @SerialName("id") var id: Int?,

    @SerialName("role") var role: String?,

    @SerialName("name") var name: String?,

    @SerialName("voiceActors") var voiceActors: List<Staff>?,

    @SerialName("media") var media: List<Media>?,

    @SerialName("favouriteOrder") var favouriteOrder: Int?,
) : java.io.Serializable

@Serializable
data class CharacterName(

    @SerialName("first") var first: String?,

    @SerialName("middle") var middle: String?,

    @SerialName("last") var last: String?,

    @SerialName("full") var full: String?,

    @SerialName("native") var native: String?,

    @SerialName("alternative") var alternative: List<String>?,

    @SerialName("alternativeSpoiler") var alternativeSpoiler: List<String>?,

    @SerialName("userPreferred") var userPreferred: String?,
) : java.io.Serializable

@Serializable
data class CharacterImage(

    @SerialName("large") var large: String?,

    @SerialName("medium") var medium: String?,
) : java.io.Serializable