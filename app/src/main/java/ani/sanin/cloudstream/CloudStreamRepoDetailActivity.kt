package ani.sanin.cloudstream

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.copyToClipboard
import ani.sanin.others.LanguageMapper
import ani.sanin.others.svg.SvgImageLoader
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.databinding.ActivityCsRepoDetailBinding
import ani.sanin.databinding.ItemCsSourceBinding
import ani.sanin.initActivity
import ani.sanin.statusBarHeight
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.SearchBarAnimator
import ani.sanin.util.TvKeyboardUtil
import ani.sanin.themes.ThemeManager
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CloudStreamRepoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCsRepoDetailBinding
    private val adapter = SourceAdapter(::onInstallClick, ::onSettingsClick, { installedIds }) { repoUrl }
    private var installedIds: Set<String> = emptySet()
    private var searchQuery: String = ""
    private lateinit var searchAnimator: SearchBarAnimator
    private var repoUrl: String = ""
    private var cachedManifest: CsRepoManifest? = null
    private var cachedPlugins: List<CsSource> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityCsRepoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }

        val repoUrl = intent.getStringExtra(ARG_REPO_URL) ?: run {
            finish()
            return
        }
        this.repoUrl = repoUrl

        binding.csRepoBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(binding.csRepoBack)
        binding.csRepoTitle.text = repoUrl.clean()

        binding.csRepoCopy.setOnClickListener {
            copyToClipboard(repoUrl, true)
        }
        binding.csRepoDelete.setOnClickListener {
            customAlertDialog().apply {
                setTitle("Remove repository")
                setMessage("Remove this repository? Installed extensions stay on device.")
                setPosButton("Remove") {
                    CsRepos.removeRepo(repoUrl)
                    finish()
                }
                setNegButton("Cancel")
                show()
            }
        }
        FocusEffectUtil.applyFocusListener(binding.csRepoCopy)
        FocusEffectUtil.applyFocusListener(binding.csRepoDelete)

        binding.csRepoFilterChip.setOnClickListener {
            CsTypeFilter.show(this) { refreshList(cachedPlugins) }
        }
        FocusEffectUtil.applyFocusListener(binding.csRepoFilterChip)

        binding.csRepoLangChip.setOnClickListener {
            val languageOptions =
                LanguageMapper.Companion.Language.entries.map { entry ->
                    entry.name.lowercase().replace("_", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }.toTypedArray()
            val listOrder: String = PrefManager.getVal(PrefName.LangSort)
            val index = LanguageMapper.Companion.Language.entries.toTypedArray()
                .indexOfFirst { it.code == listOrder }
            customAlertDialog().apply {
                setTitle("Language")
                singleChoiceItems(languageOptions, index) { selected ->
                    PrefManager.setVal(
                        PrefName.LangSort,
                        LanguageMapper.Companion.Language.entries[selected].code
                    )
                    refreshList(cachedPlugins)
                }
                setNegButton("Cancel")
                show()
            }
        }
        FocusEffectUtil.applyFocusListener(binding.csRepoLangChip)

        searchAnimator = SearchBarAnimator(
            binding.csRepoSearchView,
            binding.csRepoSearchViewText,
            binding.csRepoSearchIcon,
            R.drawable.ic_round_search_24,
            R.drawable.ic_round_close_24
        )
        binding.csRepoSearchIcon.setOnClickListener { searchAnimator.toggle() }
        FocusEffectUtil.applyFocusListener(binding.csRepoSearchIcon)
        TvKeyboardUtil.setupTvInput(binding.csRepoSearchViewText)
        binding.csRepoSearchViewText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                if (q != searchQuery) { searchQuery = q; refreshList(cachedPlugins) }
            }
        })

        binding.csRepoRecyclerView.adapter = adapter
        binding.csRepoRecyclerView.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            binding.csRepoProgressBar.visibility = View.VISIBLE
            binding.csRepoRecyclerView.visibility = View.GONE
            val manifest = runCatching { CsRepos.fetchManifest(repoUrl) }
            binding.csRepoProgressBar.visibility = View.GONE
            val result = manifest.getOrNull()
            if (result == null) {
                binding.csRepoEmptyText.visibility = View.VISIBLE
                binding.csRepoEmptyText.text = "Could not load repo:\n${manifest.exceptionOrNull()?.message}"
                return@launch
            }
            binding.csRepoTitle.text = result.name
            cachedManifest = result

            result.iconUrl?.let { icon ->
                val fullUrl = if (icon.startsWith("http")) icon else CsRepos.sourceUrl(repoUrl, icon)
                SvgImageLoader.load(
                    binding.csRepoIcon,
                    fullUrl,
                    R.drawable.ic_extension,
                    R.drawable.ic_extension
                )
            }
            binding.csRepoRecyclerView.visibility = View.VISIBLE
            val plugins = CsRepos.getRepoPlugins(repoUrl)
            cachedPlugins = plugins
            binding.csRepoEmptyText.visibility = if (plugins.isEmpty()) View.VISIBLE else View.GONE
            if (plugins.isEmpty()) binding.csRepoEmptyText.text = "No extensions found in this repo"
            refreshList(plugins)

            binding.csRepoRecyclerView.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        installedIds = CsRepos.installed(this).map { it.id }.toSet()
    }

    private fun refreshList(sources: List<CsSource>) {
        val lang = PrefManager.getVal<String>(PrefName.LangSort)
        val query = searchQuery
        val filtered = sources.filter { source ->
            CsTypeFilter.matches(source.type) &&
                (lang == "all" || source.lang.equals(lang, true)) &&
                (query.isEmpty() || source.name.contains(query, ignoreCase = true))
        }
        binding.csRepoEmptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        if (filtered.isEmpty()) binding.csRepoEmptyText.text = "No extensions match the current filter"
        adapter.submitList(filtered)
    }

    private fun onSettingsClick(source: CsSource) {
        val installed = CsRepos.installed(this).find { it.id == source.id } ?: return
        startActivity(CloudStreamSettingsActivity.intent(this, installed.id))
    }

    private fun onInstallClick(source: CsSource) {
        if (source.id in installedIds) {
            Toast.makeText(this, "${source.name} is already installed", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { CsRepos.install(this@CloudStreamRepoDetailActivity, repoUrl, source) }
            }
            if (result.isSuccess) {
                installedIds = CsRepos.installed(this@CloudStreamRepoDetailActivity).map { it.id }.toSet()
                adapter.notifyDataSetChanged()
                Toast.makeText(this@CloudStreamRepoDetailActivity, "${source.name} installed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this@CloudStreamRepoDetailActivity,
                    "Install failed: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun String.clean(): String = removePrefix("https://raw.githubusercontent.com/")
        .replace("index.json", "")
        .removeSuffix("/")

    class SourceAdapter(
        private val onInstall: (CsSource) -> Unit,
        private val onSettings: (CsSource) -> Unit,
        private val installedIdsProvider: () -> Set<String>,
        private val repoUrlProvider: () -> String
    ) : ListAdapter<CsSource, SourceAdapter.VH>(CloudStreamRepoDetailActivity.DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCsSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.binding.sourceNameTextView.text = item.name
            holder.binding.sourceMetaTextView.text =
                "v${item.version} • ${item.typeLabel} • ${item.lang}"
            val installed = item.id in installedIdsProvider()

            val skipIcons = PrefManager.getVal<Boolean>(PrefName.SkipExtensionIcons)
            if (!skipIcons) {
                val iconUrl = item.iconUrl?.let {
                    if (it.startsWith("http")) it else CsRepos.sourceUrl(repoUrlProvider(), it)
                }
                SvgImageLoader.load(
                    holder.binding.sourceIconImageView,
                    iconUrl,
                    R.drawable.ic_extension,
                    R.drawable.ic_extension
                )
            }

            holder.binding.sourceInstallImageView.setImageResource(
                if (installed) R.drawable.ic_check else R.drawable.ic_download_24
            )
            holder.binding.sourceInstallImageView.contentDescription = if (installed) "Installed" else "Install"
            holder.binding.sourceInstallImageView.setOnClickListener { onInstall(item) }

            val settingsVisible = installed
            holder.binding.sourceSettingsImageView.isVisible = settingsVisible
            holder.binding.sourceSettingsImageView.contentDescription = "Settings"
            holder.binding.sourceSettingsImageView.setOnClickListener { onSettings(item) }
            if (settingsVisible) {
                FocusEffectUtil.applyFocusListener(holder.binding.sourceSettingsImageView)
            }

            FocusEffectUtil.applyFocusListener(holder.itemView)
            FocusEffectUtil.applyFocusListener(holder.binding.sourceInstallImageView)
        }

        class VH(val binding: ItemCsSourceBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val ARG_REPO_URL = "repoUrl"

        val DIFF = object : DiffUtil.ItemCallback<CsSource>() {
            override fun areItemsTheSame(oldItem: CsSource, newItem: CsSource) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: CsSource, newItem: CsSource) =
                oldItem == newItem
        }
    }
}
