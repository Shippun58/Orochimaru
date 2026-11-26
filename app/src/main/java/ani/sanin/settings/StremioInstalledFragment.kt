package ani.sanin.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
        onToggle = ::toggleEnabled,
        onReorder = ::moveAddon,
    )
    private lateinit var touchHelper: ItemTouchHelper
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
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = moveAddon(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.allExtensionsRecyclerView)
        adapter.reorderMessage = binding.reorderMessage
        adapter.itemTouchHelper = touchHelper
        val backDisarm = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                adapter.disarmDrag(persist = true)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backDisarm)
        adapter.onDragModeChanged = { armed -> backDisarm.isEnabled = armed }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadInstalled()
    }

    private fun loadInstalled() {
        addons = StremioAddonManager.allInstalled()
        Logger.log("STREMIO: installed list count=${addons.size}")
        submitFiltered()
    }

    private fun submitFiltered() {
        if (_binding == null) return
        adapter.submitList(addons.map { StremioAddonManager.toRepoUi(it, installedTab = true) }.filter { it.matches(query) })
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

    private fun toggleEnabled(card: RepoUi, enabled: Boolean) {
        StremioAddonManager.setDisabled(card.url, !enabled)
        Logger.log("STREMIO: '${card.name}' enabled=$enabled")
        loadInstalled()
    }

    private fun moveAddon(from: Int, to: Int): Boolean {
        val size = addons.size
        if (from == to) return false
        if (from !in 0 until size || to !in 0 until size) return false
        val mutable = addons.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        addons = mutable
        StremioAddonManager.setOrder(mutable.map { it.transportUrl })
        submitFiltered()
        return true
    }

    private fun showDetails(card: RepoUi) {
        showAddonDetailsDialog(requireContext(), card)
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
