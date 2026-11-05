package ani.sanin.profile

import android.content.Intent
import android.view.View
import ani.sanin.R
import ani.sanin.databinding.ItemChartBinding
import com.github.aachartmodel.aainfographics.aachartcreator.AAChartView
import com.github.aachartmodel.aainfographics.aachartcreator.AAMoveOverEventMessageModel
import com.github.aachartmodel.aainfographics.aachartcreator.AAOptions
import com.xwray.groupie.OnItemClickListener
import com.xwray.groupie.OnItemLongClickListener
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder

class ChartItem(
    private val title: String,
    private val aaOptions: AAOptions,
    private val activity: ProfileActivity
) : BindableItem<ItemChartBinding>() {
    private lateinit var binding: ItemChartBinding
    override fun bind(viewBinding: ItemChartBinding, position: Int) {
        binding = viewBinding
        binding.typeText.text = title
        binding.chartDescription.text = descriptionFor(title)
        binding.root.visibility = View.INVISIBLE
        binding.chartView.clipToPadding = true
        val callback: AAChartView.AAChartViewCallBack = object : AAChartView.AAChartViewCallBack {

            override fun chartViewDidFinishLoad(aaChartView: AAChartView) {
                binding.root.visibility = View.VISIBLE
            }

            override fun chartViewMoveOverEventMessage(
                aaChartView: AAChartView,
                messageModel: AAMoveOverEventMessageModel
            ) {
            }
        }
        binding.chartView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        binding.chartView.callBack = callback
        binding.chartView.reload()
        binding.chartView.aa_drawChartWithChartOptions(aaOptions)
        binding.openButton.setOnClickListener {
            SingleStatActivity.chartOptions = aaOptions
            activity.startActivity(
                Intent(activity, SingleStatActivity::class.java)
            )
        }
    }

    private fun descriptionFor(title: String): String = when (title.uppercase()) {
        "SCORE FINGERPRINT" -> "How your ratings are distributed. Score, Count and Time change the measure."
        "FORMAT MIX" -> "Compare TV, films and specials using the selected measure."
        "WATCH STATUS" -> "See how your titles are split across planning, watching and completed statuses."
        "TITLE LENGTH" -> "Compare short and long titles using the selected measure."
        "RELEASE TIMELINE" -> "Explore release years and how your collection shifts over time."
        "STARTED BY YEAR" -> "See when you began titles, grouped by the year you started them."
        "GENRE RANKING" -> "Genres ranked by the selected measure. A title can count toward several genres."
        "TAG LANDSCAPE" -> "A broader view of recurring themes across your list."
        "ORIGIN COUNTRIES" -> "Compare the countries associated with titles in your list."
        "VOICE ACTOR LEADERBOARD" -> "Voice actors ranked by titles associated with them; switch the measure to compare."
        "STUDIO LEADERBOARD" -> "Studios ranked by titles in your list; titles may have multiple studios."
        "STAFF YOU FOLLOW" -> "Explore creators connected to the titles you have logged."
        else -> "Grouped by category. Use Score, Count or Time to change the measure."
    }
    override fun getLayout(): Int {
        return R.layout.item_chart
    }

    override fun initializeViewBinding(view: View): ItemChartBinding {
        return ItemChartBinding.bind(view)
    }

    override fun bind(viewHolder: GroupieViewHolder<ItemChartBinding>, position: Int) {
        viewHolder.setIsRecyclable(false)
        super.bind(viewHolder, position)
    }

    override fun bind(
        viewHolder: GroupieViewHolder<ItemChartBinding>,
        position: Int,
        payloads: MutableList<Any>
    ) {
        viewHolder.setIsRecyclable(false)
        super.bind(viewHolder, position, payloads)
    }

    override fun bind(
        viewHolder: GroupieViewHolder<ItemChartBinding>,
        position: Int,
        payloads: MutableList<Any>,
        onItemClickListener: OnItemClickListener?,
        onItemLongClickListener: OnItemLongClickListener?
    ) {
        viewHolder.setIsRecyclable(false)
        super.bind(viewHolder, position, payloads, onItemClickListener, onItemLongClickListener)
    }

    override fun getViewType(): Int {
        return 0
    }
}