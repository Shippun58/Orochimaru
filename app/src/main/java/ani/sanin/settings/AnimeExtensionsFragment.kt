package ani.sanin.settings

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
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.customAlertDialog
import androidx.lifecycle.flowWithLifecycle
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class AnimeExtensionsFragment : Fragment(), SearchQueryHandler {

    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

    private val animeExtensionManager: AnimeExtensionManager = Injekt.get()

    private val adapter = RepoCardAdapter(
        onOpen = { repo -> openRepo(repo) },
        onLongClick = { repo -> showRepoShortcuts(repo) },
        countLabel = "extensions",
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeRepos()
    }

    private fun observeRepos() {
        viewLifecycleOwner.lifecycleScope.launch {
            animeExtensionManager.availableExtensionsFlow
                .flowWithLifecycle(viewLifecycleOwner.lifecycle)
                .distinctUntilChanged()
                .collectLatest { available ->
                    repos = buildRepos(available)
                    adapter.submitList(repos.filter { it.matches(query) })
                }
        }
    }

    private fun buildRepos(available: List<AnimeExtension.Available>): List<RepoUi> {
        val urls = PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).toList()
        return urls.mapNotNull { url ->
            val ext = available.filter { it.repository == url }
            if (ext.isEmpty()) return@mapNotNull null
            RepoUi(
                name = url.clean(),
                url = url,
                count = ext.size,
                iconUrl = githubOwnerAvatar(url),
                contentTypes = improviseContentTypes(ext),
                languages = ext.mapNotNull { it.lang?.uppercase(Locale.ROOT) }.distinct().sorted()
            )
        }
    }

    private fun improviseContentTypes(ext: List<AnimeExtension.Available>): List<String> {
        val types = linkedSetOf<String>()
        ext.forEach { e ->
            val n = e.name.lowercase(Locale.ROOT)
            if (e.isNsfw) types.add("NSFW")
            when {
                "dong" in n -> types.add("Donghua")
                "anime" in n -> types.add("Anime")
                "hentai" in n -> types.add("Hentai")
                "manga" in n -> types.add("Manga")
                "jellyfin" in n || "stremio" in n || "torbox" in n -> types.add("Aggregator")
                else -> types.add("Anime")
            }
        }
        return types.toList()
    }

    private fun openRepo(repo: RepoUi) {
        startActivity(
            Intent(requireContext(), AnimeRepoDetailActivity::class.java)
                .putExtra(AnimeRepoDetailActivity.ARG_REPO_URL, repo.url)
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
                val repos = PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos) - repo.url
                PrefManager.setVal(PrefName.AnimeExtensionRepos, repos)
                viewLifecycleOwner.lifecycleScope.launch {
                    animeExtensionManager.findAvailableExtensions()
                }
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
        viewLifecycleOwner.lifecycleScope.launch {
            repos = buildRepos(animeExtensionManager.availableExtensionsFlow.value)
            adapter.submitList(repos.filter { it.matches(query) })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun String.clean(): String = removePrefix("https://raw.githubusercontent.com/")
        .replace("index.min.json", "")
        .replace("repo.json", "")
        .removeSuffix("/")
}
