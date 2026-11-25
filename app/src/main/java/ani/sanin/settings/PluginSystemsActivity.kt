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
import ani.sanin.databinding.ItemPluginSystemHeaderBinding
import ani.sanin.getThemeColor
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.plugins.nuvio.NuvioRepository
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.TvKeyboardUtil
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private data class SystemCard(
    val title: String,
    val iconRes: Int,
    val meta: String,
    val tintRes: Int?,
    val onClick: () -> Unit,
)

private sealed interface SystemEntry {
    data class Header(val title: String) : SystemEntry
    data class Card(val card: SystemCard) : SystemEntry
}

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
        if (TvKeyboardUtil.isTv(this)) FocusEffectUtil.applyFocusListener(binding.systemsBack)
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
                SystemEntry.Header("Anime"),
                SystemEntry.Card(
                    SystemCard(
                        title = "Aniyomi Extensions",
                        iconRes = R.drawable.ic_toggle_aniyomi,
                        meta = "${count(animeRepos, "repo")} · ${count(animeExtensions, "extension")}",
                        tintRes = null,
                        onClick = { openExtensions(null) },
                    ),
                ),
                SystemEntry.Header("Movie / TV"),
                SystemEntry.Card(
                    SystemCard(
                        title = "CloudStream Plugins",
                        iconRes = R.drawable.ic_toggle_cloudstream,
                        meta = "${count(cloudStreamRepos, "repo")} · ${count(cloudStreamPlugins, "plugin")}",
                        tintRes = null,
                        onClick = { openExtensions(ExtensionsActivity.EXTRA_CLOUDSTREAM_MODE) },
                    ),
                ),
                SystemEntry.Card(
                    SystemCard(
                        title = "Nuvio Plugins",
                        iconRes = R.drawable.ic_nuvio_logo,
                        meta = "${count(nuvioRepos, "repo")} · ${count(nuvioPlugins, "plugin")}",
                        tintRes = null,
                        onClick = { openExtensions(ExtensionsActivity.EXTRA_NUVIO_MODE) },
                    ),
                ),
                SystemEntry.Card(
                    SystemCard(
                        title = "Stremio Add-ons",
                        iconRes = R.drawable.ic_stremio_watermark,
                        meta = "${count(stremioAddons, "addon")} · $stremioEnabled enabled",
                        tintRes = com.google.android.material.R.attr.colorOnSurface,
                        onClick = { openExtensions(ExtensionsActivity.EXTRA_STREMIO_MODE) },
                    ),
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
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var entries: List<SystemEntry> = emptyList()

    fun submit(items: List<SystemEntry>) {
        entries = items
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (entries[position]) {
        is SystemEntry.Header -> VIEW_TYPE_HEADER
        is SystemEntry.Card -> VIEW_TYPE_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(ItemPluginSystemHeaderBinding.inflate(inflater, parent, false))
        } else {
            CardViewHolder(ItemPluginSystemBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = entries[position]) {
            is SystemEntry.Header -> (holder as HeaderViewHolder).bind(entry)
            is SystemEntry.Card -> (holder as CardViewHolder).bind(entry.card)
        }
    }

    private class HeaderViewHolder(
        private val binding: ItemPluginSystemHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: SystemEntry.Header) {
            binding.root.text = entry.title
        }
    }

    private inner class CardViewHolder(
        private val binding: ItemPluginSystemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(card: SystemCard) {
            binding.systemIcon.setImageResource(card.iconRes)
            binding.systemIcon.imageTintList = card.tintRes?.let {
                ColorStateList.valueOf(binding.root.context.getThemeColor(it))
            }
            binding.systemTitle.text = card.title
            binding.systemMeta.text = card.meta
            binding.root.setOnClickListener { onClick(card) }
            if (TvKeyboardUtil.isTv(binding.root.context)) {
                FocusEffectUtil.applyFocusListener(
                    binding.root,
                    cornerRadiusPx = 16 * binding.root.resources.displayMetrics.density,
                )
            } else {
                binding.root.onFocusChangeListener = null
            }
        }
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_CARD = 1
    }
}
