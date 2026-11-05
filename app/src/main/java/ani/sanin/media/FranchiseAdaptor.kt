package ani.sanin.media

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.databinding.ItemFranchisePosterBinding
import ani.sanin.databinding.ItemMediaFranchiseBinding
import ani.sanin.loadImage
import ani.sanin.setSafeOnClickListener
import ani.sanin.util.FocusEffectUtil

class FranchiseAdaptor(
    private val onBannerClick: (Franchise, View) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<FranchiseAdaptor.FranchiseViewHolder>() {

    private val franchises = ArrayList<Franchise>()

    private val allFranchises = ArrayList<Franchise>()

    private var query: String = ""

    private val lock = Any()

    private val seedsInFlight = LinkedHashSet<Int>()
    private val seedsSettled = HashSet<Int>()

    fun hasCardFor(seed: Int) = synchronized(lock) { seed in seedsSettled }

    fun isPending(seed: Int) = synchronized(lock) { seed in seedsInFlight }

    fun markPending(seeds: List<Int>): List<Int> = synchronized(lock) {
        seeds.filter { it !in seedsSettled && seedsInFlight.add(it) }
    }

    fun markDone(seeds: List<Int>) = synchronized(lock) {
        seeds.forEach {
            seedsInFlight -= it
            seedsSettled += it
        }
    }

    fun clear() = synchronized(lock) {
        seedsInFlight.clear()
        seedsSettled.clear()
        franchises.clear()
        allFranchises.clear()
        query = ""
        notifyDataSetChanged()
    }

    fun submit(newFranchises: List<Franchise>) {
        synchronized(lock) {
            allFranchises.clear()
            allFranchises.addAll(newFranchises)
        }
        publish()
    }

    fun setQuery(value: String) {
        val trimmed = value.trim()
        if (query == trimmed) return
        query = trimmed
        publish()
    }

    private fun publish() {
        val previous = synchronized(lock) { franchises.toList() }
        val next = synchronized(lock) { allFranchises.filter { matchesQuery(it) } }
        if (previous == next) return
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = previous[oldPos].name
                .equals(next[newPos].name, ignoreCase = true)

            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                previous[oldPos] == next[newPos]
        })
        synchronized(lock) {
            franchises.clear()
            franchises.addAll(next)
        }

        diff.dispatchUpdatesTo(this)
    }

    private fun matchesQuery(card: Franchise): Boolean {
        if (query.isBlank()) return true
        val name = normalise(card.name)
        val wanted = normalise(query)
        if (wanted.isEmpty()) return true
        if (name.contains(wanted)) return true
        return name.split(' ').any { it.startsWith(wanted) }
    }

    private fun normalise(value: String) = value.lowercase()
        .replace(NON_WORD, " ")
        .trim()
        .replace(WHITESPACE, " ")

    fun isSearchMissed(): Boolean = synchronized(lock) {
        query.isNotBlank() && allFranchises.isNotEmpty() && franchises.isEmpty()
    }

    fun currentCards(): List<Franchise> = synchronized(lock) { allFranchises.toList() }

    fun upsert(replacements: Map<String, Franchise>) {
        if (replacements.isEmpty()) return
        synchronized(lock) {
            val at = HashMap<String, Int>()
            allFranchises.forEachIndexed { index, card -> at.putIfAbsent(card.name.lowercase(), index) }
            val merged = ArrayList(allFranchises)
            replacements.forEach { (provisionalName, card) ->
                val position = at.remove(provisionalName.lowercase())
                val key = card.name.lowercase()
                if (position == null) {
                    if (at.putIfAbsent(key, merged.size) == null) {
                        merged.add(card)
                    }
                } else {
                    at.putIfAbsent(key, position)

                    if (merged[position] != card) {
                        merged[position] = card
                    }
                }
            }
            allFranchises.clear()
            allFranchises.addAll(merged)
        }

        publish()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = FranchiseViewHolder(
        ItemMediaFranchiseBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
    )

    override fun getItemCount() = franchises.size

    override fun onBindViewHolder(holder: FranchiseViewHolder, position: Int) =
        holder.bind(franchises[position])

    inner class FranchiseViewHolder(
        private val binding: ItemMediaFranchiseBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {

            binding.root.isFocusableInTouchMode = false
            binding.franchiseBanner.isFocusable = false
            binding.franchiseBanner.isFocusableInTouchMode = false
            binding.posterRow.isFocusable = false
            binding.posterRow.isFocusableInTouchMode = false
            FocusEffectUtil.applyFocusListener(binding.root)
            binding.root.setSafeOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onBannerClick(franchises[position], binding.franchiseBanner)
                }
            }
        }

        fun bind(franchise: Franchise) {
            binding.franchiseTitle.text = franchise.name
            binding.franchiseBanner.loadImage(franchise.bannerUrl)

            val row = binding.posterRow
            row.removeAllViews()
            row.post { fillRow(row, franchise) }
        }

        private fun fillRow(row: ViewGroup, franchise: Franchise) {
            val available = row.width
            if (available <= 0) return

            if (row.height <= 0) return

            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            val current = synchronized(lock) { franchises.getOrNull(position) }
            if (current !== franchise) return

            val gap = GAP_DP.dpToPx(row)
            val cells = cellCount(available, row)
            if (cells <= 0) return

            var cellWidth = ((available - gap * (cells - 1)) / cells).toInt()
            if (cellWidth <= 0) return

            val inflater = LayoutInflater.from(row.context)
            val band = binding.franchiseBand

            val titleSpace = (band.height - row.height).coerceAtLeast(0)

            val yearHeight = measureYearHeight(inflater, row)
            val posterHeight = (row.height - yearHeight).coerceAtLeast(0)

            var bandHeight = band.height +
                (cellWidth * POSTER_HEIGHT_PARTS / POSTER_WIDTH_PARTS - posterHeight)

            val cap = (row.resources.displayMetrics.heightPixels * MAX_BAND_FRACTION).toInt()
            if (bandHeight > cap) {

                bandHeight = cap
                val cappedPoster = (cap - titleSpace - yearHeight).coerceAtLeast(0)
                cellWidth = cappedPoster * POSTER_WIDTH_PARTS / POSTER_HEIGHT_PARTS
            }
            band.updateLayoutParams<FrameLayout.LayoutParams> {
                height = bandHeight
            }

            for (entry in franchise.entries.take(cells)) {
                val cell: ItemFranchisePosterBinding = ItemFranchisePosterBinding.inflate(
                    inflater,
                    row,
                    false
                )
                cell.posterYear.text = entry.year
                cell.posterImage.loadImage(entry.posterUrl)

                cell.root.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = cellWidth
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    marginEnd = gap
                }
                row.addView(cell.root)
            }
        }

        private fun measureYearHeight(inflater: LayoutInflater, row: ViewGroup): Int {
            val cell = ItemFranchisePosterBinding.inflate(inflater, row, false)
            cell.posterYear.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            return cell.posterYear.measuredHeight
        }

        private fun cellCount(available: Int, row: View): Int {
            val target = TARGET_CELL_DP.dpToPx(row)
            if (target <= 0) return 0
            return (available / target).coerceAtLeast(MIN_CELLS)
        }
    }

    private fun Int.dpToPx(view: View) =
        (this * view.resources.displayMetrics.density).toInt()

    companion object {

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        private val WHITESPACE = Regex(" +")

        const val GAP_DP = 1

        const val MIN_CELLS = 2

        const val TARGET_CELL_DP = 73

        const val POSTER_WIDTH_PARTS = 2

        const val POSTER_HEIGHT_PARTS = 3

        const val MAX_BAND_FRACTION = 0.45
    }
}

fun RecyclerView.onNearEnd(threshold: Int = 2, onNearEnd: () -> Unit) {
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0) return

            val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val last = manager.findLastVisibleItemPosition()
            val end = manager.itemCount - 1
            if (last != RecyclerView.NO_POSITION && end > 0 && last >= end - threshold) {
                onNearEnd()
            }
        }
    })
}
