package ani.sanin.settings

import android.os.Bundle
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.content.res.Configuration
import com.google.android.material.bottomsheet.BottomSheetBehavior
import ani.sanin.R
import ani.sanin.cloudstream.CsRepos
import ani.sanin.getThemeColor
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.mal.MAL
import ani.sanin.connections.simkl.Simkl
import ani.sanin.loadImage
import ani.sanin.util.Logger
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.MainActivity
import ani.sanin.parsers.AnimeSources
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MediaTrackerBottomSheet : BottomSheetDialogFragment() {

    private var _view: View? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _view = inflater.inflate(R.layout.bottom_sheet_media_tracker, container, false)
        return _view!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
    }

    private fun setupUI() {
        val v = _view!!
        val animeButton = v.findViewById<View>(R.id.sheetAnimeButton)
        val movieButton = v.findViewById<View>(R.id.sheetMovieButton)
        val animeExpanded = v.findViewById<View>(R.id.sheetAnimeExpanded)
        val movieExpanded = v.findViewById<View>(R.id.sheetMovieExpanded)
        val animeArrow = v.findViewById<ImageView>(R.id.sheetAnimeArrow)
        val movieArrow = v.findViewById<ImageView>(R.id.sheetMovieArrow)
        val aniListCheck = v.findViewById<CheckBox>(R.id.sheetAnimeAniListCheck)
        val malCheck = v.findViewById<CheckBox>(R.id.sheetAnimeMALCheck)
        val simklCheck = v.findViewById<CheckBox>(R.id.sheetMovieSimklCheck)
        val pluginRowInner = v.findViewById<View>(R.id.sheetMoviePluginRowInner)
        val pluginNameView = v.findViewById<TextView>(R.id.sheetMoviePluginName)
        val pluginArrow = v.findViewById<View>(R.id.sheetMoviePluginArrow)
        val animeCatalogueRowInner = v.findViewById<View>(R.id.sheetAnimeCatalogueRowInner)
        val animeCatalogueNameView = v.findViewById<TextView>(R.id.sheetAnimeCatalogueName)
        val animeCatalogueArrow = v.findViewById<View>(R.id.sheetAnimeCatalogueArrow)

        val animeBanner = v.findViewById<ImageView>(R.id.sheetAnimeBanner)
        val animeScrim = v.findViewById<View>(R.id.sheetAnimeScrim)
        val animeProfilePic = v.findViewById<ImageView>(R.id.sheetAnimeProfilePic)
        val animeTrackerIcon = v.findViewById<ImageView>(R.id.sheetAnimeTrackerIcon)
        val movieBanner = v.findViewById<ImageView>(R.id.sheetMovieBanner)
        val movieScrim = v.findViewById<View>(R.id.sheetMovieScrim)
        val movieProfilePic = v.findViewById<ImageView>(R.id.sheetMovieProfilePic)
        val movieTrackerIcon = v.findViewById<ImageView>(R.id.sheetMovieTrackerIcon)

        animeExpanded.visibility = View.GONE
        movieExpanded.visibility = View.GONE

        fun updateCollapsedFocusChain() {
            animeButton.nextFocusUpId = View.NO_ID
            animeButton.nextFocusDownId = movieButton.id
            movieButton.nextFocusUpId = animeButton.id
            movieButton.nextFocusDownId = View.NO_ID
        }

        fun updateAnimeExpandedFocusChain() {
            animeButton.nextFocusUpId = View.NO_ID
            animeButton.nextFocusDownId = aniListCheck.id
            aniListCheck.nextFocusUpId = animeButton.id
            aniListCheck.nextFocusDownId = malCheck.id
            malCheck.nextFocusUpId = aniListCheck.id
            malCheck.nextFocusDownId = animeCatalogueRowInner.id
            animeCatalogueRowInner.nextFocusUpId = malCheck.id
            animeCatalogueRowInner.nextFocusDownId = View.NO_ID
            movieButton.nextFocusUpId = View.NO_ID
            movieButton.nextFocusDownId = View.NO_ID
        }

        fun updateMovieExpandedFocusChain() {
            movieButton.nextFocusUpId = View.NO_ID
            movieButton.nextFocusDownId = simklCheck.id
            simklCheck.nextFocusUpId = movieButton.id
            simklCheck.nextFocusDownId = pluginRowInner.id
            pluginRowInner.nextFocusUpId = simklCheck.id
            pluginRowInner.nextFocusDownId = View.NO_ID
            animeButton.nextFocusUpId = View.NO_ID
            animeButton.nextFocusDownId = View.NO_ID
        }

        val savedTracker = PrefManager.getVal<Int>(PrefName.SelectedTracker)
        val savedType = PrefManager.getVal<Int>(PrefName.SelectedMediaType)

        if (savedType == 0 && savedTracker == 0 && Anilist.token != null) {

            val bannerUrl = Anilist.bg ?: Anilist.avatar
            if (bannerUrl != null) {
                animeBanner.loadImage(bannerUrl)
                animeBanner.visibility = View.VISIBLE
                animeScrim.visibility = View.VISIBLE
            }
            if (Anilist.avatar != null) {
                animeProfilePic.loadImage(Anilist.avatar)
                animeProfilePic.visibility = View.VISIBLE
            }
            animeTrackerIcon.setImageResource(R.drawable.ic_anilist)
            animeTrackerIcon.visibility = View.VISIBLE
        } else if (savedType == 0 && savedTracker == 1 && MAL.token != null) {

            val bannerUrl = MAL.avatar
            if (bannerUrl != null) {
                animeBanner.loadImage(bannerUrl)
                animeBanner.visibility = View.VISIBLE
                animeScrim.visibility = View.VISIBLE
            }
            if (MAL.avatar != null) {
                animeProfilePic.loadImage(MAL.avatar)
                animeProfilePic.visibility = View.VISIBLE
            }
            animeTrackerIcon.setImageResource(R.drawable.ic_myanimelist)
            animeTrackerIcon.visibility = View.VISIBLE
        }

        if (savedType == 1 && savedTracker == 2 && Simkl.token != null) {
            val bannerUrl = Simkl.avatar
            if (bannerUrl != null) {
                movieBanner.loadImage(bannerUrl)
                movieBanner.visibility = View.VISIBLE
                movieScrim.visibility = View.VISIBLE
            }
            if (Simkl.avatar != null) {
                movieProfilePic.loadImage(Simkl.avatar)
                movieProfilePic.visibility = View.VISIBLE
            }
            movieTrackerIcon.setImageResource(R.drawable.ic_simkl)
            movieTrackerIcon.visibility = View.VISIBLE
        }

        animeButton.setOnClickListener {
            val isVisible = animeExpanded.visibility == View.VISIBLE
            if (isVisible) {
                collapseSection(animeExpanded, animeArrow)
                updateCollapsedFocusChain()
            }
            else {
                expandSection(animeExpanded, animeArrow)
                collapseSection(movieExpanded, movieArrow)
                updateAnimeExpandedFocusChain()
                aniListCheck.requestFocus()
            }
        }

        movieButton.setOnClickListener {
            val isVisible = movieExpanded.visibility == View.VISIBLE
            if (isVisible) {
                collapseSection(movieExpanded, movieArrow)
                updateCollapsedFocusChain()
            }
            else {
                expandSection(movieExpanded, movieArrow)
                collapseSection(animeExpanded, animeArrow)
                updateMovieExpandedFocusChain()
                simklCheck.requestFocus()
            }
        }

        animeButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                animeExpanded.visibility == View.VISIBLE
            ) {
                aniListCheck.requestFocus()
                true
            } else {
                false
            }
        }

        movieButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                movieExpanded.visibility == View.VISIBLE
            ) {
                simklCheck.requestFocus()
                true
            } else {
                false
            }
        }

        aniListCheck.setOnKeyListener { _, keyCode, event ->
            when {
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    collapseSection(animeExpanded, animeArrow)
                    updateCollapsedFocusChain()
                    animeButton.requestFocus()
                    true
                }
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    malCheck.requestFocus()
                    true
                }
                else -> false
            }
        }

        malCheck.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                aniListCheck.requestFocus()
                true
            } else {
                false
            }
        }

        simklCheck.setOnKeyListener { _, keyCode, event ->
            when {
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    collapseSection(movieExpanded, movieArrow)
                    updateCollapsedFocusChain()
                    movieButton.requestFocus()
                    true
                }
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    pluginRowInner.requestFocus()
                    true
                }
                else -> false
            }
        }

        FocusEffectUtil.applyFocusListener(pluginRowInner)
        pluginRowInner.setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_UP && simklCheck.requestFocus().let { true }
        }
        FocusEffectUtil.applyFocusListener(animeCatalogueRowInner)
        animeCatalogueRowInner.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                malCheck.requestFocus()
                true
            } else {
                false
            }
        }

        aniListCheck.setOnClickListener {
            if (aniListCheck.isChecked) {
                if (Anilist.token == null) {
                    aniListCheck.isChecked = false
                    ani.sanin.toast("Login to AniList first")
                    return@setOnClickListener
                }
                malCheck.isChecked = false
                PrefManager.setVal(PrefName.SelectedMediaType, 0)
                PrefManager.setVal(PrefName.SelectedTracker, 0)
                PrefManager.setVal(PrefName.RescueMode, false)
                (activity as? MainActivity)?.setContentMode("anime")
                collapseSection(animeExpanded, animeArrow)
                updateCollapsedFocusChain()
                animeButton.requestFocus()
            }
        }

        malCheck.setOnClickListener {
            if (malCheck.isChecked) {
                if (MAL.token == null) {
                    malCheck.isChecked = false
                    ani.sanin.toast("Login to My Anime List first")
                    return@setOnClickListener
                }
                aniListCheck.isChecked = false
                PrefManager.setVal(PrefName.SelectedMediaType, 0)
                PrefManager.setVal(PrefName.SelectedTracker, 1)

                PrefManager.setVal(PrefName.RescueMode, true)
                (activity as? MainActivity)?.setContentMode("anime")
                collapseSection(animeExpanded, animeArrow)
                updateCollapsedFocusChain()
                animeButton.requestFocus()
            }
        }

        simklCheck.setOnClickListener {
            if (simklCheck.isChecked) {
                if (Simkl.token == null) {
                    simklCheck.isChecked = false
                    ani.sanin.toast("Login to Simkl first")
                    return@setOnClickListener
                }
                PrefManager.setVal(PrefName.SelectedMediaType, 1)
                PrefManager.setVal(PrefName.SelectedTracker, 2)
                (activity as? MainActivity)?.setContentMode("movie_tv")
                collapseSection(movieExpanded, movieArrow)
                updateCollapsedFocusChain()
                movieButton.requestFocus()
            }
        }

        val installedSources = CsRepos.installed(requireContext())
        val pluginNames = mutableListOf("Simkl")
        val pluginIds = mutableListOf("simkl")
        installedSources.forEach { src ->
            pluginNames.add(src.name)
            pluginIds.add(src.id)
        }

        fun showSourcePicker() {
            val savedSource = PrefManager.getVal<String>(PrefName.ContentSource)
            val restoreIdx = pluginIds.indexOfFirst { it.equals(savedSource, ignoreCase = true) }
            val currentIdx = if (restoreIdx >= 0) restoreIdx else 0
            pluginNameView.text = pluginNames[currentIdx]

            val adapterPos = if (currentIdx == 0) 1 else currentIdx + 3
            val picker = SourcePickerAdapter(requireContext(), pluginNames, adapterPos, headerSecond = "Plugin")
            requireContext().customAlertDialog().apply {
                setTitle(R.string.home_metadata)
                setWidthPx((240 * requireContext().resources.displayMetrics.density).toInt())

                attach { d ->
                    d.listView?.choiceMode = android.widget.ListView.CHOICE_MODE_NONE
                }
                singleChoiceAdapter(picker, adapterPos) { pos ->

                    val idx = when {
                        pos == 1 -> 0
                        pos >= 4 -> pos - 3
                        else -> return@singleChoiceAdapter
                    }
                    if (idx !in pluginIds.indices || idx !in pluginNames.indices) {
                        return@singleChoiceAdapter
                    }

                    picker.setChecked(pos)
                    PrefManager.setVal(PrefName.ContentSource, pluginIds[idx])
                    pluginNameView.text = pluginNames[idx]
                    (activity as? MainActivity)?.setContentMode("movie_tv")
                }
                show()
            }
        }

        pluginNameView.text = pluginNames.firstOrNull { it.equals(
            PrefManager.getVal<String>(PrefName.ContentSource), ignoreCase = true
        ) } ?: pluginNames[0]
        pluginRowInner.setOnClickListener { showSourcePicker() }
        pluginNameView.setOnClickListener { showSourcePicker() }
        pluginArrow.setOnClickListener { showSourcePicker() }

        fun findCheckedPos(names: ArrayList<String>, ids: ArrayList<String>, savedCat: String, trackerId: String): Int {
            if (savedCat.isNotBlank()) {
                val idx = ids.indexOfFirst { it.equals(savedCat, ignoreCase = true) }
                if (idx >= 0) return idx
            }
            val idx = ids.indexOfFirst { it.equals(trackerId, ignoreCase = true) }
            return if (idx >= 0) idx else if (names.isNotEmpty()) 1 else 0
        }

        fun showAnimeCataloguePicker() {
            val savedTracker = PrefManager.getVal<Int>(PrefName.SelectedTracker)
            val trackerName = if (savedTracker == 1) "MAL" else "AniList"
            val trackerId = if (savedTracker == 1) "mal" else "anilist"

            val catalogueNames = mutableListOf<String>()
            val catalogueIds = mutableListOf<String>()

            catalogueNames.add(trackerName)
            catalogueIds.add(trackerId)

            AnimeSources.nativeParsers.forEach { parser ->
                val name = parser.name
                if (name.isNotBlank() && !catalogueNames.contains(name)) {
                    catalogueNames.add(name)
                    catalogueIds.add("native:${parser.saveName}")
                }
            }

            try {
                AnimeSources.list.forEach { lazy ->
                    val name = lazy.name
                    if (name.isNotBlank() && name != "Local" && !catalogueNames.contains(name)) {
                        catalogueNames.add(name)
                        catalogueIds.add("ext:$name")
                    }
                }
            } catch (_: Exception) {}

            val savedCat = PrefManager.getVal<String>(PrefName.AnimeCatalogueSource)
            var currentIdx = 0
            if (savedCat.isNotBlank()) {
                val found = catalogueIds.indexOfFirst { it.equals(savedCat, ignoreCase = true) }
                if (found >= 0) currentIdx = found
            }
            animeCatalogueNameView.text = catalogueNames.getOrNull(currentIdx) ?: trackerName

            val namesWithStructure = ArrayList<String>()
            val idsWithStructure = ArrayList<String>()
            namesWithStructure.add("List")
            idsWithStructure.add("__HEADER_LIST__")

            for (i in catalogueIds.indices) {
                val id = catalogueIds[i]
                if (id.startsWith("native:") || id.startsWith("ext:")) continue
                namesWithStructure.add(catalogueNames[i])
                idsWithStructure.add(catalogueIds[i])
            }
            namesWithStructure.add("__DIVIDER__")
            idsWithStructure.add("__DIVIDER__")
            namesWithStructure.add("Extensions")
            idsWithStructure.add("__HEADER_EXT__")
            for (i in catalogueIds.indices) {
                val id = catalogueIds[i]
                if (id.startsWith("native:")) {
                    namesWithStructure.add(catalogueNames[i])
                    idsWithStructure.add(catalogueIds[i])
                }
            }
            for (i in catalogueIds.indices) {
                val id = catalogueIds[i]
                if (id.startsWith("ext:")) {
                    namesWithStructure.add(catalogueNames[i])
                    idsWithStructure.add(catalogueIds[i])
                }
            }

            val adapter = SourcePickerAdapterStructured(requireContext(), namesWithStructure, idsWithStructure, findCheckedPos(namesWithStructure, idsWithStructure, savedCat, trackerId))
            requireContext().customAlertDialog().apply {
                setTitle(R.string.source)
                setWidthPx((240 * requireContext().resources.displayMetrics.density).toInt())
                attach { d ->
                    d.listView?.choiceMode = android.widget.ListView.CHOICE_MODE_NONE
                }
                singleChoiceAdapter(adapter, adapter.checkedPosition) { pos ->
                    val id = adapter.getItemIdFor(pos)
                    if (id == "__HEADER_LIST__" || id == "__HEADER_EXT__" || id == "__DIVIDER__") return@singleChoiceAdapter
                    adapter.setChecked(pos)
                    PrefManager.setVal(PrefName.AnimeCatalogueSource, id)
                    val name = adapter.getItemNameFor(pos)
                    Logger.log("Catalogue picker: selected id='$id' name='$name'")
                    animeCatalogueNameView.text = name
                    (activity as? MainActivity)?.setContentMode("anime")
                }
                show()
            }
        }

        val savedTrackerInit = PrefManager.getVal<Int>(PrefName.SelectedTracker)
        val savedCatInit = PrefManager.getVal<String>(PrefName.AnimeCatalogueSource)
        val trackerNameInit = if (savedTrackerInit == 1) "MAL" else "AniList"
        if (savedCatInit.isBlank()) {
            animeCatalogueNameView.text = trackerNameInit
        } else {
            if (savedCatInit.equals("anilist", true) || savedCatInit.equals("mal", true)) {

                animeCatalogueNameView.text = trackerNameInit
            } else if (savedCatInit.startsWith("native:")) {
                val sn = savedCatInit.substringAfter("native:")
                val pn = AnimeSources.allNativeParsers.firstOrNull { it.saveName == sn }?.name ?: sn
                animeCatalogueNameView.text = pn
            } else if (savedCatInit.startsWith("ext:")) {
                animeCatalogueNameView.text = savedCatInit.substringAfter("ext:")
            } else {
                animeCatalogueNameView.text = savedCatInit
            }
        }
        animeCatalogueRowInner.setOnClickListener { showAnimeCataloguePicker() }
        animeCatalogueNameView.setOnClickListener { showAnimeCataloguePicker() }
        animeCatalogueArrow.setOnClickListener { showAnimeCataloguePicker() }

        when (savedTracker) {
            0 -> aniListCheck.isChecked = true
            1 -> malCheck.isChecked = true
            2 -> simklCheck.isChecked = true
        }

        updateCollapsedFocusChain()

        FocusEffectUtil.applyFocusListener(animeButton)
        FocusEffectUtil.applyFocusListener(movieButton)
        FocusEffectUtil.applyFocusListener(aniListCheck)
        FocusEffectUtil.applyFocusListener(malCheck)
        FocusEffectUtil.applyFocusListener(simklCheck)
        animeButton.post { animeButton.requestFocus() }
    }

    private fun expandSection(section: View, arrow: ImageView) {
        AnimUtils.rollExpand(section)
        arrow.animate().rotation(180f).setDuration(AnimUtils.duration(200)).start()
    }

    private fun collapseSection(section: View, arrow: ImageView) {
        AnimUtils.rollCollapse(section)
        arrow.animate().rotation(0f).setDuration(AnimUtils.duration(200)).start()
    }

    override fun onStart() {
        super.onStart()
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isTv || isLandscape) {
            val sheet = requireView().parent as? View ?: return
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _view = null
    }
}

private class SourcePickerAdapterStructured(
    private val context: Context,
    private val names: ArrayList<String>,
    private val ids: ArrayList<String>,
    var checkedPosition: Int
) : android.widget.BaseAdapter() {
    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_DIVIDER = 1
        private const val TYPE_HEADER = 2
    }

    private fun newRadioIndicator(): android.graphics.drawable.Drawable? {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.listChoiceIndicatorSingle))
        return try {
            a.getDrawable(0)?.mutate()
        } finally {
            a.recycle()
        }
    }

    private fun bindRadioState(row: android.widget.CheckedTextView, checked: Boolean) {
        row.isChecked = checked
        val d = row.compoundDrawables?.getOrNull(0) ?: return
        d.setState(
            if (checked) intArrayOf(android.R.attr.state_checked)
            else intArrayOf(-android.R.attr.state_checked)
        )
        d.setBounds(0, 0, d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1))
    }

    private val rowMaxWidth = (context.resources.displayMetrics.density * 280).toInt()

    fun setChecked(position: Int) {
        if (position == checkedPosition) return
        checkedPosition = position
        notifyDataSetChanged()
    }

    fun getItemIdFor(position: Int): String = if (position in ids.indices) ids[position] else ""
    fun getItemNameFor(position: Int): String = if (position in names.indices) names[position] else ""

    override fun getCount(): Int = names.size
    override fun getItem(position: Int): Any = names[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 3
    override fun getItemViewType(position: Int): Int = when (ids[position]) {
        "__HEADER_LIST__", "__HEADER_EXT__" -> TYPE_HEADER
        "__DIVIDER__" -> TYPE_DIVIDER
        else -> TYPE_ROW
    }
    override fun isEnabled(position: Int): Boolean = when (ids[position]) {
        "__HEADER_LIST__", "__HEADER_EXT__", "__DIVIDER__" -> false
        else -> true
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val primary = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        return when (ids[position]) {
            "__HEADER_LIST__", "__HEADER_EXT__" -> {
                val header = convertView as? TextView ?: TextView(context).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textSize = 12f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    isEnabled = false
                }
                header.text = if (ids[position] == "__HEADER_LIST__") "List" else "Extensions"
                header.setTextColor(primary)
                val dp = context.resources.displayMetrics.density
                header.setPadding(
                    (dp * 20).toInt(),
                    if (ids[position] == "__HEADER_EXT__") (dp * 12).toInt() else 0,
                    (dp * 8).toInt(),
                    0
                )
                header
            }
            "__DIVIDER__" -> {
                View(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (context.resources.displayMetrics.density * 1).toInt().coerceAtLeast(1)
                    )
                    background = android.graphics.drawable.ColorDrawable(0x2BFFFFFF)
                }
            }
            else -> {
                val row = convertView as? CheckedTextView
                    ?: CheckedTextView(context).apply {
                        setCompoundDrawablesWithIntrinsicBounds(newRadioIndicator(), null, null, null)
                        compoundDrawablePadding = (context.resources.displayMetrics.density * 2).toInt()
                        gravity = Gravity.CENTER_VERTICAL
                    }
                row.setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                row.text = names[position]
                row.maxWidth = rowMaxWidth
                row.ellipsize = android.text.TextUtils.TruncateAt.END
                row.setSingleLine(true)
                row.setPadding(0, 0, 0, 0)
                bindRadioState(row, position == checkedPosition)
                row
            }
        }
    }
}

private class SourcePickerAdapter(
    private val context: Context,
    private val names: List<String>,
    checkedPosition: Int,
    private val headerSecond: String = "Plugin"
) : android.widget.BaseAdapter() {

    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_DIVIDER = 1
        private const val TYPE_HEADER = 2
    }

    private fun newRadioIndicator(): android.graphics.drawable.Drawable? {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.listChoiceIndicatorSingle))
        return try {
            a.getDrawable(0)?.mutate()
        } finally {
            a.recycle()
        }
    }

    private fun bindRadioState(row: android.widget.CheckedTextView, checked: Boolean) {
        row.isChecked = checked
        val d = row.compoundDrawables?.getOrNull(0) ?: return
        d.setState(
            if (checked) intArrayOf(android.R.attr.state_checked)
            else intArrayOf(-android.R.attr.state_checked)
        )
        d.setBounds(0, 0, d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1))
    }

    private val rowMaxWidth = (context.resources.displayMetrics.density * 280).toInt()

    private var checkedPosition = checkedPosition

    fun setChecked(position: Int) {
        if (position == checkedPosition) return
        checkedPosition = position
        notifyDataSetChanged()
    }

    override fun getCount(): Int = names.size + 3

    override fun getItem(position: Int): Any = when {
        position == 1 -> names[0]
        position >= 4 -> names[position - 3]
        else -> ""
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 3

    override fun getItemViewType(position: Int): Int = when (position) {
        0, 3 -> TYPE_HEADER
        2 -> TYPE_DIVIDER
        else -> TYPE_ROW
    }

    override fun isEnabled(position: Int): Boolean =
        position == 1 || position >= 4

    private fun realIndex(position: Int): Int = when (position) {
        1 -> 0
        else -> position - 3
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val primary = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        return when (position) {
            0, 3 -> {
                val header = convertView as? TextView ?: TextView(context).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textSize = 12f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    isEnabled = false
                }
                header.text = if (position == 0) "List" else headerSecond
                header.setTextColor(primary)
                val dp = context.resources.displayMetrics.density
                header.setPadding(
                    (dp * 20).toInt(),
                    if (position == 3) (dp * 12).toInt() else 0,
                    (dp * 8).toInt(),
                    0
                )
                header
            }

            2 -> {

                View(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (context.resources.displayMetrics.density * 1).toInt().coerceAtLeast(1)
                    )
                    background = android.graphics.drawable.ColorDrawable(0x2BFFFFFF)
                }
            }

            else -> {
                val row = convertView as? CheckedTextView
                    ?: CheckedTextView(context).apply {

                        setCompoundDrawablesWithIntrinsicBounds(newRadioIndicator(), null, null, null)
                        compoundDrawablePadding = (context.resources.displayMetrics.density * 2).toInt()
                        gravity = Gravity.CENTER_VERTICAL
                    }
                row.setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                row.text = names[realIndex(position)]
                row.maxWidth = rowMaxWidth
                row.ellipsize = android.text.TextUtils.TruncateAt.END
                row.setSingleLine(true)
                row.setPadding(0, 0, 0, 0)

                bindRadioState(row, position == checkedPosition)
                row
            }
        }
    }
}
