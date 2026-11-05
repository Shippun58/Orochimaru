package ani.sanin.media

import ani.sanin.R

enum class FranchiseType {

    SEQUENCE,

    TV,
    TV_SHORT,
    ONA,
    OVA,
    SPECIAL,
    MOVIE,
    SIDE_STORY,
    SPIN_OFF,
    ;

    fun labelRes() = when (this) {
        SEQUENCE -> R.string.franchise_filter_series
        TV -> R.string.franchise_filter_tv
        TV_SHORT -> R.string.franchise_filter_tv_short
        ONA -> R.string.franchise_filter_ona
        OVA -> R.string.franchise_filter_ova
        SPECIAL -> R.string.franchise_filter_special
        MOVIE -> R.string.franchise_filter_movie
        SIDE_STORY -> R.string.franchise_filter_side_story
        SPIN_OFF -> R.string.franchise_filter_spin_off
    }

    fun countRes() = when (this) {
        SEQUENCE -> R.plurals.franchise_count_series
        TV -> R.plurals.franchise_count_tv
        TV_SHORT -> R.plurals.franchise_count_tv_short
        ONA -> R.plurals.franchise_count_ona
        OVA -> R.plurals.franchise_count_ova
        SPECIAL -> R.plurals.franchise_count_special
        MOVIE -> R.plurals.franchise_count_movies
        SIDE_STORY -> R.plurals.franchise_count_side_story
        SPIN_OFF -> R.plurals.franchise_count_spin_off
    }

    companion object {

        fun fromFormat(format: String?): FranchiseType? = when (format) {
            "TV" -> TV
            "TV_SHORT" -> TV_SHORT
            "ONA" -> ONA
            "OVA" -> OVA
            "SPECIAL" -> SPECIAL
            "MOVIE" -> MOVIE
            "ONE_SHOT" -> SIDE_STORY
            else -> null
        }

        fun fromRelation(relation: String?): FranchiseType? = when (relation) {
            "SPIN_OFF" -> SPIN_OFF
            "SIDE_STORY" -> SIDE_STORY
            "PREQUEL", "SEQUEL", "PARENT", "SUMMARY", "COMPILATION", "ALTERNATIVE" -> SEQUENCE
            else -> null
        }
    }
}

data class FranchiseEntry(

    val year: String,

    val sortYear: Int? = year.toIntOrNull(),

    val posterUrl: String?,

    val title: String,

    val backdropUrl: String? = null,

    val anilistId: Int? = null,

    val airDate: String? = null,

    val airDateSortKey: Int? = null,

    val durationMinutes: Int? = null,

    val score: Int? = null,

    val type: FranchiseType? = null,

    val synopsis: String? = null,

    val tmdbId: Int? = null,

    val traktId: Int? = null,

    val listStatus: String? = null,

    val tmdbType: String? = null,

    val seasonNumber: Int? = null,
) : java.io.Serializable

data class Franchise(

    val name: String,

    val bannerUrl: String?,

    val logoUrl: String? = null,

    val entries: List<FranchiseEntry>,

    val isSingleEntry: Boolean = entries.size <= 1,

    val trendingRank: Int? = null,

    val likeCount: Int? = null,
) : java.io.Serializable {
    val latest: FranchiseEntry? get() = entries.lastOrNull()

    val sortOrder: List<FranchiseEntry>
        get() = entries.sortedWith(
            compareBy(
                { it.airDateSortKey ?: (it.sortYear?.let { year -> year * 10000 } ?: Int.MAX_VALUE) },
                { it.sortYear ?: Int.MAX_VALUE }
            )
        )
}
