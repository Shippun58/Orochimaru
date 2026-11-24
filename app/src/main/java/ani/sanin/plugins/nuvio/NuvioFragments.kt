package ani.sanin.plugins.nuvio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.copyToClipboard
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.settings.ExtensionsActivity
import ani.sanin.settings.RepoCardAdapter
import ani.sanin.settings.RepoUi
import ani.sanin.settings.SearchQueryHandler
import ani.sanin.settings.githubOwnerAvatar
import ani.sanin.toast
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.launch

class NuvioInstalledFragment : Fragment(), SearchQueryHandler {
    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!
    private var items: List<NuvioScraper> = emptyList()
    private var query = ""

    private val adapter = RepoCardAdapter(
        onOpen = { showScraper(it.url) },
        onLongClick = { showScraper(it.url) },
        countLabel = "stream scrapers",
        onExitTop = { (activity as? ExtensionsActivity)?.focusPill() == true },
        actionLabel = "Settings",
        onToggle = { item, enabled ->
            NuvioStorage.setEnabled(item.url, enabled)
            loadItems()
        },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        binding.allExtensionsRecyclerView.adapter = adapter
        binding.allExtensionsRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadItems()
    }

    private fun loadItems() {
        items = NuvioRepository.installed(requireContext()).sortedBy { it.name.lowercase() }
        adapter.submitList(items.map { it.toRepoUi() }.filter { it.matches(query) })
    }

    private fun showScraper(id: String) {
        val scraper = items.firstOrNull { it.id == id } ?: return
        requireContext().customAlertDialog().apply {
            setTitle(scraper.name)
            setMessage(buildString {
                append("Version ${scraper.version}\n")
                scraper.author?.takeIf { it.isNotBlank() }?.let { append("Author $it\n") }
                scraper.description.takeIf { it.isNotBlank() }?.let { append("\n$it") }
            })
            setPosButton(if (scraper.hasSettings) "Edit settings" else "Close") {
                if (scraper.hasSettings) showSettings(scraper)
            }
            setNeutralButton(if (scraper.enabled) "Disable" else "Enable") {
                NuvioStorage.setEnabled(scraper.id, !scraper.enabled)
                loadItems()
            }
            setNegButton("Uninstall") {
                NuvioStorage.uninstall(requireContext(), scraper.stored())
                loadItems()
            }
            show()
        }
    }

    private fun showSettings(scraper: NuvioScraper) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "{}"
            setText(NuvioStorage.loadSettings(scraper.id))
            setSingleLine(false)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(32, 24, 32, 24)
            setMinWidth((320 * resources.displayMetrics.density).toInt())
        }
        requireContext().customAlertDialog().apply {
            setTitle("${scraper.name} settings")
            setMessage("JSON settings passed as SCRAPER_SETTINGS")
            setCustomView(input)
            setPosButton("Save") {
                val value = input.text.toString().ifBlank { "{}" }
                val valid = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(value)
                }.isSuccess
                if (!valid) {
                    toast("Invalid JSON")
                } else {
                    NuvioStorage.saveSettings(scraper.id, value)
                    toast("Settings saved")
                }
            }
            setNegButton("Cancel")
            show()
        }
    }

    override fun updateContentBasedOnQuery(query: String?) {
        this.query = query.orEmpty()
        adapter.submitList(items.map { it.toRepoUi() }.filter { it.matches(this.query) })
    }

    override fun notifyDataChanged() = loadItems()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun NuvioScraper.toRepoUi(): RepoUi = RepoUi(
        name = name,
        url = id,
        count = contentLanguage.size,
        iconUrl = logo,
        contentTypes = supportedTypes.map { it.replaceFirstChar { c -> c.uppercase() } },
        languages = contentLanguage.map { it.uppercase() },
        countText = version,
        scopeLabel = "languages",
        description = description.ifBlank { author },
        watermarkRes = R.drawable.ic_extension,
        toggleVisible = true,
        enabled = enabled,
        showInfoButton = true,
    )

    private fun NuvioScraper.stored() = NuvioStoredScraper(
        id = id,
        repositoryUrl = repositoryUrl,
        name = name,
        description = description,
        author = author,
        version = version,
        filename = filename,
        supportedTypes = supportedTypes,
        enabled = enabled,
        manifestEnabled = manifestEnabled,
        hasSettings = hasSettings,
        logo = logo,
        contentLanguage = contentLanguage,
        formats = formats,
    )
}

class NuvioAvailableFragment : Fragment(), SearchQueryHandler {
    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!
    private var items: List<NuvioRepositoryItem> = emptyList()
    private var query = ""

    private val adapter = RepoCardAdapter(
        onOpen = { refresh(it.url) },
        onLongClick = { showRepo(it.url) },
        countLabel = "scrapers",
        onExitTop = { (activity as? ExtensionsActivity)?.focusPill() == true },
        actionLabel = "Refresh",
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        binding.allExtensionsRecyclerView.adapter = adapter
        binding.allExtensionsRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadItems()
    }

    private fun loadItems() {
        items = NuvioRepository.repositories().sortedBy { it.name.lowercase() }
        adapter.submitList(items.map { it.toRepoUi() }.filter { it.matches(query) })
    }

    private fun refresh(url: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { NuvioRepository.refreshRepository(requireContext(), url) }
                .onFailure { toast("Could not refresh repository") }
            loadItems()
        }
    }

    private fun showRepo(url: String) {
        val repo = items.firstOrNull { it.manifestUrl == url } ?: return
        requireContext().customAlertDialog().apply {
            setTitle(repo.name)
            setMessage(buildString {
                append(repo.description.orEmpty())
                append("\n${repo.scraperCount} scrapers")
                append("\n${repo.manifestUrl}")
            })
            setPosButton("Refresh") { refresh(repo.manifestUrl) }
            setNeutralButton("Copy URL") { copyToClipboard(repo.manifestUrl, true) }
            setNegButton("Delete") {
                NuvioStorage.removeRepo(requireContext(), repo.manifestUrl)
                loadItems()
            }
            show()
        }
    }

    override fun updateContentBasedOnQuery(query: String?) {
        this.query = query.orEmpty()
        adapter.submitList(items.map { it.toRepoUi() }.filter { it.matches(this.query) })
    }

    override fun notifyDataChanged() = loadItems()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun NuvioRepositoryItem.toRepoUi(): RepoUi = RepoUi(
        name = name,
        url = manifestUrl,
        count = scraperCount,
        iconUrl = githubOwnerAvatar(manifestUrl),
        contentTypes = listOf("Nuvio"),
        languages = emptyList(),
        countText = "$scraperCount scrapers",
        description = description,
        watermarkRes = R.drawable.ic_extension,
    )
}
