package ani.sanin.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.BottomSheetDialogFragment
import ani.sanin.databinding.BottomSheetUsersBinding
import ani.sanin.util.FocusEffectUtil

class UsersDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetUsersBinding? = null
    private val binding get() = _binding!!

    private var userList = arrayListOf<User>()
    fun userList(user: ArrayList<User>) {
        userList = user
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetUsersBinding.inflate(inflater, container, false)
        FocusEffectUtil.applyFocusListener(binding.root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.usersRecyclerView.adapter = UsersAdapter(userList)
        binding.usersRecyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}