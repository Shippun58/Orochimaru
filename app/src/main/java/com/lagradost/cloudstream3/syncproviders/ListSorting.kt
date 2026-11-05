package com.lagradost.cloudstream3.syncproviders

import androidx.annotation.StringRes
import ani.sanin.R

enum class ListSorting(@StringRes val stringRes: Int) {
    Query(R.string.none),
    RatingHigh(R.string.sort_rating_desc),
    RatingLow(R.string.sort_rating_asc),
    UpdatedNew(R.string.sort_updated_new),
    UpdatedOld(R.string.sort_updated_old),
    AlphabeticalA(R.string.sort_alphabetical_a),
    AlphabeticalZ(R.string.sort_alphabetical_z),
    ReleaseDateNew(R.string.sort_release_date_new),
    ReleaseDateOld(R.string.sort_release_date_old),
}
