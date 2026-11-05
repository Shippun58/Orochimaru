package ani.sanin.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import ani.sanin.R
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.initActivity
import ani.sanin.media.MediaType
import ani.sanin.navBarHeight
import ani.sanin.restartApp
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.customAlertDialog
import ani.sanin.util.FocusEffectUtil
import eu.kanade.domain.base.BasePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SettingsExtensionsActivity : AppCompatActivity() {
    private val extensionInstaller = Injekt.get<BasePreferences>().extensionInstaller()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        val binding = ActivitySettingsSubscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.subscreenContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.subscreenBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        FocusEffectUtil.applyFocusListener(binding.subscreenBack)
        binding.subscreenTitle.text = getString(R.string.extensions)
        binding.subscreenSubtitle.text = getString(R.string.anime_add_repository_desc)
        binding.subscreenIcon.setImageResource(R.drawable.ic_settings_tools)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section(
                getString(R.string.anime_add_repository),
                R.drawable.ic_github,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = getString(R.string.anime_add_repository),
                        desc = getString(R.string.anime_add_repository_desc),
                        iconRes = R.drawable.ic_github,
                        onClick = { ctx ->
                            val animeRepos = PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos)
                            AddRepositoryBottomSheet.newInstance(
                                MediaType.ANIME,
                                animeRepos.toList(),
                                onRepositoryAdded = { input, mediaType ->
                                    AddRepositoryBottomSheet.addRepo(input, mediaType)
                                },
                                onRepositoryRemoved = { item, mediaType ->
                                    AddRepositoryBottomSheet.removeRepo(item, mediaType)
                                }
                            ).show(supportFragmentManager, "add_repo")
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Proxy",
                R.drawable.swap_horizontal_circle_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = getString(R.string.user_agent),
                        desc = getString(R.string.user_agent_desc),
                        iconRes = R.drawable.ic_round_video_settings_24,
                        onClick = { ctx ->
                            val dialogView = ani.sanin.databinding.DialogUserAgentBinding.inflate(layoutInflater)
                            val editText = dialogView.userAgentTextBox
                            editText.setText(PrefManager.getVal<String>(PrefName.DefaultUserAgent))
                            ctx.customAlertDialog().apply {
                                setTitle(R.string.user_agent)
                                setCustomView(dialogView.root)
                                setPosButton(R.string.ok) {
                                    PrefManager.setVal(PrefName.DefaultUserAgent, editText.text.toString())
                                }
                                setNeutralButton(R.string.reset) {
                                    PrefManager.removeVal(PrefName.DefaultUserAgent)
                                    editText.setText("")
                                }
                                setNegButton(R.string.cancel)
                            }.show()
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.proxy),
                        desc = getString(R.string.proxy_desc),
                        iconRes = R.drawable.swap_horizontal_circle_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.EnableSocks5Proxy) to {
                            PrefManager.setVal(PrefName.EnableSocks5Proxy, it); restartApp()
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.proxy_setup),
                        desc = getString(R.string.proxy_setup_desc),
                        iconRes = R.drawable.lan_24,
                        onClick = { ProxyDialogFragment().show(supportFragmentManager, "dialog") },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Preferences",
                R.drawable.ic_round_new_releases_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = getString(R.string.force_legacy_installer),
                        desc = getString(R.string.force_legacy_installer_desc),
                        iconRes = R.drawable.ic_round_new_releases_24,
                        switch = (extensionInstaller.get() == BasePreferences.ExtensionInstaller.LEGACY) to {
                            extensionInstaller.set(
                                if (it) BasePreferences.ExtensionInstaller.LEGACY
                                else BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
                            )
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.skip_loading_extension_icons),
                        desc = getString(R.string.skip_loading_extension_icons_desc),
                        iconRes = R.drawable.ic_round_no_icon_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.SkipExtensionIcons) to {
                            PrefManager.setVal(PrefName.SkipExtensionIcons, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.NSFWExtention),
                        desc = getString(R.string.NSFWExtention_desc),
                        iconRes = R.drawable.ic_round_nsfw_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NSFWExtension) to {
                            PrefManager.setVal(PrefName.NSFWExtension, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Auto-Update Extensions",
                        desc = "Update extensions on startup",
                        switch = PrefManager.getVal<Boolean>(PrefName.AutoUpdateExtensions) to { v: Boolean -> PrefManager.setVal(PrefName.AutoUpdateExtensions, v) },
                    ),
                ),
            ),
        ))
    }
}
