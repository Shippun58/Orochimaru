package ani.sanin.cloudstream

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil

class TmdbSuggestionsAdapter(
    private val onPick: (TmdbMedia) -> Unit,
    private val onSeeAll: (String) -> Unit,
    private val onClosed: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_SEE_ALL = 1
    }

    private var items: List<TmdbMedia> = emptyList()
    private var query: String? = null

    fun submit(list: List<TmdbMedia>, q: String) {
        items = list.take(5)
        query = q
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (position == items.size) TYPE_SEE_ALL else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SEE_ALL) {
            SeeAllViewHolder(inflater.inflate(R.layout.item_search_see_all, parent, false))
        } else {
            SuggestionViewHolder(inflater.inflate(R.layout.item_search_suggestion, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size + 1

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SuggestionViewHolder -> {
                val media = items[position]
                holder.title.text = media.displayTitle
                holder.subtitle.text = listOfNotNull(
                    media.year.takeIf { it.isNotBlank() },
                    if (media.type == "tv") "TV" else "Movie"
                ).joinToString(" · ")
                holder.poster.loadImage(Tmdb.imageUrl(media.posterPath, 200))
                holder.itemView.setOnClickListener {
                    onClosed()
                    onPick(media)
                }
            }

            is SeeAllViewHolder -> {
                val q = query ?: ""
                holder.itemView.setOnClickListener {
                    onClosed()
                    onSeeAll(q)
                }
            }
        }
    }

    class SuggestionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.searchSuggestionTitle)
        val subtitle: TextView = view.findViewById(R.id.searchSuggestionSubtitle)
        val poster: ImageView = view.findViewById(R.id.searchSuggestionPoster)

        init {
            FocusEffectUtil.applyFocusListener(view)
        }
    }

    class SeeAllViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        init {
            FocusEffectUtil.applyFocusListener(view)
        }
    }
}
