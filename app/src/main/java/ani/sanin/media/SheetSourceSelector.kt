package ani.sanin.media

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import java.util.Locale
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.databinding.BottomSheetSelectorBinding
import ani.sanin.databinding.ItemStreamBinding
import ani.sanin.databinding.ItemUrlBinding
import ani.sanin.getThemeColor
import ani.sanin.ui.GlassPill
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.GlassComponent
import ani.sanin.util.GlassEffectManager

private val ROW_HOST_SEPARATOR = Regex("""\s*-\s*""")

private val TMDB_AUDIO_DUB = Regex("""(?i)\b(?:dub|dubbed|eng\s*dub|english\s*dub)\b""")
private val TMDB_AUDIO_SUB = Regex("""(?i)\b(?:sub|subbed|softsub|subtitled|subtitle|eng\s*sub|english\s*sub)\b""")

class SheetSourceSelector : DialogFragment() {
    private var _binding: BottomSheetSelectorBinding? = null
    private val binding get() = _binding!!
    private var sources: List<String> = emptyList()
    private var onSelect: ((Int) -> Unit)? = null
    private var onDismiss: (() -> Unit)? = null
    private var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null
    private var pendingSources: List<String>? = null
    private var groupedMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sources = arguments?.getStringArrayList("sources")?.toList() ?: emptyList()
        groupedMode = arguments?.getBoolean("grouped") ?: false
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            val widthPx = (resources.displayMetrics.widthPixels * 0.65f).toInt()
            w.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
            w.setDimAmount(0.5f)
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor =
                requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
        }
        GlassEffectManager.applyGlassToSheet(binding.selectorContainer, GlassComponent.SourceSelector, 16f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.selectorProgressBar.visibility = View.GONE
        binding.selectorMakeDefault.visibility = View.GONE
        binding.selectorRecyclerView.layoutManager = LinearLayoutManager(requireActivity())
        if (groupedMode) {
            val groupedAdapter = GroupedAdapter()
            binding.selectorRecyclerView.adapter = groupedAdapter
            groupedAdapter.setSources(sources)
            adapter = groupedAdapter
            bindPendingSources()
            return
        }
        val focusColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorControlHighlight)
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(64, 24, 64, 24)
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { v, hasFocus ->
                        v.setBackgroundColor(if (hasFocus) focusColor else Color.TRANSPARENT)
                    }
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val tv = holder.itemView as TextView
                val text = sources[position]
                tv.text = text
                if (text.startsWith("───")) {
                    tv.isFocusable = false
                    tv.isClickable = false
                    tv.alpha = 0.5f
                    tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                } else {
                    tv.isFocusable = true
                    tv.isClickable = true
                    tv.alpha = 1f
                    tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                    tv.setOnClickListener {
                        onSelect?.invoke(position)
                        dismissAllowingStateLoss()
                    }
                }
            }

            override fun getItemCount() = sources.size
        }
        binding.selectorRecyclerView.adapter = adapter
        bindPendingSources()
    }

    private fun bindPendingSources() {
        pendingSources?.let {
            pendingSources = null
            sources = it
            if (groupedMode) {
                (adapter as? GroupedAdapter)?.setSources(it)
            } else {
                adapter?.notifyDataSetChanged()
            }
        }
    }

    fun updateSources(newSources: List<String>) {
        if (_binding == null) {

            pendingSources = newSources
            return
        }
        sources = newSources
        if (groupedMode) {
            (adapter as? GroupedAdapter)?.setSources(newSources)
        } else {
            adapter?.notifyDataSetChanged()
        }
    }

    fun setOnSelect(cb: (Int) -> Unit) {
        onSelect = cb
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismiss?.invoke()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class GroupedAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val groupNames = mutableListOf<String>()
        private val groupRows = mutableListOf<List<Int>>()
        private var loadingMessage: String? = null

        fun setSources(rows: List<String>) {
            loadingMessage = rows.singleOrNull()?.takeIf { it.trim().startsWith("───") }
                ?.trim()?.trim('─', ' ')?.ifBlank { null }
            groupNames.clear()
            groupRows.clear()
            if (loadingMessage == null) {
                val keyToName = LinkedHashMap<String, String>()
                val keyToRows = LinkedHashMap<String, MutableList<Int>>()
                rows.forEachIndexed { index, row ->
                    val host = rowHost(row)
                    val key = host.lowercase(Locale.ROOT)
                    if (keyToRows[key] == null) {
                        keyToName[key] = host
                        keyToRows[key] = mutableListOf()
                    }
                    keyToRows[key]!!.add(index)
                }
                groupNames.addAll(keyToName.values)
                groupRows.addAll(keyToRows.values)
            }
            notifyDataSetChanged()
        }

        private fun rowHost(row: String): String {
            val sep = ROW_HOST_SEPARATOR.find(row) ?: return row.trim()
            val host = row.substring(0, sep.range.first).trim()
            return host.ifBlank { row.trim() }
        }

        override fun getItemCount(): Int = if (loadingMessage != null) 1 else groupNames.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder =
            GroupViewHolder(
                ItemStreamBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val binding = (holder as GroupViewHolder).binding
            GlassPill.applyServerCard(binding.streamCardContainer)
            if (loadingMessage != null) {
                binding.streamName.text = loadingMessage
                binding.streamMeta.visibility = View.GONE
                binding.streamLoading.visibility = View.VISIBLE
                binding.streamRecyclerView.visibility = View.GONE
                return
            }
            binding.streamName.text = groupNames[position]
            val tag = audioTagFor(groupRows[position])
            if (tag != MediaNameAdapter.SubDubType.NULL) {
                binding.streamMeta.text = tag.name
                binding.streamMeta.visibility = View.VISIBLE
            } else {
                binding.streamMeta.visibility = View.GONE
            }
            binding.streamLoading.visibility = View.GONE
            binding.streamRecyclerView.visibility = View.VISIBLE
            binding.streamRecyclerView.layoutManager = LinearLayoutManager(requireActivity())
            binding.streamRecyclerView.adapter = RowsAdapter(groupRows[position])
        }

        private inner class GroupViewHolder(val binding: ItemStreamBinding) :
            RecyclerView.ViewHolder(binding.root) {
            init {
                itemView.isFocusable = false
            }
        }
    }

    private inner class RowsAdapter(private val rowIndices: List<Int>) :
        RecyclerView.Adapter<RowsAdapter.RowViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder =
            RowViewHolder(ItemUrlBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
            val binding = holder.binding
            binding.urlQuality.text = sources[rowIndices[position]]
            binding.urlNote.visibility = View.GONE
            binding.urlSize.visibility = View.GONE
            binding.urlSub.visibility = View.GONE
            binding.urlDownload.visibility = View.GONE
            val r = 16f * holder.itemView.resources.displayMetrics.density
            val top = if (position == 0) r else 0f
            val bottom = if (position == itemCount - 1) r else 0f
            FocusEffectUtil.applyFocusListener(
                holder.itemView,
                floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom),
            )
        }

        override fun getItemCount(): Int = rowIndices.size

        private inner class RowViewHolder(val binding: ItemUrlBinding) :
            RecyclerView.ViewHolder(binding.root) {
            init {
                itemView.isFocusable = true
                itemView.setOnClickListener {
                    onSelect?.invoke(rowIndices[bindingAdapterPosition])
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    private fun audioTagFor(rowIndices: List<Int>): MediaNameAdapter.SubDubType {
        val hasDub = rowIndices.any { TMDB_AUDIO_DUB.containsMatchIn(sources[it]) }
        val hasSub = rowIndices.any { TMDB_AUDIO_SUB.containsMatchIn(sources[it]) }
        return when {
            hasDub && !hasSub -> MediaNameAdapter.SubDubType.DUB
            hasSub && !hasDub -> MediaNameAdapter.SubDubType.SUB
            else -> MediaNameAdapter.SubDubType.NULL
        }
    }

    companion object {
        fun newInstance(
            sources: ArrayList<String>,
            onSelect: (Int) -> Unit,
            onDismiss: (() -> Unit)? = null,
            grouped: Boolean = false
        ): SheetSourceSelector {
            val f = SheetSourceSelector()
            f.onSelect = onSelect
            f.onDismiss = onDismiss
            f.arguments = Bundle().apply {
                putStringArrayList("sources", sources)
                putBoolean("grouped", grouped)
            }
            return f
        }

        fun newInstanceLoading(
            message: String,
            onDismiss: (() -> Unit)? = null,
            grouped: Boolean = false
        ): SheetSourceSelector {
            val f = SheetSourceSelector()
            f.onDismiss = onDismiss
            f.arguments = Bundle().apply {
                putStringArrayList("sources", arrayListOf("─── $message ───"))
                putBoolean("grouped", grouped)
            }
            return f
        }
    }
}
