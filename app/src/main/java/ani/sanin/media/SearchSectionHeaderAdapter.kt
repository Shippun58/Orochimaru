package ani.sanin.media

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import com.google.android.material.color.MaterialColors

class SearchSectionHeaderAdapter(
    private val label: String,
    private val count: Int,
) : RecyclerView.Adapter<SearchSectionHeaderAdapter.HeaderViewHolder>() {

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_section_header, parent, false)
        return HeaderViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        val countText = count.toString()
        val title = "$label · $countText"
        val spannable = SpannableString(title)
        val primary = MaterialColors.getColor(
            holder.text,
            com.google.android.material.R.attr.colorPrimary
        )
        spannable.setSpan(
            ForegroundColorSpan(primary),
            title.length - countText.length,
            title.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        holder.text.text = spannable
    }

    override fun getItemCount(): Int = 1
}
