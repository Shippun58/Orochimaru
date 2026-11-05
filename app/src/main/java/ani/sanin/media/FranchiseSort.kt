package ani.sanin.media

import android.view.View
import android.widget.RadioGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import ani.sanin.R
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

enum class FranchiseSort {
    RANDOM,
    TRENDING,
    POPULAR,
    ALPHABETICAL,

    COLLECTIONS,
    ;

    fun labelRes() = when (this) {
        RANDOM -> R.string.franchise_sort_random
        TRENDING -> R.string.franchise_sort_trending
        POPULAR -> R.string.franchise_sort_popular
        ALPHABETICAL -> R.string.franchise_sort_az
        COLLECTIONS -> R.string.franchise_sort_collections
    }

    companion object {
        fun fromName(name: String?): FranchiseSort =
            entries.firstOrNull { it.name == name } ?: RANDOM
    }
}

enum class FranchiseSortDirection {
    DESCENDING,
    ASCENDING,
    ;

    fun inverted() = if (this == DESCENDING) ASCENDING else DESCENDING

    fun labelRes() = when (this) {
        DESCENDING -> R.string.franchise_sort_descending
        ASCENDING -> R.string.franchise_sort_ascending
    }

    companion object {
        fun fromName(name: String?): FranchiseSortDirection =
            entries.firstOrNull { it.name == name } ?: DESCENDING
    }
}

data class FranchiseListPrefs(
    val sort: FranchiseSort = FranchiseSort.RANDOM,
    val direction: FranchiseSortDirection = FranchiseSortDirection.DESCENDING,

    val showSingleEntry: Boolean = false,
) {
    companion object {
        val DEFAULT = FranchiseListPrefs()
    }
}

object FranchiseSorter {

    fun apply(
        franchises: List<Franchise>,
        prefs: FranchiseListPrefs,
        shuffledIn: List<Franchise> = franchises,
    ): List<Franchise> {
        val visible = if (prefs.showSingleEntry) franchises
        else franchises.filterNot { it.isSingleEntry }

        if (prefs.sort == FranchiseSort.RANDOM) {

            val multiEntry = visible.filterNot { it.isSingleEntry }.mapTo(HashSet()) { it.name }
            return shuffledIn.sortedBy { if (it.name in multiEntry) 0 else 1 }
        }

        val natural = when (prefs.sort) {
            FranchiseSort.RANDOM -> visible

            FranchiseSort.TRENDING -> visible.sortedBy { it.trendingRank ?: Int.MAX_VALUE }

            FranchiseSort.POPULAR -> visible.sortedByDescending { it.likeCount ?: -1 }
            FranchiseSort.ALPHABETICAL -> visible.sortedBy { it.name.lowercase() }

            FranchiseSort.COLLECTIONS -> visible
        }
        return if (prefs.direction == FranchiseSortDirection.DESCENDING) natural
        else natural.reversed()
    }
}

fun Fragment.showFranchiseSortDialog(
    showCollections: Boolean = false,
    onChanged: () -> Unit,
) {
    val prefs = FranchiseListPrefs.read()
    val body = layoutInflater.inflate(R.layout.dialog_franchise_sort, null)

    val group = body.findViewById<RadioGroup>(R.id.franchiseSortGroup)
    body.findViewById<View>(R.id.franchiseSortCollections).isVisible = showCollections
    group.check(prefs.sort.radioId())
    body.findViewById<MaterialSwitch>(R.id.franchiseShowSingleEntry).apply {
        isSaveEnabled = false
        isChecked = prefs.showSingleEntry

        setOnCheckedChangeListener { _, checked ->
            PrefManager.setVal(PrefName.FranchiseShowSingleEntry, checked)
            onChanged()
        }
    }

    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.franchise_sort_dialog_title)
        .setView(body)

        .setPositiveButton(android.R.string.ok) { _, _ ->
            val chosen = FranchiseSort.entries
                .firstOrNull { it.radioId() == group.checkedRadioButtonId } ?: prefs.sort
            PrefManager.setVal(PrefName.FranchiseSortOrder, chosen.name)
            onChanged()
        }
        .show()
}

fun FranchiseListPrefs.Companion.read() = FranchiseListPrefs(
    sort = FranchiseSort.fromName(PrefManager.getVal(PrefName.FranchiseSortOrder)),
    direction = FranchiseSortDirection.fromName(PrefManager.getVal(PrefName.FranchiseSortDirectionPref)),
    showSingleEntry = PrefManager.getVal(PrefName.FranchiseShowSingleEntry),
)

private fun FranchiseSort.radioId() = when (this) {
    FranchiseSort.RANDOM -> R.id.franchiseSortRandom
    FranchiseSort.TRENDING -> R.id.franchiseSortTrending
    FranchiseSort.POPULAR -> R.id.franchiseSortPopular
    FranchiseSort.ALPHABETICAL -> R.id.franchiseSortAlphabetical
    FranchiseSort.COLLECTIONS -> R.id.franchiseSortCollections
}
