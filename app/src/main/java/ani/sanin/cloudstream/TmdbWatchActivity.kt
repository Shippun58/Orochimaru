package ani.sanin.cloudstream

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import ani.sanin.R
import ani.sanin.themes.ThemeManager

class TmdbWatchActivity : AppCompatActivity(), TmdbWatchFragment.Host {

    companion object {
        const val ARG_MEDIA_TYPE = "mediaType"
        const val ARG_MEDIA_ID = "mediaId"
        const val ARG_PLUGIN_SOURCE = "pluginSource"
        const val ARG_PLUGIN_URL = "pluginUrl"
        private const val TAG_WATCH = "tmdbWatch"
    }

    override fun onWatchBackPressed() {
        finish()
    }

    override fun onWatchOpenTitle(type: String, id: Int) {
        startActivity(
            Intent(this, TmdbWatchActivity::class.java)
                .putExtra(ARG_MEDIA_TYPE, type)
                .putExtra(ARG_MEDIA_ID, id)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        setContentView(R.layout.activity_tmdb_watch)
        val container = findViewById<FrameLayout>(R.id.tmdbWatchHostContainer)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    container.id,
                    TmdbWatchFragment.newInstance(
                        mediaType = intent.getStringExtra(ARG_MEDIA_TYPE) ?: "movie",
                        mediaId = intent.getIntExtra(ARG_MEDIA_ID, -1),
                        pluginSourceId = intent.getStringExtra(ARG_PLUGIN_SOURCE),
                        pluginUrl = intent.getStringExtra(ARG_PLUGIN_URL)
                    ),
                    TAG_WATCH
                )
                .commit()
        }

        container.post {
            val watch = supportFragmentManager.findFragmentByTag(TAG_WATCH) as? TmdbWatchFragment
            if (watch?.isAdded != true || !watch.focusSelectedSourceChip()) {
                container.requestFocus()
            }
        }
    }
}
