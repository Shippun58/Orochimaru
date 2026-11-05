package ani.sanin.settings

import android.app.NotificationManager
import android.view.MotionEvent
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.sanin.R
import ani.sanin.connections.crashlytics.CrashlyticsInterface
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.others.LanguageMapper.Companion.getLanguageName
import ani.sanin.parsers.AnimeSources
import ani.sanin.settings.extensionprefs.AnimeSourcePreferencesFragment
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import ani.sanin.util.FocusEffectUtil

import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.launch
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
    private val extensionsAdapter = AnimeExtensionsAdapter(
        { pkg ->
            val name = pkg.name
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
                                .apply { if (AnimUtils.enabled(PrefName.TransitionAnimations)) setCustomAnimations(R.anim.slide_up, R.anim.slide_down) }
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
                        .apply { if (AnimUtils.enabled(PrefName.TransitionAnimations)) setCustomAnimations(R.anim.slide_up, R.anim.slide_down) }
                        .replace(R.id.fragmentExtensionsContainer, fragment)
                        .addToBackStack(null)
                        .commit()

                }

                changeUIVisibility(false)
            } else {
                Toast.makeText(requireContext(), "Source is not configurable", Toast.LENGTH_SHORT)
                    .show()
            }
        },
        { pkg ->
            if (isAdded) {
                animeExtensionManager.uninstallExtension(pkg.pkgName)
                snackString("Extension uninstalled")
            }
        }, { pkg ->
            if (isAdded) {
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
        }, skipIcons
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

        lifecycleScope.launch {
            animeExtensionManager.installedExtensionsFlow.collect { extensions ->
                extensionsAdapter.updateData(sortToAnimeSourcesList(extensions))
            }
        }
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
    }

    private fun sortToAnimeSourcesList(inpt: List<AnimeExtension.Installed>): List<AnimeExtension.Installed> {
        val sourcesMap = inpt.associateBy { it.name }
        val orderedSources = AnimeSources.pinnedAnimeSources.mapNotNull { name ->
            sourcesMap[name]
        }
        return orderedSources + inpt.filter { !AnimeSources.pinnedAnimeSources.contains(it.name) }
    }

    override fun onDestroyView() {
        super.onDestroyView();_binding = null
    }

    override fun updateContentBasedOnQuery(query: String?) {
        extensionsAdapter.filter(
            query ?: "",
            sortToAnimeSourcesList(animeExtensionManager.installedExtensionsFlow.value)
        )
    }

    override fun notifyDataChanged() {
    }

    private class AnimeExtensionsAdapter(
        private val onSettingsClicked: (AnimeExtension.Installed) -> Unit,
        private val onUninstallClicked: (AnimeExtension.Installed) -> Unit,
        private val onUpdateClicked: (AnimeExtension.Installed) -> Unit,
        val skipIcons: Boolean
    ) : ListAdapter<AnimeExtension.Installed, AnimeExtensionsAdapter.ViewHolder>(
        DIFF_CALLBACK_INSTALLED
    ) {
        var dragActivePosition: Int? = null
        var reorderMessage: TextView? = null
        var itemTouchHelper: ItemTouchHelper? = null
        var onDragModeChanged: ((Boolean) -> Unit)? = null

        fun updateData(newExtensions: List<AnimeExtension.Installed>) {
            submitList(newExtensions)
        }

        fun updatePref() {
            val map = currentList.map { it.name }
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
                .inflate(R.layout.item_extension, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val extension = getItem(position)
            val nsfw = if (extension.isNsfw) "(18+)" else ""
            val lang = getLanguageName(extension.lang)
            holder.extensionNameTextView.text = extension.name
            val majorLib = extension.libVersion.toInt()
            val displayVersion = if (extension.versionName.startsWith("$majorLib.") || extension.versionName.startsWith("$majorLib")) {
                extension.versionName
            } else {
                "$majorLib.${extension.versionName}"
            }
            val versionText = "$lang $displayVersion $nsfw".trim()
            holder.extensionVersionTextView.text = versionText
            if (!skipIcons) {
                holder.extensionIconImageView.setImageDrawable(extension.icon)
            }
            if (extension.hasUpdate) {
                holder.updateView.isVisible = true
            } else {
                holder.updateView.isVisible = false
            }
            holder.deleteView.setOnClickListener {
                onUninstallClicked(extension)
            }
            holder.updateView.setOnClickListener {
                onUpdateClicked(extension)
            }
            holder.settingsImageView.setOnClickListener {
                onSettingsClicked(extension)
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
            FocusEffectUtil.applyFocusListener(holder.settingsImageView)
            FocusEffectUtil.applyFocusListener(holder.deleteView)
            if (extension.hasUpdate) {
                FocusEffectUtil.applyFocusListener(holder.updateView)
            }
        }

        fun filter(query: String, currentList: List<AnimeExtension.Installed>) {
            val filteredList = ArrayList<AnimeExtension.Installed>()
            for (extension in currentList) {
                if (extension.name.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT))) {
                    filteredList.add(extension)
                }
            }
            if (filteredList != currentList)
                submitList(filteredList)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val extensionNameTextView: TextView = view.findViewById(R.id.extensionNameTextView)
            val extensionVersionTextView: TextView =
                view.findViewById(R.id.extensionVersionTextView)
            val settingsImageView: ImageView = view.findViewById(R.id.settingsImageView)
            val extensionIconImageView: ImageView = view.findViewById(R.id.extensionIconImageView)
            val deleteView: ImageView = view.findViewById(R.id.deleteTextView)
            val updateView: ImageView = view.findViewById(R.id.updateTextView)
            val dragHandle: LinearLayout = view.findViewById(R.id.dragHandle)
            val dragUpArrow: ImageView = view.findViewById(R.id.dragUpArrow)
            val dragDownArrow: ImageView = view.findViewById(R.id.dragDownArrow)
        }

        companion object {
            val DIFF_CALLBACK_INSTALLED =
                object : DiffUtil.ItemCallback<AnimeExtension.Installed>() {
                    override fun areItemsTheSame(
                        oldItem: AnimeExtension.Installed,
                        newItem: AnimeExtension.Installed
                    ): Boolean {
                        return oldItem.pkgName == newItem.pkgName
                    }

                    override fun areContentsTheSame(
                        oldItem: AnimeExtension.Installed,
                        newItem: AnimeExtension.Installed
                    ): Boolean {
                        return oldItem == newItem
                    }
                }
        }
    }

}
