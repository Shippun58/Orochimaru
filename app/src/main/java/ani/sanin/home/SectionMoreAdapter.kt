package ani.sanin.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.isTvDevice
import ani.sanin.ui.LensButtonBackground
import ani.sanin.util.FocusEffectUtil

class SectionMoreAdapter(
    private val onClick: (View) -> Unit
) : RecyclerView.Adapter<SectionMoreAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_section_more, parent, false)
        val button = view.findViewById<View>(R.id.sectionMoreButton)
        LensButtonBackground.apply(button)

        if (isTvDevice(parent.context)) {
            FocusEffectUtil.applyFocusListener(button)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val button = holder.itemView.findViewById<View>(R.id.sectionMoreButton)
        button.setOnClickListener { onClick(it) }

        holder.itemView.post {
            val rv = holder.itemView.parent as? RecyclerView ?: return@post
            val target = rv.height
            if (target <= 0) return@post
            val lp = holder.itemView.layoutParams
            if (lp != null && lp.height != target) {
                lp.height = target
                holder.itemView.layoutParams = lp
            }
        }
    }

    override fun getItemCount(): Int = 1
}