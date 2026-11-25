package ani.sanin.plugins.nuvio

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.copyToClipboard
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.databinding.ItemNuvioSourceInstalledBinding
import ani.sanin.others.svg.SvgImageLoader
import ani.sanin.plugins.nuvio.runtime.NuvioRuntime
import ani.sanin.settings.ExtensionsActivity
import ani.sanin.settings.RepoCardAdapter
import ani.sanin.settings.RepoUi
import ani.sanin.settings.SearchQueryHandler
import ani.sanin.settings.githubOwnerAvatar
import ani.sanin.toast
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun showNuvioSettings(context: Context, scraper: NuvioScraper, layoutJson: String) {
    val json = Json { ignoreUnknownKeys = true }
    val layout = runCatching { json.parseToJsonElement(layoutJson) as? JsonArray }.getOrNull()
        ?: JsonArray(emptyList())
    if (layout.isEmpty()) {
        toast("${scraper.name} has no configurable settings")
        return
    }
    val saved = runCatching {
        json.parseToJsonElement(NuvioStorage.loadSettings(scraper.id)) as? JsonObject
    }.getOrNull() ?: JsonObject(emptyMap())
    val values = saved.toMutableMap()
    val density = context.resources.displayMetrics.density

    fun stringValue(value: JsonElement?): String? = (value as? JsonPrimitive)?.content
    fun boolValue(value: JsonElement?): Boolean = stringValue(value)?.toBoolean() ?: false

    fun makeLabel(text: String) = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
    }

    fun makeDescription(text: String) = TextView(context).apply {
        this.text = text
        textSize = 12f
        alpha = 0.72f
    }

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
    }
    val scroll = ScrollView(context).apply { addView(container) }

    fun addField(view: View, topDp: Int = 12) {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = (topDp * density).toInt()
        container.addView(view, params)
    }

    layout.forEach { element ->
        val field = element as? JsonObject ?: return@forEach
        val type = stringValue(field["type"]) ?: "info"
        val key = stringValue(field["key"]).orEmpty()
        val label = stringValue(field["label"]).orEmpty()
        val description = stringValue(field["description"])
        when (type) {
            "header" -> addField(makeLabel(label).apply {
                textSize = 16f
            }, 16)
            "info" -> addField(makeDescription(label), 10)
            "text" -> {
                addField(makeLabel(label))
                val defaultValue = stringValue(field["defaultValue"]).orEmpty()
                val isPassword = boolValue(field["isPassword"])
                val input = EditText(context).apply {
                    hint = stringValue(field["placeholder"]).orEmpty()
                    setText(stringValue(values[key]) ?: defaultValue)
                    if (isPassword) {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                }
                input.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        values[key] = JsonPrimitive(s?.toString().orEmpty())
                    }
                })
                addField(input, 4)
                description?.let { addField(makeDescription(it), 2) }
            }
            "select" -> {
                addField(makeLabel(label))
                val options = field["options"] as? JsonArray ?: JsonArray(emptyList())
                val labels = options.map { option ->
                    stringValue((option as? JsonObject)?.get("label")).orEmpty()
                }.toTypedArray()
                val optionValues = options.map { option ->
                    stringValue((option as? JsonObject)?.get("value")).orEmpty()
                }
                var selectedIndex = optionValues.indexOf(stringValue(values[key]))
                    .takeIf { it >= 0 }
                    ?: optionValues.indexOf(stringValue(field["defaultValue"])).let { if (it < 0) 0 else it }
                val button = Button(context).apply {
                    isAllCaps = false
                    text = labels.getOrNull(selectedIndex).takeUnless { it.isNullOrBlank() } ?: "Select option"
                }
                button.setOnClickListener {
                    context.customAlertDialog().apply {
                        setTitle(label)
                        singleChoiceItems(labels, selectedIndex) { selected ->
                            selectedIndex = selected
                            optionValues.getOrNull(selected)?.let { values[key] = JsonPrimitive(it) }
                            button.text = labels.getOrNull(selected) ?: "Select option"
                        }
                        setNegButton("Cancel")
                        show()
                    }
                }
                addField(button, 4)
                description?.let { addField(makeDescription(it), 2) }
            }
            "toggle" -> {
                val toggle = Switch(context).apply {
                    text = label
                    isChecked = values[key]?.let { boolValue(it) }
                        ?: boolValue(field["defaultValue"])
                }
                toggle.setOnCheckedChangeListener { _, checked -> values[key] = JsonPrimitive(checked) }
                addField(toggle, 8)
                description?.let { addField(makeDescription(it), 2) }
            }
        }
    }

    context.customAlertDialog().apply {
        setTitle("${scraper.name} Settings")
        setCustomView(scroll)
        setPosButton("Save") {
            NuvioStorage.saveSettings(scraper.id, JsonObject(values).toString())
            toast("Settings saved")
        }
        setNegButton("Cancel")
        show()
    }
}

class NuvioInstalledFragment : Fragment(), SearchQueryHandler {
    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!
    private var items: List<NuvioScraper> = emptyList()
    private var query = ""

    private val adapter = NuvioInstalledAdapter(
        onInfo = { showInfo(it) },
        onSettings = { openSettings(it) },
        onDelete = { confirmDelete(it) },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        binding.allExtensionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.allExtensionsRecyclerView.adapter = adapter
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled() = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                adapter.move(viewHolder.absoluteAdapterPosition, target.absoluteAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.elevation = 8f
                    viewHolder?.itemView?.translationZ = 8f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.disarmDrag()
                viewHolder.itemView.elevation = 0f
                viewHolder.itemView.translationZ = 0f
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.allExtensionsRecyclerView)
        adapter.itemTouchHelper = itemTouchHelper
        adapter.reorderMessage = binding.reorderMessage
        adapter.persistOrder = { ordered -> NuvioStorage.saveOrder(ordered.map { it.id }) }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val backDisarm = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = adapter.disarmDrag()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backDisarm)
        adapter.onDragModeChanged = { armed -> backDisarm.isEnabled = armed }
    }

    override fun onResume() {
        super.onResume()
        loadItems()
    }

    private fun loadItems() {
        items = NuvioRepository.installed(requireContext())
        adapter.submitList(items.filter { it.matches(query) })
    }

    private fun openSettings(scraper: NuvioScraper) {
        viewLifecycleOwner.lifecycleScope.launch {
            val layout = runCatching { NuvioRuntime.settingsLayout(scraper) }.getOrDefault("[]")
            if (isAdded) showNuvioSettings(requireContext(), scraper, layout)
        }
    }

    private fun showInfo(scraper: NuvioScraper) {
        requireContext().customAlertDialog().apply {
            setTitle(scraper.name)
            setMessage(buildString {
                append("v${scraper.version}\n")
                append(scraper.supportedTypes.joinToString(" / ").replaceFirstChar { it.uppercase() })
                if (scraper.contentLanguage.isNotEmpty()) append("\n${scraper.contentLanguage.joinToString(", ").uppercase()}")
                scraper.author?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
                scraper.description.takeIf { it.isNotBlank() }?.let { append("\n\n$it") }
            })
            setPosButton(if (scraper.hasSettings) "Settings" else "Close") {
                if (scraper.hasSettings) openSettings(scraper)
            }
            setNegButton("Uninstall") { confirmDelete(scraper) }
            show()
        }
    }

    private fun confirmDelete(scraper: NuvioScraper) {
        requireContext().customAlertDialog().apply {
            setTitle("Uninstall plugin?")
            setMessage("Remove ${scraper.name} from installed plugins?")
            setPosButton("Uninstall") {
                NuvioStorage.uninstall(requireContext(), scraper.stored())
                loadItems()
                toast("${scraper.name} uninstalled")
            }
            setNegButton("Cancel")
            show()
        }
    }

    override fun updateContentBasedOnQuery(query: String?) {
        this.query = query.orEmpty()
        adapter.submitList(items.filter { it.matches(this.query) })
    }

    override fun notifyDataChanged() = loadItems()

    override fun onDestroyView() {
        binding.allExtensionsRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private fun NuvioScraper.matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.lowercase()
        return name.lowercase().contains(needle) ||
            id.lowercase().contains(needle) ||
            description.lowercase().contains(needle) ||
            author.orEmpty().lowercase().contains(needle) ||
            supportedTypes.any { it.lowercase().contains(needle) } ||
            contentLanguage.any { it.lowercase().contains(needle) }
    }

    private fun NuvioScraper.stored() = NuvioStoredScraper(
        id = id,
        repositoryUrl = repositoryUrl,
        name = name,
        description = description,
        author = author,
        version = version,
        filename = filename,
        supportedTypes = supportedTypes,
        enabled = enabled,
        manifestEnabled = manifestEnabled,
        hasSettings = hasSettings,
        logo = logo,
        contentLanguage = contentLanguage,
        formats = formats,
    )
}

class NuvioInstalledAdapter(
    private val onInfo: (NuvioScraper) -> Unit,
    private val onSettings: (NuvioScraper) -> Unit,
    private val onDelete: (NuvioScraper) -> Unit,
) : ListAdapter<NuvioScraper, NuvioInstalledAdapter.VH>(DIFF) {
    var dragActivePosition: Int? = null
    var reorderMessage: TextView? = null
    var itemTouchHelper: ItemTouchHelper? = null
    var onDragModeChanged: ((Boolean) -> Unit)? = null
    var persistOrder: ((List<NuvioScraper>) -> Unit)? = null

    fun disarmDrag() {
        dragActivePosition = null
        reorderMessage?.visibility = View.GONE
        persistOrder?.invoke(currentList)
        onDragModeChanged?.invoke(false)
        notifyDataSetChanged()
    }

    private fun tintArrows(holder: VH, active: Boolean) {
        val typedValue = TypedValue()
        holder.itemView.context.theme.resolveAttribute(
            if (active) android.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface,
            typedValue,
            true,
        )
        holder.binding.dragUpArrow.setColorFilter(typedValue.data)
        holder.binding.dragDownArrow.setColorFilter(typedValue.data)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemNuvioSourceInstalledBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position), position)

    fun move(from: Int, to: Int) {
        if (from == to || from < 0 || to < 0 || from >= itemCount || to >= itemCount) return
        val list = currentList.toMutableList()
        list.add(to, list.removeAt(from))
        submitList(list)
    }

    inner class VH(val binding: ItemNuvioSourceInstalledBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NuvioScraper, position: Int) {
            binding.sourceNameTextView.text = item.name
            binding.sourceMetaTextView.text = buildString {
                append("v${item.version}")
                if (item.supportedTypes.isNotEmpty()) {
                    append(" • ")
                    append(item.supportedTypes.joinToString("/"))
                }
                if (item.contentLanguage.isNotEmpty()) {
                    append(" • ")
                    append(item.contentLanguage.joinToString(",").uppercase())
                }
            }
            binding.sourceIconImageView.let { view ->
                val logoUrl = if (item.logo.isNullOrBlank()) {
                    NUVIO_WATERMARK_URL
                } else {
                    NuvioRepository.sourceUrl(item.repositoryUrl, item.logo)
                }
                SvgImageLoader.load(view, logoUrl, R.drawable.ic_extension, R.drawable.ic_extension)
            }
            binding.settingsImageView.visibility = if (item.hasSettings) View.VISIBLE else View.GONE
            binding.sourceCardView.setOnClickListener { onInfo(item) }
            binding.settingsImageView.setOnClickListener { onSettings(item) }
            binding.deleteImageView.setOnClickListener { onDelete(item) }
            tintArrows(this, dragActivePosition == position)

            binding.dragHandle.setOnClickListener {
                val currentPosition = absoluteAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                if (dragActivePosition == currentPosition) {
                    disarmDrag()
                } else {
                    dragActivePosition = currentPosition
                    reorderMessage?.visibility = View.VISIBLE
                    onDragModeChanged?.invoke(true)
                    tintArrows(this, true)
                    binding.dragHandle.post { binding.dragHandle.requestFocus() }
                }
            }

            itemView.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    dragActivePosition == absoluteAdapterPosition
                ) {
                    itemTouchHelper?.startDrag(this)
                }
                false
            }

            binding.dragHandle.setOnLongClickListener {
                itemTouchHelper?.startDrag(this)
                true
            }

            binding.dragHandle.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val currentPosition = absoluteAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) return@setOnKeyListener false
                if (dragActivePosition != currentPosition) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (currentPosition > 0) {
                            dragActivePosition = currentPosition - 1
                            move(currentPosition, currentPosition - 1)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (currentPosition < itemCount - 1) {
                            dragActivePosition = currentPosition + 1
                            move(currentPosition, currentPosition + 1)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        disarmDrag()
                        false
                    }
                    else -> false
                }
            }

            FocusEffectUtil.applyFocusListener(binding.sourceCardView)
            FocusEffectUtil.applyFocusListener(binding.dragHandle)
            FocusEffectUtil.applyFocusListener(binding.settingsImageView)
            FocusEffectUtil.applyFocusListener(binding.deleteImageView)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NuvioScraper>() {
            override fun areItemsTheSame(oldItem: NuvioScraper, newItem: NuvioScraper) = oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: NuvioScraper, newItem: NuvioScraper) =
                oldItem.name == newItem.name &&
                    oldItem.version == newItem.version &&
                    oldItem.description == newItem.description &&
                    oldItem.hasSettings == newItem.hasSettings
        }
    }
}

class NuvioAvailableFragment : Fragment(), SearchQueryHandler {
    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!
    private var items: List<NuvioRepositoryItem> = emptyList()
    private var query = ""

    private val adapter = RepoCardAdapter(
        onOpen = { openRepo(it.url) },
        onLongClick = { showRepo(it.url) },
        countLabel = "plugins",
        onExitTop = { (activity as? ExtensionsActivity)?.focusPill() == true },
        actionLabel = "Browse",
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        binding.allExtensionsRecyclerView.adapter = adapter
        binding.allExtensionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadItems()
    }

    private fun loadItems() {
        val cached = NuvioRepository.repositories()
        items = cached.sortedBy { it.name.lowercase() }
        submitItems()
        viewLifecycleOwner.lifecycleScope.launch {
            val fresh = cached.map { repo ->
                async {
                    runCatching { NuvioRepository.refreshRepository(repo.manifestUrl) }.getOrNull() ?: repo
                }
            }.awaitAll().sortedBy { it.name.lowercase() }
            items = fresh
            submitItems()
        }
    }

    private fun submitItems() {
        adapter.submitList(items.map { it.toRepoUi() }.filter { it.matches(query) })
    }

    private fun openRepo(url: String) {
        startActivity(Intent(requireContext(), NuvioRepoDetailActivity::class.java).apply {
            putExtra(NuvioRepoDetailActivity.ARG_REPO_URL, url)
        })
    }

    private fun showRepo(url: String) {
        val repo = items.firstOrNull { it.manifestUrl == url } ?: return
        requireContext().customAlertDialog().apply {
            setTitle(repo.name)
            setMessage(buildString {
                append(repo.description.orEmpty())
                append("\n${repo.scraperCount} plugins")
                append("\n${repo.manifestUrl}")
            })
            setPosButton("Browse") { openRepo(repo.manifestUrl) }
            setNeutralButton("Copy URL") { copyToClipboard(repo.manifestUrl, true) }
            setNegButton("Delete") {
                NuvioStorage.removeRepo(repo.manifestUrl)
                loadItems()
                toast("Repository removed; installed plugins kept")
            }
            show()
        }
    }

    override fun updateContentBasedOnQuery(query: String?) {
        this.query = query.orEmpty()
        submitItems()
    }

    override fun notifyDataChanged() = loadItems()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun NuvioRepositoryItem.toRepoUi(): RepoUi = RepoUi(
        name = name,
        url = manifestUrl,
        count = scraperCount,
        iconUrl = this.iconUrl ?: githubOwnerAvatar(manifestUrl) ?: NUVIO_WATERMARK_URL,
        contentTypes = listOf("Nuvio"),
        languages = emptyList(),
        countText = "$scraperCount plugins",
        description = description,
        watermarkRes = R.drawable.ic_extension,
        watermarkUrl = NUVIO_WATERMARK_URL,
    )
}
