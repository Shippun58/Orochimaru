package ani.sanin.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.databinding.ItemTmdbExplorePageBinding

class TmdbExplorePageAdapter : RecyclerView.Adapter<TmdbExplorePageAdapter.PageViewHolder>() {

    lateinit var binding: ItemTmdbExplorePageBinding
        private set

    var onPageBound: ((ItemTmdbExplorePageBinding) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val inflated = ItemTmdbExplorePageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PageViewHolder(inflated)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        binding = holder.binding
        onPageBound?.invoke(holder.binding)
    }

    override fun getItemCount(): Int = 1

    class PageViewHolder(val binding: ItemTmdbExplorePageBinding) :
        RecyclerView.ViewHolder(binding.root)
}
