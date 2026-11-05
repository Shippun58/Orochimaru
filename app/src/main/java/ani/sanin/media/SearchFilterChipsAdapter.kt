package ani.sanin.media

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.anilist.Anilist
import ani.sanin.databinding.ItemSearchFilterChipBinding
import ani.sanin.util.FocusEffectUtil

class SearchFilterChipsAdapter(
    private var chips: List<ChipSpec> = emptyList(),
    private val onChipClicked: (ChipSpec) -> Unit
) : RecyclerView.Adapter<SearchFilterChipsAdapter.ChipViewHolder>() {

    data class ChipSpec(val key: String, val label: String)

    fun submit(list: List<ChipSpec>) {
        chips = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val binding = ItemSearchFilterChipBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        FocusEffectUtil.applyFocusListener(binding.root)
        return ChipViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        val chip = chips[position]
        holder.binding.root.text = chip.label
        holder.binding.root.setOnClickListener {
            onChipClicked(chip)
        }
    }

    override fun getItemCount(): Int = chips.size

    class ChipViewHolder(val binding: ItemSearchFilterChipBinding) :
        RecyclerView.ViewHolder(binding.root)
}

fun buildFilterChips(result: ani.sanin.connections.anilist.AniMangaSearchResults): MutableList<SearchFilterChipsAdapter.ChipSpec> {
    val chips = mutableListOf<SearchFilterChipsAdapter.ChipSpec>()
    result.sort?.let { sort ->
        val idx = Anilist.sortBy.indexOf(sort)
        val label = when (idx) {
            0 -> "Score"
            1 -> "Popular"
            2 -> "Trending"
            3 -> "Recent"
            4 -> "A-Z"
            5 -> "Z-A"
            6 -> "Peerless"
            else -> "Sort"
        }
        chips.add(SearchFilterChipsAdapter.ChipSpec("sort", label))
    }
    result.status?.let { chips.add(SearchFilterChipsAdapter.ChipSpec("status", it.replace("_", " "))) }
    result.source?.let { chips.add(SearchFilterChipsAdapter.ChipSpec("source", it.replace("_", " "))) }
    result.format?.let { chips.add(SearchFilterChipsAdapter.ChipSpec("format", it.replace("_", " "))) }
    result.countryOfOrigin?.let { chips.add(SearchFilterChipsAdapter.ChipSpec("country", it)) }
    result.season?.let { chips.add(SearchFilterChipsAdapter.ChipSpec("season", it)) }
    result.seasonYear?.let { chips.add(SearchFilterChipsAdapter.ChipSpec("seasonYear", it.toString())) }
    result.startYear?.let { chips.add(SearchFilterChipsAdapter.ChipSpec("startYear", it.toString())) }
    if (result.isAdult) chips.add(SearchFilterChipsAdapter.ChipSpec("adult", "Adult"))
    result.genres?.forEach { chips.add(SearchFilterChipsAdapter.ChipSpec("genre:$it", it)) }
    result.tags?.forEach { chips.add(SearchFilterChipsAdapter.ChipSpec("tag:$it", it)) }
    result.excludedGenres?.forEach { chips.add(SearchFilterChipsAdapter.ChipSpec("exgenre:$it", "Not $it")) }
    result.excludedTags?.forEach { chips.add(SearchFilterChipsAdapter.ChipSpec("extag:$it", "Not $it")) }
    return chips
}
