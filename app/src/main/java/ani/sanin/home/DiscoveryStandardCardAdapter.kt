package ani.sanin.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.cloudstream.TmdbCards
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.util.FocusEffectUtil

class DiscoveryStandardCardAdapter(
    private val cards: MutableList<Card>,
    private val onClick: (Card) -> Unit,
) : RecyclerView.Adapter<DiscoveryStandardCardAdapter.VH>() {

    data class Card(
        val id: Int,
        val title: String,
        val posterUrl: String?,
        val year: String? = null,
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = cards[position]
        val rv = holder.itemView.parent as? RecyclerView
        val span = (rv?.layoutManager as? GridLayoutManager)?.spanCount ?: 0
        val cell = if (rv != null && span > 0) TmdbCards.cellWidthPx(rv, span) else null
        TmdbCards.applyPlainCardStyle(holder.binding, item.title, item.posterUrl, cell)
        if (rv != null) {
            val gap = TmdbCards.gridRowGapPx(rv)
            holder.itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = gap }
        }
        holder.binding.tmdbCardTitle.text = item.title
        holder.binding.tmdbCardYear.text = item.year.orEmpty()
        holder.itemView.setOnClickListener { onClick(item) }
        FocusEffectUtil.applyFocusListener(holder.itemView, holder.binding.tmdbCard)
    }

    override fun getItemCount(): Int = cards.size

    class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.isFocusableInTouchMode = false
        }
    }
}
