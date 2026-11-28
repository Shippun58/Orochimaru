package ani.sanin.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.NotificationCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.sanin.R
import ani.sanin.bridge.AnimeBridgeStore
import ani.sanin.bridge.AnimeBridgeUi
import ani.sanin.cloudstream.CloudStreamSettingsActivity
import ani.sanin.cloudstream.CsInstalledSource
import ani.sanin.cloudstream.CsRepos
import ani.sanin.connections.crashlytics.CrashlyticsInterface
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.others.LanguageMapper.Companion.getLanguageName
import ani.sanin.others.svg.SvgImageLoader
import ani.sanin.parsers.AnimeSources
import ani.sanin.plugins.nuvio.NUVIO_WATERMARK_URL
import ani.sanin.plugins.nuvio.NuvioRepository
import ani.sanin.plugins.nuvio.NuvioScraper
import ani.sanin.plugins.nuvio.NuvioSources
import ani.sanin.plugins.nuvio.NuvioStorage
import ani.sanin.plugins.nuvio.NuvioStoredScraper
import ani.sanin.plugins.nuvio.runtime.NuvioRuntime
import ani.sanin.plugins.nuvio.showNuvioSettings
import ani.sanin.settings.extensionprefs.AnimeSourcePreferencesFragment
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.OverflowMenuItem
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import ani.sanin.util.showOverflowMenu
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class InstalledAnimeExtensionsFragment : Fragment(), SearchQueryHandler {
    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!
    private lateinit var extensionsRecyclerView: RecyclerView
    private val skipIcons: Boolean = PrefManager.getVal(PrefName.SkipExtensionIcons)
    private val animeExtensionManager: AnimeExtensionManager = Injekt.get()
    private var installedExtensions: List<AnimeExtension.Installed> = emptyList()
    private var allRows: List<InstalledRow> = emptyList()
    private var query = ""
    private var rowLoadJob: Job? = null
    private val extensionsAdapter = AnimeExtensionsAdapter(
        onUpdateClicked = { row -> updateRow(row) },
        onOverflowClicked = { anchor, row -> showRowMenu(anchor, row) },
        skipIcons = skipIcons
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        FocusEffectUtil.applyFocusListener(binding.root)

        extensionsRecyclerView = binding.allExtensionsRecyclerView
        extensionsRecyclerView.layoutManager = SafeLinearLayoutManager(requireContext())
        extensionsRecyclerView.adapter = extensionsAdapter

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
                val newList = extensionsAdapter.currentList.toMutableList().apply {
                    add(toPosition, removeAt(fromPosition))
                }
                extensionsAdapter.submitList(newList)
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
                extensionsAdapter.disarmDrag(persist = true)
                viewHolder.itemView.elevation = 0f
                viewHolder.itemView.translationZ = 0f
            }
        }
        val touchHelper = ItemTouchHelper(itemTouchHelperCallback)
        touchHelper.attachToRecyclerView(extensionsRecyclerView)
        extensionsAdapter.itemTouchHelper = touchHelper
        extensionsAdapter.reorderMessage = binding.reorderMessage
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val backDisarm = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                extensionsAdapter.disarmDrag(persist = true)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backDisarm)
        extensionsAdapter.onDragModeChanged = { armed -> backDisarm.isEnabled = armed }
        viewLifecycleOwner.lifecycleScope.launch {
            animeExtensionManager.installedExtensionsFlow.collect { extensions ->
                refreshRows(extensions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            refreshRows(animeExtensionManager.installedExtensionsFlow.value)
        }
    }

    private fun refreshRows(extensions: List<AnimeExtension.Installed> = installedExtensions) {
        installedExtensions = extensions
        if (!isAdded || view == null) return
        val context = context ?: return
        rowLoadJob?.cancel()
        rowLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { buildRows(context, extensions) }
            allRows = sortRows(rows)
            applyFilter()
        }
    }

    private fun buildRows(
        context: Context,
        extensions: List<AnimeExtension.Installed>
    ): List<InstalledRow> {
        val extensionRows = extensions.map { extension ->
            InstalledRow(
                key = "extension:${extension.pkgName}",
                name = extension.name,
                meta = extension.versionText(),
                icon = extension.icon,
                iconUrl = null,
                extension = extension
            )
        }
        val cloudStreamRows = CsRepos.installed(context)
            .filter {
                AnimeBridgeStore.isBridged(AnimeBridgeStore.CLOUDSTREAM, it.id)
            }
            .map { source ->
                InstalledRow(
                    key = "cloudstream:${source.id}",
                    name = source.name,
                    meta = "v${source.version} • ${source.type} • ${source.lang}",
                    icon = null,
                    iconUrl = source.iconUrl,
                    cloudStream = source
                )
            }
        val nuvioRows = NuvioRepository.installed(context)
            .filter {
                it.enabled && AnimeBridgeStore.isBridged(
                    AnimeBridgeStore.NUVIO,
                    NuvioSources.sourceId(it)
                )
            }
            .map { scraper ->
                val logo = scraper.logo
                InstalledRow(
                    key = "nuvio:${NuvioSources.sourceId(scraper)}",
                    name = scraper.name,
                    meta = scraper.versionText(),
                    icon = null,
                    iconUrl = if (logo.isNullOrBlank()) {
                        NUVIO_WATERMARK_URL
                    } else {
                        NuvioRepository.sourceUrl(scraper.repositoryUrl, logo)
                    },
                    nuvio = scraper
                )
            }
        val stremioRows = StremioAddonManager.installed()
            .filter { AnimeBridgeStore.isBridged(AnimeBridgeStore.STREMIO, it.transportUrl) }
            .map { addon ->
                InstalledRow(
                    key = "stremio:${addon.transportUrl}",
                    name = addon.manifest.name,
                    meta = addon.versionText(),
                    icon = null,
                    iconUrl = addon.manifest.logo,
                    stremio = addon
                )
            }
        return extensionRows + cloudStreamRows + nuvioRows + stremioRows
    }

    private fun sortRows(rows: List<InstalledRow>): List<InstalledRow> {
        val pinned = AnimeSources.pinnedAnimeSources
        val ordered = ArrayList<InstalledRow>(rows.size)
        val used = HashSet<String>()
        pinned.forEach { name ->
            rows.filter { it.name == name && it.key !in used }.forEach { row ->
                ordered.add(row)
                used.add(row.key)
            }
        }
        rows.filter { !it.bridged && it.key !in used }.forEach { row ->
            ordered.add(row)
            used.add(row.key)
        }
        rows.filter { it.bridged && it.key !in used }.forEach { row ->
            ordered.add(row)
            used.add(row.key)
        }
        return ordered
    }

    private fun applyFilter() {
        if (_binding == null) return
        extensionsAdapter.updateData(allRows.filter { it.matches(query) })
    }

    private fun showRowMenu(anchor: View, row: InstalledRow) {
        val items = mutableListOf<OverflowMenuItem>()
        if (row.bridged) {
            items.add(
                OverflowMenuItem("Destroy bridge", R.drawable.ic_places_bridge) {
                    removeBridge(row)
                }
            )
        }
        items.add(
            OverflowMenuItem("Settings", R.drawable.ic_round_settings_24) {
                openSettings(row)
            }
        )
        items.add(
            OverflowMenuItem("Uninstall", R.drawable.ic_round_delete_24) {
                uninstall(row)
            }
        )
        showOverflowMenu(anchor, items)
    }

    private fun openSettings(row: InstalledRow) {
        val extension = row.extension
        if (extension != null) {
            openExtensionSettings(extension)
            return
        }
        val cloudStream = row.cloudStream
        if (cloudStream != null) {
            startActivity(CloudStreamSettingsActivity.intent(requireContext(), cloudStream.id))
            return
        }
        val nuvio = row.nuvio
        if (nuvio != null) {
            openNuvioSettings(nuvio)
            return
        }
        val stremio = row.stremio
        if (stremio != null) {
            openStremioSettings(stremio)
        }
    }

    private fun openExtensionSettings(pkg: AnimeExtension.Installed) {
        val changeUIVisibility: (Boolean) -> Unit = { show ->
            val activity = requireActivity() as ExtensionsActivity
            activity.findViewById<ViewPager2>(R.id.viewPager).isVisible = show
            activity.findViewById<TabLayout>(R.id.tabLayout).isVisible = show
            activity.findViewById<TextInputLayout>(R.id.searchView).isVisible = show
            activity.findViewById<FrameLayout>(R.id.fragmentExtensionsContainer).isGone = show
        }
        var itemSelected = false
        val allSettings = pkg.sources.filterIsInstance<ConfigurableAnimeSource>()
        if (allSettings.isNotEmpty()) {
            var selectedSetting = allSettings[0]
            if (allSettings.size > 1) {
                val names = allSettings.map { getLanguageName(it.lang) }
                    .toTypedArray()
                var selectedIndex = 0
                requireContext().customAlertDialog().apply {
                    setTitle("Select a Source")
                    singleChoiceItems(names, selectedIndex) { which ->
                        itemSelected = true
                        selectedIndex = which
                        selectedSetting = allSettings[selectedIndex]

                        val fragment =
                            AnimeSourcePreferencesFragment().getInstance(selectedSetting) {
                                changeUIVisibility(true)
                            }
                        parentFragmentManager.beginTransaction()
                            .apply {
                                if (AnimUtils.enabled(PrefName.TransitionAnimations)) {
                                    setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                                }
                            }
                            .replace(R.id.fragmentExtensionsContainer, fragment)
                            .addToBackStack(null)
                            .commit()
                    }
                    onDismiss {
                        if (!itemSelected) {
                            changeUIVisibility(true)
                        }
                    }
                    show()
                }
            } else {
                val fragment =
                    AnimeSourcePreferencesFragment().getInstance(selectedSetting) {
                        changeUIVisibility(true)
                    }
                parentFragmentManager.beginTransaction()
                    .apply {
                        if (AnimUtils.enabled(PrefName.TransitionAnimations)) {
                            setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                        }
                    }
                    .replace(R.id.fragmentExtensionsContainer, fragment)
                    .addToBackStack(null)
                    .commit()
            }
            changeUIVisibility(false)
        } else {
            Toast.makeText(requireContext(), "Source is not configurable", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun openNuvioSettings(scraper: NuvioScraper) {
        viewLifecycleOwner.lifecycleScope.launch {
            val layout = runCatching { NuvioRuntime.settingsLayout(scraper) }.getOrDefault("[]")
            if (isAdded) {
                showNuvioSettings(requireContext(), scraper, layout)
            }
        }
    }

    private fun openStremioSettings(addon: StremioAddon) {
        val url = StremioAddonManager.toRepoUi(addon).configUrl
        if (url == null) {
            Toast.makeText(
                requireContext(),
                "${addon.manifest.name} is not configurable",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(requireContext(), "Could not open configuration", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun uninstall(row: InstalledRow) {
        if (!isAdded) return
        val context = requireContext()
        val extension = row.extension
        val cloudStream = row.cloudStream
        val nuvio = row.nuvio
        val stremio = row.stremio
        when {
            extension != null -> {
                animeExtensionManager.uninstallExtension(extension.pkgName)
                snackString("Extension uninstalled")
            }
            cloudStream != null -> {
                CsRepos.uninstall(context, cloudStream)
                AnimeSources.refreshPluginSources()
                Toast.makeText(context, "${cloudStream.name} uninstalled", Toast.LENGTH_SHORT)
                    .show()
            }
            nuvio != null -> {
                NuvioStorage.uninstall(context, nuvio.toStored())
                AnimeSources.refreshPluginSources()
                Toast.makeText(context, "${nuvio.name} uninstalled", Toast.LENGTH_SHORT).show()
            }
            stremio != null -> {
                StremioAddonManager.uninstall(stremio.transportUrl)
                AnimeSources.refreshPluginSources()
                Toast.makeText(
                    context,
                    "${stremio.manifest.name} uninstalled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        refreshRows()
    }

    private fun removeBridge(row: InstalledRow) {
        val kind = row.bridgeKind ?: return
        val id = row.bridgeId ?: return
        AnimeBridgeUi.confirm(requireContext(), kind, id, row.name) {
            refreshRows()
        }
    }

    private fun updateRow(row: InstalledRow) {
        val pkg = row.extension ?: return
        if (!isAdded) return
        val context = requireContext()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (pkg.hasUpdate) {
            animeExtensionManager.updateExtension(pkg)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { installStep ->
                        val builder = NotificationCompat.Builder(
                            context,
                            Notifications.CHANNEL_DOWNLOADER_PROGRESS
                        )
                            .setSmallIcon(R.drawable.ic_round_sync_24)
                            .setContentTitle("Updating extension")
                            .setContentText("Step: $installStep")
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                        notificationManager.notify(1, builder.build())
                    },
                    { error ->
                        Injekt.get<CrashlyticsInterface>().logException(error)
                        Logger.log(error)
                        val builder = NotificationCompat.Builder(
                            context,
                            Notifications.CHANNEL_DOWNLOADER_ERROR
                        )
                            .setSmallIcon(R.drawable.ic_round_info_24)
                            .setContentTitle("Update failed: ${error.message}")
                            .setContentText("Error: ${error.message}")
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                        notificationManager.notify(1, builder.build())
                        snackString("Update failed: ${error.message}")
                    },
                    {
                        val builder = NotificationCompat.Builder(
                            context,
                            Notifications.CHANNEL_DOWNLOADER_PROGRESS
                        )
                            .setSmallIcon(R.drawable.ic_circle_check)
                            .setContentTitle("Update complete")
                            .setContentText("The extension has been successfully updated.")
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                        notificationManager.notify(1, builder.build())
                        snackString("Extension updated")
                    }
                )
        } else {
            snackString("No update available")
        }
    }

    private fun AnimeExtension.Installed.versionText(): String {
        val nsfw = if (isNsfw) "(18+)" else ""
        val lang = getLanguageName(lang)
        val majorLib = libVersion.toInt()
        val displayVersion =
            if (versionName.startsWith("$majorLib.") || versionName.startsWith("$majorLib")) {
                versionName
            } else {
                "$majorLib.$versionName"
            }
        return "$lang $displayVersion $nsfw".trim()
    }

    private fun NuvioScraper.versionText(): String = buildString {
        append("v$version")
        if (supportedTypes.isNotEmpty()) {
            append(" • ")
            append(supportedTypes.joinToString("/"))
        }
        if (contentLanguage.isNotEmpty()) {
            append(" • ")
            append(contentLanguage.joinToString(",").uppercase(Locale.ROOT))
        }
    }

    private fun StremioAddon.versionText(): String = buildString {
        append("v${manifest.version}")
        if (manifest.types.isNotEmpty()) {
            append(" • ")
            append(manifest.types.joinToString("/"))
        }
    }

    override fun onDestroyView() {
        rowLoadJob?.cancel()
        rowLoadJob = null
        super.onDestroyView()
        _binding = null
    }

    override fun updateContentBasedOnQuery(query: String?) {
        this.query = query.orEmpty()
        applyFilter()
    }

    override fun notifyDataChanged() {
        refreshRows()
    }

    private class AnimeExtensionsAdapter(
        private val onUpdateClicked: (InstalledRow) -> Unit,
        private val onOverflowClicked: (View, InstalledRow) -> Unit,
        val skipIcons: Boolean
    ) : ListAdapter<InstalledRow, AnimeExtensionsAdapter.ViewHolder>(
        DIFF_CALLBACK_INSTALLED
    ) {
        var dragActivePosition: Int? = null
        var reorderMessage: TextView? = null
        var itemTouchHelper: ItemTouchHelper? = null
        var onDragModeChanged: ((Boolean) -> Unit)? = null

        fun updateData(newRows: List<InstalledRow>) {
            submitList(newRows)
        }

        fun updatePref() {
            val map = currentList.map { it.name }.distinct()
            PrefManager.setVal(PrefName.AnimeSourcesOrder, map)
            AnimeSources.pinnedAnimeSources = map
            AnimeSources.performReorderAnimeSources()
        }

        fun disarmDrag(persist: Boolean) {
            dragActivePosition = null
            reorderMessage?.visibility = View.GONE
            if (persist) updatePref()
            onDragModeChanged?.invoke(false)
            notifyDataSetChanged()
        }

        private fun tintArrows(holder: ViewHolder, active: Boolean) {
            val typedValue = android.util.TypedValue()
            holder.itemView.context.theme.resolveAttribute(
                if (active) android.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface,
                typedValue, true
            )
            holder.dragUpArrow.setColorFilter(typedValue.data)
            holder.dragDownArrow.setColorFilter(typedValue.data)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_installed_anime_source, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = getItem(position)
            holder.extensionNameTextView.text = row.name
            holder.extensionVersionTextView.text = row.meta
            holder.bridgeIcon.isVisible = row.bridged
            Glide.with(holder.extensionIconImageView).clear(holder.extensionIconImageView)
            if (skipIcons) {
                holder.extensionIconImageView.setImageDrawable(null)
            } else if (row.iconUrl != null) {
                SvgImageLoader.load(
                    holder.extensionIconImageView,
                    row.iconUrl,
                    R.drawable.ic_extension,
                    R.drawable.ic_extension
                )
            } else {
                holder.extensionIconImageView.setImageDrawable(row.icon)
            }
            holder.updateView.isVisible = row.hasUpdate
            holder.updateView.setOnClickListener {
                onUpdateClicked(row)
            }
            holder.overflowView.setOnClickListener { anchor ->
                onOverflowClicked(anchor, row)
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
            holder.dragUpArrow.setColorFilter(tintColor)
            holder.dragDownArrow.setColorFilter(tintColor)

            holder.dragHandle.setOnClickListener {
                val currentPos = holder.absoluteAdapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (dragActivePosition == currentPos) {
                    disarmDrag(persist = true)
                } else {
                    dragActivePosition = currentPos
                    reorderMessage?.visibility = View.VISIBLE
                    onDragModeChanged?.invoke(true)
                    tintArrows(holder, true)
                    holder.dragHandle.post { holder.dragHandle.requestFocus() }
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

            holder.dragHandle.setOnLongClickListener {
                val currentPos = holder.absoluteAdapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                itemTouchHelper?.startDrag(holder)
                true
            }

            holder.dragHandle.setOnKeyListener { v, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val currentPos = holder.absoluteAdapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                if (dragActivePosition != currentPos) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (currentPos > 0) {
                            val newList = currentList.toMutableList()
                            val item = newList.removeAt(currentPos)
                            newList.add(currentPos - 1, item)
                            dragActivePosition = currentPos - 1
                            submitList(newList)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (currentPos < currentList.size - 1) {
                            val newList = currentList.toMutableList()
                            val item = newList.removeAt(currentPos)
                            newList.add(currentPos + 1, item)
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
            FocusEffectUtil.applyFocusListener(holder.dragHandle)
            FocusEffectUtil.applyFocusListener(holder.overflowView)
            if (row.hasUpdate) {
                FocusEffectUtil.applyFocusListener(holder.updateView)
            }
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val extensionNameTextView: TextView = view.findViewById(R.id.extensionNameTextView)
            val extensionVersionTextView: TextView =
                view.findViewById(R.id.extensionVersionTextView)
            val bridgeIcon: ImageView = view.findViewById(R.id.bridgeIconImageView)
            val extensionIconImageView: ImageView = view.findViewById(R.id.extensionIconImageView)
            val updateView: ImageView = view.findViewById(R.id.updateTextView)
            val overflowView: ImageView = view.findViewById(R.id.overflowImageView)
            val dragHandle: LinearLayout = view.findViewById(R.id.dragHandle)
            val dragUpArrow: ImageView = view.findViewById(R.id.dragUpArrow)
            val dragDownArrow: ImageView = view.findViewById(R.id.dragDownArrow)
        }

        companion object {
            val DIFF_CALLBACK_INSTALLED =
                object : DiffUtil.ItemCallback<InstalledRow>() {
                    override fun areItemsTheSame(oldItem: InstalledRow, newItem: InstalledRow) =
                        oldItem.key == newItem.key

                    override fun areContentsTheSame(oldItem: InstalledRow, newItem: InstalledRow) =
                        oldItem == newItem
                }
        }
    }

}

private data class InstalledRow(
    val key: String,
    val name: String,
    val meta: String,
    val icon: Drawable?,
    val iconUrl: String?,
    val extension: AnimeExtension.Installed? = null,
    val cloudStream: CsInstalledSource? = null,
    val nuvio: NuvioScraper? = null,
    val stremio: StremioAddon? = null,
) {
    val bridged: Boolean
        get() = cloudStream != null || nuvio != null || stremio != null

    val hasUpdate: Boolean
        get() = extension?.hasUpdate == true

    val bridgeKind: String?
        get() = when {
            cloudStream != null -> AnimeBridgeStore.CLOUDSTREAM
            nuvio != null -> AnimeBridgeStore.NUVIO
            stremio != null -> AnimeBridgeStore.STREMIO
            else -> null
        }

    val bridgeId: String?
        get() = cloudStream?.id
            ?: nuvio?.let { NuvioSources.sourceId(it) }
            ?: stremio?.transportUrl

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.lowercase(Locale.ROOT)
        return name.lowercase(Locale.ROOT).contains(needle) ||
            meta.lowercase(Locale.ROOT).contains(needle)
    }
}

private fun NuvioScraper.toStored() = NuvioStoredScraper(
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
