package ani.sanin.plugins.nuvio

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.copyToClipboard
import ani.sanin.databinding.ActivityCsRepoDetailBinding
import ani.sanin.databinding.ItemCsSourceBinding
import ani.sanin.initActivity
import ani.sanin.others.svg.SvgImageLoader
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.SearchBarAnimator
import ani.sanin.util.TvKeyboardUtil
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.launch

class NuvioRepoDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCsRepoDetailBinding
    private var repoUrl: String = ""
    private var installedIds: Set<String> = emptySet()
    private var cachedScrapers: List<NuvioManifestScraper> = emptyList()
    private var searchQuery = ""
    private var typeFilter = 0
    private var languageFilter = 0
    private lateinit var searchAnimator: SearchBarAnimator

    private val adapter = SourceAdapter(
        onInstall = { install(it) },
        onSettings = { openSettings(it) },
        installedIdsProvider = { installedIds },
        repoUrlProvider = { repoUrl },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityCsRepoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }

        repoUrl = intent.getStringExtra(ARG_REPO_URL) ?: run {
            finish()
            return
        }
        binding.csRepoTitle.text = repoUrl.removePrefix("https://raw.githubusercontent.com/").removeSuffix("/manifest.json")
        SvgImageLoader.load(
            binding.csRepoIcon,
            NUVIO_WATERMARK_URL,
            R.drawable.ic_extension,
            R.drawable.ic_extension,
        )

        binding.csRepoBack.setOnClickListener { finish() }
        binding.csRepoCopy.setOnClickListener { copyToClipboard(repoUrl, true) }
        binding.csRepoDelete.setOnClickListener {
            customAlertDialog().apply {
                setTitle("Remove repository")
                setMessage("Remove this repository? Installed plugins stay on device.")
                setPosButton("Remove") {
                    NuvioStorage.removeRepo(repoUrl)
                    finish()
                }
                setNegButton("Cancel")
                show()
            }
        }
        FocusEffectUtil.applyFocusListener(binding.csRepoBack)
        FocusEffectUtil.applyFocusListener(binding.csRepoCopy)
        FocusEffectUtil.applyFocusListener(binding.csRepoDelete)

        binding.csRepoFilterChip.setOnClickListener {
            val options = arrayOf("All", "Movie", "TV")
            customAlertDialog().apply {
                setTitle("Type")
                singleChoiceItems(options, typeFilter) { selected ->
                    typeFilter = selected
                    binding.csRepoFilterChip.contentDescription = "Type: ${options[selected]}"
                    refreshList(cachedScrapers)
                }
                setNegButton("Cancel")
                show()
            }
        }
        FocusEffectUtil.applyFocusListener(binding.csRepoFilterChip)

        binding.csRepoLangChip.setOnClickListener {
            val languages = cachedScrapers.flatMap { it.contentLanguage.orEmpty() }.distinct().sorted()
            val options = listOf("All") + languages
            customAlertDialog().apply {
                setTitle("Language")
                singleChoiceItems(options.toTypedArray(), languageFilter) { selected ->
                    languageFilter = selected
                    binding.csRepoLangChip.contentDescription = "Language: ${options[selected]}"
                    refreshList(cachedScrapers)
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
            R.drawable.ic_round_close_24,
        )
        binding.csRepoSearchIcon.setOnClickListener { searchAnimator.toggle() }
        FocusEffectUtil.applyFocusListener(binding.csRepoSearchIcon)
        TvKeyboardUtil.setupTvInput(binding.csRepoSearchViewText)
        binding.csRepoSearchViewText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                if (query != searchQuery) {
                    searchQuery = query
                    refreshList(cachedScrapers)
                }
            }
        })

        binding.csRepoRecyclerView.adapter = adapter
        binding.csRepoRecyclerView.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            binding.csRepoProgressBar.visibility = View.VISIBLE
            binding.csRepoRecyclerView.visibility = View.GONE
            val result = runCatching { NuvioRepository.fetchManifest(repoUrl) }
            binding.csRepoProgressBar.visibility = View.GONE
            val manifest = result.getOrNull()
            if (manifest == null) {
                binding.csRepoEmptyText.visibility = View.VISIBLE
                binding.csRepoEmptyText.text = "Could not load repo:\n${result.exceptionOrNull()?.message}"
                return@launch
            }
            binding.csRepoTitle.text = manifest.name
            cachedScrapers = manifest.scrapers.filter { NuvioRepository.supportsPlatform(it) }
            val repoIcon = manifest.iconUrl ?: manifest.icon ?: manifest.logo
            val repoIconUrl = repoIcon?.let { NuvioRepository.sourceUrl(repoUrl, it) }
                ?: ani.sanin.settings.githubOwnerAvatar(repoUrl)
                ?: NUVIO_WATERMARK_URL
            SvgImageLoader.load(
                binding.csRepoIcon,
                repoIconUrl,
                R.drawable.ic_extension,
                R.drawable.ic_extension,
            )
            NuvioStorage.saveRepoMeta(
                NuvioRepositoryItem(
                    manifestUrl = repoUrl,
                    name = manifest.name,
                    description = manifest.description,
                    version = manifest.version,
                    iconUrl = repoIconUrl,
                    scraperCount = cachedScrapers.size,
                    lastUpdated = System.currentTimeMillis(),
                )
            )
            installedIds = NuvioStorage.installed(this@NuvioRepoDetailActivity).map { it.id }.toSet()
            binding.csRepoRecyclerView.visibility = View.VISIBLE
            binding.csRepoEmptyText.visibility = if (cachedScrapers.isEmpty()) View.VISIBLE else View.GONE
            if (cachedScrapers.isEmpty()) binding.csRepoEmptyText.text = "No plugins found in this repo"
            refreshList(cachedScrapers)
            binding.csRepoRecyclerView.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        installedIds = NuvioStorage.installed(this).map { it.id }.toSet()
        adapter.notifyDataSetChanged()
    }

    private fun refreshList(scrapers: List<NuvioManifestScraper>) {
        val languages = cachedScrapers.flatMap { it.contentLanguage.orEmpty() }.distinct().sorted()
        val selectedLanguage = languages.getOrNull(languageFilter - 1)
        val filtered = scrapers.filter { scraper ->
            val typeOk = when (typeFilter) {
                1 -> scraper.supportedTypes.any { it.equals("movie", true) || it.equals("anime", true) }
                2 -> scraper.supportedTypes.any {
                    it.equals("tv", true) || it.equals("series", true) || it.equals("show", true)
                }
                else -> true
            }
            val languageOk = languageFilter == 0 ||
                (selectedLanguage?.let { selected ->
                    scraper.contentLanguage.orEmpty().any { it.equals(selected, true) }
                } ?: false)
            val queryOk = searchQuery.isBlank() ||
                scraper.name.contains(searchQuery, true) ||
                scraper.id.contains(searchQuery, true) ||
                scraper.description.orEmpty().contains(searchQuery, true)
            typeOk && languageOk && queryOk
        }
        binding.csRepoEmptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        if (filtered.isEmpty()) binding.csRepoEmptyText.text = "No plugins match the current filter"
        adapter.submitList(filtered)
    }

    private fun openSettings(scraper: NuvioManifestScraper) {
        val installed = NuvioStorage.installed(this).firstOrNull { it.id == scraper.id } ?: return
        showNuvioSettings(this, installed)
    }

    private fun install(scraper: NuvioManifestScraper) {
        if (scraper.id in installedIds) {
            Toast.makeText(this, "${scraper.name} is already installed", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = runCatching { NuvioRepository.installScraper(this@NuvioRepoDetailActivity, repoUrl, scraper) }
            if (result.isSuccess) {
                installedIds = NuvioStorage.installed(this@NuvioRepoDetailActivity).map { it.id }.toSet()
                adapter.notifyDataSetChanged()
                Toast.makeText(this@NuvioRepoDetailActivity, "${scraper.name} installed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this@NuvioRepoDetailActivity,
                    "Install failed: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    class SourceAdapter(
        private val onInstall: (NuvioManifestScraper) -> Unit,
        private val onSettings: (NuvioManifestScraper) -> Unit,
        private val installedIdsProvider: () -> Set<String>,
        private val repoUrlProvider: () -> String,
    ) : ListAdapter<NuvioManifestScraper, SourceAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCsSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(val binding: ItemCsSourceBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: NuvioManifestScraper) {
                val installed = item.id in installedIdsProvider()
                val languages = item.contentLanguage.orEmpty()
                binding.sourceNameTextView.text = item.name
                binding.sourceMetaTextView.text = buildString {
                    append("v${item.version}")
                    if (item.supportedTypes.isNotEmpty()) {
                        append(" • ")
                        append(item.supportedTypes.joinToString("/"))
                    }
                    if (languages.isNotEmpty()) {
                        append(" • ")
                        append(languages.joinToString(",").uppercase())
                    }
                }
                binding.sourceSettingsImageView.visibility = if (installed) View.VISIBLE else View.GONE
                binding.sourceInstallImageView.alpha = if (installed) 0.35f else 1f
                if (item.logo.isNullOrBlank()) {
                    SvgImageLoader.load(
                        binding.sourceIconImageView,
                        NUVIO_WATERMARK_URL,
                        R.drawable.ic_extension,
                        R.drawable.ic_extension,
                    )
                } else {
                    SvgImageLoader.load(
                        binding.sourceIconImageView,
                        NuvioRepository.sourceUrl(repoUrlProvider(), item.logo),
                        R.drawable.ic_extension,
                        R.drawable.ic_extension,
                    )
                }
                binding.sourceCardView.setOnClickListener {
                    if (installed) onSettings(item) else onInstall(item)
                }
                binding.sourceSettingsImageView.setOnClickListener { onSettings(item) }
                binding.sourceInstallImageView.setOnClickListener { onInstall(item) }
            }
        }
    }

    companion object {
        const val ARG_REPO_URL = "nuvio_repo_url"

        fun intent(context: android.content.Context, url: String): android.content.Intent =
            android.content.Intent(context, NuvioRepoDetailActivity::class.java).putExtra(ARG_REPO_URL, url)

        private val DIFF = object : DiffUtil.ItemCallback<NuvioManifestScraper>() {
            override fun areItemsTheSame(oldItem: NuvioManifestScraper, newItem: NuvioManifestScraper) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: NuvioManifestScraper, newItem: NuvioManifestScraper) =
                oldItem.name == newItem.name && oldItem.version == newItem.version
        }
    }
}
