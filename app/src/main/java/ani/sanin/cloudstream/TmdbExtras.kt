package ani.sanin.cloudstream

import ani.sanin.connections.tmdb.Tmdb

/** TMDB extras parsing/resolution shared by TmdbServersActivity and the player server rail. */
object TmdbExtras {

    fun parseStremioTarget(url: String?): Pair<String, String>? {
        if (url == null || !url.startsWith("stremio://", ignoreCase = true)) return null
        val segments = android.net.Uri.parse(url).pathSegments
        val rawId = segments.getOrNull(2) ?: return null
        val baseId = rawId.substringBefore(':')
        return when {
            baseId.startsWith("tt") -> {
                val type = segments.getOrNull(1) ?: return null
                (if (type.equals("series", true) || type.equals("tv", true)) "tv" else "movie") to baseId
            }
            baseId.startsWith("tmdb") -> {
                val numericId = baseId.substringAfter("tmdb:", "")
                    .takeIf { it.toIntOrNull() != null }
                    ?: return null
                val type = segments.getOrNull(1) ?: return null
                (if (type.equals("series", true) || type.equals("tv", true)) "tv" else "movie") to numericId
            }
            else -> null
        }
    }

    data class ExtraDescription(
        val releaseDate: String? = null,
        val rating: Double? = null,
        val genres: List<String> = emptyList(),
        val popularity: Double? = null,
        val overview: String? = null,
    ) {
        val isTemplateMatch: Boolean
            get() = releaseDate != null || rating != null || popularity != null
    }

    private val extraReleaseRx = Regex("Release(?:d)?\\s*:?\\s*(\\d{4}-\\d{1,2}-\\d{1,2})")
    private val extraRatingRx = Regex("Rating\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:/\\s*10|%)?")
    private val extraPopularityRx = Regex("Popularity\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)")
    private val extraOverviewRx = Regex("Overview\\s*:?\\s*", RegexOption.IGNORE_CASE)
    private val extraGenreSegmentRx = Regex("Genre\\s*:?\\s*([^\\n📈📖◎★]+)", RegexOption.IGNORE_CASE)

    fun parseExtraDescription(text: String?): ExtraDescription? {
        if (matchesTorrentQualityText(text)) return null
        val release = extraReleaseRx.find(text ?: return null)?.groupValues?.getOrNull(1)
        val rating = extraRatingRx.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val popularity = extraPopularityRx.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val overview = extraOverviewRx.find(text)?.let { m ->
            text.substring(m.range.last + 1).trim().takeIf { it.isNotBlank() }
        }
        val genres = extraGenreSegmentRx.find(text)?.groupValues?.getOrNull(1)
            ?.split("|", ",")
            ?.mapNotNull { part ->
                part.replace(Regex("[^\\p{L}\\p{Nd}'\\- ]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .takeIf { it.length > 1 }
            }
            .orEmpty()
        if (release == null && rating == null && popularity == null && genres.isEmpty() && overview == null) {
            return null
        }
        return ExtraDescription(release, rating, genres, popularity, overview)
    }

    private fun matchesTorrentQualityText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val hasStructuredExtra = extraReleaseRx.containsMatchIn(text) ||
            extraRatingRx.containsMatchIn(text) ||
            extraPopularityRx.containsMatchIn(text)
        if (hasStructuredExtra) return false
        return containsStreamQualityTokens(text)
    }

    private fun containsStreamQualityTokens(text: String): Boolean = listOf(
        "HDR", "HDR10+", "DV ", "DoVi", "Blocks", "⚠️", "👤", "⚙️",
        ".mkv", ".mp4", "Torrent", "WebRip", "WEB-DL", "BluRay", "1080p", "2160p", "720p", "4k",
    ).any { token ->
        text.contains(token, ignoreCase = true)
    }

    suspend fun resolveExtraMedia(
        link: TmdbStreamResolver.StreamResult.PlayableLink,
        fallbackType: String,
    ): TmdbMedia? {
        val desc = parseExtraDescription(link.rawDetails ?: link.details)
        val target = parseStremioTarget(link.externalUrl)
        val addonTitle = link.label.trim().takeIf { it.isNotBlank() }
        val mediaType = target?.first ?: fallbackType
        val tmdb = when {
            target != null && target.second.startsWith("tt") ->
                Tmdb.findByImdb(target.second, target.first)?.copy(mediaType = target.first)
            target != null -> resolveTmdbTargetMedia(target.first, target.second.toIntOrNull() ?: 0)
            !addonTitle.isNullOrBlank() -> {
                val results = runCatching { Tmdb.search(addonTitle) }.getOrDefault(emptyList())
                val typed = results.filter { it.type == mediaType }
                typed.ifEmpty { results }.firstOrNull()
            }
            else -> null
        }
        if (tmdb == null && addonTitle == null) return null
        return TmdbMedia(
            id = tmdb?.id ?: 0,
            title = tmdb?.title ?: (if (mediaType == "movie") addonTitle else null),
            name = tmdb?.name ?: (if (mediaType == "tv") addonTitle else null),
            posterPath = tmdb?.posterPath,
            backdropPath = tmdb?.backdropPath,
            voteAverage = desc?.rating?.takeIf { it > 0 } ?: (tmdb?.voteAverage ?: 0.0),
            popularity = desc?.popularity?.takeIf { it > 0 } ?: (tmdb?.popularity ?: 0.0),
            releaseDate = desc?.releaseDate ?: tmdb?.releaseDate,
            firstAirDate = tmdb?.firstAirDate,
            overview = desc?.overview ?: tmdb?.overview,
            genreIds = tmdb?.genreIds ?: emptyList(),
            mediaType = mediaType,
            imdbId = target?.second?.takeIf { it.startsWith("tt") },
        )
    }

    private suspend fun resolveTmdbTargetMedia(type: String, id: Int): TmdbMedia? {
        if (id <= 0) return null
        val d = Tmdb.detail(type, id) ?: return null
        return TmdbMedia(
            id = d.id,
            title = if (type == "movie") d.title else null,
            name = if (type == "tv") d.name else null,
            posterPath = d.posterPath,
            backdropPath = d.backdropPath,
            voteAverage = d.voteAverage,
            popularity = d.popularity,
            releaseDate = d.releaseDate,
            firstAirDate = d.firstAirDate,
            overview = d.overview,
            mediaType = type,
        )
    }
}
