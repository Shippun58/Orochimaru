package ani.sanin.youtube

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ani.sanin.R
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.media.MediaDetailsActivity
import ani.sanin.reels.ReelFeed
import ani.sanin.reels.TmdbReelFeed
import ani.sanin.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ReelShortsPlayerActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ShortsAdapter

    private lateinit var videoTitle: TextView
    private lateinit var channelAvatar: ImageView
    private lateinit var animePill: View
    private lateinit var animeTitle: TextView
    private lateinit var synopsis: TextView
    private lateinit var genres: TextView
    private lateinit var playPause: ImageButton
    private lateinit var muteButton: ImageButton
    private lateinit var progress: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeDuration: TextView
    private lateinit var loading: View
    private lateinit var commentsPanel: LinearLayout
    private lateinit var btnLike: ImageButton
    private lateinit var btnDislike: ImageButton

    private var isPlaying = false
    private var isMuted = false
    private var userSeeking = false

    private val liked = mutableSetOf<String>()
    private val disliked = mutableSetOf<String>()

    private var reelMode = false

    private var reelSource = SOURCE_ANIME
    private val seenReelKeys = HashSet<String>()

    private val seenReelShows = HashSet<String>()
    private var reelLoading = false
    private var reelRetryJob: Job? = null

    private val hidePlayPause = Runnable { playPause.isVisible = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        setContentView(R.layout.activity_youtube_shorts_player)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        val videoIds = intent.getStringArrayListExtra(EXTRA_VIDEO_IDS) ?: arrayListOf()
        val startIdx = intent.getIntExtra(EXTRA_START_INDEX, 0)
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        val animeTitles = intent.getStringArrayListExtra(EXTRA_ANIME_TITLES) ?: arrayListOf()
        val synopses = intent.getStringArrayListExtra(EXTRA_SYNOPSIS) ?: arrayListOf()
        val genresRaw = intent.getStringArrayListExtra(EXTRA_GENRES) ?: arrayListOf()
        val covers = intent.getStringArrayListExtra(EXTRA_COVERS) ?: arrayListOf()
        val animeIds = intent.getIntegerArrayListExtra(EXTRA_ANIME_IDS) ?: arrayListOf()
        val tmdbIds = intent.getIntegerArrayListExtra(EXTRA_TMDB_IDS) ?: arrayListOf()
        val tmdbTypes = intent.getStringArrayListExtra(EXTRA_TMDB_TYPES) ?: arrayListOf()
        reelMode = intent.getBooleanExtra(EXTRA_REEL_MODE, false)
        reelSource = intent.getStringExtra(EXTRA_REEL_SOURCE) ?: SOURCE_ANIME

        if (videoIds.isEmpty()) { finish(); return }

        seenReelKeys.addAll(videoIds)
        seenReelShows.addAll(animeIds.map { "a:$it" })
        tmdbIds.forEachIndexed { index, id ->
            seenReelShows.add("t:${tmdbTypes.getOrElse(index) { "movie" }}:$id")
        }

        recycler = findViewById(R.id.shortsRecyclerView)
        videoTitle = findViewById(R.id.videoTitleText)
        channelAvatar = findViewById(R.id.channelAvatar)
        animePill = findViewById(R.id.animePill)
        animeTitle = findViewById(R.id.animeTitleText)
        synopsis = findViewById(R.id.synopsisText)
        genres = findViewById(R.id.genresText)
        playPause = findViewById(R.id.playerPlayPause)
        muteButton = findViewById(R.id.playerMute)
        progress = findViewById(R.id.playerProgress)
        timeCurrent = findViewById(R.id.playerTimeCurrent)
        timeDuration = findViewById(R.id.playerTimeDuration)
        loading = findViewById(R.id.playerLoading)
        commentsPanel = findViewById(R.id.commentsPanel)
        btnLike = findViewById(R.id.btnLike)
        btnDislike = findViewById(R.id.btnDislike)

        adapter = ShortsAdapter(videoIds, animeIds, tmdbIds, tmdbTypes, titles, animeTitles, synopses, genresRaw, covers)
        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
            .apply { isItemPrefetchEnabled = false }

        recycler.setItemViewCacheSize(0)
        recycler.recycledViewPool.setMaxRecycledViews(0, 1)
        recycler.adapter = adapter

        PagerSnapHelper().attachToRecyclerView(recycler)

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (!reelMode) return
                val last = (rv.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
                if (last >= adapter.itemCount - REEL_PREFETCH_DISTANCE) loadMoreReels()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val pos = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                    if (pos != RecyclerView.NO_POSITION) {
                        adapter.setCurrent(pos)
                        onCurrentChanged(pos)
                        adapter.playCurrent()
                    }
                }
            }
        })

        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                togglePlayPause()
                return true
            }
        })
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                tapDetector.onTouchEvent(e)
                return false
            }
        })

        setUpControls()

        recycler.post {
            recycler.scrollToPosition(startIdx)
            adapter.setCurrent(startIdx)
            onCurrentChanged(startIdx)
            adapter.playCurrent()
        }
    }

    private fun setUpControls() {
        findViewById<ImageButton>(R.id.closeBtn).setOnClickListener { finish() }
        playPause.setOnClickListener { togglePlayPause() }
        muteButton.setOnClickListener {
            isMuted = !isMuted
            adapter.setMuted(isMuted)
            muteButton.setImageResource(
                if (isMuted) R.drawable.ic_baseline_volume_mute_24
                else R.drawable.ic_baseline_volume_up_24
            )
        }

        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) timeCurrent.text = formatTime(value / 1000)
            }

            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                adapter.seekTo(sb.progress / 1000f)
            }
        })

        btnLike.setOnClickListener {
            val id = adapter.currentVideoId() ?: return@setOnClickListener
            if (id in liked) liked.remove(id) else { liked.add(id); disliked.remove(id) }
            updateActionIcons()
        }
        btnDislike.setOnClickListener {
            val id = adapter.currentVideoId() ?: return@setOnClickListener
            if (id in disliked) disliked.remove(id) else { disliked.add(id); liked.remove(id) }
            updateActionIcons()
        }
        findViewById<ImageButton>(R.id.btnComments).setOnClickListener {
            commentsPanel.isVisible = !commentsPanel.isVisible
        }
        findViewById<ImageButton>(R.id.btnShare).setOnClickListener {
            val id = adapter.currentVideoId() ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")))
        }

        animePill.setOnClickListener { openInfo() }
    }

    private fun openInfo() {
        val position = adapter.currentPos
        val tmdbId = adapter.tmdbIdAt(position)
        if (tmdbId > 0) {
            startActivity(
                Intent(this, TmdbDetailsActivity::class.java)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, adapter.tmdbTypeAt(position))
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, tmdbId)
                    .putExtra(TmdbDetailsActivity.ARG_OPEN_TAB, TmdbDetailsActivity.TAB_INFO)
            )
            return
        }
        val animeId = adapter.animeIdAt(position)
        if (animeId > 0) {
            startActivity(
                Intent(this, MediaDetailsActivity::class.java)
                    .putExtra("mediaId", animeId)
                    .putExtra(MediaDetailsActivity.TAB_TO_OPEN, MediaDetailsActivity.INFO_TAB)
            )
        }
    }

    private fun onCurrentChanged(position: Int) {
        videoTitle.text = adapter.titleAt(position)

        val cover = adapter.coverAt(position)
        if (cover.isNullOrBlank()) {
            channelAvatar.isVisible = false
        } else {
            channelAvatar.isVisible = true
            Glide.with(this).load(cover).circleCrop().into(channelAvatar)
        }

        val show = adapter.animeTitleAt(position)
        animePill.isVisible = show.isNotBlank() &&
            (adapter.animeIdAt(position) > 0 || adapter.tmdbIdAt(position) > 0)
        animeTitle.text = show

        val desc = adapter.synopsisAt(position)
        synopsis.isVisible = desc.isNotBlank()
        synopsis.text = desc

        val tags = formatGenres(adapter.genresAt(position))
        genres.isVisible = tags.isNotEmpty()
        genres.text = tags

        userSeeking = false
        progress.progress = 0
        timeCurrent.text = formatTime(0)
        timeDuration.text = formatTime(0)
        loading.isVisible = true
        updateActionIcons()
        adapter.setMuted(isMuted)
    }

    private fun formatGenres(raw: String): String =
        raw.split('|')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { "#" + it.lowercase().replace(Regex("[^a-z0-9]"), "") }
            .filter { it.length > 1 }
            .take(4)
            .joinToString(" ")

    private fun updateActionIcons() {
        val id = adapter.currentVideoId()
        btnLike.setColorFilter(
            if (id != null && id in liked) android.graphics.Color.parseColor("#FF4CAF50")
            else android.graphics.Color.WHITE
        )
        btnDislike.setColorFilter(
            if (id != null && id in disliked) android.graphics.Color.parseColor("#FFE53935")
            else android.graphics.Color.WHITE
        )
    }

    private fun togglePlayPause() {
        if (isPlaying) {
            adapter.pauseCurrent()
            showPlayPause(R.drawable.ic_baseline_play_arrow_24, persist = true)
        } else {
            adapter.playCurrent()
            showPlayPause(R.drawable.ic_baseline_pause_24, persist = false)
        }
    }

    private fun showPlayPause(icon: Int, persist: Boolean) {
        playPause.setImageResource(icon)
        playPause.isVisible = true
        playPause.removeCallbacks(hidePlayPause)
        if (!persist) playPause.postDelayed(hidePlayPause, 900)
    }

    fun onPlayerState(state: Int) {
        when (state) {
            1 -> {
                isPlaying = true
                loading.isVisible = false
                showPlayPause(R.drawable.ic_baseline_pause_24, persist = false)
            }
            2 -> {
                isPlaying = false
                loading.isVisible = false
                showPlayPause(R.drawable.ic_baseline_play_arrow_24, persist = true)
            }
            3 -> loading.isVisible = true
            0 -> {
                isPlaying = false
                onReelEnded()
            }
        }
    }

    fun onPlayerProgress(currentSeconds: Double, durationSeconds: Double) {
        if (userSeeking) return
        val duration = durationSeconds.toInt()
        val current = currentSeconds.toInt()
        if (duration > 0) {
            progress.max = duration * 1000
            progress.progress = current * 1000
            timeDuration.text = formatTime(duration)
        }
        timeCurrent.text = formatTime(current)
    }

    private fun onReelEnded() {
        moveReel(1)
    }

    private fun moveReel(delta: Int) {
        val next = adapter.currentPos + delta
        if (next < adapter.itemCount) {
            if (next >= 0) recycler.smoothScrollToPosition(next)
        } else if (reelMode && delta > 0) {
            loadMoreReels {
                if (adapter.currentPos + 1 < adapter.itemCount) {
                    recycler.smoothScrollToPosition(adapter.currentPos + 1)
                }
            }
        }
    }

    private fun formatTime(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "0:00"
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun loadMoreReels(onAppended: (() -> Unit)? = null) {
        if (reelLoading) return
        reelLoading = true
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { nextReelBatch() }
            if (items.isEmpty()) {

                scheduleReelRetry()
            } else {
                adapter.appendItems(
                    items.map { it.key },
                    items.map { it.anilistId },
                    items.map { it.tmdbId },
                    items.map { it.mediaType ?: "" },
                    items.map { it.videoName },
                    items.map { it.animeTitle },
                    items.map { it.synopsis ?: "" },
                    items.map { it.genres.joinToString("|") },
                    items.map { it.cover ?: "" },
                )
            }
            reelLoading = false
            onAppended?.invoke()
        }
    }

    private fun scheduleReelRetry() {
        if (reelRetryJob?.isActive == true) return
        reelRetryJob = lifecycleScope.launch {
            delay(REEL_RETRY_DELAY_MS)
            loadMoreReels()
        }
    }

    private suspend fun nextReelBatch(): List<ReelFeed.ReelItem> =
        if (reelSource == SOURCE_TMDB) nextTmdbBatch() else nextAnimeBatch()

    private suspend fun nextAnimeBatch(): List<ReelFeed.ReelItem> {
        var attempts = 0
        while (attempts++ < REEL_MAX_EMPTY_PAGES) {
            val pool = runCatching { ReelFeed.draw() }.getOrDefault(emptyList())
            if (pool.isEmpty()) return emptyList()

            val freshShows = pool.filter { seenReelShows.add("a:${it.anilistId}") }
            if (freshShows.isEmpty()) continue
            val fresh = runCatching { ReelFeed.buildInterleaved(freshShows) }.getOrDefault(emptyList())
                .filter { seenReelKeys.add(it.key) }
            if (fresh.isNotEmpty()) return fresh
        }
        return emptyList()
    }

    private suspend fun nextTmdbBatch(): List<ReelFeed.ReelItem> {
        var attempts = 0
        while (attempts++ < REEL_MAX_EMPTY_PAGES) {
            val pool = runCatching { TmdbReelFeed.draw() }.getOrDefault(emptyList())
            if (pool.isEmpty()) return emptyList()
            val freshShows = pool.filter { seenReelShows.add(it.showKey) }
            if (freshShows.isEmpty()) continue
            val fresh = runCatching { TmdbReelFeed.buildInterleaved(freshShows) }.getOrDefault(emptyList())
                .filter { seenReelKeys.add(it.key) }
            if (fresh.isNotEmpty()) return fresh
        }
        return emptyList()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val initialPress = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (initialPress) moveReel(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (initialPress) moveReel(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (initialPress) openInfo()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (initialPress) togglePlayPause()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        if (::adapter.isInitialized) adapter.pauseCurrent()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::adapter.isInitialized) adapter.destroyAll()
    }

    inner class ShortsAdapter(
        private val videoIds: MutableList<String>,
        private val animeIds: MutableList<Int>,
        private val tmdbIds: MutableList<Int>,
        private val tmdbTypes: MutableList<String>,
        private val titles: MutableList<String>,
        private val animeTitles: MutableList<String>,
        private val synopses: MutableList<String>,
        private val genres: MutableList<String>,
        private val covers: MutableList<String>,
    ) : RecyclerView.Adapter<ShortsAdapter.VH>() {

        private val webViews = mutableMapOf<Int, WebView>()

        private val allWebViews = HashSet<WebView>()
        var currentPos = -1

        fun currentVideoId(): String? = videoIds.getOrNull(currentPos)

        fun animeIdAt(position: Int): Int = animeIds.getOrElse(position) { -1 }

        fun tmdbIdAt(position: Int): Int = tmdbIds.getOrElse(position) { 0 }

        fun tmdbTypeAt(position: Int): String = tmdbTypes.getOrElse(position) { "movie" }

        fun titleAt(position: Int): String = titles.getOrElse(position) { "" }

        fun animeTitleAt(position: Int): String = animeTitles.getOrElse(position) { "" }

        fun synopsisAt(position: Int): String = synopses.getOrElse(position) { "" }

        fun genresAt(position: Int): String = genres.getOrElse(position) { "" }

        fun coverAt(position: Int): String? = covers.getOrNull(position)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_reel_short_player, parent, false)
            return VH(view)
        }

        override fun getItemCount() = videoIds.size

        fun appendItems(
            newIds: List<String>,
            newAnimeIds: List<Int>,
            newTmdbIds: List<Int>,
            newTmdbTypes: List<String>,
            newTitles: List<String>,
            newAnimeTitles: List<String>,
            newSynopses: List<String>,
            newGenres: List<String>,
            newCovers: List<String>,
        ) {
            if (newIds.isEmpty()) return
            val start = videoIds.size
            videoIds.addAll(newIds)
            animeIds.addAll(newAnimeIds)
            tmdbIds.addAll(newTmdbIds)
            tmdbTypes.addAll(newTmdbTypes)
            titles.addAll(newTitles)
            animeTitles.addAll(newAnimeTitles)
            synopses.addAll(newSynopses)
            genres.addAll(newGenres)
            covers.addAll(newCovers)
            notifyItemRangeInserted(start, newIds.size)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.boundPosition = position

            if (position == currentPos) load(holder, position)
        }

        private fun load(holder: VH, position: Int) {
            val videoId = videoIds[position]
            if (holder.loadedVideoId == videoId) return
            holder.loadedVideoId = videoId
            setupWebView(holder, videoId, position)
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            val pos = holder.boundPosition
            if (pos != RecyclerView.NO_POSITION && webViews[pos] === holder.youtubeWebView) {
                webViews.remove(pos)
            }
            holder.loadedVideoId = null
            holder.youtubeWebView.stopLoading()
            holder.youtubeWebView.loadUrl("about:blank")
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun setupWebView(holder: VH, videoId: String, position: Int) {
            val webView = holder.youtubeWebView
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            }
            webView.addJavascriptInterface(makeBridge(position), "Android")
            webView.loadDataWithBaseURL(
                "https://www.youtube-nocookie.com", buildHtml(videoId), "text/html", "utf-8", null
            )
            webViews[position] = webView
            allWebViews.add(webView)
        }

        private fun makeBridge(position: Int): Any = object {
            @JavascriptInterface
            fun onReady() {
                runOnUiThread { if (position == currentPos) { loading.isVisible = false; playCurrent() } }
            }

            @JavascriptInterface
            fun onState(state: String) {
                val s = state.toDoubleOrNull()?.toInt() ?: return
                runOnUiThread { if (position == currentPos) onPlayerState(s) }
            }

            @JavascriptInterface
            fun onProgress(currentSeconds: String, durationSeconds: String) {
                if (position != currentPos) return

                val now = System.currentTimeMillis()
                if (now - lastProgressAt < PROGRESS_THROTTLE_MS) return
                lastProgressAt = now
                val cur = currentSeconds.toDoubleOrNull() ?: return
                val dur = durationSeconds.toDoubleOrNull() ?: 0.0
                runOnUiThread { if (position == currentPos) onPlayerProgress(cur, dur) }
            }

            var lastProgressAt = 0L
        }

        fun setCurrent(position: Int) {
            val previous = currentPos
            currentPos = position

            if (previous != position && previous >= 0) webViews[previous]?.let { command(it, "pause()") }
            val holder = recycler.findViewHolderForAdapterPosition(position) as? VH
            if (holder != null) {
                load(holder, position)
            } else {

                recycler.post {
                    (recycler.findViewHolderForAdapterPosition(position) as? VH)?.let { load(it, position) }
                }
            }
        }

        fun playCurrent() {
            val webView = webViews[currentPos] ?: return
            webViews.forEach { (pos, wv) -> if (pos != currentPos) command(wv, "pause()") }
            command(webView, "play()")
        }

        fun pauseCurrent() {
            webViews[currentPos]?.let { command(it, "pause()") }
        }

        fun seekTo(seconds: Float) {
            webViews[currentPos]?.let { command(it, "seek($seconds)") }
        }

        fun setMuted(muted: Boolean) {
            webViews[currentPos]?.let { command(it, if (muted) "mute()" else "unMute()") }
        }

        private fun command(webView: WebView, call: String) {
            webView.evaluateJavascript("window.cmd && window.cmd.$call", null)
        }

        fun destroyAll() {
            allWebViews.forEach { it.destroy() }
            allWebViews.clear()
            webViews.clear()
        }

        private fun buildHtml(videoId: String): String = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}iframe{width:100%;height:100%;border:0;display:block}</style>
</head><body>
<iframe id="yt" src="https://www.youtube-nocookie.com/embed/$videoId?enablejsapi=1&controls=0&rel=0&iv_load_policy=3&disablekb=1&playsinline=1&fs=0&cc_load_policy=0&origin=https%3A%2F%2Fwww.youtube-nocookie.com" allow="autoplay; encrypted-media" frameborder="0"></iframe>
<script>
(function(){
  var f=document.getElementById('yt');
  function send(m){ try{ f.contentWindow.postMessage(JSON.stringify(m),'*'); }catch(e){} }
  window.cmd={
    play:function(){send({event:'command',func:'playVideo',args:[]})},
    pause:function(){send({event:'command',func:'pauseVideo',args:[]})},
    seek:function(s){send({event:'command',func:'seekTo',args:[s,true]})},
    mute:function(){send({event:'command',func:'mute',args:[]})},
    unMute:function(){send({event:'command',func:'unMute',args:[]})}
  };
  function listen(){ send({event:'listening',id:1,channel:'widget'}); }
  f.addEventListener('load',function(){ listen(); setTimeout(listen,300); });
  window.addEventListener('message',function(e){
    if(e.source!==f.contentWindow) return;
    var d; try{ d=JSON.parse(e.data); }catch(err){ return; }
    if(d.event==='onReady'){ Android.onReady(); }
    else if(d.event==='onStateChange' && typeof d.info==='number'){ Android.onState(''+d.info); }
    else if(d.event==='infoDelivery' && d.info){
      if(typeof d.info.playerState==='number'){ Android.onState(''+d.info.playerState); }
      if(typeof d.info.currentTime==='number'){ Android.onProgress(''+d.info.currentTime, ''+(d.info.duration||0)); }
    }
  });
})();
</script>
</body></html>
""".trimIndent()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val youtubeWebView: WebView = view.findViewById(R.id.youtubeWebView)
            var boundPosition = RecyclerView.NO_POSITION
            var loadedVideoId: String? = null
        }
    }

    companion object {
        const val EXTRA_VIDEO_IDS = "video_ids"
        const val EXTRA_ANIME_IDS = "anime_ids"
        const val EXTRA_TMDB_IDS = "tmdb_ids"
        const val EXTRA_TMDB_TYPES = "tmdb_types"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_ANIME_TITLES = "anime_titles"
        const val EXTRA_SYNOPSIS = "synopsis"
        const val EXTRA_GENRES = "genres"
        const val EXTRA_COVERS = "covers"
        const val EXTRA_REEL_MODE = "reel_mode"

        const val EXTRA_REEL_SOURCE = "reel_source"
        const val SOURCE_ANIME = "anime"
        const val SOURCE_TMDB = "tmdb"

        private const val REEL_PREFETCH_DISTANCE = 4

        private const val REEL_MAX_EMPTY_PAGES = 6

        private const val REEL_RETRY_DELAY_MS = 8_000L

        private const val PROGRESS_THROTTLE_MS = 500L
    }
}
