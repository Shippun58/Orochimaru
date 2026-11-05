package ani.sanin.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.BottomSheetDialogFragment
import ani.sanin.R
import ani.sanin.databinding.BottomSheetFirsttimeProvidersBinding
import ani.sanin.parsers.AnimeSources
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

class FirstTimeProviderDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetFirsttimeProvidersBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetFirsttimeProvidersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val enabled = PrefManager.getVal<Set<String>>(PrefName.EnabledProviders)

        val items = AnimeSources.orderedNativeParsers.map { parser ->
            val isEnglish = AnimeSources.isEnglishProvider(parser.saveName)
            ProviderItem(
                name = parser.name,
                saveName = parser.saveName,
                isEnabled = parser.saveName in enabled,
                language = getString(
                    if (isEnglish) R.string.provider_language_english
                    else R.string.provider_language_spanish
                ),
                isEnglish = isEnglish
            )
        }.toMutableList()

        binding.firstTimeRecyclerView.layoutManager = SafeLinearLayoutManager(requireContext())
        val adapter = ProviderAdapter(
            items,
            onStateChanged = {
                val enabledNow = items.filter { it.isEnabled }.map { it.saveName }.toSet()
                PrefManager.setVal(PrefName.EnabledProviders, enabledNow)
                AnimeSources.rebuildNativeParsers()
            },
            onReorder = { names -> AnimeSources.reorderProviders(names) }
        )
        binding.firstTimeRecyclerView.adapter = adapter

        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.absoluteAdapterPosition
                val to = target.absoluteAdapterPosition
                adapter.moveItem(from, to)
                adapter.dragActivePosition = to
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.elevation = 8f
                    viewHolder?.itemView?.translationZ = 8f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.disarmDrag(persist = true)
                viewHolder.itemView.elevation = 0f
                viewHolder.itemView.translationZ = 0f
            }
        }
        val touchHelper = ItemTouchHelper(itemTouchHelperCallback)
        touchHelper.attachToRecyclerView(binding.firstTimeRecyclerView)
        adapter.itemTouchHelper = touchHelper
        adapter.reorderMessage = binding.reorderMessage

        val backDisarm = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                adapter.disarmDrag(persist = true)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backDisarm)
        adapter.onDragModeChanged = { armed -> backDisarm.isEnabled = armed }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
