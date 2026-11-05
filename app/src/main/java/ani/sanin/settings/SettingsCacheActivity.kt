package ani.sanin.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import ani.sanin.R
import ani.sanin.connections.LogoApi
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.initActivity
import ani.sanin.media.anime.ExoplayerView
import ani.sanin.navBarHeight
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsCacheActivity : AppCompatActivity() {
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
        binding.subscreenTitle.text = "Cache Manager"
        binding.subscreenSubtitle.text = "Storage, trimming & limits"
        binding.subscreenIcon.setImageResource(R.drawable.ic_baseline_storage_24)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section(
                "Storage",
                R.drawable.ic_baseline_storage_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Clear All Cache",
                        desc = "Delete cached media, Glide & ExoPlayer data",
                        iconRes = R.drawable.ic_round_delete_24,
                        onClick = {
                            lifecycleScope.launch {
                                try {
                                    Glide.get(this@SettingsCacheActivity).clearMemory()
                                    LogoApi.clearCache()
                                    withContext(Dispatchers.IO) {
                                        this@SettingsCacheActivity.cacheDir.deleteRecursively()
                                        this@SettingsCacheActivity.externalCacheDir?.deleteRecursively()
                                        Glide.get(this@SettingsCacheActivity).clearDiskCache()
                                        ExoplayerView.clearAllCaches()
                                    }
                                    snackString("Cache cleared")
                                } catch (e: Exception) {
                                    snackString("Failed to clear cache: ${e.message}")
                                }
                            }
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Smart Trim",
                R.drawable.ic_baseline_tune_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Enable Smart Trim",
                        desc = "Automatically trim old cache files",
                        iconRes = R.drawable.ic_baseline_tune_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.SmartTrim) to {
                            PrefManager.setVal(PrefName.SmartTrim, it)
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Trim Settings",
                R.drawable.ic_baseline_tune_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Cache Cap",
                        desc = "Maximum cache size in megabytes",
                        iconRes = R.drawable.ic_baseline_storage_24,
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Int>(PrefName.CacheCapMb).toFloat(),
                            valueFrom = 70f,
                            valueTo = 200f,
                            step = 10f,
                            suffix = " MB",
                        ) { PrefManager.setVal(PrefName.CacheCapMb, it.toInt()) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Trim Interval",
                        desc = "Minutes between automatic trims",
                        iconRes = R.drawable.ic_round_history_24,
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Int>(PrefName.TrimIntervalMin).toFloat(),
                            valueFrom = 5f,
                            valueTo = 30f,
                            step = 5f,
                            suffix = " min",
                        ) { PrefManager.setVal(PrefName.TrimIntervalMin, it.toInt()) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Trim Intensity",
                        desc = "How aggressively to trim old files",
                        iconRes = R.drawable.ic_round_brightness_high_24,
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Int>(PrefName.TrimIntensity).toFloat(),
                            valueFrom = 40f,
                            valueTo = 100f,
                            step = 10f,
                            suffix = "%",
                        ) { PrefManager.setVal(PrefName.TrimIntensity, it.toInt()) },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Poster Cache",
                R.drawable.format_image_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Poster Cache Cap",
                        desc = "Maximum poster bitmap memory in megabytes",
                        iconRes = R.drawable.ic_baseline_storage_24,
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Int>(PrefName.PosterCacheCapMb).toFloat(),
                            valueFrom = 70f,
                            valueTo = 200f,
                            step = 10f,
                            suffix = " MB",
                        ) { PrefManager.setVal(PrefName.PosterCacheCapMb, it.toInt()) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Poster Trim Interval",
                        desc = "Minutes between poster cache trims",
                        iconRes = R.drawable.ic_round_history_24,
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Int>(PrefName.PosterCacheTrimIntervalMin).toFloat(),
                            valueFrom = 5f,
                            valueTo = 30f,
                            step = 5f,
                            suffix = " min",
                        ) { PrefManager.setVal(PrefName.PosterCacheTrimIntervalMin, it.toInt()) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Poster Trim Intensity",
                        desc = "How aggressively to trim old posters",
                        iconRes = R.drawable.ic_round_brightness_high_24,
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Int>(PrefName.PosterCacheTrimIntensity).toFloat(),
                            valueFrom = 40f,
                            valueTo = 100f,
                            step = 10f,
                            suffix = "%",
                        ) { PrefManager.setVal(PrefName.PosterCacheTrimIntensity, it.toInt()) },
                    ),
                ),
            ),
        ))
    }
}
