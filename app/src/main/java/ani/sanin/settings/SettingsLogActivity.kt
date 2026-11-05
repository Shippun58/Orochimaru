package ani.sanin.settings

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import ani.sanin.R
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.toast
import ani.sanin.util.Logger
import ani.sanin.util.LogcatBuffer

class SettingsLogActivity : AppCompatActivity() {
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
        binding.subscreenTitle.text = "Log Manager"
        binding.subscreenSubtitle.text = "Logging, logcat & diagnostics"
        binding.subscreenIcon.setImageResource(R.drawable.ic_round_edit_note_24)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section(
                "Logging",
                R.drawable.ic_round_edit_note_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Master Logging",
                        desc = "Toggle all log capture features",
                        iconRes = R.drawable.ic_round_edit_note_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.LoggingEnabled) to { isChecked ->
                            PrefManager.setVal(PrefName.LoggingEnabled, isChecked)
                            if (isChecked) {
                                Logger.init(this)
                                LogcatBuffer.start()
                                Logger.log(Log.WARN, "Logging enabled manually")
                                toast("Logging enabled")
                            } else {
                                LogcatBuffer.stop()
                                Logger.clearLog()
                                toast("Logging disabled")
                            }
                            recreate()
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Log Actions",
                R.drawable.ic_round_history_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "View Live Logcat",
                        desc = "Real-time logcat output",
                        iconRes = R.drawable.ic_round_view_list_24,
                        onClick = { startActivity(Intent(this, LiveLogcatActivity::class.java)) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Capture Last 2 Minutes",
                        desc = "Export recent logcat entries",
                        iconRes = R.drawable.ic_round_history_24,
                        onClick = {
                            if (!PrefManager.getVal<Boolean>(PrefName.LoggingEnabled)) {
                                toast("Enable Logging first")
                            } else {
                                Logger.shareTextAsFile(this, Logger.readLogcatLastMinutes(2), "Logcat - Last 2 Minutes")
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Clear Log Cache",
                        desc = "Delete all stored log files",
                        iconRes = R.drawable.ic_round_delete_24,
                        onClick = { Logger.clearLog(); toast("Log cache cleared") },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Share Log File",
                        desc = "Send the saved log to others",
                        iconRes = R.drawable.ic_round_share_24,
                        onClick = { Logger.shareLog(this) },
                    ),
                ),
            ),
        ))
    }
}
