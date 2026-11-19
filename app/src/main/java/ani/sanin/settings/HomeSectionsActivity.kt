package ani.sanin.settings

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.ActivityHomeSectionsBinding
import ani.sanin.getThemeColor
import ani.sanin.home.HomeSectionEntry
import ani.sanin.home.HomeSectionsConfig
import ani.sanin.home.HomeSectionsMode
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HomeSectionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeSectionsBinding
    private val items = mutableListOf<HomeSectionEntry>()
    private lateinit var adapter: HomeSectionsAdapter
    private var touchHelper: ItemTouchHelper? = null
    private var mode = HomeSectionsMode.MOVIE_TV
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityHomeSectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.homeSectionsRoot.setPadding(0, statusBarHeight, 0, navBarHeight)
        binding.homeSectionsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.homeSectionsTitle.text = "Home Sections"
        binding.homeSectionsSubtitle.text = "Show, hide & reorder by mode"
        binding.homeSectionsIcon.setImageResource(R.drawable.ic_set_home)

        adapter = HomeSectionsAdapter()
        binding.homeSectionsList.layoutManager = LinearLayoutManager(this)
        binding.homeSectionsList.adapter = adapter
        setupTouchHelper()
        adapter.onDragModeChanged = { active ->
            if (active) {
                binding.homeSectionsStatus.text = "Reorder mode • drag, or up/down • right to finish"
            } else {
                updateStatus()
            }
        }

        binding.homeSectionsAnimeTab.setOnClickListener { selectMode(HomeSectionsMode.ANIME) }
        binding.homeSectionsMovieTab.setOnClickListener { selectMode(HomeSectionsMode.MOVIE_TV) }
        binding.homeSectionsRefresh.setOnClickListener { reload() }

        selectMode(HomeSectionsMode.MOVIE_TV, force = true)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    private fun selectMode(newMode: HomeSectionsMode, force: Boolean = false) {
        if (!force && mode == newMode && items.isNotEmpty()) return
        mode = newMode
        updateTabs()
        reload()
    }

    private fun updateTabs() {
        val primary = getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val idle = getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val anime = mode == HomeSectionsMode.ANIME
        binding.homeSectionsAnimeTab.setTextColor(if (anime) primary else idle)
        binding.homeSectionsAnimeTab.alpha = if (anime) 1f else 0.6f
        binding.homeSectionsMovieTab.setTextColor(if (anime) idle else primary)
        binding.homeSectionsMovieTab.alpha = if (anime) 0.6f else 1f
    }

    private fun reload() {
        loadJob?.cancel()
        val target = mode
        loadJob = lifecycleScope.launch {
            binding.homeSectionsProgress.isVisible = true
            binding.homeSectionsRefresh.isEnabled = false
            val initial = if (target == HomeSectionsMode.ANIME) {
                HomeSectionsConfig.loadAnime()
            } else {
                HomeSectionsConfig.loadMovieTv().toMutableList()
            }
            if (mode != target) return@launch
            adapter.dragActivePosition = null
            items.clear()
            items.addAll(initial)
            adapter.notifyDataSetChanged()
            updateStatus()

            val enumeration = try {
                HomeSectionsConfig.enumerate(target, this@HomeSectionsActivity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (mode != target) return@launch
            if (enumeration != null) {
                val merged = HomeSectionsConfig.merge(items.toList(), enumeration.sections, enumeration.validProviderIds)
                items.clear()
                items.addAll(merged)
                adapter.notifyDataSetChanged()
                HomeSectionsConfig.save(target, items.toList())
            }
            binding.homeSectionsProgress.isVisible = false
            binding.homeSectionsRefresh.isEnabled = true
            updateStatus()
        }
    }

    private fun updateStatus() {
        val hidden = items.count { !it.enabled }
        binding.homeSectionsStatus.text = when {
            items.isEmpty() -> "No sections found"
            hidden == 0 -> "${items.size} sections"
            else -> "${items.size} sections • $hidden hidden"
        }
    }

    private fun persist() {
        HomeSectionsConfig.save(mode, items.toList())
    }

    private fun setupTouchHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                items.add(to, items.removeAt(from))
                adapter.dragActivePosition = to
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.disarmDrag(true)
                updateStatus()
            }
        }
        touchHelper = ItemTouchHelper(callback).also {
            it.attachToRecyclerView(binding.homeSectionsList)
        }
    }

    private inner class HomeSectionsAdapter : RecyclerView.Adapter<HomeSectionsAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.homeRowTitle)
            val desc: TextView = view.findViewById(R.id.homeRowDesc)
            val switch: MaterialSwitch = view.findViewById(R.id.homeRowSwitch)
            val dragHandle: LinearLayout = view.findViewById(R.id.homeRowDragHandle)
            val up: ImageView = view.findViewById(R.id.homeRowUpArrow)
            val down: ImageView = view.findViewById(R.id.homeRowDownArrow)
        }

        var dragActivePosition: Int? = null
        var onDragModeChanged: ((Boolean) -> Unit)? = null

        private fun tintArrows(holder: VH, active: Boolean) {
            val color = holder.itemView.context.getThemeColor(
                if (active) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurface
            )
            holder.up.setColorFilter(color)
            holder.down.setColorFilter(color)
        }

        fun disarmDrag(persist: Boolean) {
            val had = dragActivePosition != null
            dragActivePosition = null
            if (persist && had) persist()
            onDragModeChanged?.invoke(false)
            notifyDataSetChanged()
        }

        private fun moveWithTint(holder: VH, from: Int, to: Int) {
            if (from !in items.indices || to !in items.indices || from == to) return
            items.add(to, items.removeAt(from))
            dragActivePosition = to
            notifyItemMoved(from, to)
            notifyItemChanged(if (from < to) from else to)
            notifyItemChanged(if (from < to) to else from)
            persist()
            updateStatus()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val view = layoutInflater.inflate(R.layout.item_home_section_row, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items.getOrNull(position) ?: return
            holder.title.text = item.title
            holder.desc.text = item.providerName
            holder.desc.visibility = View.VISIBLE

            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = item.enabled
            holder.switch.setOnCheckedChangeListener { _, checked ->
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                items[pos] = items[pos].copy(enabled = checked)
                persist()
                updateStatus()
            }

            val row = holder.itemView
            val drag = holder.dragHandle
            val switch = holder.switch

            tintArrows(holder, dragActivePosition == position)
            row.setOnClickListener {
                if (dragActivePosition == null) switch.toggle()
            }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val pos = holder.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION && dragActivePosition != pos) {
                            dragActivePosition = pos
                            tintArrows(holder, true)
                            onDragModeChanged?.invoke(true)
                        }
                        drag.post { drag.requestFocus() }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (dragActivePosition == holder.bindingAdapterPosition) disarmDrag(true)
                        switch.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER -> { switch.toggle(); true }
                    else -> false
                }
            }
            switch.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        drag.requestFocus(); true
                    }
                    else -> false
                }
            }
            drag.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (dragActivePosition == pos) {
                    disarmDrag(true)
                } else {
                    dragActivePosition = pos
                    tintArrows(holder, true)
                    onDragModeChanged?.invoke(true)
                    drag.post { drag.requestFocus() }
                }
            }
            drag.setOnLongClickListener {
                touchHelper?.startDrag(holder)
                true
            }
            drag.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                if (dragActivePosition != pos) {
                    dragActivePosition = pos
                    tintArrows(holder, true)
                    onDragModeChanged?.invoke(true)
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { moveWithTint(holder, pos, pos - 1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveWithTint(holder, pos, pos + 1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        disarmDrag(true); row.requestFocus(); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { drag.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER -> {
                        disarmDrag(true); row.requestFocus(); true
                    }
                    else -> false
                }
            }
            row.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    dragActivePosition == holder.bindingAdapterPosition
                ) {
                    touchHelper?.startDrag(holder)
                    true
                } else {
                    false
                }
            }

            FocusEffectUtil.applyFocusListener(row)
            FocusEffectUtil.applyFocusListener(drag)
        }
    }
}
