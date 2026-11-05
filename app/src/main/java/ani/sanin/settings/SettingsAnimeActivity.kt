package ani.sanin.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
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

class SettingsAnimeActivity : AppCompatActivity() {
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
        binding.subscreenTitle.text = "Playback & App Behaviour"
        binding.subscreenSubtitle.text = "Player, decoding & sync"
        binding.subscreenIcon.setImageResource(R.drawable.ic_set_video)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section("Player", R.drawable.ic_set_video, entries = listOf(
                SubscreenBuilder.Entry(
                    title = "Player Settings",
                    desc = "Advanced player configuration",
                    iconRes = R.drawable.ic_set_video,
                    onClick = { startActivity(Intent(this, PlayerSettingsActivity::class.java)) },
                ),
                SubscreenBuilder.Entry(
                    title = "Blur Unwatched Episodes",
                    desc = "Blur episodes you haven't seen",
                    switch = PrefManager.getVal<Boolean>(PrefName.BlurUnwatchedEpisodes) to {
                        PrefManager.setVal(PrefName.BlurUnwatchedEpisodes, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Grey Watched Episodes",
                    desc = "Grey out episodes you've watched",
                    switch = PrefManager.getVal<Boolean>(PrefName.GreyWatchedEpisodes) to {
                        PrefManager.setVal(PrefName.GreyWatchedEpisodes, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.episode_metadata_source),
                    desc = "AniZip / Kitsu for anime, TMDB / Simkl for movies",
                    onClick = { showEpisodeMetadataDialog() },
                ),
                SubscreenBuilder.Entry(
                    title = "Group servers by host and language tags",
                    desc = "Group quality links by host and show SUB/DUB tags in the server sheet",
                    switch = PrefManager.getVal<Boolean>(PrefName.GroupServersByHostAndLanguageTags) to {
                        PrefManager.setVal(PrefName.GroupServersByHostAndLanguageTags, it)
                    },
                ),
            )),

            SubscreenBuilder.Section("Behavior", R.drawable.ic_set_misc, entries = listOf(
                SubscreenBuilder.Entry(
                    title = "Startup Tab",
                    desc = "Which tab opens on launch",
                    choice = SubscreenBuilder.Choice(
                        title = "Startup Tab",
                        options = arrayOf("Home", "Search", "Library", "Extensions"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.DefaultStartUpTab).coerceIn(0, 3),
                    ) { idx -> PrefManager.setVal(PrefName.DefaultStartUpTab, idx) },
                ),
                SubscreenBuilder.Entry(
                    title = "Remember Media Tab",
                    desc = "Reopen a title on the tab you last used",
                    switch = PrefManager.getVal<Boolean>(PrefName.RememberMediaTab) to { v: Boolean -> PrefManager.setVal(PrefName.RememberMediaTab, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Continue Media",
                    desc = "Auto-resume from last position",
                    switch = PrefManager.getVal<Boolean>(PrefName.ContinueMedia) to { v: Boolean -> PrefManager.setVal(PrefName.ContinueMedia, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Hide Private Sources",
                    desc = "Hide NSFW extensions from lists",
                    switch = PrefManager.getVal<Boolean>(PrefName.HidePrivate) to { v: Boolean -> PrefManager.setVal(PrefName.HidePrivate, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Adult Content",
                    desc = "Show adult-only sources",
                    switch = PrefManager.getVal<Boolean>(PrefName.AdultOnly) to { v: Boolean -> PrefManager.setVal(PrefName.AdultOnly, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Search All Sources",
                    desc = "Search across all installed sources",
                    switch = PrefManager.getVal<Boolean>(PrefName.SearchSources) to { v: Boolean -> PrefManager.setVal(PrefName.SearchSources, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Recently Updated Only",
                    desc = "Only show recently updated entries",
                    switch = PrefManager.getVal<Boolean>(PrefName.RecentlyListOnly) to { v: Boolean -> PrefManager.setVal(PrefName.RecentlyListOnly, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Keyboard Mode",
                    desc = "System keyboard or in-app toggle",
                    choice = SubscreenBuilder.Choice(
                        title = "Keyboard Mode",
                        options = arrayOf("System Keyboard", "In-App Toggle"),
                        currentIndex = if (PrefManager.getVal<Int>(PrefName.KeyboardMode) == 2) 1 else 0,
                    ) { idx -> PrefManager.setVal(PrefName.KeyboardMode, if (idx == 1) 2 else 0) },
                ),
                SubscreenBuilder.Entry(
                    title = "Server Timeout",
                    desc = "Seconds before server load timeout",
                    choice = SubscreenBuilder.Choice(
                        title = "Server Timeout",
                        options = arrayOf("4s", "8s", "12s (Default)", "16s", "20s", "30s"),
                        currentIndex = when (PrefManager.getVal<Int>(PrefName.ServerLoadTimeoutSeconds)) {
                            4 -> 0; 8 -> 1; 12 -> 2; 16 -> 3; 20 -> 4; 30 -> 5; else -> 2
                        },
                    ) { idx -> PrefManager.setVal(PrefName.ServerLoadTimeoutSeconds, intArrayOf(4, 8, 12, 16, 20, 30)[idx]) },
                ),
                SubscreenBuilder.Entry(
                    title = "Smart Source Memory",
                    desc = "Remember last source across sessions",
                    switch = PrefManager.getVal<Boolean>(PrefName.SmartSourcePersistence) to {
                        PrefManager.setVal(PrefName.SmartSourcePersistence, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.kitsu_curated_order),
                    desc = getString(R.string.kitsu_curated_order_desc),
                    switch = PrefManager.getVal<Boolean>(PrefName.KitsuCuratedOrder) to {
                        PrefManager.setVal(PrefName.KitsuCuratedOrder, it)
                    },
                ),
            )),

            SubscreenBuilder.Section("Library Sync", R.drawable.ic_set_anime, entries = listOf(
                SubscreenBuilder.Entry(
                    title = "Auto-Sync AniList",
                    desc = "Keep AniList library in sync when you set MAL as your tracker.",
                    switch = PrefManager.getVal<Boolean>(PrefName.AutoSyncAniList) to { v: Boolean -> PrefManager.setVal(PrefName.AutoSyncAniList, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Auto-Update Progress",
                    desc = "Turning on hides the prompt to track a new show and tracks automatically.",
                    switch = PrefManager.getVal<Boolean>(PrefName.AutoTrackNewShows) to { v: Boolean -> PrefManager.setVal(PrefName.AutoTrackNewShows, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Include in Library",
                    desc = "Show anime list in library tab",
                    switch = PrefManager.getVal<Boolean>(PrefName.IncludeAnimeList) to {
                        PrefManager.setVal(PrefName.IncludeAnimeList, it); restartApp()
                    },
                ),
            )),
        ))
    }

    private fun showEpisodeMetadataDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_episode_metadata, null)

        val animeRow = view.findViewById<View>(R.id.epMetaAnimeRow)
        val animeExpanded = view.findViewById<View>(R.id.epMetaAnimeExpanded)
        val animeChevron = view.findViewById<ImageView>(R.id.epMetaAnimeChevron)
        val anizipCheck = view.findViewById<CheckBox>(R.id.epMetaAniZipCheck)
        val kitsuCheck = view.findViewById<CheckBox>(R.id.epMetaKitsuCheck)
        val anizipRow = view.findViewById<View>(R.id.epMetaAniZipRow)

        val movieRow = view.findViewById<View>(R.id.epMetaMovieRow)
        val movieExpanded = view.findViewById<View>(R.id.epMetaMovieExpanded)
        val movieChevron = view.findViewById<ImageView>(R.id.epMetaMovieChevron)
        val tmdbCheck = view.findViewById<CheckBox>(R.id.epMetaTmdbCheck)
        val simklCheck = view.findViewById<CheckBox>(R.id.epMetaSimklCheck)

        val metadataApi = PrefManager.getVal<Int>(PrefName.EpisodeMetadataSource)
        anizipCheck.isChecked = metadataApi == 1
        kitsuCheck.isChecked = metadataApi == 0

        fun animeSync(enabled: Int) {
            anizipCheck.isChecked = enabled == 1
            kitsuCheck.isChecked = enabled == 0
            PrefManager.setVal(PrefName.EpisodeMetadataSource, enabled)
        }
        anizipRow.setOnClickListener { animeSync(1) }
        kitsuCheck.setOnClickListener { animeSync(0) }

        animeRow.setOnClickListener {
            if (movieExpanded.visibility == View.VISIBLE) {
                movieExpanded.animate().alpha(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 120 else 0).withEndAction {
                    movieExpanded.visibility = View.GONE; movieExpanded.alpha = 1f
                }.start()
                movieChevron.animate().rotation(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
            }
            if (animeExpanded.visibility == View.VISIBLE) {
                animeExpanded.animate().alpha(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 120 else 0).withEndAction {
                    animeExpanded.visibility = View.GONE; animeExpanded.alpha = 1f
                }.start()
                animeChevron.animate().rotation(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
            } else {
                animeExpanded.visibility = View.VISIBLE
                animeExpanded.alpha = 0f
                animeExpanded.animate().alpha(1f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
                animeChevron.animate().rotation(180f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
            }
        }
        FocusEffectUtil.applyFocusListener(animeRow)
        FocusEffectUtil.applyFocusListener(anizipRow)
        FocusEffectUtil.applyFocusListener(kitsuCheck)

        movieRow.setOnClickListener {
            if (animeExpanded.visibility == View.VISIBLE) {
                animeExpanded.animate().alpha(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 120 else 0).withEndAction {
                    animeExpanded.visibility = View.GONE; animeExpanded.alpha = 1f
                }.start()
                animeChevron.animate().rotation(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
            }
            if (movieExpanded.visibility == View.VISIBLE) {
                movieExpanded.animate().alpha(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 120 else 0).withEndAction {
                    movieExpanded.visibility = View.GONE; movieExpanded.alpha = 1f
                }.start()
                movieChevron.animate().rotation(0f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
            } else {
                movieExpanded.visibility = View.VISIBLE
                movieExpanded.alpha = 0f
                movieExpanded.animate().alpha(1f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
                movieChevron.animate().rotation(180f).setDuration(if (AnimUtils.enabled(PrefName.XpandableAnimations)) 200 else 0).start()
            }
        }
        FocusEffectUtil.applyFocusListener(movieRow)
        FocusEffectUtil.applyFocusListener(tmdbCheck)
        FocusEffectUtil.applyFocusListener(simklCheck)

        customAlertDialog().apply {
            setCustomView(view)
            setCancelable(true)
            show()
        }
    }
}
