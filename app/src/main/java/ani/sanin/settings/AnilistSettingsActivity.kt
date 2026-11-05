package ani.sanin.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anilist.Anilist.activityMergeTimeMap
import ani.sanin.connections.anilist.Anilist.rowOrderMap
import ani.sanin.connections.anilist.Anilist.scoreFormats
import ani.sanin.connections.anilist.Anilist.staffNameLang
import ani.sanin.connections.anilist.Anilist.titleLang
import ani.sanin.connections.anilist.AnilistMutations
import ani.sanin.connections.anilist.api.ScoreFormat
import ani.sanin.connections.anilist.api.UserStaffNameLanguage
import ani.sanin.connections.anilist.api.UserTitleLanguage
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.restartApp
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.launch

class AnilistSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsSubscreenBinding
    private lateinit var anilistMutations: AnilistMutations

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        anilistMutations = AnilistMutations()

        binding = ActivitySettingsSubscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.subscreenContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.subscreenBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.subscreenTitle.text = "AniList"
        binding.subscreenSubtitle.text = "Anime tracking preferences"
        binding.subscreenSubtitle.visibility = android.view.View.VISIBLE
        binding.subscreenIcon.setImageResource(R.drawable.ic_anilist)

        val titleLangIdx = UserTitleLanguage.entries
            .indexOfFirst { it.name == Anilist.titleLanguage }.coerceAtLeast(0)
        val staffLangIdx = UserStaffNameLanguage.entries
            .indexOfFirst { it.name == Anilist.staffNameLanguage }.coerceAtLeast(0)
        val mergeTimeKey = activityMergeTimeMap.entries
            .firstOrNull { it.value == Anilist.activityMergeTime }?.key ?: "${Anilist.activityMergeTime} mins"
        val mergeTimeIdx = activityMergeTimeMap.keys.indexOf(mergeTimeKey).coerceAtLeast(0)
        val rowOrderIdx = rowOrderMap.entries
            .firstOrNull { it.value == Anilist.rowOrder }?.key?.let { rowOrderMap.keys.indexOf(it) }?.coerceAtLeast(0) ?: 0
        val scoreFmtIdx = ScoreFormat.entries.indexOfFirst { it.name == Anilist.scoreFormat }.coerceAtLeast(0)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section(
                "Display", R.drawable.ic_set_theme,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Title Language",
                        desc = "How anime titles appear",
                        choice = SubscreenBuilder.Choice(
                            "Title Language", titleLang.toTypedArray(), titleLangIdx
                        ) { idx ->
                            lifecycleScope.launch {
                                anilistMutations.updateSettings(titleLanguage = UserTitleLanguage.entries[idx].name)
                                Anilist.titleLanguage = UserTitleLanguage.entries[idx].name
                                restartApp()
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Staff Names",
                        desc = "How staff names appear",
                        choice = SubscreenBuilder.Choice(
                            "Staff Names", staffNameLang.toTypedArray(), staffLangIdx
                        ) { idx ->
                            lifecycleScope.launch {
                                anilistMutations.updateSettings(staffNameLanguage = UserStaffNameLanguage.entries[idx].name)
                                Anilist.staffNameLanguage = UserStaffNameLanguage.entries[idx].name
                                restartApp()
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Score Format",
                        desc = "How you rate anime",
                        choice = SubscreenBuilder.Choice(
                            "Score Format", scoreFormats.toTypedArray(), scoreFmtIdx
                        ) { idx ->
                            lifecycleScope.launch {
                                val fmt = ScoreFormat.entries[idx]
                                anilistMutations.updateSettings(scoreFormat = fmt.name)
                                Anilist.scoreFormat = fmt.name
                                restartApp()
                            }
                        },
                    ),
                ),
            ),
            SubscreenBuilder.Section(
                "Activity", R.drawable.ic_set_home,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Activity Merge Time",
                        desc = "When activities merge into one",
                        choice = SubscreenBuilder.Choice(
                            "Activity Merge Time", activityMergeTimeMap.keys.toTypedArray(), mergeTimeIdx
                        ) { idx ->
                            val time = activityMergeTimeMap.values.toList()[idx]
                            lifecycleScope.launch {
                                anilistMutations.updateSettings(activityMergeTime = time)
                                Anilist.activityMergeTime = time
                                restartApp()
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Activity Sort Order",
                        desc = "Order of feed activities",
                        choice = SubscreenBuilder.Choice(
                            "Activity Sort Order", rowOrderMap.keys.toTypedArray(), rowOrderIdx
                        ) { idx ->
                            val order = rowOrderMap.values.toList()[idx]
                            lifecycleScope.launch {
                                anilistMutations.updateSettings(rowOrder = order)
                                Anilist.rowOrder = order
                                restartApp()
                            }
                        },
                    ),
                ),
            ),
            SubscreenBuilder.Section(
                "Privacy", R.drawable.ic_set_dns,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Display Adult Content",
                        desc = "Show 18+ titles",
                        switch = Anilist.adult to { isChecked ->
                            lifecycleScope.launch {
                                anilistMutations.updateSettings(displayAdultContent = isChecked)
                                Anilist.adult = isChecked
                                restartApp()
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Restrict Messages",
                        desc = "Only from followers",
                        switch = Anilist.restrictMessagesToFollowing to { isChecked ->
                            lifecycleScope.launch {
                                anilistMutations.updateSettings(restrictMessagesToFollowing = isChecked)
                                Anilist.restrictMessagesToFollowing = isChecked
                                restartApp()
                            }
                        },
                    ),
                ),
            ),
        ))
    }
}
