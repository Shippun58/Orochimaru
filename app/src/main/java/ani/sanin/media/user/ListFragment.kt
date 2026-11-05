package ani.sanin.media.user

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.databinding.FragmentListBinding
import ani.sanin.media.Media
import ani.sanin.media.MediaAdaptor
import ani.sanin.media.OtherDetailsViewModel
import ani.sanin.cloudstream.TmdbCards

class ListFragment : Fragment() {
    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private var pos: Int? = null
    private var calendar = false
    private var grid: Boolean? = null
    private var list: MutableList<Media>? = null

    var onExitUp: (() -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            pos = it.getInt("list")
            calendar = it.getBoolean("calendar")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val screenWidth = resources.displayMetrics.run { widthPixels / density }

        binding.listRecyclerView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                dpadUpToHeader()
            } else {
                false
            }
        }

        fun update() {
            if (grid != null && list != null) {
                val adapter = MediaAdaptor(if (grid!!) 0 else 1, list!!, requireActivity(), true)
                binding.listRecyclerView.layoutManager =
                    GridLayoutManager(
                        requireContext(),
                        if (grid!!) TmdbCards.gridSpan(screenWidth) else 1
                    )
                binding.listRecyclerView.adapter = adapter
            }
        }

        if (calendar) {
            val model: OtherDetailsViewModel by activityViewModels()
            model.getCalendar().observe(viewLifecycleOwner) {
                if (it != null) {
                    list = it.values.toList().getOrNull(pos!!)
                    update()
                }
            }
            grid = true
        } else {
            val model: ListViewModel by activityViewModels()
            model.getLists().observe(viewLifecycleOwner) {
                if (it != null) {
                    list = it.values.toList().getOrNull(pos!!)
                    update()
                }
            }
            model.grid.observe(viewLifecycleOwner) {
                grid = it
                update()
            }
        }
    }

    fun randomOptionClick() {
        val adapter = binding.listRecyclerView.adapter as MediaAdaptor
        adapter.randomOptionClick()
    }

    fun focusFirstCard(): Boolean {
        val rv = _binding?.listRecyclerView ?: return false
        rv.stopScroll()
        rv.scrollToPosition(0)
        val attached = rv.findViewHolderForAdapterPosition(0)?.itemView
        if (attached != null && attached.requestFocus()) return true
        rv.post {
            val target = rv.findViewHolderForAdapterPosition(0)?.itemView ?: rv.getChildAt(0)
            target?.requestFocus()
        }
        return true
    }

    private fun dpadUpToHeader(): Boolean {
        val rv = _binding?.listRecyclerView ?: return false
        val focused = rv.findFocus() ?: return false
        val holder = rv.findContainingViewHolder(focused) ?: return false
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return false
        val span = (rv.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        if (position >= span) return false
        return onExitUp?.invoke() ?: false
    }

    companion object {
        fun newInstance(pos: Int, calendar: Boolean = false): ListFragment =
            ListFragment().apply {
                arguments = Bundle().apply {
                    putInt("list", pos)
                    putBoolean("calendar", calendar)
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (grid == true) {
            _binding?.listRecyclerView?.let { TmdbCards.applyGridSpan(it) }
        }
    }
}