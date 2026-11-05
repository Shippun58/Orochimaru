package ani.sanin.profile

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.sanin.connections.simkl.Simkl
import ani.sanin.databinding.ActivitySimklProfileBinding
import ani.sanin.initActivity
import ani.sanin.loadImage
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimklProfileActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySimklProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivitySimklProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FocusEffectUtil.applyFocusListener(
            binding.simklProfileAvatar,
            binding.simklProfileClose,
            binding.simklProfileMoviesBox,
            binding.simklProfileShowsBox
        )

        binding.simklProfileClose.setOnClickListener { finish() }
        binding.simklProfileAvatar.setOnClickListener { finish() }

        binding.simklProfileName.text = Simkl.username?.takeIf { it.isNotBlank() } ?: "Not signed in"
        Simkl.avatar?.takeIf { it.isNotBlank() }?.let { binding.simklProfileAvatar.loadImage(it) }

        loadCounts()
    }

    private fun loadCounts() {
        lifecycleScope.launch {

            val movies = runCatching { withContext(Dispatchers.IO) { Simkl.getMovieLibrary().size } }.getOrNull()
            val shows = runCatching { withContext(Dispatchers.IO) { Simkl.getShowLibrary().size } }.getOrNull()

            binding.simklProfileProgress.visibility = View.GONE
            movies?.let { binding.simklProfileMovieCount.text = it.toString() }
            shows?.let { binding.simklProfileShowCount.text = it.toString() }
        }
    }
}