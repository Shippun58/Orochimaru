package ani.sanin.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.BottomSheetDialogFragment
import ani.sanin.connections.github.Contributors
import ani.sanin.databinding.BottomSheetDevelopersBinding
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.launch

class DevelopersDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetDevelopersBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDevelopersBinding.inflate(inflater, container, false)
        FocusEffectUtil.applyFocusListener(binding.root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.devsProgressBar.visibility = View.VISIBLE
        binding.devsRecyclerView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val contributors = Contributors().getContributors()
                binding.devsRecyclerView.adapter = DevelopersAdapter(contributors)
                binding.devsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
                binding.devsRecyclerView.visibility = View.VISIBLE
                binding.devsProgressBar.visibility = View.GONE
            } catch (e: Exception) {
                e.printStackTrace()
                binding.devsProgressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}
