package ani.sanin.settings

import android.view.ViewGroup
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import ani.sanin.R
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.download.DownloadManager
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.toast
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.LauncherWrapper
import ani.sanin.util.customAlertDialog

class SettingsDownloadsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsSubscreenBinding
    private lateinit var folderPicker: LauncherWrapper

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
        binding.subscreenTitle.text = getString(R.string.downloads)
        binding.subscreenSubtitle.text = getString(R.string.download_settings_desc)
        binding.subscreenIcon.setImageResource(R.drawable.ic_download_24)

        folderPicker = LauncherWrapper(this, ActivityResultContracts.OpenDocumentTree())

        buildScreen()
    }

    private fun buildScreen() {
        val shared = PrefManager.getVal<Int>(PrefName.DownloadStorage) == 1
        val folderChosen = !PrefManager.getVal<String>(PrefName.DownloadsDir).isNullOrBlank()

        SubscreenBuilder.build(
            this,
            binding.subscreenContent,
            listOf(
                SubscreenBuilder.Section(
                    title = getString(R.string.downloads),
                    iconRes = R.drawable.ic_download_24,
                    defaultExpanded = true,
                    entries = listOf(
                        SubscreenBuilder.Entry(
                            title = getString(R.string.download_settings_storage),
                            desc = getString(R.string.download_settings_storage_desc),
                            iconRes = R.drawable.ic_download_24,
                            choice = SubscreenBuilder.Choice(
                                title = getString(R.string.download_settings_storage),
                                options = arrayOf(
                                    getString(R.string.download_settings_storage_app),
                                    getString(R.string.download_settings_storage_shared),
                                ),
                                currentIndex = if (shared) 1 else 0,
                            ) { idx ->
                                PrefManager.setVal(PrefName.DownloadStorage, idx)
                            },
                        ),
                        SubscreenBuilder.Entry(
                            title = getString(R.string.download_settings_choose_folder),
                            desc = if (folderChosen) {
                                PrefManager.getVal<String>(PrefName.DownloadsDir)
                            } else {
                                getString(R.string.download_settings_choose_folder_desc)
                            },
                            iconRes = R.drawable.ic_download_24,
                            isEnabled = shared,
                            onClick = {
                                folderPicker.registerForCallback { ok ->
                                    if (ok) buildScreen()
                                }
                                folderPicker.launch()
                            },
                        ),
                    ),
                ),

                SubscreenBuilder.Section(
                    title = "Queue",
                    iconRes = R.drawable.ic_set_misc,
                    defaultExpanded = true,
                    entries = listOf(
                        SubscreenBuilder.Entry(
                            title = getString(R.string.download_settings_wifi),
                            desc = "Downloads wait until you're on Wi-Fi",
                            iconRes = R.drawable.ic_set_dns,
                            switch = PrefManager.getVal<Boolean>(PrefName.DownloadOnlyOverWifi) to {
                                PrefManager.setVal(PrefName.DownloadOnlyOverWifi, it)
                            },
                        ),
                        SubscreenBuilder.Entry(
                            title = getString(R.string.download_settings_concurrency),
                            desc = "How many episodes download at once",
                            iconRes = R.drawable.ic_set_misc,
                            choice = SubscreenBuilder.Choice(
                                title = getString(R.string.download_settings_concurrency),
                                options = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"),
                                currentIndex =
                                    (PrefManager.getVal<Int>(PrefName.DownloadConcurrency) - 1)
                                        .coerceIn(0, 9),
                            ) { idx ->
                                PrefManager.setVal(PrefName.DownloadConcurrency, idx + 1)
                            },
                        ),
                        SubscreenBuilder.Entry(
                            title = getString(R.string.download_settings_segments),
                            desc = getString(R.string.download_settings_segments_desc),
                            iconRes = R.drawable.ic_set_misc,
                            choice = SubscreenBuilder.Choice(
                                title = getString(R.string.download_settings_segments),
                                options = arrayOf("1", "2", "3", "4", "5", "6", "7", "8"),
                                currentIndex =
                                    (PrefManager.getVal<Int>(PrefName.DownloadSegments) - 1)
                                        .coerceIn(0, 7),
                            ) { idx ->
                                PrefManager.setVal(PrefName.DownloadSegments, idx + 1)
                            },
                        ),
                    ),
                ),

                SubscreenBuilder.Section(
                    title = "Notifications",
                    iconRes = R.drawable.ic_set_overlay,
                    entries = listOf(
                        SubscreenBuilder.Entry(
                            title = getString(R.string.download_settings_notifications),
                            desc = "Show progress while downloading",
                            switch = PrefManager.getVal<Boolean>(PrefName.DownloadNotifications) to {
                                PrefManager.setVal(PrefName.DownloadNotifications, it)
                            },
                        ),
                        SubscreenBuilder.Entry(
                            title = getString(R.string.download_settings_completed_notif),
                            desc = "Ping when an episode is ready",
                            isEnabled = PrefManager.getVal<Boolean>(PrefName.DownloadNotifications),
                            switch = PrefManager.getVal<Boolean>(PrefName.DownloadCompletedNotification) to {
                                PrefManager.setVal(PrefName.DownloadCompletedNotification, it)
                            },
                        ),
                    ),
                ),

                SubscreenBuilder.Section(
                    title = "Storage cleanup",
                    iconRes = R.drawable.ic_settings_tools,
                    entries = listOf(
                        SubscreenBuilder.Entry(
                            title = getString(R.string.download_settings_auto_delete),
                            desc = "Remove files after you've watched them",
                            switch = PrefManager.getVal<Boolean>(PrefName.DownloadAutoDeleteWatched) to {
                                PrefManager.setVal(PrefName.DownloadAutoDeleteWatched, it)
                            },
                        ),
                        SubscreenBuilder.Entry(
                            title = getString(R.string.download_clear_all),
                            desc = "Delete every downloaded episode",
                            iconRes = R.drawable.ic_settings_tools,
                            onClick = {
                                customAlertDialog().apply {
                                    setTitle(getString(R.string.download_clear_all))
                                    setMessage(getString(R.string.download_clear_all_msg))
                                    setPosButton(getString(R.string.ok)) {
                                        DownloadManager.deleteAll()
                                        toast(R.string.downloads_cleared)
                                    }
                                    setNegButton(getString(R.string.cancel)) {}
                                }.show()
                            },
                        ),
                    ),
                ),
            ),
        )
        FocusEffectUtil.applyFocusListener(binding.subscreenContent)
    }
}