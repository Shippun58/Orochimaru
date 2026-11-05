package ani.sanin.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.databinding.ItemDiscoverListRowBinding
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil

class DiscoveryListRowAdapter(
    private val cards: MutableList<DiscoveryStandardCardAdapter.Card>,
    private val onClick: (DiscoveryStandardCardAdapter.Card) -> Unit,
) : RecyclerView.Adapter<DiscoveryListRowAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDiscoverListRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = cards[position]
        holder.binding.discoverListRowBanner.loadImage(item.posterUrl)
        holder.binding.discoverListRowPoster.loadImage(item.posterUrl)
        holder.binding.discoverListRowTitle.text = item.title
        holder.binding.discoverListRowYear.text = item.year.orEmpty()
        holder.binding.discoverListRowYear.visibility =
            if (item.year.isNullOrBlank()) View.GONE else View.VISIBLE
        holder.binding.root.setOnClickListener { onClick(item) }
        FocusEffectUtil.applyFocusListener(holder.binding.root)
    }

    override fun getItemCount(): Int = cards.size

    class VH(val binding: ItemDiscoverListRowBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.isFocusable = true
            binding.root.isFocusableInTouchMode = false
        }
    }
}
