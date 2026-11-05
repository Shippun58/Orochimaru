package ani.sanin.profile

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anilist.api.User
import ani.sanin.databinding.ActivityFollowBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.ui.LensButtonBackground
import ani.sanin.util.FocusEffectUtil
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FollowActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFollowBinding
    val adapter = GroupieAdapter()
    var users: List<User>? = null
    private lateinit var selected: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityFollowBinding.inflate(layoutInflater)
        binding.listToolbar.updateLayoutParams<MarginLayoutParams> { topMargin = statusBarHeight }
        binding.listFrameLayout.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        setContentView(binding.root)
        FocusEffectUtil.applyFocusListener(binding.root)
        val layoutType = PrefManager.getVal<Int>(PrefName.FollowerLayout)
        selected = getSelected(layoutType)
        binding.followFilterButton.visibility = View.GONE
        LensButtonBackground.apply(binding.followerList)
        LensButtonBackground.apply(binding.followerGrid)
        FocusEffectUtil.applyFocusListener(binding.followerList, binding.followerGrid)
        binding.followerGrid.alpha = 0.33f
        binding.followerList.alpha = 0.33f
        selected(selected)
        binding.listRecyclerView.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.VERTICAL, false
        )
        binding.listRecyclerView.adapter = adapter
        binding.listProgressBar.visibility = View.VISIBLE
        binding.listBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val title = intent.getStringExtra("title")
        val userID = intent.getIntExtra("userId", 0)
        binding.listTitle.text = title

        lifecycleScope.launch(Dispatchers.IO) {
            val respond: List<User>? = when (title) {
                "Following" -> Anilist.query.userFollowing(userID)
                "Followers" -> Anilist.query.userFollowers(userID)
                else -> null
            }
            users = respond
            withContext(Dispatchers.Main) {
                fillList()
                binding.listProgressBar.visibility = View.GONE
            }
        }
        binding.followerList.setOnClickListener {
            selected(it as ImageButton)
            PrefManager.setVal(PrefName.FollowerLayout, 0)
            fillList()
        }
        binding.followerGrid.setOnClickListener {
            selected(it as ImageButton)
            PrefManager.setVal(PrefName.FollowerLayout, 1)
            fillList()
        }
        binding.followSwipeRefresh.setOnRefreshListener {
            binding.followSwipeRefresh.isRefreshing = false
        }
    }

    private fun fillList() {
        adapter.clear()
        val screenWidth = resources.displayMetrics.run { widthPixels / density }
        binding.listRecyclerView.layoutManager = when (getLayoutType(selected)) {
            0 -> LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
            1 -> GridLayoutManager(
                this, (screenWidth / 120f).toInt(), GridLayoutManager.VERTICAL, false
            )

            else -> LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        }
        users?.forEach { user ->
            if (getLayoutType(selected) == 0) {
                adapter.add(
                    FollowerItem(
                        false,
                        user,
                        lifecycleScope,
                    ) { onUserClick(it) })
            } else {
                adapter.add(
                    FollowerItem(
                        true,
                        user,
                        lifecycleScope,
                    ) { onUserClick(it) })
            }
        }
    }

    fun selected(it: ImageButton) {
        selected.alpha = 0.33f
        selected = it
        selected.alpha = 1f
    }

    private fun getSelected(pos: Int): ImageButton {
        return when (pos) {
            0 -> binding.followerList
            1 -> binding.followerGrid
            else -> binding.followerList
        }
    }

    private fun getLayoutType(it: ImageButton): Int {
        return when (it) {
            binding.followerList -> 0
            binding.followerGrid -> 1
            else -> 0
        }
    }

    private fun onUserClick(id: Int) {
        val intent = Intent(this, ProfileActivity::class.java)
        intent.putExtra("userId", id)
        startActivity(intent)
    }
}
