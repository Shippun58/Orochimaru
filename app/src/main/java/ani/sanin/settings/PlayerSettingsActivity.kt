package ani.sanin.settings

import android.app.AlertDialog
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.updateLayoutParams
import ani.sanin.R
import ani.sanin.databinding.ActivityPlayerSettingsBinding
import ani.sanin.databinding.ItemSubtitlePreviewBinding
import ani.sanin.initActivity
import ani.sanin.media.Media
import ani.sanin.parsers.Subtitle
import ani.sanin.others.Xpandable
import ani.sanin.others.getSerialized
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.statusBarHeight
import ani.sanin.navBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.toast
import ani.sanin.withAlphaFraction
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import com.google.android.material.slider.Slider
import com.google.android.material.slider.Slider.OnChangeListener
import com.google.android.material.tabs.TabLayout
import kotlin.math.roundToInt

class PlayerSettingsActivity :
    AppCompatActivity() {

    private var previewBinding: ItemSubtitlePreviewBinding? = null

    lateinit var binding: ActivityPlayerSettingsBinding
    private val player = "player_settings"

    var media: Media? = null
    var subtitle: Subtitle? = null

    private val Int.toSP
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            this.toFloat(),
            Resources.getSystem().displayMetrics,
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityPlayerSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        onBackPressedDispatcher.addCallback(this) { finish() }

        try {
            media = intent.getSerialized("media")
            subtitle = intent.getSerialized("subtitle")
        } catch (e: Exception) {
            toast(e.toString())
        }

        binding.playerSettingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        binding.playerSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        setupTabs()
    }

    private fun setupTabs() {
        val tabs = listOf("Video", "Subtitle", "Playback", "Progress")
        tabs.forEach { title ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(title))
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> buildVideoTab()
                    1 -> buildSubtitleTab()
                    2 -> buildPlaybackTab()
                    3 -> buildProgressTab()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> buildVideoTab()
                    1 -> buildSubtitleTab()
                    2 -> buildPlaybackTab()
                    3 -> buildProgressTab()
                }
            }
        })

        binding.tabLayout.getTabAt(0)?.select()
    }

    private fun clearTabs() {
        binding.subtitlePreviewContainer.removeAllViews()
        binding.subtitlePreviewContainer.visibility = View.GONE
        binding.tabContent.removeAllViews()
        previewBinding = null
    }

    private fun buildVideoTab() {
        clearTabs()
        val speeds = arrayOf(0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 2f)
        val speedsName = speeds.map { "${it}x" }.toTypedArray()
        val wrapBtnLabels = arrayOf("Off", "Dark Tint", "White Tint", "No Tint", "Primary Tint")

        SubscreenBuilder.build(this, binding.tabContent, listOf(
            SubscreenBuilder.Section("Video", R.drawable.ic_set_video, entries = listOf(
                SubscreenBuilder.Entry(
                    title = getString(R.string.prefer_dub),
                    desc = "Use dub track when available",
                    iconRes = R.drawable.ic_round_subtitles_24,
                    switch = PrefManager.getVal<Boolean>(PrefName.PreferDub) to {
                        PrefManager.setVal(PrefName.PreferDub, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Buffer duration",
                    desc = "Maximum forward buffer time.",
                    choice = SubscreenBuilder.Choice(
                        title = "Buffer duration",
                        options = arrayOf("15 s", "30 s", "60 s", "90 s", "120 s"),
                        currentIndex = when (PrefManager.getVal<Int>(PrefName.BufferDurationSeconds)) {
                            15 -> 0; 30 -> 1; 60 -> 2; 90 -> 3; 120 -> 4; else -> 2
                        },
                    ) { idx ->
                        PrefManager.setVal(PrefName.BufferDurationSeconds, intArrayOf(15, 30, 60, 90, 120)[idx])
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Buffer memory limit",
                    desc = "Caps RAM used by playback buffers.",
                    choice = SubscreenBuilder.Choice(
                        title = "Buffer memory limit",
                        options = arrayOf("64 MB", "128 MB", "192 MB", "256 MB"),
                        currentIndex = when (PrefManager.getVal<Int>(PrefName.BufferTargetMb)) {
                            64 -> 0; 128 -> 1; 192 -> 2; 256 -> 3; else -> 1
                        },
                    ) { idx ->
                        PrefManager.setVal(PrefName.BufferTargetMb, intArrayOf(64, 128, 192, 256)[idx])
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Pause Overlay",
                    desc = "Show overlay when paused",
                    iconRes = R.drawable.ic_set_overlay,
                    switch = PrefManager.getVal<Boolean>(PrefName.PauseOverlay) to {
                        PrefManager.setVal(PrefName.PauseOverlay, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Wrap Buttons",
                    desc = "Player control button style",
                    choice = SubscreenBuilder.Choice(
                        title = "Wrap Buttons",
                        options = wrapBtnLabels,
                        currentIndex = PrefManager.getVal<Int>(PrefName.WrapButtons),
                    ) { idx -> PrefManager.setVal(PrefName.WrapButtons, idx) },
                ),
                SubscreenBuilder.Entry(
                    title = "Gesture Sliders",
                    desc = "Brightness & volume via swipe",
                    iconRes = R.drawable.ic_set_motion,
                    switch = PrefManager.getVal<Boolean>(PrefName.GestureSliders) to {
                        PrefManager.setVal(PrefName.GestureSliders, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.confirm_player_exit),
                    desc = "Show exit confirmation dialog",
                    switch = PrefManager.getVal<Boolean>(PrefName.ConfirmPlayerExit) to {
                        PrefManager.setVal(PrefName.ConfirmPlayerExit, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Pause on Uninteraction",
                    desc = "Pause when player loses focus",
                    switch = PrefManager.getVal<Boolean>(PrefName.FocusPause) to {
                        PrefManager.setVal(PrefName.FocusPause, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.fast_forward),
                    desc = "Enable fast forward button",
                    switch = PrefManager.getVal<Boolean>(PrefName.FastForward) to {
                        PrefManager.setVal(PrefName.FastForward, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.double_tap),
                    desc = getString(R.string.double_tap_info),
                    switch = PrefManager.getVal<Boolean>(PrefName.DoubleTap) to {
                        PrefManager.setVal(PrefName.DoubleTap, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.seek_time),
                    desc = getString(R.string.seek_time_info),
                    slider = SubscreenBuilder.SliderOption(
                        value = PrefManager.getVal<Int>(PrefName.SeekTime).toFloat(),
                        valueFrom = 1f, valueTo = 30f, step = 1f, suffix = "s"
                    ) { PrefManager.setVal(PrefName.SeekTime, it.toInt()) },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.seek_sensitivity),
                    desc = getString(R.string.seek_sensitivity_info),
                    slider = SubscreenBuilder.SliderOption(
                        value = PrefManager.getVal<Int>(PrefName.SeekSensitivity).toFloat(),
                        valueFrom = 50f, valueTo = 500f, step = 10f, suffix = "ms"
                    ) { PrefManager.setVal(PrefName.SeekSensitivity, it.toInt()) },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.skip_time),
                    desc = getString(R.string.skip_time_info),
                    onClick = { ctx ->
                        ctx.customAlertDialog().apply {
                            setTitle(getString(R.string.skip_time))
                            val input = android.widget.EditText(this@PlayerSettingsActivity).apply {
                                setText(PrefManager.getVal<Int>(PrefName.SkipTime).toString())
                                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                                selectAll()
                            }
                            setCustomView(input)
                            setPosButton(R.string.ok) {
                                val time = input.text.toString().toIntOrNull()
                                if (time != null) PrefManager.setVal(PrefName.SkipTime, time)
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Default Playback Speed",
                    desc = getString(R.string.default_speed),
                    choice = SubscreenBuilder.Choice(
                        title = getString(R.string.default_speed),
                        options = speedsName,
                        currentIndex = PrefManager.getVal<Int>(PrefName.DefaultSpeed),
                    ) { idx -> PrefManager.setVal(PrefName.DefaultSpeed, idx) },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.picture_in_picture),
                    desc = getString(R.string.picture_in_picture_des),
                    iconRes = R.drawable.ic_round_picture_in_picture_alt_24,
                    switch = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) to {
                        PrefManager.setVal(PrefName.Pip, it)
                    },
                    isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
                ),
                SubscreenBuilder.Entry(
                    title = "Decoder",
                    desc = "Hardware is fastest; Hardware+ falls back to software; Software is most compatible.",
                    iconRes = R.drawable.ic_baseline_extension_24,
                    choice = SubscreenBuilder.Choice(
                        title = "Decoder",
                        options = arrayOf("Hardware", "Hardware+", "Software"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.DecodingMode).coerceIn(0, 2),
                    ) { idx -> PrefManager.setVal(PrefName.DecodingMode, idx) },
                ),
                SubscreenBuilder.Entry(
                    title = "Video output",
                    desc = "SurfaceView is fastest; TextureView fixes black video on some devices.",
                    iconRes = R.drawable.ic_set_video,
                    choice = SubscreenBuilder.Choice(
                        title = "Video output",
                        options = arrayOf("SurfaceView", "TextureView"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.VideoOutput).coerceIn(0, 1),
                    ) { idx -> PrefManager.setVal(PrefName.VideoOutput, idx) },
                ),
            )),
        ))
    }

    private fun buildSubtitleTab() {
        clearTabs()

        val pv = ItemSubtitlePreviewBinding.inflate(layoutInflater, binding.subtitlePreviewContainer, false)
        previewBinding = pv
        binding.subtitlePreviewContainer.addView(pv.root)
        binding.subtitlePreviewContainer.visibility = View.VISIBLE

        val subtitleTest = pv.root.findViewById<ani.sanin.others.Xpandable>(R.id.subtitleTest)
        subtitleTest?.addOnChangeListener(object : Xpandable.OnChangeListener {
            override fun onExpand() {
                pv.subtitlePreviewDesc.setText(R.string.sub_preview_collapse)
                updateSubPreview()
            }
            override fun onRetract() { pv.subtitlePreviewDesc.setText(R.string.sub_preview_expand) }
        })
        updateSubPreview()

        val allProviders = arrayOf("Wyzie", "Stremio", "OpenSubtitles")
        val allLanguages = arrayOf("en","ar","pt","es","id","fr","ru","zh","ja","tr","it","de","pl","th","vi","ko")
        val allFullLanguages = arrayOf("English","Arabic","Portuguese","Spanish","Indonesian","French","Russian","Chinese","Japanese","Turkish","Italian","German","Polish","Thai","Vietnamese","Korean")
        val subLanguages = arrayOf("Albanian","Arabic","Bosnian","Bulgarian","Chinese","Croatian","Czech","Danish","Dutch","English","Estonian","Finnish","French","Georgian","German","Greek","Hebrew","Hindi","Indonesian","Irish","Italian","Japanese","Korean","Lithuanian","Luxembourgish","Macedonian","Mongolian","Norwegian","Polish","Portuguese","Punjabi","Romanian","Russian","Serbian","Slovak","Slovenian","Spanish","Turkish","Ukrainian","Urdu","Vietnamese")
        val fonts = arrayOf("Poppins Semi Bold","Poppins Bold","Poppins","Poppins Thin","Century Gothic","Levenim MT Bold","Blocky")
        val typesOutline = arrayOf("Outline", "Shine", "Drop Shadow", "None")

        val subtitlesEnabled = PrefManager.getVal<Boolean>(PrefName.Subtitles)
        SubscreenBuilder.build(this, binding.tabContent, listOf(
            SubscreenBuilder.Section("General", R.drawable.ic_set_overlay, entries = listOf(
                SubscreenBuilder.Entry(
                    title = "Subtitles",
                    desc = "Master toggle for all subtitle styling",
                    iconRes = R.drawable.ic_round_subtitles_24,
                    switch = subtitlesEnabled to { checked ->
                        PrefManager.setVal(PrefName.Subtitles, checked)

                        binding.tabLayout.getTabAt(1)?.select()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Auto Download Subtitle",
                    desc = "Automatically download server subtitles and start episodes with them",
                    isEnabled = subtitlesEnabled,
                    switch = PrefManager.getVal<Boolean>(PrefName.AutoSubtitleDownload) to { checked ->
                        PrefManager.setVal(PrefName.AutoSubtitleDownload, checked)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.subtitle_render_mode),
                    isEnabled = subtitlesEnabled,
                    desc = "CPU canvas or GPU OpenGL",
                    choice = SubscreenBuilder.Choice(
                        title = getString(R.string.subtitle_render_mode),
                        options = arrayOf("Canvas (TV default)", "OpenGL (Phone default)"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.SubtitleRenderMode),
                    ) { idx -> PrefManager.setVal(PrefName.SubtitleRenderMode, idx) },
                ),
                SubscreenBuilder.Entry(
                    title = "Online Providers",
                    desc = "Subtitle search providers",
                    isEnabled = subtitlesEnabled,
                    onClick = { ctx ->
                        val currentProviders = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleProviders)
                        val checkedItems = BooleanArray(allProviders.size) { currentProviders.contains(allProviders[it]) }
                        ctx.customAlertDialog().apply {
                            setTitle("Online Providers")
                            multiChoiceItems(allProviders, checkedItems) { checked ->
                                val selected = mutableSetOf<String>()
                                checked.forEachIndexed { idx, isChecked -> if (isChecked) selected.add(allProviders[idx]) }
                                PrefManager.setVal(PrefName.OnlineSubtitleProviders, selected)
                            }
                            setPosButton("Done", null)
                            show()
                        }
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Online Language",
                    desc = "Preferred subtitle language",
                    isEnabled = subtitlesEnabled,
                    onClick = { ctx ->
                        val currentLanguages = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleLanguages)
                        val checkedItems = BooleanArray(allLanguages.size) { currentLanguages.contains(allLanguages[it]) }
                        ctx.customAlertDialog().apply {
                            setTitle("Online Language")
                            multiChoiceItems(allFullLanguages, checkedItems) { checked ->
                                val selected = mutableSetOf<String>()
                                checked.forEachIndexed { idx, isChecked -> if (isChecked) selected.add(allLanguages[idx]) }
                                PrefManager.setVal(PrefName.OnlineSubtitleLanguages, selected)
                            }
                            setPosButton("Done", null)
                            show()
                        }
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Auto-sync subtitles (audio)",
                    desc = "Matches timing to detected speech. Manual sync still wins.",
                    isEnabled = subtitlesEnabled,
                    switch = PrefManager.getVal<Boolean>(PrefName.SubtitleAutoSyncVad) to { checked ->
                        PrefManager.setVal(PrefName.SubtitleAutoSyncVad, checked)
                    },
                ),
            )),

            SubscreenBuilder.Section("Display", R.drawable.ic_set_misc, entries = listOf(
                SubscreenBuilder.Entry(
                    title = getString(R.string.subtitle_font_size),
                    desc = "Adjust subtitle text size",
                    isEnabled = subtitlesEnabled,
                    slider = SubscreenBuilder.SliderOption(
                        value = PrefManager.getVal<Int>(PrefName.FontSize).toFloat(),
                        valueFrom = 8f, valueTo = 72f, step = 1f, suffix = "sp"
                    ) {
                        PrefManager.setVal(PrefName.FontSize, it.toInt())
                        updateSubPreview()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.sub_alpha),
                    desc = "Subtitle transparency level",
                    isEnabled = subtitlesEnabled,
                    slider = SubscreenBuilder.SliderOption(
                        value = PrefManager.getVal(PrefName.SubAlpha),
                        valueFrom = 0f, valueTo = 1f, step = 0.05f, suffix = ""
                    ) {
                        PrefManager.setVal(PrefName.SubAlpha, it)
                        updateSubPreview()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.subtitle_font),
                    desc = "Subtitle typeface",
                    isEnabled = subtitlesEnabled,
                    choice = SubscreenBuilder.Choice(
                        title = getString(R.string.subtitle_font),
                        options = fonts,
                        currentIndex = PrefManager.getVal<Int>(PrefName.Font),
                    ) {
                        PrefManager.setVal(PrefName.Font, it)
                        updateSubPreview()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.subtitle_langauge),
                    desc = "Preferred subtitle language",
                    isEnabled = subtitlesEnabled,
                    choice = SubscreenBuilder.Choice(
                        title = getString(R.string.subtitle_langauge),
                        options = subLanguages,
                        currentIndex = PrefManager.getVal<Int>(PrefName.SubLanguage),
                    ) { idx -> PrefManager.setVal(PrefName.SubLanguage, idx) },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.primary_sub_color),
                    desc = "Subtitle text color",
                    isEnabled = subtitlesEnabled,
                    onClick = { showColorPicker(PrefManager.getVal<Int>(PrefName.PrimaryColor), getString(R.string.primary_sub_color)) { PrefManager.setVal(PrefName.PrimaryColor, it); updateSubPreview() } },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.outline_sub_color),
                    desc = getString(R.string.secondary_sub_color_select),
                    isEnabled = subtitlesEnabled,
                    onClick = { showColorPicker(PrefManager.getVal<Int>(PrefName.SecondaryColor), getString(R.string.outline_sub_color)) { PrefManager.setVal(PrefName.SecondaryColor, it); updateSubPreview() } },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.outline_type),
                    desc = "Subtitle outline style",
                    isEnabled = subtitlesEnabled,
                    choice = SubscreenBuilder.Choice(
                        title = getString(R.string.outline_type),
                        options = typesOutline,
                        currentIndex = PrefManager.getVal<Int>(PrefName.Outline),
                    ) {
                        PrefManager.setVal(PrefName.Outline, it)
                        updateSubPreview()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.sub_background_color_select),
                    desc = "Subtitle background color",
                    isEnabled = subtitlesEnabled,
                    onClick = { showColorPicker(PrefManager.getVal<Int>(PrefName.SubBackground), getString(R.string.sub_background_color_select), allowNone = true) { PrefManager.setVal(PrefName.SubBackground, it); updateSubPreview() } },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.sub_window_color_select),
                    desc = "Subtitle window background",
                    isEnabled = subtitlesEnabled,
                    onClick = { ctx ->
                        ctx.customAlertDialog().apply {
                            setTitle(getString(R.string.sub_window_color_select))
                            setMessage(getString(R.string.sub_window_color_info))
                            setPosButton(R.string.ok) {
                                showColorPicker(PrefManager.getVal<Int>(PrefName.SubWindow), getString(R.string.sub_window_color_select), allowNone = true) { PrefManager.setVal(PrefName.SubWindow, it); updateSubPreview() }
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.textview_sub),
                    desc = "Experimental text-based subtitles",
                    isEnabled = subtitlesEnabled,
                    switch = PrefManager.getVal<Boolean>(PrefName.TextviewSubtitles) to {
                        PrefManager.setVal(PrefName.TextviewSubtitles, it)
                        binding.tabLayout.getTabAt(1)?.select()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.textview_sub_stroke),
                    desc = "Subtitle stroke width",
                    isEnabled = subtitlesEnabled && PrefManager.getVal<Boolean>(PrefName.TextviewSubtitles),
                    slider = SubscreenBuilder.SliderOption(
                        value = PrefManager.getVal(PrefName.SubStroke),
                        valueFrom = 0f, valueTo = 10f, step = 1f, suffix = ""
                    ) { PrefManager.setVal(PrefName.SubStroke, it) },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.textview_sub_bottom_margin),
                    desc = "Bottom margin for subtitles",
                    isEnabled = subtitlesEnabled && PrefManager.getVal<Boolean>(PrefName.TextviewSubtitles),
                    slider = SubscreenBuilder.SliderOption(
                        value = PrefManager.getVal(PrefName.SubBottomMargin),
                        valueFrom = 0f, valueTo = 100f, step = 1f, suffix = ""
                    ) { PrefManager.setVal(PrefName.SubBottomMargin, it) },
                ),
            )),
        ))
    }

    private fun buildPlaybackTab() {
        clearTabs()
        SubscreenBuilder.build(this, binding.tabContent, listOf(
            SubscreenBuilder.Section("Playback", R.drawable.ic_set_video, entries = listOf(
                SubscreenBuilder.Entry(
                    title = getString(R.string.data_saver_video),
                    desc = getString(R.string.data_saver_video_desc),
                    switch = PrefManager.getVal<Boolean>(PrefName.DataSaver) to {
                        PrefManager.setVal(PrefName.DataSaver, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.timestamps),
                    desc = "Enable segment timestamps, proxy, and skip button",
                    switch = PrefManager.getVal<Boolean>(PrefName.TimeStampsEnabled) to { checked ->
                        PrefManager.setVal(PrefName.TimeStampsEnabled, checked)
                        if (checked) {
                            PrefManager.setVal(PrefName.UseProxyForTimeStamps, true)
                            PrefManager.setVal(PrefName.ShowTimeStampButton, true)
                        }
                        binding.tabLayout.getTabAt(2)?.select()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.timestamp_proxy),
                    desc = getString(R.string.timestamp_proxy_desc),
                    isEnabled = PrefManager.getVal<Boolean>(PrefName.TimeStampsEnabled),
                    switch = PrefManager.getVal<Boolean>(PrefName.UseProxyForTimeStamps) to {
                        PrefManager.setVal(PrefName.UseProxyForTimeStamps, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.show_skip_time_stamp_button),
                    desc = "Show the skip button over the timeline",
                    isEnabled = PrefManager.getVal<Boolean>(PrefName.TimeStampsEnabled),
                    switch = PrefManager.getVal<Boolean>(PrefName.ShowTimeStampButton) to {
                        PrefManager.setVal(PrefName.ShowTimeStampButton, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.hide_skip_button),
                    desc = "Auto-hide timestamps overlay after 5 seconds",
                    isEnabled = PrefManager.getVal<Boolean>(PrefName.TimeStampsEnabled) && PrefManager.getVal<Boolean>(PrefName.ShowTimeStampButton),
                    switch = PrefManager.getVal<Boolean>(PrefName.AutoHideTimeStamps) to {
                        PrefManager.setVal(PrefName.AutoHideTimeStamps, it)
                    },
                ),
            )),

            SubscreenBuilder.Section("Automations", R.drawable.ic_set_misc, entries = listOf(
                SubscreenBuilder.Entry(
                    title = getString(R.string.auto_skip_op_ed),
                    desc = "Auto skip opening & ending",
                    switch = (PrefManager.getVal<Boolean>(PrefName.AutoSkipOPED) and PrefManager.getVal<Boolean>(PrefName.TimeStampsEnabled)) to {
                        PrefManager.setVal(PrefName.AutoSkipOPED, it)
                    },
                    isEnabled = PrefManager.getVal<Boolean>(PrefName.TimeStampsEnabled),
                ),
                SubscreenBuilder.Entry(
                    title = "Auto Skip Recap",
                    desc = "Skip recap segments",
                    switch = (PrefManager.getVal<Boolean>(PrefName.AutoSkipRecap) and PrefManager.getVal<Boolean>(PrefName.TimeStampsEnabled)) to {
                        PrefManager.setVal(PrefName.AutoSkipRecap, it)
                    },
                    isEnabled = PrefManager.getVal<Boolean>(PrefName.TimeStampsEnabled),
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.auto_play_next_episode),
                    desc = getString(R.string.auto_play_next_episode_info),
                    switch = PrefManager.getVal<Boolean>(PrefName.AutoPlay) to {
                        PrefManager.setVal(PrefName.AutoPlay, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.auto_skip_fillers),
                    desc = getString(R.string.auto_skip_fillers_info),
                    switch = PrefManager.getVal<Boolean>(PrefName.AutoSkipFiller) to {
                        PrefManager.setVal(PrefName.AutoSkipFiller, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Compatibility: Seek Bar Refresh",
                    desc = "Periodically refreshes the seek bar/watch-status timestamp even when the player controls are hidden. Turn on only if you notice stale or frozen timestamps in the player.",
                    switch = PrefManager.getVal<Boolean>(PrefName.AlwaysUpdateSeekbar) to {
                        PrefManager.setVal(PrefName.AlwaysUpdateSeekbar, it)
                    },
                ),
            )),
        ))
    }

    private fun buildProgressTab() {
        clearTabs()
        SubscreenBuilder.build(this, binding.tabContent, listOf(
            SubscreenBuilder.Section(getString(R.string.update_progress), R.drawable.ic_set_anime, entries = listOf(
                SubscreenBuilder.Entry(
                    title = getString(R.string.ask_update_progress_anime),
                    desc = getString(R.string.ask_update_progress_info_ep),
                    switch = PrefManager.getVal<Boolean>(PrefName.AskIndividualPlayer) to {
                        PrefManager.setVal(PrefName.AskIndividualPlayer, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.ask_update_progress_chapter_zero),
                    desc = getString(R.string.ask_update_progress_info_zero),
                    switch = PrefManager.getVal<Boolean>(PrefName.ChapterZeroPlayer) to {
                        PrefManager.setVal(PrefName.ChapterZeroPlayer, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.ask_update_progress_hentai),
                    desc = "Update progress for adult content",
                    switch = PrefManager.getVal<Boolean>(PrefName.UpdateForHPlayer) to {
                        PrefManager.setVal(PrefName.UpdateForHPlayer, it)
                        if (it) snackString(getString(R.string.very_bold))
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Completion Percentage",
                    desc = "At what percentage to mark as watched",
                    slider = SubscreenBuilder.SliderOption(
                        value = (PrefManager.getVal<Float>(PrefName.WatchPercentage) * 100).roundToInt().toFloat(),
                        valueFrom = 10f, valueTo = 100f, step = 5f, suffix = "%"
                    ) { PrefManager.setVal(PrefName.WatchPercentage, it / 100) },
                ),
            )),
        ))
    }

    private val presetColorNames = arrayOf(
        "White", "Yellow", "Green", "Cyan", "Blue", "Magenta", "Red", "Orange", "Black", "Grey"
    )
    private val presetColors = intArrayOf(
        Color.WHITE,
        Color.parseColor("#FFFF00"),
        Color.parseColor("#00FF00"),
        Color.parseColor("#00FFFF"),
        Color.parseColor("#0080FF"),
        Color.parseColor("#FF00FF"),
        Color.RED,
        Color.parseColor("#FF8C00"),
        Color.BLACK,
        Color.GRAY
    )

    private fun showColorPicker(
        originalColor: Int,
        title: String,
        allowNone: Boolean = false,
        callback: (Int) -> Unit,
    ) {

        val names = if (allowNone) arrayOf("None") + presetColorNames else presetColorNames
        val colors = if (allowNone) intArrayOf(Color.TRANSPARENT) + presetColors else presetColors
        val current = colors.indexOf(originalColor).let { found ->

            if (found >= 0) found else if (allowNone) -1 else 0
        }
        val backgroundOff = allowNone && originalColor == Color.TRANSPARENT
        var shown: AlertDialog? = null
        customAlertDialog().apply {
            setTitle(title)
            singleChoiceItems(names, current) { idx ->
                callback(colors[idx])
            }
            attach { dialog -> shown = dialog }

            setOnShowListener {
                val list = shown?.listView

                val listFrame = list?.parent as? ViewGroup
                val column = listFrame?.parent as? ViewGroup ?: return@setOnShowListener
                val sliderView = layoutInflater.inflate(
                    R.layout.item_settings_section_slider, column, false
                )
                val sl = sliderView.findViewById<Slider>(R.id.slider)
                val slTitle = sliderView.findViewById<TextView>(R.id.sliderTitle)
                val slDesc = sliderView.findViewById<TextView>(R.id.sliderDesc)
                val slValue = sliderView.findViewById<TextView>(R.id.sliderValue)
                slTitle.text = getString(R.string.sub_alpha)
                sl.valueFrom = 0f
                sl.valueTo = 1f
                sl.stepSize = 0.05f
                sl.value = PrefManager.getVal<Float>(PrefName.SubAlpha).coerceIn(0f, 1f)
                slValue.text = "${(sl.value * 100).roundToInt()}%"
                if (backgroundOff) {

                    slDesc.text = "Background transparency (off with no background)"
                    sl.isEnabled = false
                    sl.alpha = 0.4f
                    slValue.alpha = 0.4f
                } else {
                    slDesc.text = "Background transparency"
                }
                slDesc.visibility = View.VISIBLE
                sl.addOnChangeListener { _, value, fromUser ->

                    slValue.text = "${(value * 100).roundToInt()}%"
                    if (fromUser && !backgroundOff) {
                        PrefManager.setVal(PrefName.SubAlpha, value)
                        updateSubPreview()
                    }
                }

                sliderView.setOnKeyListener { _, keyCode, event ->
                    if (backgroundOff) return@setOnKeyListener false
                    if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val nv = when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> sl.value - sl.stepSize
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> sl.value + sl.stepSize
                        else -> return@setOnKeyListener false
                    }.coerceIn(sl.valueFrom, sl.valueTo)
                    if (nv != sl.value) {
                        sl.value = nv
                        PrefManager.setVal(PrefName.SubAlpha, nv)
                        updateSubPreview()
                        true
                    } else false
                }
                column.addView(
                    sliderView,
                    column.indexOfChild(listFrame) + 1
                )
            }
            show()
        }
    }

    private fun updateSubPreview() {
        val pv = previewBinding ?: return

        pv.subtitleTestWindow.setBackgroundColor(PrefManager.getVal<Int>(PrefName.SubWindow))
        pv.subtitleTestText.textSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()
        pv.subtitleTestText.typeface = when (PrefManager.getVal<Int>(PrefName.Font)) {
            0 -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
            1 -> ResourcesCompat.getFont(this, R.font.poppins_bold)
            2 -> ResourcesCompat.getFont(this, R.font.poppins)
            3 -> ResourcesCompat.getFont(this, R.font.poppins_thin)
            4 -> ResourcesCompat.getFont(this, R.font.century_gothic_regular)
            5 -> ResourcesCompat.getFont(this, R.font.levenim_mt_bold)
            6 -> ResourcesCompat.getFont(this, R.font.blocky)
            else -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
        }
        pv.subtitleTestText.setTextColor(PrefManager.getVal<Int>(PrefName.PrimaryColor))
        pv.subtitleTestText.setBackgroundColor(
            PrefManager.getVal<Int>(PrefName.SubBackground)
                .withAlphaFraction(PrefManager.getVal<Float>(PrefName.SubAlpha))
        )
    }
}
