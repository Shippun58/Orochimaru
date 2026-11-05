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

class SettingsHomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsSubscreenBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsSubscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.subscreenContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight; bottomMargin = navBarHeight
        }
        binding.subscreenBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.subscreenTitle.text = "Home"
        binding.subscreenSubtitle.text = "Banner & section visibility"
        binding.subscreenIcon.setImageResource(R.drawable.ic_set_home)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section("Banner", R.drawable.ic_set_home, entries = listOf(
                SubscreenBuilder.Entry(
                    title = getString(R.string.home_banner_mode),
                    desc = getString(R.string.home_banner_mode_desc),
                    choice = SubscreenBuilder.Choice(
                        title = getString(R.string.home_banner_mode),
                        options = arrayOf(
                            getString(R.string.home_banner_carousel),
                            getString(R.string.home_banner_profile),
                            getString(R.string.home_banner_navigating),
                            getString(R.string.home_banner_off),
                        ),
                        currentIndex = PrefManager.getVal<Int>(PrefName.HomeBannerMode),
                    ) { idx -> PrefManager.setVal(PrefName.HomeBannerMode, idx) },
                ),
            )),
            SubscreenBuilder.Section("Sections", R.drawable.ic_set_cards, entries = listOf(
                SubscreenBuilder.Entry(title = "Continue Watching", switch = restartSwitch(PrefName.ShowContinueWatching)),
                SubscreenBuilder.Entry(title = "Planned", switch = restartSwitch(PrefName.ShowPlanned)),
                SubscreenBuilder.Entry(title = "Recommendations", switch = restartSwitch(PrefName.ShowRecommendations)),
                SubscreenBuilder.Entry(title = "Trending", switch = restartSwitch(PrefName.ShowTrending)),
                SubscreenBuilder.Entry(title = "Popular", switch = restartSwitch(PrefName.ShowPopular)),
                SubscreenBuilder.Entry(title = "Recent", switch = restartSwitch(PrefName.ShowRecent)),
            )),
        ))
    }

    private fun restartSwitch(pref: PrefName): Pair<Boolean, (Boolean) -> Unit> =
        PrefManager.getVal<Boolean>(pref) to { v: Boolean -> PrefManager.setVal(pref, v); restartApp() }
}
