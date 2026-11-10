package ani.sanin.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.launch

class StremioAvailableFragment : Fragment(), SearchQueryHandler {
    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

    private val adapter = RepoCardAdapter(
        onOpen = ::install,
        onLongClick = ::showDetails,
        countLabel = "catalogs",
        onExitTop = { (activity as? ExtensionsActivity)?.focusPill() == true },
        actionLabel = "Install",
        onConfigure = ::openConfiguration,
    )
    private var addons: List<StremioAddon> = emptyList()
    private var query = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        binding.allExtensionsRecyclerView.adapter = adapter
        binding.allExtensionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadCommunity()
    }

    private fun loadCommunity() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val cached = StremioAddonManager.cachedCommunity(appContext)
            Logger.log("STREMIO: available cache count=${cached.size}")
            if (cached.isNotEmpty()) {
                addons = cached
                submitFiltered()
            }
            val fresh = runCatching {
                StremioAddonManager.fetchCommunity(appContext)
            }.getOrNull() ?: run {
                Logger.log("STREMIO: community fetch failed, keeping cached=${addons.size}")
                return@launch
            }
            addons = fresh
            submitFiltered()
        }
    }

    private fun submitFiltered() {
        if (_binding == null) return
        val installedUrls = StremioAddonManager.installed()
            .map { it.transportUrl.lowercase() }
            .toSet()
        adapter.submitList(
            addons.asSequence()
                .filterNot { it.transportUrl.lowercase() in installedUrls }
                .map { StremioAddonManager.toRepoUi(it) }
                .filter { it.matches(query) }
                .toList()
        )
    }

    private fun install(card: RepoUi) {
        val addon = addons.firstOrNull { it.transportUrl == card.url } ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            Logger.log("STREMIO: install requested '${addon.manifest.name}' url=${addon.transportUrl}")
            val installed = StremioAddonManager.installWithManifest(addon)
            if (!isAdded) return@launch
            if (installed == null) {
                Toast.makeText(
                    requireContext(),
                    "${addon.manifest.name} is already installed",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val detail = listOf(
                    StremioAddonClient.capabilities(installed).joinToString(),
                    StremioAddonClient.supportedTypes(installed).joinToString(),
                ).filter { it.isNotBlank() }.joinToString(" · ")
                val message = if (detail.isBlank()) "${installed.manifest.name} installed"
                else "${installed.manifest.name} installed ($detail)"
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
            submitFiltered()
        }
    }

    private fun openConfiguration(card: RepoUi) {
        val url = card.configUrl
        if (url == null) {
            Logger.log("STREMIO: '${card.name}' is not configurable")
            Toast.makeText(
                requireContext(),
                "${card.name} is not configurable",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        Logger.log("STREMIO: opening configuration for '${card.name}' url=$url")
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(requireContext(), "Could not open configuration", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDetails(card: RepoUi) {
        requireContext().customAlertDialog().apply {
            setTitle(card.name)
            setMessage(
                buildString {
                    if (!card.description.isNullOrBlank()) append(card.description)
                    append("\n\n")
                    append(card.countText.orEmpty())
                    if (card.contentTypes.isNotEmpty()) {
                        append("\nResources: ${card.contentTypes.joinToString()}")
                    }
                    if (card.languages.isNotEmpty()) {
                        append("\nTypes: ${card.languages.joinToString()}")
                    }
                }.trim()
            )
            setPosButton("OK")
            show()
        }
    }

    override fun updateContentBasedOnQuery(query: String?) {
        this.query = query.orEmpty()
        submitFiltered()
    }

    override fun notifyDataChanged() {
        submitFiltered()
        loadCommunity()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
