package ani.sanin.settings

import android.content.Intent
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

class SettingsExtrasActivity : AppCompatActivity() {
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
        binding.subscreenTitle.text = "Extensions"
        binding.subscreenSubtitle.text = "Sources, add-ons & diagnostics"
        binding.subscreenIcon.setImageResource(R.drawable.ic_settings_tools)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section("Management", R.drawable.ic_settings_tools, entries = listOf(
                SubscreenBuilder.Entry(
                    title = "Extension Manager",
                    desc = "Install & update content sources",
                    iconRes = R.drawable.ic_baseline_extension_24,
                    onClick = { startActivity(Intent(this, SettingsExtensionsActivity::class.java)) },
                ),
                SubscreenBuilder.Entry(
                    title = "Add-ons",
                    desc = "Plugins & community extensions",
                    iconRes = R.drawable.ic_widgets,
                    onClick = { startActivity(Intent(this, SettingsAddonActivity::class.java)) },
                ),
                SubscreenBuilder.Entry(
                    title = "Cache Manager",
                    desc = "Free up storage space",
                    iconRes = R.drawable.ic_set_backup,
                    onClick = { startActivity(Intent(this, SettingsCacheActivity::class.java)) },
                ),
                SubscreenBuilder.Entry(
                    title = "Log Manager",
                    desc = "Diagnostics & error logs",
                    iconRes = R.drawable.ic_bug_report,
                    onClick = { startActivity(Intent(this, SettingsLogActivity::class.java)) },
                ),
            )),

            SubscreenBuilder.Section("Notifications", R.drawable.ic_set_overlay, listOf(
                SubscreenBuilder.Entry(
                    title = "Alert Settings",
                    desc = "Configure what you get notified about",
                    iconRes = R.drawable.ic_set_overlay,
                    onClick = { startActivity(Intent(this, SettingsNotificationActivity::class.java)) },
                ),
            )),

            SubscreenBuilder.Section("Display Hacks", R.drawable.ic_set_theme, listOf(
                SubscreenBuilder.Entry(
                    title = "Immersive Mode",
                    desc = "Hide system bars during video",
                    switch = PrefManager.getVal<Boolean>(PrefName.ImmersiveMode) to {
                        PrefManager.setVal(PrefName.ImmersiveMode, it); restartApp()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Mini Player",
                    desc = "Compact floating video mode",
                    switch = PrefManager.getVal<Boolean>(PrefName.SmallView) to {
                        PrefManager.setVal(PrefName.SmallView, it); restartApp()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Hide Scroll To Top Button",
                    desc = "Hide the arrow that jumps back to the top of a list",
                    switch = PrefManager.getVal<Boolean>(PrefName.HideScrollTopButtons) to {
                        PrefManager.setVal(PrefName.HideScrollTopButtons, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Include Adult Content In Discovery Shelves",
                    desc = "Enable NSFW titles inside Discover shelves",
                    switch = PrefManager.getVal<Boolean>(PrefName.IncludeAdultDiscovery) to {
                        PrefManager.setVal(PrefName.IncludeAdultDiscovery, it)
                    },
                ),

            )),
        ))
    }

    override fun onResume() { ThemeManager(this).applyTheme(); super.onResume() }
}
