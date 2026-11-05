package ani.sanin.connections.anilist.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Staff(

    @SerialName("id") var id: Int,

    @SerialName("name") var name: StaffName?,

    @SerialName("languageV2") var languageV2: String?,

    @SerialName("image") var image: StaffImage?,

    @SerialName("description") var description: String?,

    @SerialName("primaryOccupations") var primaryOccupations: List<String>?,

    @SerialName("gender") var gender: String?,

    @SerialName("dateOfBirth") var dateOfBirth: FuzzyDate?,

    @SerialName("dateOfDeath") var dateOfDeath: FuzzyDate?,

    @SerialName("age") var age: Int?,

    @SerialName("yearsActive") var yearsActive: List<Int>?,

    @SerialName("homeTown") var homeTown: String?,

    @SerialName("bloodType") var bloodType: String?,

    @SerialName("isFavourite") var isFavourite: Boolean?,

    @SerialName("isFavouriteBlocked") var isFavouriteBlocked: Boolean?,

    @SerialName("siteUrl") var siteUrl: String?,

    @SerialName("staffMedia") var staffMedia: MediaConnection?,

    @SerialName("characters") var characters: CharacterConnection?,

    @SerialName("characterMedia") var characterMedia: MediaConnection?,

    @SerialName("staff") var staff: Staff?,

    @SerialName("submitter") var submitter: User?,

    @SerialName("submissionStatus") var submissionStatus: Int?,

    @SerialName("submissionNotes") var submissionNotes: String?,

    @SerialName("favourites") var favourites: Int?,

    @SerialName("modNotes") var modNotes: String?,
)

@Serializable
data class StaffName(
    var userPreferred: String?
)

@Serializable
data class StaffConnection(
    @SerialName("edges") var edges: List<StaffEdge>?,

    @SerialName("nodes") var nodes: List<Staff>?,

)

@Serializable
data class StaffImage(

    @SerialName("large") var large: String?,

    @SerialName("medium") var medium: String?,
) : java.io.Serializable

@Serializable
data class StaffEdge(
    var role: String?,
    var node: Staff?
)