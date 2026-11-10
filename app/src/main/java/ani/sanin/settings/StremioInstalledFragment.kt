package ani.sanin.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog

class StremioInstalledFragment : Fragment(), SearchQueryHandler {
    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

    private val adapter = RepoCardAdapter(
        onOpen = ::confirmUninstall,
        onLongClick = ::showDetails,
        countLabel = "catalogs",
        onExitTop = { (activity as? ExtensionsActivity)?.focusPill() == true },
        actionLabel = "Uninstall",
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
        loadInstalled()
    }

    private fun loadInstalled() {
        addons = StremioAddonManager.installed()
        Logger.log("STREMIO: installed list count=${addons.size}")
        submitFiltered()
    }

    private fun submitFiltered() {
        if (_binding == null) return
        adapter.submitList(addons.map { StremioAddonManager.toRepoUi(it) }.filter { it.matches(query) })
    }

    private fun confirmUninstall(card: RepoUi) {
        requireContext().customAlertDialog().apply {
            setTitle("Remove add-on")
            setMessage("Remove ${card.name} from installed add-ons?")
            setPosButton("Uninstall") {
                StremioAddonManager.uninstall(card.url)
                Toast.makeText(requireContext(), "${card.name} removed", Toast.LENGTH_SHORT).show()
                loadInstalled()
            }
            setNegButton("Cancel")
            show()
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
        loadInstalled()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
