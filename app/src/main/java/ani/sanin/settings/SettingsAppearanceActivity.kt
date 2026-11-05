package ani.sanin.settings

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.updateLayoutParams
import ani.sanin.R
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.restartApp
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class SettingsAppearanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsSubscreenBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsSubscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.subscreenContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.subscreenBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.subscreenTitle.text = "Appearance"
        binding.subscreenSubtitle.text = "Colors, cards, blur & glass"
        binding.subscreenIcon.setImageResource(R.drawable.ic_set_theme)

        buildSections = { SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section(
                "Theme & Palette", R.drawable.ic_set_theme,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Light or Dark",
                        desc = "Choose the base theme",
                        choice = SubscreenBuilder.Choice(
                            title = "Light or Dark",
                            options = arrayOf("Light", "Dark"),
                            currentIndex = PrefManager.getVal<Int>(PrefName.DarkMode),
                        ) { idx ->
                            PrefManager.setVal(PrefName.DarkMode, idx)
                            AppCompatDelegate.setDefaultNightMode(
                                if (idx == 1) AppCompatDelegate.MODE_NIGHT_YES
                                else AppCompatDelegate.MODE_NIGHT_NO
                            )
                            recreate()
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "OLED Background",
                        desc = "Deep black or dark surface style",
                        onClick = { ctx -> showOledBackgroundDialog() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Accent Tint",
                        desc = "App-wide color accent",
                        iconRes = R.drawable.ic_set_theme,
                        onClick = { showAccentColorPicker() },
                    ),
                ),
            ),
        ))
            addHomeScreenSection(binding.subscreenContent)
            SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section(
                "Card Design", R.drawable.ic_set_cards,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Scale",
                        desc = "How big cards appear",
                        choice = floatChoice(
                            "Scale", arrayOf("Tiny (1×)", "Small (1.25×)", "Default (1.5×)", "Big (1.75×)", "Large (2×)", "XL (2.25×)", "XXL (2.5×)"),
                            floatArrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f),
                            PrefManager.getVal<Float>(PrefName.CardSize),
                        ) { PrefManager.setVal(PrefName.CardSize, it); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Shape",
                        desc = "Rounded or compact",
                        choice = SubscreenBuilder.Choice(
                            title = "Shape", options = arrayOf("Rounded", "Compact"),
                            currentIndex = PrefManager.getVal<Int>(PrefName.CardStyle).coerceAtMost(1),
                        ) { idx -> PrefManager.setVal(PrefName.CardStyle, idx); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Orientation",
                        desc = "Landscape or portrait cards",
                        choice = SubscreenBuilder.Choice(
                            title = "Orientation", options = arrayOf("Landscape", "Portrait"),
                            currentIndex = PrefManager.getVal<Int>(PrefName.CardOrientation),
                        ) { idx -> PrefManager.setVal(PrefName.CardOrientation, idx); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Title Placement",
                        desc = "Where the card title sits",
                        onClick = { ctx -> showTitlePlacementDialog() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Card Metadata",
                        desc = "Rating, airing & info badges on cards",
                        onClick = { ctx -> showCardMetadataDialog() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Corner Radius",
                        desc = "Roundness of standard cards",
                        choice = intChoice(
                            "Corner Radius",
                            arrayOf("Sharp (0)", "Slight (20)", "Soft (40)", "Standard (50)", "Round (60)", "Very Round (80)", "Pill (100)"),
                            intArrayOf(0, 20, 40, 50, 60, 80, 100),
                            PrefManager.getVal<Int>(PrefName.StandardCardRoundness),
                        ) { PrefManager.setVal(PrefName.StandardCardRoundness, it); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Continue-Watch Radius",
                        desc = "Roundness of the continue card",
                        choice = intChoice(
                            "Continue-Watch Radius",
                            arrayOf("Sharp (0)", "Slight (20)", "Soft (40)", "Standard (50)", "Round (60)", "Very Round (80)", "Pill (100)"),
                            intArrayOf(0, 20, 40, 50, 60, 80, 100),
                            PrefManager.getVal<Int>(PrefName.ContinueWatchingCardRoundness),
                        ) { PrefManager.setVal(PrefName.ContinueWatchingCardRoundness, it); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Hide Notification Dot",
                        desc = "Remove the red badge on bell icon",
                        switch = !PrefManager.getVal<Boolean>(PrefName.ShowNotificationRedDot) to {
                            PrefManager.setVal(PrefName.ShowNotificationRedDot, !it)
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Banner & Blur", R.drawable.ic_set_blur,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Blur Banners",
                        desc = "Apply blur to home banners",
                        switch = PrefManager.getVal<Boolean>(PrefName.BlurBanners) to {
                            PrefManager.setVal(PrefName.BlurBanners, it)
                            rebuildSubscreen()
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Show Media Banner",
                        desc = "Load banner art behind the info, watch & comment tabs",
                        switch = PrefManager.getVal<Boolean>(PrefName.ShowMediaBanner) to {
                            PrefManager.setVal(PrefName.ShowMediaBanner, it)
                            rebuildSubscreen()
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Banner Transparency",
                        desc = "How much banner art shows through. 0% hides it behind a flat surface, 100% shows the art fully.",
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Float>(PrefName.BannerTransparency) * 100f,
                            valueFrom = 0f, valueTo = 100f, step = 1f,
                            suffix = "%"
                        ) { PrefManager.setVal(PrefName.BannerTransparency, it / 100f) },
                        isEnabled = PrefManager.getVal<Boolean>(PrefName.ShowMediaBanner),
                    ),
                    SubscreenBuilder.Entry(
                        title = "Banner Type",
                        desc = "Compact keeps the narrow card; Classic uses the logo, Watch Now and chips; Modern is a cinematic banner with synopsis and poster",
                        choice = SubscreenBuilder.Choice(
                            title = "Banner Type",
                            options = arrayOf("Compact", "Classic", "Modern"),
                            currentIndex = PrefManager.getVal<Int>(PrefName.BannerType),
                        ) { idx -> PrefManager.setVal(PrefName.BannerType, idx) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Blur Strength",
                        desc = "How strong the blur looks. 0 leaves the art sharp.",
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Float>(PrefName.BlurStrength),
                            valueFrom = 0f, valueTo = 25f, step = 1f,
                        ) { PrefManager.setVal(PrefName.BlurStrength, it) },
                        isEnabled = PrefManager.getVal<Boolean>(PrefName.BlurBanners),
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Glass Layers", R.drawable.ic_set_glass,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Enable Glass",
                        desc = "Master toggle for glassmorphism",
                        switch = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled) to {
                            PrefManager.setVal(PrefName.GlassEffectEnabled, it); rebuildSubscreen()
                        },
                    ),
                    SubscreenBuilder.Entry(title = "Nav Pills", switch = glassSwitch(PrefName.GlassEffectNavPills), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Side Rail", switch = glassSwitch(PrefName.GlassEffectSideRail), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Server Sheet", switch = glassSwitch(PrefName.GlassEffectServerSheet), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "List Editor", switch = glassSwitch(PrefName.GlassEffectListEditor), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Source Picker", switch = glassSwitch(PrefName.GlassEffectSourceSelector), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Episode Drawer", switch = glassSwitch(PrefName.GlassEffectEpisodeDrawer), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Subtitle Sync", switch = glassSwitch(PrefName.GlassEffectSubtitleSync), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Keyboard", switch = glassSwitch(PrefName.GlassEffectKeyboard), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                ),
            ),

            SubscreenBuilder.Section(
                "Glass Tuning", R.drawable.ic_set_glass,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Blur Amount", desc = "How blurry the glass is", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled),
                        choice = floatChoice("Blur Amount",
                            arrayOf("5", "10", "15", "20", "25", "30", "40", "50", "60", "80"),
                            floatArrayOf(5f, 10f, 15f, 20f, 25f, 30f, 40f, 50f, 60f, 80f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectBlurRadius),
                        ) { PrefManager.setVal(PrefName.GlassEffectBlurRadius, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Tint Strength", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), desc = "Color overlay opacity",
                        choice = floatChoice("Tint Strength",
                            arrayOf("10%", "20%", "30%", "40%", "50%", "60%", "70%", "80%", "90%", "Full"),
                            floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectTintOpacity),
                        ) { PrefManager.setVal(PrefName.GlassEffectTintOpacity, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Color Pop", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), desc = "Vibrancy boost",
                        choice = floatChoice("Color Pop",
                            arrayOf("Flat", "Subtle", "Soft", "Normal", "Bright", "Vivid", "Punchy", "Intense"),
                            floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 1.0f, 1.25f, 1.5f, 2.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectVibrancy),
                        ) { PrefManager.setVal(PrefName.GlassEffectVibrancy, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Refraction Height", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), choice = floatChoice("Refraction Height",
                            arrayOf("Off", "Low", "Medium", "High", "Max"),
                            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectRefractionHeight),
                        ) { PrefManager.setVal(PrefName.GlassEffectRefractionHeight, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Refraction Amount", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), choice = floatChoice("Refraction Amount",
                            arrayOf("Off", "Low", "Medium", "High", "Max"),
                            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectRefractionAmount),
                        ) { PrefManager.setVal(PrefName.GlassEffectRefractionAmount, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Fringe Effect", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), desc = "Chromatic aberration at edges",
                        choice = floatChoice("Fringe Effect",
                            arrayOf("Off", "Low", "Medium", "High", "Max"),
                            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectChromaticAberration),
                        ) { PrefManager.setVal(PrefName.GlassEffectChromaticAberration, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "3D Depth", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), desc = "Parallax depth on glass",
                        switch = PrefManager.getVal<Boolean>(PrefName.GlassEffectDepth) to {
                            PrefManager.setVal(PrefName.GlassEffectDepth, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Surface Tint", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), desc = "Color overlay on glass",
                        iconRes = R.drawable.ic_set_theme,
                        onClick = { showColorGrid("Surface Tint", PrefName.GlassEffectSurfaceTint) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Text on Glass", desc = "Text color over glass", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled),
                        iconRes = R.drawable.ic_set_theme,
                        onClick = { showColorGrid("Text on Glass", PrefName.GlassEffectTextColor) },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Side Rail & Focus", R.drawable.ic_set_nav,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Focus Style", desc = "Visual ring on focused items",
                        choice = SubscreenBuilder.Choice(
                            title = "Focus Style",
                            options = arrayOf("Glow", "Breathing", "Pulse", "Shaking", "None"),
                            currentIndex = PrefManager.getVal<Int>(PrefName.FocusEffect),
                        ) { idx -> PrefManager.setVal(PrefName.FocusEffect, idx) },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Navigation Pills", R.drawable.ic_set_nav,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Pill Height", desc = "Button height",
                        choice = intChoice("Pill Height",
                            arrayOf("36dp", "42dp", "48dp", "52dp", "58dp", "64dp", "72dp"),
                            intArrayOf(36, 42, 48, 52, 58, 64, 72),
                            PrefManager.getVal<Int>(PrefName.NavPillHeight).coerceIn(36, 72),
                        ) { PrefManager.setVal(PrefName.NavPillHeight, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Icon Size", desc = "Size of icons inside pills",
                        choice = intChoice("Icon Size",
                            arrayOf("Tiny (12dp)", "Small (16dp)", "Medium (20dp)", "Default (23dp)", "Large (28dp)"),
                            intArrayOf(12, 16, 20, 23, 28),
                            PrefManager.getVal<Int>(PrefName.NavPillIconSize).coerceIn(8, 28),
                        ) { PrefManager.setVal(PrefName.NavPillIconSize, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Corner Round", desc = "How round the pill corners are",
                        choice = intChoice("Corner Round",
                            arrayOf("Square (0)", "Slight (6)", "Soft (12)", "Default (18)", "Round (24)", "Very Round (32)", "Full Pill (48)"),
                            intArrayOf(0, 6, 12, 18, 24, 32, 48),
                            PrefManager.getVal<Int>(PrefName.NavPillCornerRadius).coerceIn(0, 48),
                        ) { PrefManager.setVal(PrefName.NavPillCornerRadius, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Icon Tint", desc = "Color of the navigation pill icons",
                        iconRes = R.drawable.ic_set_theme,
                        onClick = { showColorGrid("Icon Tint", PrefName.NavPillIconColor) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Show Icon Labels", desc = "Show the text next to the selected pill",
                        switch = PrefManager.getVal<Boolean>(PrefName.NavPillShowLabel) to {
                            PrefManager.setVal(PrefName.NavPillShowLabel, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Shrink Pill On Scroll", desc = "Collapse the rail to fewer icons while scrolling",
                        switch = PrefManager.getVal<Boolean>(PrefName.NavPillShrinkOnScroll) to {
                            PrefManager.setVal(PrefName.NavPillShrinkOnScroll, it)
                        },
                    ),

                ),
            ),
            SubscreenBuilder.Section(
                "Interface", R.drawable.ic_set_theme,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "UI Scale",
                        desc = "Resize all interface elements",
                        iconRes = R.drawable.ic_set_theme,
                        choice = SubscreenBuilder.Choice(
                            title = "UI Scale",
                            options = arrayOf("0.75x", "0.85x", "1.0x Default", "1.15x", "1.25x"),
                            currentIndex = when (PrefManager.getVal<Float>(PrefName.UIScale)) {
                                0.75f -> 0; 0.85f -> 1; 1.15f -> 3; 1.25f -> 4; else -> 2
                            },
                        ) { idx -> PrefManager.setVal(PrefName.UIScale, floatArrayOf(0.75f, 0.85f, 1f, 1.15f, 1.25f)[idx]) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Use Clearlogo in Posters",
                        desc = "Show the series logo on banners & cards instead of text titles",
                        switch = PrefManager.getVal<Boolean>(PrefName.UseClearlogoInPosters) to {
                            PrefManager.setVal(PrefName.UseClearlogoInPosters, it)
                        },
                    ),
                ),
            ),
        ), clear = false) }
        buildSections?.invoke()
    }

    private var buildSections: (() -> Unit)? = null

    private fun rebuildSubscreen() {
        buildSections?.invoke()
    }

    private fun glassSwitch(pref: PrefName): Pair<Boolean, (Boolean) -> Unit> =
        PrefManager.getVal<Boolean>(pref) to { v: Boolean -> PrefManager.setVal(pref, v) }

    private val homeSectionTitles = arrayOf(
        "Continue Watching", "Favorite", "Planned", "Missed Sequels", "Recommended"
    )
    private val homeSectionDescs = arrayOf(
        "Resume where you left off",
        "Your favourite anime",
        "What you plan to watch next",
        "Sequels you haven't caught up on",
        "Picks based on your taste",
    )

    private fun homeOrderList(): MutableList<Int> =
        PrefManager.getVal<List<Int>>(PrefName.HomeLayoutOrder).toMutableList().apply {
            val valid = filter { it in homeSectionTitles.indices }.distinct()
            clear(); addAll(valid)
            addAll(homeSectionTitles.indices.filterNot { it in valid })
        }

    private fun homeVisibilityList(): MutableList<Boolean> =
        PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout).toMutableList().apply {
            while (size < homeSectionTitles.size) add(true)
            if (size > homeSectionTitles.size) subList(homeSectionTitles.size, size).clear()
        }

    private fun showBannerStyleDialog() {
        customAlertDialog().apply {
            setTitle("Banner Style")
            singleChoiceItems(
                arrayOf(
                    getString(R.string.home_banner_carousel),
                    getString(R.string.home_banner_profile),
                    getString(R.string.home_banner_navigating),
                    getString(R.string.home_banner_off),
                ),
                PrefManager.getVal<Int>(PrefName.HomeBannerMode),
            ) { idx -> PrefManager.setVal(PrefName.HomeBannerMode, idx) }
            show()
        }
    }

    private fun addHomeScreenSection(container: LinearLayout) {
        val inflater = layoutInflater
        val sectionView = inflater.inflate(R.layout.item_settings_section, container, false)
        val header = sectionView.findViewById<LinearLayout>(R.id.sectionHeader)
        val icon = sectionView.findViewById<ImageView>(R.id.sectionIcon)
        val title = sectionView.findViewById<TextView>(R.id.sectionTitle)
        val desc = sectionView.findViewById<TextView>(R.id.sectionDesc)
        val chevron = sectionView.findViewById<ImageView>(R.id.sectionChevron)
        val items = sectionView.findViewById<LinearLayout>(R.id.sectionItems)

        icon.setImageResource(R.drawable.ic_set_home)
        title.text = "Home Screen"
        desc.text = "Show, hide & reorder home feed sections"
        desc.visibility = View.VISIBLE

        val bannerRow = inflater.inflate(R.layout.item_settings_section_entry, items, false)
        bannerRow.findViewById<ImageView>(R.id.entryIcon).setImageResource(R.drawable.ic_set_home)
        bannerRow.findViewById<TextView>(R.id.entryTitle).text = "Banner Style"
        bannerRow.findViewById<TextView>(R.id.entryDesc).apply {
            text = "How the home banner behaves"
            visibility = View.VISIBLE
        }
        FocusEffectUtil.applyFocusListener(bannerRow)
        bannerRow.setOnClickListener { showBannerStyleDialog() }
        items.addView(bannerRow)

        val hintDefault = "Drag to reorder \u2014 hold the arrows and move, or press UP/DOWN"
        val hint = TextView(this).apply {
            text = hintDefault
            setPadding(16, 12, 16, 8)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val tvColor = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tvColor, true)
            setTextColor(tvColor.data)
            alpha = 0.72f
        }

        val order = homeOrderList()
        val visibility = homeVisibilityList()
        val rows = mutableListOf<View>()
        var dragRow: Int? = null
        var keyboardRow: Int? = null

        fun saveOrder() {
            PrefManager.setVal(PrefName.HomeLayoutOrder, order.toList())
        }

        fun tintArrows(row: View, active: Boolean) {
            val tv = TypedValue()
            theme.resolveAttribute(
                if (active) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface,
                tv, true
            )
            row.findViewById<ImageView>(R.id.homeRowUpArrow).setColorFilter(tv.data)
            row.findViewById<ImageView>(R.id.homeRowDownArrow).setColorFilter(tv.data)
        }

        fun refreshArrows() {
            rows.forEachIndexed { pos, row -> tintArrows(row, dragRow == pos || keyboardRow == pos) }
        }

        fun moveRow(fromPos: Int, toPos: Int) {
            if (fromPos == toPos) return
            order.add(toPos, order.removeAt(fromPos))
            val row = rows.removeAt(fromPos)
            rows.add(toPos, row)
            items.removeViewAt(fromPos + 1)
            items.addView(row, toPos + 1)
        }

        val dragDisarm = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                dragRow = null
                keyboardRow = null
                hint.text = hintDefault
                rows.forEach { it.elevation = 0f }
                refreshArrows()
                saveOrder()
                isEnabled = false
            }
        }
        onBackPressedDispatcher.addCallback(this, dragDisarm)

        fun updateDragDisarmState() {
            dragDisarm.isEnabled = keyboardRow != null || dragRow != null
        }

        order.forEach { idx ->
            val row = inflater.inflate(R.layout.item_home_section_row, items, false)
            row.findViewById<TextView>(R.id.homeRowTitle).text = homeSectionTitles[idx]
            row.findViewById<TextView>(R.id.homeRowDesc).text = homeSectionDescs[idx]
            val dragHandle = row.findViewById<LinearLayout>(R.id.homeRowDragHandle)
            val switch = row.findViewById<MaterialSwitch>(R.id.homeRowSwitch)
            switch.isChecked = visibility[idx]
            tintArrows(row, false)

            switch.setOnCheckedChangeListener { _, checked ->
                visibility[idx] = checked
                PrefManager.setVal(PrefName.HomeLayout, visibility.toList())
                restartApp()
            }
            row.findViewById<TextView>(R.id.homeRowTitle).setOnClickListener {
                switch.isChecked = !switch.isChecked
            }

            dragHandle.setOnClickListener {
                val pos = rows.indexOf(row)
                if (keyboardRow == pos) {
                    keyboardRow = null
                    hint.text = hintDefault
                    refreshArrows()
                    saveOrder()
                    updateDragDisarmState()
                } else {
                    keyboardRow = pos
                    hint.text = "Press UP/DOWN to reorder, ENTER to confirm"
                    refreshArrows()
                    updateDragDisarmState()
                }
                dragHandle.post { dragHandle.requestFocus() }
            }

            dragHandle.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val pos = rows.indexOf(row)
                if (keyboardRow != pos) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (pos > 0) { moveRow(pos, pos - 1); keyboardRow = pos - 1; refreshArrows() }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (pos < rows.size - 1) { moveRow(pos, pos + 1); keyboardRow = pos + 1; refreshArrows() }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        keyboardRow = null
                        hint.text = hintDefault
                        refreshArrows()
                        saveOrder()
                        updateDragDisarmState()
                        false
                    }
                    else -> false
                }
            }

            row.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val pos = rows.indexOf(row)
                        if (keyboardRow == pos) {
                            keyboardRow = null
                            dragRow = pos
                            row.elevation = 8f
                            hint.text = "Release to drop"
                            refreshArrows()
                            updateDragDisarmState()
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            true
                        } else false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (dragRow != null) {
                            val rawY = event.rawY
                            var target = dragRow!!
                            rows.forEach { r ->
                                val arr = IntArray(2)
                                r.getLocationOnScreen(arr)
                                if (rawY >= arr[1] && rawY <= arr[1] + r.height) target = rows.indexOf(r)
                            }
                            if (target != dragRow) {
                                moveRow(dragRow!!, target)
                                dragRow = target
                                refreshArrows()
                            }
                            true
                        } else false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragRow != null) {
                            row.elevation = 0f
                            dragRow = null
                            hint.text = hintDefault
                            refreshArrows()
                            saveOrder()
                            updateDragDisarmState()
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    else -> false
                }
            }

            FocusEffectUtil.applyFocusListener(row)
            FocusEffectUtil.applyFocusListener(dragHandle)
            rows.add(row)
            items.addView(row)
        }

        items.addView(hint)

        var expanded = false
        fun toggleSection() {
            expanded = !expanded
            ObjectAnimator.ofFloat(
                chevron, "rotation",
                if (expanded) 0f else 180f,
                if (expanded) 180f else 0f
            ).apply { duration = if (AnimUtils.enabled(PrefName.XpandableAnimations)) 250 else 0; start() }
            if (expanded) AnimUtils.rollExpand(items) else AnimUtils.rollCollapse(items)
        }
        header.setOnClickListener { toggleSection() }
        FocusEffectUtil.applyFocusListener(header)
        chevron.rotation = 0f
        items.visibility = View.GONE

        container.addView(sectionView)
    }

    private fun showOledBackgroundDialog() {
        val initialMode = PrefManager.getVal<Int>(PrefName.OledMode)
        val view = layoutInflater.inflate(R.layout.dialog_oled_background, null)

        val group = view.findViewById<RadioGroup>(R.id.oledModeGroup)
        val buttons = listOf(
            view.findViewById<RadioButton>(R.id.oledModeOff),
            view.findViewById<RadioButton>(R.id.oledModeAmoled),
            view.findViewById<RadioButton>(R.id.oledModeGlow),
            view.findViewById<RadioButton>(R.id.oledModeGradient),
            view.findViewById<RadioButton>(R.id.oledModeVignette),
        )

        group.clearCheck()
        buttons.getOrNull(initialMode)?.isChecked = true

        val slider = view.findViewById<Slider>(R.id.slider)
        val sliderValue = view.findViewById<TextView>(R.id.sliderValue)
        view.findViewById<TextView>(R.id.sliderTitle).text = "Intensity"
        view.findViewById<TextView>(R.id.sliderDesc).apply {
            text = "Strength of the selected OLED effect"
            visibility = View.VISIBLE
        }

        fun syncSliderEnabled() {
            val enabled = buttons.indexOfFirst { it.id == group.checkedRadioButtonId } != 0
            slider.isEnabled = enabled
            slider.alpha = if (enabled) 1f else 0.35f
            sliderValue.alpha = if (enabled) 1f else 0.35f
        }

        slider.valueFrom = 0f
        slider.valueTo = 100f
        slider.stepSize = 5f
        slider.value = PrefManager.getVal<Float>(PrefName.OledIntensity) * 100f
        sliderValue.text = "${slider.value.toInt()}%"
        syncSliderEnabled()
        slider.addOnChangeListener { _, value, fromUser ->
            sliderValue.text = "${value.toInt()}%"
            if (fromUser) PrefManager.setVal(PrefName.OledIntensity, value / 100f)
        }

        var changed = false
        group.setOnCheckedChangeListener { _, checkedId ->
            val idx = buttons.indexOfFirst { it.id == checkedId }
            if (idx >= 0) {
                PrefManager.setVal(PrefName.OledMode, idx)
                if (idx != initialMode) changed = true
                syncSliderEnabled()
            }
        }

        customAlertDialog().apply {
            setCustomView(view)
            setCancelable(true)
            onDismiss { if (changed) restartApp() }
            show()
        }
    }

    private fun showTitlePlacementDialog() {
        val initialPosition = PrefManager.getVal<Int>(PrefName.CardTitlePosition)
        val view = layoutInflater.inflate(R.layout.dialog_title_placement, null)

        val group = view.findViewById<RadioGroup>(R.id.titlePlacementGroup)
        val buttons = listOf(
            view.findViewById<RadioButton>(R.id.titlePlacementOverlay),
            view.findViewById<RadioButton>(R.id.titlePlacementBelow),
            view.findViewById<RadioButton>(R.id.titlePlacementHidden),
        )

        group.clearCheck()
        buttons.getOrNull(initialPosition)?.isChecked = true

        var changed = false
        group.setOnCheckedChangeListener { _, checkedId ->
            val idx = buttons.indexOfFirst { it.id == checkedId }
            if (idx >= 0 && idx != initialPosition) {
                PrefManager.setVal(PrefName.CardTitlePosition, idx)
                changed = true
            }
        }

        val slider = view.findViewById<Slider>(R.id.slider)
        val sliderValue = view.findViewById<TextView>(R.id.sliderValue)
        view.findViewById<TextView>(R.id.sliderTitle).text = "Gradient Intensity"
        view.findViewById<TextView>(R.id.sliderDesc).apply {
            text = "How dark the gradient behind the card title is. Lower looks cleaner."
            visibility = View.VISIBLE
        }
        slider.valueFrom = 0f
        slider.valueTo = 100f
        slider.stepSize = 1f
        slider.value = PrefManager.getVal<Float>(PrefName.CardGradientIntensity) * 100f
        sliderValue.text = "${slider.value.toInt()}%"
        slider.addOnChangeListener { _, value, _ ->
            sliderValue.text = "${value.toInt()}%"
            PrefManager.setVal(PrefName.CardGradientIntensity, value / 100f)
        }

        customAlertDialog().apply {
            setCustomView(view)
            setCancelable(true)
            onDismiss { if (changed) restartApp() }
            show()
        }
    }

    private fun showCardMetadataDialog() {
        val topPref = PrefManager.getVal<Int>(PrefName.CardMetadataTop)
        val bottomPref = PrefManager.getVal<Int>(PrefName.CardMetadataBottom)
        val view = layoutInflater.inflate(R.layout.dialog_card_metadata, null)

        val topRow = view.findViewById<View>(R.id.metadataTopRow)
        val topExpanded = view.findViewById<View>(R.id.metadataTopExpanded)
        val topChevron = view.findViewById<ImageView>(R.id.metadataTopChevron)
        val ratingCheck = view.findViewById<android.widget.CheckBox>(R.id.metadataRatingCheck)
        val airingCheck = view.findViewById<android.widget.CheckBox>(R.id.metadataAiringCheck)
        val topNone = view.findViewById<android.widget.CheckBox>(R.id.metadataTopNoneCheck)

        val bottomRow = view.findViewById<View>(R.id.metadataBottomRow)
        val bottomExpanded = view.findViewById<View>(R.id.metadataBottomExpanded)
        val bottomChevron = view.findViewById<ImageView>(R.id.metadataBottomChevron)
        val subDubCheck = view.findViewById<android.widget.CheckBox>(R.id.metadataSubDubCheck)
        val progressCheck = view.findViewById<android.widget.CheckBox>(R.id.metadataProgressCheck)
        val bottomNone = view.findViewById<android.widget.CheckBox>(R.id.metadataBottomNoneCheck)

        ratingCheck.isChecked = topPref and 1 != 0
        airingCheck.isChecked = topPref and 2 != 0
        topNone.isChecked = topPref == 0
        subDubCheck.isChecked = bottomPref == 1
        progressCheck.isChecked = bottomPref == 2
        bottomNone.isChecked = bottomPref == 0

        topRow.setOnClickListener {
            val closingBottom = bottomExpanded.visibility == View.VISIBLE
            if (bottomExpanded.visibility == View.VISIBLE) {
                bottomExpanded.animate().alpha(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 120 else 0).withEndAction {
                    bottomExpanded.visibility = View.GONE; bottomExpanded.alpha = 1f
                }.start()
                bottomChevron.animate().rotation(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
            }
            if (topExpanded.visibility == View.VISIBLE) {
                topExpanded.animate().alpha(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 120 else 0).withEndAction {
                    topExpanded.visibility = View.GONE; topExpanded.alpha = 1f
                }.start()
                topChevron.animate().rotation(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
            } else {
                topExpanded.visibility = View.VISIBLE
                topExpanded.alpha = 0f
                topExpanded.animate().alpha(1f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
                topChevron.animate().rotation(180f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
                if (closingBottom) bottomChevron.animate().rotation(0f).start()
            }
        }

        ratingCheck.setOnClickListener {
            if (ratingCheck.isChecked) topNone.isChecked = false
            PrefManager.setVal(PrefName.CardMetadataTop,
                (if (ratingCheck.isChecked) 1 else 0) or (if (airingCheck.isChecked) 2 else 0))
        }
        airingCheck.setOnClickListener {
            if (airingCheck.isChecked) topNone.isChecked = false
            PrefManager.setVal(PrefName.CardMetadataTop,
                (if (ratingCheck.isChecked) 1 else 0) or (if (airingCheck.isChecked) 2 else 0))
        }
        topNone.setOnClickListener {
            if (topNone.isChecked) { ratingCheck.isChecked = false; airingCheck.isChecked = false }
            PrefManager.setVal(PrefName.CardMetadataTop, 0)
        }

        bottomRow.setOnClickListener {
            if (topExpanded.visibility == View.VISIBLE) {
                topExpanded.animate().alpha(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 120 else 0).withEndAction {
                    topExpanded.visibility = View.GONE; topExpanded.alpha = 1f
                }.start()
                topChevron.animate().rotation(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
            }
            if (bottomExpanded.visibility == View.VISIBLE) {
                bottomExpanded.animate().alpha(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 120 else 0).withEndAction {
                    bottomExpanded.visibility = View.GONE; bottomExpanded.alpha = 1f
                }.start()
                bottomChevron.animate().rotation(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
            } else {
                bottomExpanded.visibility = View.VISIBLE
                bottomExpanded.alpha = 0f
                bottomExpanded.animate().alpha(1f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
                bottomChevron.animate().rotation(180f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
            }
        }

        val bottomChecks = listOf(subDubCheck, progressCheck, bottomNone)

        fun bottomSync(enabled: Int) {
            bottomChecks.forEach { it.setOnCheckedChangeListener(null) }
            subDubCheck.isChecked = enabled == 1
            progressCheck.isChecked = enabled == 2
            bottomNone.isChecked = enabled == 0
            PrefManager.setVal(PrefName.CardMetadataBottom, enabled)
            bottomChecks.forEach { cb ->
                cb.setOnCheckedChangeListener { _, _ -> }
            }
        }

        fun bottomClick(which: Int) {
            val target = if (which == 0 && (subDubCheck.isChecked || progressCheck.isChecked)) 0 else which
            bottomSync(target)
        }

        bottomChecks.forEach { cb ->
            cb.setOnCheckedChangeListener { _, _ -> }
        }
        subDubCheck.setOnClickListener { bottomClick(1) }
        progressCheck.setOnClickListener { bottomClick(2) }
        bottomNone.setOnClickListener { bottomClick(0) }

        customAlertDialog().apply {
            setCustomView(view)
            setCancelable(true)
            onDismiss { restartApp() }
            show()
        }
    }

        private fun floatChoice(title: String, labels: Array<String>, values: FloatArray, current: Float, onSelect: (Float) -> Unit) =
        SubscreenBuilder.Choice(title, labels,
            values.indices.minByOrNull { idx -> kotlin.math.abs(values[idx] - current) } ?: 0
        ) { idx -> onSelect(values[idx]) }

    private fun intChoice(title: String, labels: Array<String>, values: IntArray, current: Int, onSelect: (Int) -> Unit) =
        SubscreenBuilder.Choice(title, labels,
            values.indices.minByOrNull { idx -> kotlin.math.abs(values[idx] - current) } ?: 0
        ) { idx -> onSelect(values[idx]) }

    private fun showAccentColorPicker() {
        val labels = arrayOf("Sanin", "Ocean", "Blood", "Lime", "Sun", "Kurama", "Saikou", "Indigo", "Monochrome")
        customAlertDialog().apply {
            setTitle("Accent Tint")
            singleChoiceItems(labels, PrefManager.getVal<Int>(PrefName.AccentColor)) { idx ->
                PrefManager.setVal(PrefName.AccentColor, idx); restartApp()
            }
            show()
        }
    }

    private val paletteColors = intArrayOf(
        Color.WHITE, Color.BLACK, Color.RED, Color.parseColor("#FF9800"),
        Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE,
        Color.parseColor("#9C27B0"), Color.parseColor("#E91E63"),
        Color.GRAY, Color.parseColor("#607D8B"), Color.parseColor("#795548"),
        Color.parseColor("#4CAF50"), Color.parseColor("#03A9F4"), Color.parseColor("#FF5722"),
    )
    private val paletteNames = arrayOf(
        "White", "Black", "Red", "Orange", "Yellow", "Green",
        "Cyan", "Blue", "Purple", "Pink", "Grey", "Blue Grey",
        "Brown", "Green 500", "Light Blue", "Deep Orange",
    )

    private fun showColorGrid(title: String, pref: PrefName) {
        val dp = resources.displayMetrics.density
        val chipSize = (48 * dp).toInt()
        val margin = (4 * dp).toInt()
        val grid = GridLayout(this).apply {
            columnCount = 4; rowCount = (paletteColors.size + 3) / 4
            setPadding(margin, margin, margin, margin)
        }
        for (i in paletteColors.indices) {
            val chip = ImageButton(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(paletteColors[i])
                    setStroke(if (paletteColors[i] == Color.BLACK) 2 else 0,
                        if (paletteColors[i] == Color.BLACK) Color.GRAY else Color.TRANSPARENT)
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = chipSize; height = chipSize; setMargins(margin, margin, margin, margin)
                }
                contentDescription = paletteNames[i]; isFocusable = true
            }
            grid.addView(chip)
        }
        AlertDialog.Builder(this).setTitle(title).setView(grid)
            .setNegativeButton("Cancel", null).show().also { dialog ->
                for (i in 0 until grid.childCount) {
                    grid.getChildAt(i).setOnClickListener {
                        PrefManager.setVal(pref, paletteColors[i]); dialog.dismiss()
                    }
                }
            }
    }
}
