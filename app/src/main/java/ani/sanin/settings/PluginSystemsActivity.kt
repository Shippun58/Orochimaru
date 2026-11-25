package ani.sanin.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.cloudstream.CsRepos
import ani.sanin.databinding.ActivityPluginSystemsBinding
import ani.sanin.databinding.ItemPluginSystemBinding
import ani.sanin.getThemeColor
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.plugins.nuvio.NuvioRepository
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private data class SystemCard(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val meta: String,
    val tintIcon: Boolean,
    val onClick: () -> Unit,
)

class PluginSystemsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPluginSystemsBinding
    private val animeExtensionManager: AnimeExtensionManager = Injekt.get()
    private val adapter = SystemCardAdapter { it.onClick() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityPluginSystemsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.systemsContainer.updatePadding(top = statusBarHeight, bottom = navBarHeight)
        binding.systemsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        FocusEffectUtil.applyFocusListener(binding.systemsBack)
        binding.pluginSystemsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.pluginSystemsRecyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        renderCards()
    }

    private fun renderCards() {
        val animeRepos = safeCount { PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).size }
        val animeExtensions = safeCount { animeExtensionManager.installedExtensionsFlow.value.size }
        val cloudStreamRepos = safeCount { CsRepos.repos().size }
        val cloudStreamPlugins = safeCount { CsRepos.installed(this).size }
        val nuvioRepos = safeCount { NuvioRepository.repositories().size }
        val nuvioPlugins = safeCount { NuvioRepository.installed(this).size }
        val stremioAddons = safeCount { StremioAddonManager.allInstalled().size }
        val stremioEnabled = safeCount { StremioAddonManager.installed().size }

        adapter.submit(
            listOf(
                SystemCard(
                    title = "Aniyomi",
                    subtitle = "Anime",
                    iconRes = R.drawable.ic_toggle_aniyomi,
                    meta = "${count(animeRepos, "repo")} · ${count(animeExtensions, "extension")}",
                    tintIcon = false,
                    onClick = { openExtensions(null) },
                ),
                SystemCard(
                    title = "CloudStream Plugins",
                    subtitle = "Movie & TV",
                    iconRes = R.drawable.ic_toggle_cloudstream,
                    meta = "${count(cloudStreamRepos, "repo")} · ${count(cloudStreamPlugins, "plugin")}",
                    tintIcon = false,
                    onClick = { openExtensions(ExtensionsActivity.EXTRA_CLOUDSTREAM_MODE) },
                ),
                SystemCard(
                    title = "Nuvio",
                    subtitle = "Plugins",
                    iconRes = R.drawable.ic_baseline_extension_24,
                    meta = "${count(nuvioRepos, "repo")} · ${count(nuvioPlugins, "plugin")}",
                    tintIcon = true,
                    onClick = { openExtensions(ExtensionsActivity.EXTRA_NUVIO_MODE) },
                ),
                SystemCard(
                    title = "Stremio",
                    subtitle = "Add-ons",
                    iconRes = R.drawable.ic_stremio_watermark,
                    meta = "${count(stremioAddons, "addon")} · $stremioEnabled enabled",
                    tintIcon = false,
                    onClick = { openExtensions(ExtensionsActivity.EXTRA_STREMIO_MODE) },
                ),
            ),
        )
    }

    private fun openExtensions(modeExtra: String?) {
        val intent = Intent(this, ExtensionsActivity::class.java)
        if (modeExtra != null) intent.putExtra(modeExtra, true)
        startActivity(intent)
    }

    private fun safeCount(block: () -> Int): Int = runCatching(block).getOrDefault(0)

    private fun count(value: Int, singular: String): String =
        "$value ${if (value == 1) singular else "${singular}s"}"
}

private class SystemCardAdapter(
    private val onClick: (SystemCard) -> Unit,
) : RecyclerView.Adapter<SystemCardAdapter.ViewHolder>() {
    private var cards: List<SystemCard> = emptyList()

    fun submit(items: List<SystemCard>) {
        cards = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemPluginSystemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = cards.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(cards[position])
    }

    inner class ViewHolder(
        private val binding: ItemPluginSystemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(card: SystemCard) {
            binding.systemIcon.setImageResource(card.iconRes)
            binding.systemIcon.imageTintList = if (card.tintIcon) {
                ColorStateList.valueOf(binding.root.context.getThemeColor(com.google.android.material.R.attr.colorPrimary))
            } else {
                null
            }
            binding.systemTitle.text = card.title
            binding.systemSubtitle.text = card.subtitle
            binding.systemMeta.text = card.meta
            binding.root.setOnClickListener { onClick(card) }
            FocusEffectUtil.applyFocusListener(
                binding.root,
                cornerRadiusPx = 16 * binding.root.resources.displayMetrics.density,
            )
        }
    }
}
