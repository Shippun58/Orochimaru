package ani.sanin.cloudstream

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.R
import ani.sanin.copyToClipboard
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.settings.RepoCardAdapter
import ani.sanin.settings.RepoUi
import ani.sanin.settings.ExtensionsActivity
import ani.sanin.settings.githubOwnerAvatar
import ani.sanin.settings.SearchQueryHandler
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

class CloudStreamAvailableFragment : Fragment(), SearchQueryHandler {

    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

    private val cacheJson = Json { ignoreUnknownKeys = true }
    private fun cacheFile() = File(requireContext().cacheDir, "cs_repo_cache.json")

    @Serializable
    private data class CachedRepo(
        val name: String, val url: String, val count: Int, val iconUrl: String? = null,
        val contentTypes: List<String> = emptyList(), val languages: List<String> = emptyList()
    )

    private fun saveCache(list: List<RepoUi>) {
        runCatching {
            cacheFile().writeText(cacheJson.encodeToString(list.map {
                CachedRepo(it.name, it.url, it.count, it.iconUrl, it.contentTypes, it.languages)
            }))
        }
    }

    private fun loadCache(): List<RepoUi>? {
        return runCatching {
            val file = cacheFile()
            if (!file.exists()) return null
            cacheJson.decodeFromString<List<CachedRepo>>(file.readText()).map {
                RepoUi(it.name, it.url, it.count, it.iconUrl, it.contentTypes, it.languages)
            }
        }.getOrNull()
    }

    private val adapter = RepoCardAdapter(
        onOpen = { repo -> openRepo(repo) },
        onLongClick = { repo -> showRepoShortcuts(repo) },
        onExitTop = { (activity as? ExtensionsActivity)?.focusPill() == true }
    )
    private var repos: List<RepoUi> = emptyList()
    private var query = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        binding.allExtensionsRecyclerView.adapter = adapter
        binding.allExtensionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadRepos()
    }

    private fun loadRepos() {
        val urls = CsRepos.repos().toList()
        val urlSet = urls.toSet()

        loadCache()?.let { cached ->
            val valid = cached.filter { it.url in urlSet }
            if (valid.isNotEmpty()) {
                repos = valid
                adapter.submitList(valid.filter { it.matches(query) })
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val fresh = urls.map { url ->
                async {
                    val manifest = runCatching { CsRepos.fetchManifest(url) }.getOrNull()
                    val plugins = if (manifest != null) CsRepos.getRepoPlugins(url) else emptyList()

                    val manifestIcon = manifest?.iconUrl?.let {
                        if (it.startsWith("http")) it else CsRepos.sourceUrl(url, it)
                    }
                    val iconUrl = manifestIcon ?: githubOwnerAvatar(url)
                    val contentTypes = plugins.map { it.typeLabel }.distinct().sorted()
                    val languages = plugins.map { it.lang.uppercase(Locale.ROOT) }.distinct().sorted()
                    RepoUi(
                        name = manifest?.name ?: url.clean(),
                        url = url,
                        count = plugins.size,
                        iconUrl = iconUrl,
                        contentTypes = contentTypes,
                        languages = languages
                    )
                }
            }.awaitAll()
            repos = fresh
            adapter.submitList(fresh.filter { it.matches(query) })
            saveCache(fresh)
        }
    }

    private fun openRepo(repo: RepoUi) {
        startActivity(
            Intent(requireContext(), CloudStreamRepoDetailActivity::class.java)
                .putExtra(CloudStreamRepoDetailActivity.ARG_REPO_URL, repo.url)
        )
    }

    private fun showRepoShortcuts(repo: RepoUi) {
        requireContext().customAlertDialog().apply {
            setTitle(repo.name)
            setMessage("Choose an action for this repository")
            setPosButton("Copy URL") {
                copyToClipboard(repo.url, true)
                Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
            }
            setNeutralButton("Delete") {
                CsRepos.removeRepo(repo.url)
                loadRepos()
            }
            setNegButton("Cancel")
            show()
        }
    }

    override fun updateContentBasedOnQuery(query: String?) {
        this.query = query.orEmpty()
        adapter.submitList(repos.filter { it.matches(this.query) })
    }

    override fun notifyDataChanged() {
        loadRepos()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun String.clean(): String = removePrefix("https://raw.githubusercontent.com/")
        .replace("index.json", "")
        .removeSuffix("/")
}
