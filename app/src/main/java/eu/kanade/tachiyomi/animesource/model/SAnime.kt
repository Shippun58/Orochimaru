@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

interface SAnime : Serializable {

    var url: String

    var title: String

    var artist: String?

    var author: String?

    var description: String?

    var genre: String?

    var status: Int

    var thumbnail_url: String?

    var background_url: String?

    var update_strategy: AnimeUpdateStrategy

    var fetch_type: FetchType

    var season_number: Double

    var memo: JsonObject

    var initialized: Boolean

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct()
    }

    fun copy() = create().also {
        it.url = url
        it.title = title
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre
        it.status = status
        it.thumbnail_url = thumbnail_url
        it.background_url = background_url
        it.update_strategy = update_strategy
        it.fetch_type = fetch_type
        it.season_number = season_number
        it.memo = memo
        it.initialized = initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6
        const val UPCOMING = 7

        fun create(): SAnime {
            return SAnimeImpl()
        }
    }
}

fun SAnime.copy(
    url: String = this.url,
    title: String = this.title,
    artist: String? = this.artist,
    author: String? = this.author,
    description: String? = this.description,
    genre: String? = this.genre,
    status: Int = this.status,
    thumbnail_url: String? = this.thumbnail_url,
    fetch_type: FetchType = this.fetch_type,
    season_number: Double = this.season_number,
    memo: JsonObject = this.memo,
    initialized: Boolean = this.initialized,
) = SAnime.create().also {
    it.url = url
    it.title = title
    it.artist = artist
    it.author = author
    it.description = description
    it.genre = genre
    it.status = status
    it.thumbnail_url = thumbnail_url
    it.background_url = this@copy.background_url
    it.update_strategy = this@copy.update_strategy
    it.fetch_type = fetch_type
    it.season_number = season_number
    it.memo = memo
    it.initialized = initialized
}

fun SAnime.copyFrom(other: SAnime) {
    if (other.author != null) author = other.author
    if (other.artist != null) artist = other.artist
    if (other.description != null) description = other.description
    if (other.genre != null) genre = other.genre
    if (other.thumbnail_url != null) thumbnail_url = other.thumbnail_url
    if (other.background_url != null) background_url = other.background_url
    status = other.status
    update_strategy = other.update_strategy
    fetch_type = other.fetch_type
    season_number = other.season_number

    if (other.memo.isNotEmpty()) memo = other.memo
    initialized = other.initialized
}

@Suppress("UNUSED")
class SAnimeEpisodeUpdate(val anime: SAnime, val episodes: List<SEpisode>)

@Suppress("UNUSED")
class SAnimeSeasonUpdate(val anime: SAnime, val seasons: List<SAnime>)
