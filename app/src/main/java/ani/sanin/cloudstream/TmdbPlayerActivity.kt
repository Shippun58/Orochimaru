package ani.sanin.cloudstream

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import ani.sanin.databinding.ActivityTmdbPlayerBinding
import ani.sanin.defaultHeaders
import ani.sanin.okHttpClient
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import com.lagradost.nicehttp.ignoreAllSSLErrors
import java.util.concurrent.TimeUnit

class TmdbPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_HEADERS = "headers"
    }

    private lateinit var binding: ActivityTmdbPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTmdbPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tmdbPlayerBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(binding.tmdbPlayerBack)
        binding.tmdbPlayerView.useController = true
        binding.tmdbPlayerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)

        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val referer = intent.getStringExtra(EXTRA_REFERER)
        @Suppress("DEPRECATION")
        val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>)
            ?: HashMap()
        if (url.isNullOrBlank()) {
            snackString("No video URL")
            finish()
            return
        }

        val requestProperties = HashMap<String, String>().apply {
            putAll(defaultHeaders)
            putAll(headers)
            if (!referer.isNullOrBlank()) put("Referer", referer)
        }
        val httpClient = okHttpClient.newBuilder()
            .apply {
                ignoreAllSSLErrors()
                followRedirects(true)
                followSslRedirects(true)
                connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(20, TimeUnit.SECONDS)
                writeTimeout(20, TimeUnit.SECONDS)
            }
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setDefaultRequestProperties(requestProperties)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().also {
                binding.tmdbPlayerView.player = it
            }
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                binding.tmdbPlayerProgress.isVisible = state == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.tmdbPlayerProgress.isVisible = false
                snackString("Playback error: ${error.errorCodeName}")
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
