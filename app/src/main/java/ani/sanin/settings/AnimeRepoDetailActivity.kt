package ani.sanin.settings

import android.app.NotificationManager
import android.content.Context
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
import ani.sanin.databinding.ActivityAnimeRepoDetailBinding
import ani.sanin.databinding.ItemExtensionAllBinding
import ani.sanin.initActivity
import ani.sanin.statusBarHeight
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.SearchBarAnimator
import ani.sanin.util.TvKeyboardUtil
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.themes.ThemeManager
import ani.sanin.util.customAlertDialog
import com.bumptech.glide.Glide
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class AnimeRepoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnimeRepoDetailBinding
    private val animeExtensionManager: AnimeExtensionManager = Injekt.get()
    private val adapter = SourceAdapter(::onInstallClick)
    private var searchQuery: String = ""
    private lateinit var searchAnimator: SearchBarAnimator
    private var repoUrl: String = ""
    private var allExtensions: List<eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available> = emptyList()
    private var filterOptions: List<String> = listOf("All")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityAnimeRepoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }

        val repoUrl = intent.getStringExtra(ARG_REPO_URL) ?: run { finish(); return }
        this.repoUrl = repoUrl

        binding.animeRepoBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(binding.animeRepoBack)
        binding.animeRepoTitle.text = repoUrl.clean()

        githubOwnerAvatar(repoUrl)?.let { avatar ->
            Glide.with(this).load(avatar).into(binding.animeRepoIcon)
        }

        binding.animeRepoCopy.setOnClickListener { copyToClipboard(repoUrl, true) }
        binding.animeRepoDelete.setOnClickListener {
            customAlertDialog().apply {
                setTitle("Remove repository")
                setMessage("Remove this repository? Installed extensions stay on device.")
                setPosButton("Remove") {
                    val repos =
                        PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos) - repoUrl
                    PrefManager.setVal(PrefName.AnimeExtensionRepos, repos)
                    lifecycleScope.launch { animeExtensionManager.findAvailableExtensions() }
                    finish()
                }
                setNegButton("Cancel")
                show()
            }
        }
        FocusEffectUtil.applyFocusListener(binding.animeRepoCopy)
        FocusEffectUtil.applyFocusListener(binding.animeRepoDelete)

        binding.animeRepoFilterChip.setOnClickListener {
            AnimeTypeFilter.show(this, filterOptions) { refreshList() }
        }
        FocusEffectUtil.applyFocusListener(binding.animeRepoFilterChip)

        binding.animeRepoLangChip.setOnClickListener {
            val languageOptions =
                LanguageMapper.Companion.Language.entries.map { entry ->
                    entry.name.lowercase().replace("_", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }.toTypedArray()
            val listOrder: String = PrefManager.getVal(PrefName.AnimeLangSort)
            val index = LanguageMapper.Companion.Language.entries.toTypedArray()
                .indexOfFirst { it.code == listOrder }
            customAlertDialog().apply {
                setTitle("Language")
                singleChoiceItems(languageOptions, index) { selected ->
                    PrefManager.setVal(
                        PrefName.AnimeLangSort,
                        LanguageMapper.Companion.Language.entries[selected].code
                    )
                    refreshList()
                }
                setNegButton("Cancel")
                show()
            }
        }
        FocusEffectUtil.applyFocusListener(binding.animeRepoLangChip)

        searchAnimator = SearchBarAnimator(
            binding.animeRepoSearchView,
            binding.animeRepoSearchViewText,
            binding.animeRepoSearchIcon,
            R.drawable.ic_round_search_24,
            R.drawable.ic_round_close_24
        )
        binding.animeRepoSearchIcon.setOnClickListener { searchAnimator.toggle() }
        FocusEffectUtil.applyFocusListener(binding.animeRepoSearchIcon)
        TvKeyboardUtil.setupTvInput(binding.animeRepoSearchViewText)
        binding.animeRepoSearchViewText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                if (q != searchQuery) { searchQuery = q; refreshList() }
            }
        })

        binding.animeRepoRecyclerView.adapter = adapter
        binding.animeRepoRecyclerView.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            binding.animeRepoProgressBar.visibility = View.VISIBLE
            binding.animeRepoRecyclerView.visibility = View.GONE
            animeExtensionManager.availableExtensionsFlow.collectLatest { available ->
                allExtensions = available.filter { it.repository == repoUrl }
                filterOptions = AnimeTypeFilter.optionsFor(allExtensions)

                if (AnimeTypeFilter.current() !in filterOptions) {
                    PrefManager.setVal(PrefName.AnimeTypeFilter, "All")
                }
                binding.animeRepoProgressBar.visibility = View.GONE
                binding.animeRepoRecyclerView.visibility = View.VISIBLE
                refreshList()
            }
        }
    }

    private fun refreshList() {
        val lang = PrefManager.getVal<String>(PrefName.AnimeLangSort)
        val query = searchQuery
        val filtered = allExtensions.filter { ext ->
            AnimeTypeFilter.matches(ext) &&
                (lang == "all" || ext.lang?.lowercase(Locale.ROOT) == lang) &&
                (query.isEmpty() || ext.name.contains(query, ignoreCase = true))
        }
        binding.animeRepoEmptyText.isVisible = filtered.isEmpty()
        if (filtered.isEmpty()) {
            binding.animeRepoEmptyText.text = "No extensions match the current filter"
        }
        adapter.submitList(filtered)
        if (filtered.isNotEmpty()) binding.animeRepoRecyclerView.requestFocus()
    }

    private fun onInstallClick(extension: eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available) {
        val context = this
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val installerSteps = InstallerSteps(notificationManager, context)
        animeExtensionManager.installExtension(extension)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { installStep -> installerSteps.onInstallStep(installStep) {} },
                { error -> installerSteps.onError(error) {} },
                { installerSteps.onComplete { } }
            )
    }

    private fun String.clean(): String = removePrefix("https://raw.githubusercontent.com/")
        .replace("index.min.json", "")
        .replace("repo.json", "")
        .removeSuffix("/")

    class SourceAdapter(
        private val onInstall: (eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available) -> Unit
    ) : ListAdapter<eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available, SourceAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemExtensionAllBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.binding.extensionNameTextView.text = item.name
            val lang = item.lang?.let {
                LanguageMapper.getLanguageName(it)
            } ?: "all"
            holder.binding.extensionVersionTextView.text =
                "$lang v${item.versionName}${if (item.isNsfw) " (18+)" else ""}"

            val skipIcons = PrefManager.getVal<Boolean>(PrefName.SkipExtensionIcons)
            if (!skipIcons) {
                Glide.with(holder.itemView.context)
                    .load(item.iconUrl)
                    .placeholder(R.drawable.ic_extension)
                    .error(R.drawable.ic_extension)
                    .into(holder.binding.extensionIconImageView)
            }

            holder.binding.extensionCardView.isFocusable = true
            holder.binding.closeTextView.setImageResource(R.drawable.ic_download_24)
            holder.binding.closeTextView.contentDescription = "Install ${item.name}"
            holder.binding.closeTextView.setOnClickListener { onInstall(item) }
            FocusEffectUtil.applyFocusListener(holder.itemView)
            FocusEffectUtil.applyFocusListener(holder.binding.closeTextView)
        }

        class VH(val binding: ItemExtensionAllBinding) : RecyclerView.ViewHolder(binding.root)

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available>() {
                override fun areItemsTheSame(
                    oldItem: eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available,
                    newItem: eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available
                ) = oldItem.pkgName == newItem.pkgName

                override fun areContentsTheSame(
                    oldItem: eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available,
                    newItem: eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available
                ) = oldItem == newItem
            }
        }
    }

    companion object {
        const val ARG_REPO_URL = "repoUrl"
    }
}
