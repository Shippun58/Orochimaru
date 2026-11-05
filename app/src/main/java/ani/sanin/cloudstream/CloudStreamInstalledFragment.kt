package ani.sanin.cloudstream

import android.view.MotionEvent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.databinding.ItemCsSourceInstalledBinding
import ani.sanin.settings.SearchQueryHandler
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import java.util.Locale
import ani.sanin.settings.saving.PrefName
import ani.sanin.settings.saving.PrefManager
import ani.sanin.others.svg.SvgImageLoader
import kotlinx.coroutines.launch

class CloudStreamInstalledFragment : Fragment(), SearchQueryHandler {

    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

    private val adapter = InstalledAdapter(
        onDelete = { source -> confirmDelete(source) },
        onSettings = { source -> openSettings(source) },
        onUpdate = { item -> updateSource(item) }
    )
    private var installedItems: List<InstalledItem> = emptyList()
    private var query = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        binding.allExtensionsRecyclerView.adapter = adapter
        binding.allExtensionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.absoluteAdapterPosition
                val toPosition = target.absoluteAdapterPosition
                val newList = adapter.currentList.toMutableList().apply {
                    add(toPosition, removeAt(fromPosition))
                }
                adapter.submitList(newList)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.elevation = 8f
                    viewHolder?.itemView?.translationZ = 8f
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                adapter.disarmDrag(persist = true)
                viewHolder.itemView.elevation = 0f
                viewHolder.itemView.translationZ = 0f
            }
        }
        val touchHelper = ItemTouchHelper(itemTouchHelperCallback)
        touchHelper.attachToRecyclerView(binding.allExtensionsRecyclerView)
        adapter.itemTouchHelper = touchHelper
        adapter.reorderMessage = binding.reorderMessage
        adapter.persistOrder = { ordered ->
            val orderedIds = ordered.map { it.source.id }.toSet()
            val remainder = installedItems.filterNot { it.source.id in orderedIds }
            installedItems = ordered + remainder
            CsRepos.saveOrder(installedItems.map { it.source })
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val backDisarm = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                adapter.disarmDrag(persist = true)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backDisarm)
        adapter.onDragModeChanged = { armed -> backDisarm.isEnabled = armed }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val sources = CsRepos.installed(requireContext())
        installedItems = sources.map { InstalledItem(it) }
        adapter.setItems(installedItems.filter { it.matches(query) })
        viewLifecycleOwner.lifecycleScope.launch {
            val withUpdates = sources.map { source -> source to findAvailableUpdate(source) }
            installedItems = withUpdates.map { (source, update) ->
                InstalledItem(
                    source = source,
                    hasUpdate = update != null,
                    updateVersion = update?.first?.version,
                    updateRepoUrl = update?.second
                )
            }
            adapter.setItems(installedItems.filter { it.matches(query) })
        }
    }

    private suspend fun findAvailableUpdate(source: CsInstalledSource): Pair<CsSource, String>? {
        for (repoUrl in CsRepos.repos()) {
            runCatching {
                val plugins = CsRepos.getRepoPlugins(repoUrl)
                val available = plugins.find { it.id == source.id }
                if (available != null && available.version > source.version) {
                    return available to repoUrl
                }
            }
        }
        return null
    }

    private fun updateSource(item: InstalledItem) {
        val source = item.source
        val repoUrl = item.updateRepoUrl ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val sourceAvailable = runCatching {
                CsRepos.getRepoPlugins(repoUrl).find { it.id == source.id && it.version == item.updateVersion }
            }.getOrNull()
            if (sourceAvailable == null) {
                Toast.makeText(requireContext(), "Update not found for ${source.name}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val result = runCatching { CsRepos.install(requireContext(), repoUrl, sourceAvailable) }
            if (result.isSuccess) {
                Toast.makeText(requireContext(), "${source.name} updated", Toast.LENGTH_SHORT).show()
                load()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Update failed: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openSettings(source: CsInstalledSource) {

        startActivity(CloudStreamSettingsActivity.intent(requireContext(), source.id))
    }

    private fun confirmDelete(source: CsInstalledSource) {
        requireContext().customAlertDialog().apply {
            setTitle("Remove extension")
            setMessage("Remove ${source.name} from installed extensions?")
            setPosButton("Remove") {
                CsRepos.uninstall(requireContext(), source)
                Toast.makeText(requireContext(), "${source.name} removed", Toast.LENGTH_SHORT).show()
                load()
            }
            setNegButton("Cancel")
            show()
        }
    }

    override fun updateContentBasedOnQuery(query: String?) {
        this.query = query.orEmpty()
        adapter.setItems(installedItems.filter { it.matches(this.query) })
    }

    override fun notifyDataChanged() {
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun InstalledItem.matches(q: String): Boolean {
        if (q.isBlank()) return true
        return source.name.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT)) ||
            source.type.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT))
    }

    class InstalledAdapter(
        private val onDelete: (CsInstalledSource) -> Unit,
        private val onSettings: (CsInstalledSource) -> Unit,
        private val onUpdate: (InstalledItem) -> Unit
    ) : ListAdapter<InstalledItem, InstalledAdapter.VH>(DIFF) {

        var dragActivePosition: Int? = null
        var reorderMessage: TextView? = null
        var itemTouchHelper: ItemTouchHelper? = null
        var onDragModeChanged: ((Boolean) -> Unit)? = null
        var persistOrder: ((List<InstalledItem>) -> Unit)? = null

        fun setItems(items: List<InstalledItem>) {
            submitList(items)
        }

        fun updatePref() {
            persistOrder?.invoke(currentList)
        }

        fun disarmDrag(persist: Boolean) {
            dragActivePosition = null
            reorderMessage?.visibility = View.GONE
            if (persist) updatePref()
            onDragModeChanged?.invoke(false)
            notifyDataSetChanged()
        }

        private fun tintArrows(holder: VH, active: Boolean) {
            val typedValue = android.util.TypedValue()
            holder.itemView.context.theme.resolveAttribute(
                if (active) android.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface,
                typedValue, true
            )
            holder.binding.dragUpArrow.setColorFilter(typedValue.data)
            holder.binding.dragDownArrow.setColorFilter(typedValue.data)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemCsSourceInstalledBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            val source = item.source
            holder.binding.sourceNameTextView.text = source.name
            holder.binding.sourceMetaTextView.text = "v${source.version} • ${source.type} • ${source.lang}"

            val skipIcons = PrefManager.getVal<Boolean>(PrefName.SkipExtensionIcons)
            if (!skipIcons) {
                SvgImageLoader.load(
                    holder.binding.sourceIconImageView,
                    source.iconUrl,
                    R.drawable.ic_extension,
                    R.drawable.ic_extension
                )
            }

            holder.binding.deleteImageView.setOnClickListener { onDelete(source) }
            holder.binding.settingsImageView.setOnClickListener { onSettings(source) }

            val hasUpdate = item.hasUpdate
            holder.binding.updateImageView.isVisible = hasUpdate
            if (hasUpdate) {
                holder.binding.updateImageView.setOnClickListener { onUpdate(item) }
            }

            val isDragging = dragActivePosition == position
            val ctx = holder.itemView.context
            val tintColor = if (isDragging) {
                val typedValue = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
                typedValue.data
            } else {
                val typedVal = android.util.TypedValue()
                ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedVal, true)
                typedVal.data
            }
            holder.binding.dragUpArrow.setColorFilter(tintColor)
            holder.binding.dragDownArrow.setColorFilter(tintColor)

            holder.binding.dragHandle.setOnClickListener {
                val currentPos = holder.absoluteAdapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (dragActivePosition == currentPos) {
                    disarmDrag(persist = true)
                } else {
                    dragActivePosition = currentPos
                    reorderMessage?.visibility = View.VISIBLE
                    onDragModeChanged?.invoke(true)
                    tintArrows(holder, true)
                    holder.binding.dragHandle.post { holder.binding.dragHandle.requestFocus() }
                }
            }

            holder.itemView.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    dragActivePosition == holder.absoluteAdapterPosition
                ) {
                    itemTouchHelper?.startDrag(holder)
                }
                false
            }

            holder.binding.dragHandle.setOnLongClickListener {
                val currentPos = holder.absoluteAdapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                itemTouchHelper?.startDrag(holder)
                true
            }

            holder.binding.dragHandle.setOnKeyListener { v, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val currentPos = holder.absoluteAdapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                if (dragActivePosition != currentPos) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (currentPos > 0) {
                            val newList = currentList.toMutableList()
                            val item2 = newList.removeAt(currentPos)
                            newList.add(currentPos - 1, item2)
                            dragActivePosition = currentPos - 1
                            submitList(newList)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (currentPos < currentList.size - 1) {
                            val newList = currentList.toMutableList()
                            val item2 = newList.removeAt(currentPos)
                            newList.add(currentPos + 1, item2)
                            dragActivePosition = currentPos + 1
                            submitList(newList)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        disarmDrag(persist = true)
                        false
                    }
                    else -> false
                }
            }

            FocusEffectUtil.applyFocusListener(holder.itemView)
            FocusEffectUtil.applyFocusListener(holder.binding.dragHandle)
            FocusEffectUtil.applyFocusListener(holder.binding.settingsImageView)
            FocusEffectUtil.applyFocusListener(holder.binding.deleteImageView)
            if (hasUpdate) {
                FocusEffectUtil.applyFocusListener(holder.binding.updateImageView)
            }
        }

        class VH(val binding: ItemCsSourceInstalledBinding) : RecyclerView.ViewHolder(binding.root)

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<InstalledItem>() {
                override fun areItemsTheSame(oldItem: InstalledItem, newItem: InstalledItem) =
                    oldItem.source.id == newItem.source.id

                override fun areContentsTheSame(oldItem: InstalledItem, newItem: InstalledItem) =
                    oldItem == newItem
            }
        }
    }
}

data class InstalledItem(
    val source: CsInstalledSource,
    val hasUpdate: Boolean = false,
    val updateVersion: Int? = null,
    val updateRepoUrl: String? = null
)
