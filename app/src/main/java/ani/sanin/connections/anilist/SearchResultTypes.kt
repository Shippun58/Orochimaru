package ani.sanin.connections.anilist

import ani.sanin.media.Media
import ani.sanin.media.Author
import ani.sanin.media.Studio

interface SearchResults<out T> {
    var search: String?
}

data class AniMangaSearchResults(
    var type: String = "",
    var perPage: Int? = null,
    var search: String? = null,
    var sort: String? = null,
    var isAdult: Boolean = false,
    var onList: Boolean? = null,
    var genres: MutableList<String>? = null,
    var excludedGenres: MutableList<String>? = null,
    var tags: MutableList<String>? = null,
    var excludedTags: MutableList<String>? = null,
    var status: String? = null,
    var source: String? = null,
    var format: String? = null,
    var countryOfOrigin: String? = null,
    var startYear: Int? = null,
    var seasonYear: Int? = null,
    var season: String? = null,
    var results: MutableList<Media> = mutableListOf(),
    var page: Int = 0,
    var hasNextPage: Boolean = false,
)

data class ChipItem(val text: String, val active: Boolean = true)

fun AniMangaSearchResults.toChipList(): MutableList<ChipItem> = mutableListOf()
fun AniMangaSearchResults.removeChip(chip: ChipItem) {}

data class CharacterSearchResults(
    override var search: String? = null,
    @Suppress("UNCHECKED_CAST")
    var results: MutableList<Any> = mutableListOf(),
    var page: Int = 0,
    var hasNextPage: Boolean = false,
) : SearchResults<Character>

data class StudioSearchResults(
    override var search: String? = null,
    @Suppress("UNCHECKED_CAST")
    var results: MutableList<Any> = mutableListOf(),
    var page: Int = 0,
    var hasNextPage: Boolean = false,
) : SearchResults<Studio>

data class StaffSearchResults(
    override var search: String? = null,
    @Suppress("UNCHECKED_CAST")
    var results: MutableList<Any> = mutableListOf(),
    var page: Int = 0,
    var hasNextPage: Boolean = false,
) : SearchResults<Author>

data class UserSearchResults(
    override var search: String? = null,
    @Suppress("UNCHECKED_CAST")
    var results: MutableList<Any> = mutableListOf(),
    var page: Int = 0,
    var hasNextPage: Boolean = false,
) : SearchResults<ani.sanin.profile.User>
