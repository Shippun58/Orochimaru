package ani.sanin.settings

import android.os.Bundle
import android.view.ViewGroup
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

class SettingsAnimationActivity : AppCompatActivity() {

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
        binding.subscreenTitle.text = "Motion"
        binding.subscreenSubtitle.text = "Tweak how things move"
        binding.subscreenIcon.setImageResource(R.drawable.ic_set_motion)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section(
                "Animation Engine", R.drawable.ic_set_motion,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Master Toggle",
                        desc = "Enable or disable all motion globally",
                        switch = PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) to {
                            PrefManager.setVal(PrefName.AnimationsEnabled, it); restartApp()
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Use Sanin custom spinner",
                        desc = "turn off to use normal Stock Progress Bar",
                        switch = PrefManager.getVal<Boolean>(PrefName.SaninCustomSpinner) to {
                            PrefManager.setVal(PrefName.SaninCustomSpinner, it); restartApp()
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Playback Speed",
                        desc = "How fast animations play",
                        choice = SubscreenBuilder.Choice(
                            title = "Playback Speed",
                            options = arrayOf("Frozen (0×)", "Crawl (0.5×)", "Smooth (0.75×)", "Normal (1×)", "Snappy (1.25×)", "Swift (1.5×)", "Instant (1.75×)", "Blink (2×)"),
                            currentIndex = speedToIndex(PrefManager.getVal(PrefName.AnimationSpeed)),
                        ) { idx -> PrefManager.setVal(PrefName.AnimationSpeed, indexToSpeed(idx)) },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Feed & Scrolling", R.drawable.ic_set_home,
                entries = listOf(
                    entry(PrefName.BannerAnimations, "Banner Transitions"),
                    entry(PrefName.LayoutAnimations, "Layout Shuffles"),
                    entry(PrefName.TrendingScroller, "Trending Carousel"),
                    entry(PrefName.HomeAnimations, "Home Section Flows"),
                    entry(PrefName.ProfileAnimations, "Profile Entrance"),
                    entry(PrefName.LiveSideRail, "Live Side Rail"),
                ),
            ),

            SubscreenBuilder.Section(
                "Player Motion", R.drawable.ic_set_video,
                entries = listOf(
                    entry(PrefName.PlayerGestureAnimations, "Gesture Feedback"),
                    entry(PrefName.PlayerControllerAnimations, "Controller Fade"),
                    entry(PrefName.PlayerOverlayAnimations, "Overlay Slide"),
                    entry(PrefName.DoubleTapAnimations, "Double-Tap Ripple"),
                    entry(PrefName.SeekBarAnimations, "Seek Bar Glide"),
                ),
            ),

            SubscreenBuilder.Section(
                "Focus & Transit", R.drawable.ic_set_focus,
                entries = listOf(
                    entry(PrefName.NavRailAnimations, "Nav Rail Slide"),
                    entry(PrefName.FocusAnimations, "Focus Glow"),
                    entry(PrefName.KeyboardKeyAnimations, "Key Press Pop"),
                ),
            ),

            SubscreenBuilder.Section(
                "Popups & Chrome", R.drawable.ic_set_overlay,
                entries = listOf(
                    entry(PrefName.TransitionAnimations, "Screen Crossfade"),
                    entry(PrefName.NotificationPopupAnimations, "Toast Float"),
                    entry(PrefName.CommentInputAnimations, "Comment Expand"),
                    entry(PrefName.ImageDialogAnimations, "Image Zoom"),
                    entry(PrefName.LikeButtonAnimations, "Heart Burst"),
                ),
            ),

            SubscreenBuilder.Section(
                "Micro-Interactions", R.drawable.ic_set_misc,
                entries = listOf(
                    entry(PrefName.IncognitoBannerAnimations, "Incognito Drop"),
                    entry(PrefName.SearchHeaderAnimations, "Search Bounce"),
                    entry(PrefName.ScrollToTopAnimations, "Scroll-to-Top Float"),
                    entry(PrefName.InstallSpinnerAnimations, "Install Spinner"),
                    entry(PrefName.FilterResetAnimations, "Filter Snap-back"),
                    entry(PrefName.DescriptionExpandAnimations, "Description Unfold"),
                    entry(PrefName.InfoPageAnimations, "Info Page Fade"),
                    entry(PrefName.NoInternetAnimations, "Offline Splash"),
                    entry(PrefName.XpandableAnimations, "Expandable Lists"),
                    entry(PrefName.AnimatedVectorDrawables, "Vector Animations"),
                    entry(PrefName.MiscUiAnimations, "Everything Else"),
                ),
            ),
        ))
    }

    private fun entry(pref: PrefName, title: String) = SubscreenBuilder.Entry(
        title = title,
        switch = PrefManager.getVal<Boolean>(pref) to { PrefManager.setVal(pref, it) },
    )

    private fun speedToIndex(speed: Float): Int = when (speed) {
        0f -> 0; 0.625f -> 1; 1f -> 2; 1.25f -> 3
        1.5f -> 4; 1.75f -> 5; 2f -> 6; else -> 2
    }

    private fun indexToSpeed(idx: Int): Float = floatArrayOf(
        0f, 0.625f, 1f, 1.25f, 1.5f, 1.75f, 2f
    ).getOrElse(idx) { 1f }
}
