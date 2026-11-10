package ani.sanin.media.anime
import ani.sanin.settings.AnimUtils
import ani.sanin.ui.components.TomoeView

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.AudioManager.AUDIOFOCUS_GAIN
import android.media.AudioManager.AUDIOFOCUS_LOSS
import android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
import android.media.AudioManager.STREAM_MUSIC
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings.System
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_B
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_N
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.math.MathUtils.clamp
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.C.TRACK_TYPE_VIDEO
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.StreamKey
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

import androidx.media3.session.MediaSession
import androidx.drawerlayout.widget.DrawerLayout
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DEPRESSED
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import ani.sanin.GesturesListener
import ani.sanin.media.EpisodeMapper
import ani.sanin.NoPaddingArrayAdapter
import ani.sanin.R
import ani.sanin.brightnessConverter
import ani.sanin.withAlphaFraction
import ani.sanin.circularReveal
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.crashlytics.CrashlyticsInterface
import ani.sanin.connections.mal.MAL
import ani.sanin.Refresh
import ani.sanin.connections.updateProgress
import ani.sanin.connections.simkl.Simkl
import ani.sanin.cloudstream.TmdbStreamResolver
import ani.sanin.media.anime.live.LiveHelper
import ani.sanin.media.anime.live.LivestreamChunk
import ani.sanin.media.anime.live.PREFERRED_LIVE_OFFSET
import ani.sanin.databinding.ActivityExoplayerBinding
import ani.sanin.defaultHeaders
import ani.sanin.download.DownloadHlsCache
import ani.sanin.dp
import ani.sanin.getCurrentBrightnessValue
import ani.sanin.getLanguageCode
import ani.sanin.hideSystemBars
import ani.sanin.hideSystemBarsExtendView
import ani.sanin.isOnline
import ani.sanin.logError
import ani.sanin.media.Media
import ani.sanin.media.MediaDetailsViewModel
import ani.sanin.media.MediaNameAdapter
import ani.sanin.media.MediaType
import ani.sanin.notifications.subscription.SubscriptionHelper
import ani.sanin.okHttpClient
import ani.sanin.others.AniSkip
import ani.sanin.others.AniSkip.getType
import ani.sanin.others.IdMappers
import ani.sanin.others.LanguageMapper
import ani.sanin.others.ResettableTimer
import ani.sanin.others.Xubtitle
import ani.sanin.others.getSerialized
import ani.sanin.parsers.AnimeSources
import ani.sanin.parsers.HAnimeSources
import ani.sanin.parsers.Subtitle
import ani.sanin.parsers.SubtitleType
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.DrmInfo
import ani.sanin.parsers.VideoType
import ani.sanin.settings.PlayerSettingsActivity
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import ani.sanin.snackString
import ani.sanin.startMainActivity
import ani.sanin.themes.OledBackgroundManager
import ani.sanin.themes.ThemeManager
import ani.sanin.toPx
import ani.sanin.toast
import ani.sanin.tryWithSuspend
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import com.anggrayudi.storage.file.extension
import java.io.File
import java.text.SimpleDateFormat
import kotlinx.coroutines.withContext
import android.content.res.Resources
import android.view.LayoutInflater
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.bumptech.glide.Glide
import com.google.android.material.slider.Slider
import com.lagradost.nicehttp.ignoreAllSSLErrors
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import androidx.core.net.toUri
import ani.sanin.connections.LogoApi
import ani.sanin.connections.comments.AnikotoAPI
import ani.sanin.connections.comments.Comment
import ani.sanin.connections.comments.CommentsAPI
import ani.sanin.connections.subtitles.StremioSubtitles
import ani.sanin.connections.subtitles.StremioSub
import ani.sanin.connections.subtitles.OpenSubtitles
import ani.sanin.media.comments.CommentZoomDialog
import ani.sanin.media.comments.parseGifCommentContent
import ani.sanin.loadImage
import java.net.URI

@UnstableApi
@SuppressLint("ClickableViewAccessibility")
class ExoplayerView :
    AppCompatActivity(),
    Player.Listener,
    SubtitleSyncHost {
    private val resumeWindow = "resumeWindow"
    private val resumePosition = "resumePosition"
    private val playerFullscreen = "playerFullscreen"
    private val playerOnPlay = "playerOnPlay"
    private var disappeared: Boolean = false
    private var functionstarted: Boolean = false

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var cacheFactory: CacheDataSource.Factory
    private lateinit var playbackParameters: PlaybackParameters
    private lateinit var mediaItem: MediaItem
    private lateinit var mediaSource: MergingMediaSource
    private var mediaSession: MediaSession? = null

    private lateinit var binding: ActivityExoplayerBinding
    private lateinit var playerView: PlayerView
    private lateinit var exoPlay: ImageButton
    private lateinit var exoSource: ImageButton
    private lateinit var exoSettings: ImageButton
    private lateinit var exoSubtitle: ImageButton
    private lateinit var exoSubtitleView: SubtitleView
    private lateinit var subtitleDrawerContent: View
    private lateinit var subtitleDrawerClose: ImageButton
    private lateinit var subtitleLangButton: ImageButton
    private lateinit var subtitleDrawerList: RecyclerView
    private var subtitleRailController: SubtitleRailController? = null
    private var embeddedSubTracks: List<Tracks.Group> = emptyList()
    private var playerTracksBtn: ImageButton? = null
    private lateinit var tracksDrawerContent: View
    private lateinit var tracksDrawerClose: ImageButton
    private lateinit var tracksDrawerList: RecyclerView
    private var trackRailController: TrackRailController? = null
    private var tracksHidden = true
    private lateinit var exoAudioTrack: ImageButton
    private lateinit var exoSpeed: ImageButton
    private lateinit var exoScreen: ImageButton
    private lateinit var exoRotate: ImageButton
    private lateinit var exoNext: ImageButton
    private lateinit var exoPrev: ImageButton
    private lateinit var exoSkipOpEd: ImageButton
    private lateinit var exoPip: ImageButton
    private lateinit var exoBrightness: Slider
    private lateinit var exoVolume: Slider
    private lateinit var exoBrightnessCont: View
    private lateinit var exoVolumeCont: View
    private lateinit var exoSkip: View
    private lateinit var skipTimeButton: View
    private lateinit var skipTimeText: TextView
    private lateinit var timeStampText: TextView
    private lateinit var animeTitle: TextView
    private lateinit var videoInfo: TextView
    private lateinit var audioLangInfo: TextView
    private lateinit var episodeTitle: Spinner
    private lateinit var episodeTitleText: TextView
    private lateinit var episodeTitleBtn: View
    private lateinit var episodeDrawer: DrawerLayout
    private lateinit var episodeDrawerContent: View
    private lateinit var episodeDrawerList: RecyclerView
    private lateinit var episodeDrawerClose: ImageButton
    private var episodeDrawerAdapter: EpisodeRailAdapter? = null
    private lateinit var episodeCommentPanel: View
    private lateinit var episodeCommentList: RecyclerView
    private lateinit var episodeCommentClose: ImageButton
    private lateinit var episodeCommentTitle: TextView
    private lateinit var episodeCommentProgress: TomoeView
    private var episodeCommentAdapter: EpisodeCommentPillAdapter? = null
    private var episodeCommentJob: Job? = null
    private var commentPanelEpisode: String? = null
    private lateinit var customSubtitleView: Xubtitle
    private lateinit var customSubtitleTopView: Xubtitle
    private var assHandler: AssHandler? = null
    private var assSubtitleView: io.github.peerless2012.ass.media.widget.AssSubtitleView? = null
    private lateinit var assMediaSourceFactory: DefaultMediaSourceFactory

    private var orientationListener: OrientationEventListener? = null

    private var hasExtSubtitles = false
    private var audioLanguages = mutableListOf<Pair<String, String>>()
    private val storedSyncCues = mutableListOf<SyncCue>()
    private val seenCueTexts = mutableSetOf<String>()
    private val cueTextStartTimes = mutableMapOf<String, MutableList<Long>>()
    private val speechRecorder = AudioSpeechRecorder()
    private var speechRecorderKey: String? = null
    private var autoSyncJob: Job? = null
    private var autoSyncCues: List<SyncCue> = emptyList()
    private var autoSyncCueKey: String? = null
    private var autoSyncGeneration = 0

    companion object {
        var initialized = false
        lateinit var media: Media

        var offlinePlayback = false

        private const val DEFAULT_MIN_BUFFER_MS = 30000
        private const val DEFAULT_MAX_BUFFER_MS = 60000
        private const val BUFFER_FOR_PLAYBACK_MS = 8000
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5000
        private const val BACK_BUFFER_DURATION_MS = 1000 * 60 * 2
        private const val MAX_PLAYER_ERROR_RETRIES = 1
        private const val MAX_LIVE_RECONNECTS = 3
        private const val AUTO_SYNC_INTERVAL_MS = 15_000L
        private const val AUTO_SYNC_MIN_SPEECH_MS = 25_000L
        private const val AUTO_SYNC_MAX_CAPTURE_MS = 10 * 60_000L
        private const val AUTO_SYNC_STRONG_SCORE = 0.90
        private const val AUTO_SYNC_STABLE_SCORE = 0.80
        private const val AUTO_SYNC_MIN_GAIN = 0.05
        private const val AUTO_SYNC_MIN_OFFSET_CHANGE_MS = 80L
        private const val AUTO_SYNC_MIN_RATE_CHANGE = 0.001

        fun clearAllCaches() {
            try {
                val ctx = ani.sanin.App.instance
                val cacheDir = ctx?.cacheDir
                if (cacheDir != null) {
                    cacheDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("online_subtitle_") || file.name.startsWith("local_sub_")) {
                            file.delete()
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private lateinit var episode: Episode
    private lateinit var episodes: MutableMap<String, Episode>
    private lateinit var episodeArr: List<String>
    private lateinit var episodeTitleArr: ArrayList<String>
    private var currentEpisodeIndex = 0
    private var epChanging = false

    private lateinit var railSeasonScroll: android.widget.HorizontalScrollView
    private lateinit var railSeasonChips: ChipGroup
    private var seasonArr: List<String> = emptyList()
    private var currentRailSeason: String? = null

    private var extractor: VideoExtractor? = null
    private var video: Video? = null
    private var subtitle: Subtitle? = null

    private var notchHeight: Int = 0
    private var currentWindow = 0
    private var playbackPosition: Long = 0
    private var episodeLength: Float = 0f
    private var simklAddedToWatchlist = false
    private var isFullscreen: Int = 0
    private var isInitialized = false
    private var isPlayerPlaying = true
    private var changingServer = false
    private var interacted = false

    private var pipEnabled = false
    private var aspectRatio = Rational(16, 9)
    private var backPressTime = 0L
    private var dpadPressTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var pauseMetadataTimer: Runnable? = null
    private lateinit var pauseOverlay: View
    private lateinit var pauseTitle: TextView
    private lateinit var pauseSynopsis: TextView
    private lateinit var pauseGenres: ChipGroup
    private lateinit var pauseRating: TextView
    private lateinit var pauseLogo: ImageView
    val model: MediaDetailsViewModel by viewModels()
    private val getContent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        uri?.let { applyLocalSubtitle(it) }
    }

    private var isTimeStampsLoaded = false
    private var timeStampsLoading = false
    private var lastTimeStampAttempt = 0L
    private var isSeeking = false
    private var isFastForwarding = false
    private var playerErrorRetryCount = 0
    private var opaqueFormatRetryStage = 0
    private var liveReconnectCount = 0

    private var liveDetectedFromPlayer = false
    private var liveSupportRegistered = false

    private val audioTrackGroups = mutableListOf<Tracks.Group>()

    @Volatile private var pendingSubtitleLabel: String? = null
    @Volatile private var initialSubtitleLabel: String? = null

    var rotation = 0

    override fun onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val displayCutout = window.decorView.rootWindowInsets.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    notchHeight =
                        min(
                            displayCutout.boundingRects[0].width(),
                            displayCutout.boundingRects[0].height(),
                        )
                    checkNotch()
                }
            }
        }
        super.onAttachedToWindow()
    }

    private fun checkNotch() {
        if (notchHeight != 0) {
            val orientation = resources.configuration.orientation
            playerView
                .findViewById<View>(R.id.exo_controller_margin)
                .updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        marginStart = notchHeight
                        marginEnd = notchHeight
                        topMargin = 0
                    } else {
                        topMargin = notchHeight
                        marginStart = 0
                        marginEnd = 0
                    }
                }
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_buffering).translationY =
                (if (orientation == Configuration.ORIENTATION_LANDSCAPE) 0 else (notchHeight + 8.toPx)).dp
            exoBrightnessCont.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginEnd =
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) notchHeight else 0
            }
            exoVolumeCont.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart =
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) notchHeight else 0
            }
        }
    }

    private fun setupSubFormatting(playerView: PlayerView) {
        val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)

        val secondaryColor = PrefManager.getVal<Int>(PrefName.SecondaryColor)

        val outline =
            when (PrefManager.getVal<Int>(PrefName.Outline)) {
                0 -> EDGE_TYPE_OUTLINE
                1 -> EDGE_TYPE_DEPRESSED
                2 -> EDGE_TYPE_DROP_SHADOW
                3 -> EDGE_TYPE_NONE
                else -> EDGE_TYPE_OUTLINE
            }

        val subAlpha = PrefManager.getVal<Float>(PrefName.SubAlpha)
        val subBackground = PrefManager.getVal<Int>(PrefName.SubBackground).withAlphaFraction(subAlpha)

        val subWindow = PrefManager.getVal<Int>(PrefName.SubWindow)

        val font =
            when (PrefManager.getVal<Int>(PrefName.Font)) {
                0 -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
                1 -> ResourcesCompat.getFont(this, R.font.poppins_bold)
                2 -> ResourcesCompat.getFont(this, R.font.poppins)
                3 -> ResourcesCompat.getFont(this, R.font.poppins_thin)
                4 -> ResourcesCompat.getFont(this, R.font.century_gothic_regular)
                5 -> ResourcesCompat.getFont(this, R.font.levenim_mt_bold)
                6 -> ResourcesCompat.getFont(this, R.font.blocky)
                else -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
            }
        val fontSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()

        playerView.subtitleView?.let { subtitles ->
            subtitles.setApplyEmbeddedStyles(false)
            subtitles.setApplyEmbeddedFontSizes(false)

            subtitles.setStyle(
                CaptionStyleCompat(
                    primaryColor,
                    subBackground,
                    subWindow,
                    outline,
                    secondaryColor,
                    font,
                ),
            )

            subtitles.alpha =
                when (PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
                    true -> 1f
                    false -> 0f
                }

            subtitles.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        }
    }

    private fun applySubtitleStyles(textView: Xubtitle) {
        val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)

        val subBackground = PrefManager.getVal<Int>(PrefName.SubBackground)
            .withAlphaFraction(PrefManager.getVal<Float>(PrefName.SubAlpha))

        val secondaryColor = PrefManager.getVal<Int>(PrefName.SecondaryColor)

        val subStroke = PrefManager.getVal<Float>(PrefName.SubStroke)

        val fontSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()

        val font =
            when (PrefManager.getVal<Int>(PrefName.Font)) {
                0 -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
                1 -> ResourcesCompat.getFont(this, R.font.poppins_bold)
                2 -> ResourcesCompat.getFont(this, R.font.poppins)
                3 -> ResourcesCompat.getFont(this, R.font.poppins_thin)
                4 -> ResourcesCompat.getFont(this, R.font.century_gothic_regular)
                5 -> ResourcesCompat.getFont(this, R.font.levenim_mt_bold)
                6 -> ResourcesCompat.getFont(this, R.font.blocky)
                else -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
            }

        textView.setBackgroundColor(subBackground)
        textView.setTextColor(primaryColor)
        textView.typeface = font
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)

        textView.apply {
            when (PrefManager.getVal<Int>(PrefName.Outline)) {
                0 -> applyOutline(secondaryColor, subStroke)
                1 -> applyShineEffect(secondaryColor)
                2 -> applyDropShadow(secondaryColor, subStroke)
                3 -> {}
                else -> applyOutline(secondaryColor, subStroke)
            }
        }

        textView.alpha =
            when (PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
                true -> 1f
                false -> 0f
            }

        val textElevation =
            PrefManager.getVal<Float>(PrefName.SubBottomMargin) / 50 * resources.displayMetrics.heightPixels

        val positionOffset = 10f
        textView.translationY = -textElevation + positionOffset
    }

    private fun applyTopSubtitleStyles(textView: Xubtitle) {
        applySubtitleStyles(textView)
        textView.translationY = 0f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        OledBackgroundManager.remove(this)
        binding = ActivityExoplayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemBarsExtendView()

        playerView = findViewById(R.id.player_view)

        exoPlay = playerView.findViewById(androidx.media3.ui.R.id.exo_play)
        exoSource = playerView.findViewById(R.id.exo_source)
        exoSettings = playerView.findViewById(R.id.exo_settings)
        exoSubtitle = playerView.findViewById(R.id.exo_sub)
        exoAudioTrack = playerView.findViewById(R.id.exo_audio)
        exoSubtitleView = playerView.findViewById(androidx.media3.ui.R.id.exo_subtitles)

        exoSubtitleView?.setBottomPaddingFraction(0.0f)

        exoSpeed = playerView.findViewById(androidx.media3.ui.R.id.exo_playback_speed)
        exoScreen = playerView.findViewById(R.id.exo_screen)
        exoRotate = playerView.findViewById(R.id.exo_rotate)
        exoBrightness = playerView.findViewById(R.id.exo_brightness)
        exoVolume = playerView.findViewById(R.id.exo_volume)
        exoBrightnessCont = playerView.findViewById(R.id.exo_brightness_cont)
        exoVolumeCont = playerView.findViewById(R.id.exo_volume_cont)
        exoSkipOpEd = playerView.findViewById(R.id.exo_skip_op_ed)
        exoPip = playerView.findViewById(R.id.exo_pip)
        exoSkip = playerView.findViewById(R.id.exo_skip)
        skipTimeButton = playerView.findViewById(R.id.exo_skip_timestamp)
        skipTimeText = skipTimeButton.findViewById(R.id.exo_skip_timestamp_text)
        timeStampText = playerView.findViewById(R.id.exo_time_stamp_text)
        customSubtitleView = playerView.findViewById(R.id.customSubtitleView)
        customSubtitleTopView = playerView.findViewById(R.id.customSubtitleTopView)

        animeTitle = playerView.findViewById(R.id.exo_anime_title)
        episodeTitle = playerView.findViewById(R.id.exo_ep_sel)
        episodeTitleText = playerView.findViewById(R.id.exo_ep_sel_text)
        episodeTitleBtn = playerView.findViewById(R.id.exo_ep_sel_btn)

        playerView.findViewById<View>(R.id.exo_autoplay).apply {
            val thumb = findViewById<ImageView>(R.id.exo_autoplay_thumb)
            fun renderAutoplay(enabled: Boolean, animate: Boolean = false) {
                isSelected = enabled
                thumb.setImageResource(
                    if (enabled) R.drawable.ic_play_circle_filled_24
                    else R.drawable.ic_pause_circle_filled_24
                )
                thumb.setColorFilter(
                    if (enabled) {
                        val tv = TypedValue()
                        theme.resolveAttribute(
                            com.google.android.material.R.attr.colorPrimary, tv, true
                        )
                        tv.data
                    } else {
                        0xFFFFFFFF.toInt()
                    }
                )
                contentDescription = "${context.getString(R.string.autoplay)}: ${if (enabled) "on" else "off"}"
                fun place() {
                    if (width > 0 && thumb.width > 0) {
                        val target = (if (enabled) width - thumb.width else 0).toFloat()
                        if (animate) {
                            thumb.animate().translationX(target).setDuration(250).start()
                        } else {
                            thumb.translationX = target
                        }
                    }
                }
                if (width > 0) {
                    place()
                } else {
                    doOnLayout { place() }
                }
            }
            var enabled = PrefManager.getVal<Boolean>(PrefName.AutoPlay)
            renderAutoplay(enabled)
            setOnClickListener {
                enabled = !enabled
                PrefManager.setVal(PrefName.AutoPlay, enabled)
                renderAutoplay(enabled, animate = AnimUtils.enabled(ani.sanin.settings.saving.PrefName.MiscUiAnimations))
                snackString("${context.getString(R.string.autoplay)} ${if (enabled) "on" else "off"}")
                Logger.log("Player: AUTOPLAY toggled — enabled=$enabled")
            }
        }

        applyWrapButtonsStyle()

        episodeDrawer = binding.root
        episodeDrawerList = findViewById(R.id.episodeDrawerList)
        episodeDrawerClose = findViewById(R.id.episodeDrawerClose)
        episodeDrawerContent = findViewById(R.id.episodeDrawer)
        railSeasonScroll = findViewById(R.id.episodeRailSeasonScroll)
        railSeasonChips = findViewById(R.id.episodeRailSeasonChips)
        episodeDrawerClose.nextFocusDownId = R.id.episodeDrawerList
        episodeDrawerList.nextFocusUpId = R.id.episodeDrawerClose
        FocusEffectUtil.applyFocusListener(episodeDrawerClose)
        episodeDrawerClose.setOnClickListener { episodeDrawer.closeDrawer(episodeDrawerContent) }

        episodeCommentPanel = findViewById(R.id.episodeCommentPanel)
        episodeCommentList = findViewById(R.id.episodeCommentList)
        episodeCommentClose = findViewById(R.id.episodeCommentClose)
        episodeCommentTitle = findViewById(R.id.episodeCommentTitle)
        episodeCommentProgress = findViewById(R.id.episodeCommentProgress)
        episodeCommentList.layoutManager = LinearLayoutManager(this)
        episodeCommentAdapter = EpisodeCommentPillAdapter { comment ->
            openPlayerCommentZoom(comment)
        }
        episodeCommentList.adapter = episodeCommentAdapter
        episodeCommentClose.nextFocusDownId = R.id.episodeCommentList
        episodeCommentList.nextFocusUpId = R.id.episodeCommentClose
        FocusEffectUtil.applyFocusListener(episodeCommentClose)
        episodeCommentClose.setOnClickListener { closeEpisodeCommentPanel(returnToRail = true) }

        episodeDrawer.setDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: View) {
                if (drawerView !== episodeDrawerContent) return

                playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                playerView.isFocusable = false
                val currentKey = episodes.getEpisodeKey(media.anime?.selectedEpisode)
                val pos = railEpisodes().keys.indexOfFirst { it == currentKey }
                    .coerceIn(0, episodeDrawerAdapter?.itemCount?.minus(1) ?: 0)
                episodeDrawerList.scrollToPosition(pos)
                episodeDrawerList.post {
                    val holder = episodeDrawerList.findViewHolderForAdapterPosition(pos)
                    if (holder != null) {
                        holder.itemView.requestFocus()
                    } else {
                        episodeDrawerList.requestFocus()
                    }
                }
            }
            override fun onDrawerClosed(drawerView: View) {
                if (drawerView !== episodeDrawerContent) return
                if (episodeCommentPanel.visibility != View.VISIBLE) {
                    playerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    playerView.isFocusable = true
                    episodeTitleBtn.requestFocus()
                }
            }
            override fun onDrawerStateChanged(newState: Int) {}
        })

        episodeTitleBtn.setOnClickListener {
            if (episodeCommentPanel.visibility == View.VISIBLE) {
                closeEpisodeCommentPanel(returnToRail = true)
            } else if (episodeDrawer.isDrawerOpen(episodeDrawerContent)) {
                episodeDrawer.closeDrawer(episodeDrawerContent)
            } else {
                episodeDrawer.openDrawer(episodeDrawerContent)
            }
        }

        subtitleDrawerContent = findViewById(R.id.subtitleDrawer)
        subtitleDrawerClose = findViewById(R.id.subtitleDrawerClose)
        subtitleLangButton = findViewById(R.id.subtitleLangButton)
        subtitleDrawerList = findViewById(R.id.subtitleDrawerList)
        subtitleRailController = SubtitleRailController(
            this,
            model,
            binding.root,
            subtitleDrawerContent,
            subtitleLangButton,
            subtitleDrawerClose,
            subtitleDrawerList,
        )

        binding.root.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: View) {
                if (drawerView !== subtitleDrawerContent) return
                playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                playerView.isFocusable = false
                subtitleRailController?.focusFirst()
            }
            override fun onDrawerClosed(drawerView: View) {
                if (drawerView !== subtitleDrawerContent) return
                playerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                playerView.isFocusable = true
                exoSubtitle.requestFocus()
            }
            override fun onDrawerStateChanged(newState: Int) {}
        })

        tracksDrawerContent = findViewById(R.id.tracksDrawer)
        tracksDrawerClose = findViewById(R.id.tracksDrawerClose)
        tracksDrawerList = findViewById(R.id.tracksDrawerList)
        trackRailController = TrackRailController(
            this,
            tracksDrawerContent,
            tracksDrawerClose,
            tracksDrawerList,
        )
        playerTracksBtn = playerView.findViewById(R.id.exo_tracks)

        trackRailController?.let { rail ->
            playerTracksBtn?.setOnClickListener {
                Logger.log("Player: TRACKS pressed")
                rail.open()
                playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                playerView.isFocusable = false
            }
            tracksDrawerClose.setOnClickListener {
                closeTracksRail()
            }
        }

        playerView.controllerShowTimeoutMs = PrefManager.getVal<Int>(PrefName.AutoHideTimeout) * 1000

        val audioManager = applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager

        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus({ focus ->
            when (focus) {
                AUDIOFOCUS_LOSS_TRANSIENT, AUDIOFOCUS_LOSS -> if (isInitialized) exoPlayer.pause()
                AUDIOFOCUS_GAIN -> if (isInitialized && !userPaused) exoPlayer.play()
            }
        }, AUDIO_CONTENT_TYPE_MOVIE, AUDIOFOCUS_GAIN)

        if (savedInstanceState != null) {
            currentWindow = savedInstanceState.getInt(resumeWindow)
            playbackPosition = savedInstanceState.getLong(resumePosition)
            isFullscreen = savedInstanceState.getInt(playerFullscreen)
            isPlayerPlaying = savedInstanceState.getBoolean(playerOnPlay)
        }

        val exoBack = playerView.findViewById<ImageButton>(R.id.exo_back)
        exoBack.setOnClickListener {
            Logger.log("Player: BACK pressed — exiting player (ep=${media.anime?.selectedEpisode})")
            finishAndRemoveTask()
        }
        onBackPressedDispatcher.addCallback(this) {
            if (!handleBackPress()) {
                finishAndRemoveTask()
            }
        }

        model.timeStamps.observe(this) { it ->

            isTimeStampsLoaded = !it.isNullOrEmpty()
            Logger.log(
                "Player: timeStamps observer fired for ep '${media.anime?.selectedEpisode}' " +
                    "value=${if (it == null) "null" else "list(${it.size})"} " +
                    "isTimeStampsLoaded=$isTimeStampsLoaded"
            )
            exoSkipOpEd.visibility =
                if (it.isNullOrEmpty()) {
                    playerView.setExtraAdGroupMarkers(longArrayOf(), booleanArrayOf())
                    View.GONE
                } else {
                    val adGroups =
                        it
                            .flatMap {
                                listOf(
                                    (it.interval.startTime * 1000).toLong(),
                                    (it.interval.endTime * 1000).toLong(),
                                )
                            }.toLongArray()
                    val playedAdGroups =
                        it
                            .flatMap {
                                listOf(false, false)
                            }.toBooleanArray()
                    playerView.setExtraAdGroupMarkers(adGroups, playedAdGroups)
                    View.VISIBLE
                }
            (exoSkipOpEd.parent as? ViewGroup)?.requestLayout()
        }

        exoSkipOpEd.alpha = if (PrefManager.getVal(PrefName.AutoSkipOPED)) 1f else 0.3f
        exoSkipOpEd.setOnClickListener {
            val prev: Boolean = PrefManager.getVal(PrefName.AutoSkipOPED)
            Logger.log("Player: SKIP_OP_ED pressed — toggling AutoSkip from $prev")
            if (PrefManager.getVal(PrefName.AutoSkipOPED)) {
                snackString(getString(R.string.disabled_auto_skip))
                PrefManager.setVal(PrefName.AutoSkipOPED, false)
            } else {
                snackString(getString(R.string.auto_skip))
                PrefManager.setVal(PrefName.AutoSkipOPED, true)
            }
            exoSkipOpEd.alpha = if (PrefManager.getVal(PrefName.AutoSkipOPED)) 1f else 0.3f
        }

        exoPlay.setOnClickListener {
            if (isInitialized) {
                val wasPlaying = exoPlayer.playWhenReady
                Logger.log("Player: PLAY/PAUSE pressed — wasPlaying=$wasPlaying (ep=${media.anime?.selectedEpisode})")
                if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.AnimatedVectorDrawables)) (exoPlay.drawable as Animatable?)?.start()
                if (wasPlaying) {
                    Glide.with(this).load(R.drawable.anim_play_to_pause).into(exoPlay)
                    exoPlayer.pause()
                    userPaused = true
                } else {
                    Glide.with(this).load(R.drawable.anim_pause_to_play).into(exoPlay)
                    exoPlayer.play()
                    userPaused = false
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pipEnabled =
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                        PrefManager.getVal(PrefName.Pip)
            if (pipEnabled) {
                exoPip.setOnClickListener {
                    enterPipMode()
                }
            }
        }

        var skipTime = PrefManager.getVal<Int>(PrefName.SkipTime)
        if (skipTime > 0) {
            exoSkip.findViewById<TextView>(R.id.exo_skip_time).text = skipTime.toString()
            exoSkip.setOnClickListener {
                if (isInitialized) {
                    val pos = exoPlayer.currentPosition
                    Logger.log("Player: SKIP pressed — seek +${skipTime}s from ${pos}ms")
                    exoPlayer.seekTo(pos + skipTime * 1000)
                }
            }
            exoSkip.setOnLongClickListener {
                val dialog = Dialog(this, R.style.MyPopup)
                dialog.setContentView(R.layout.item_seekbar_dialog)
                dialog.setCancelable(true)
                dialog.setCanceledOnTouchOutside(true)
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                if (skipTime <= 120) {
                    dialog.findViewById<Slider>(R.id.seekbar).value = skipTime.toFloat()
                } else {
                    dialog.findViewById<Slider>(R.id.seekbar).value = 120f
                }
                dialog.findViewById<Slider>(R.id.seekbar).addOnChangeListener { _, value, _ ->
                    skipTime = value.toInt()

                    PrefManager.setVal(PrefName.SkipTime, skipTime)
                    playerView.findViewById<TextView>(R.id.exo_skip_time).text =
                        skipTime.toString()
                    dialog.findViewById<TextView>(R.id.seekbar_value).text =
                        skipTime.toString()
                }
                dialog
                    .findViewById<Slider>(R.id.seekbar)
                    .addOnSliderTouchListener(
                        object : Slider.OnSliderTouchListener {
                            override fun onStartTrackingTouch(slider: Slider) {}

                            override fun onStopTrackingTouch(slider: Slider) {
                                dialog.dismiss()
                            }
                        },
                    )
                dialog.findViewById<TextView>(R.id.seekbar_title).text =
                    getString(R.string.skip_time)
                dialog.findViewById<TextView>(R.id.seekbar_value).text =
                    skipTime.toString()
                @Suppress("DEPRECATION")
                dialog.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                dialog.show()
                true
            }
        } else {
            exoSkip.visibility = View.GONE
        }

        val gestureSpeed = if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.PlayerGestureAnimations)) (300 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong() else 0L

        val brightnessRunnable =
            Runnable {
                if (exoBrightnessCont.alpha == 1f) {
                    lifecycleScope.launch {
                        ObjectAnimator
                            .ofFloat(exoBrightnessCont, "alpha", 1f, 0f)
                            .setDuration(gestureSpeed)
                            .start()
                        delay(gestureSpeed)
                        exoBrightnessCont.visibility = View.GONE
                        checkNotch()
                    }
                }
            }
        val volumeRunnable =
            Runnable {
                if (exoVolumeCont.alpha == 1f) {
                    lifecycleScope.launch {
                        ObjectAnimator
                            .ofFloat(exoVolumeCont, "alpha", 1f, 0f)
                            .setDuration(gestureSpeed)
                            .start()
                        delay(gestureSpeed)
                        exoVolumeCont.visibility = View.GONE
                        checkNotch()
                    }
                }
            }
        listOf(
            androidx.media3.ui.R.id.exo_play, R.id.exo_source, R.id.exo_settings, R.id.exo_sub,
            R.id.exo_audio, R.id.exo_screen, R.id.exo_rotate,
            R.id.exo_skip_op_ed, R.id.exo_back, R.id.exo_skip, R.id.exo_next_ep,
            R.id.exo_prev_ep,
            androidx.media3.ui.R.id.exo_playback_speed,
            R.id.exo_fast_forward_button, R.id.exo_fast_rewind_button,
            R.id.exo_fast_forward_button_cont, R.id.exo_fast_rewind_button_cont,
            R.id.exo_skip_timestamp,
            R.id.exo_ep_sel_btn, R.id.exo_tracks, R.id.exo_autoplay,
        ).forEach { id ->
            playerView.findViewById<View>(id)?.apply {
                isFocusable = true
                isFocusableInTouchMode = false
            }
        }
        animeTitle.isFocusable = false
        listOf(
            androidx.media3.ui.R.id.exo_play, R.id.exo_source, R.id.exo_settings, R.id.exo_sub,
            R.id.exo_audio, R.id.exo_screen, R.id.exo_rotate,
            R.id.exo_skip_op_ed, R.id.exo_back, R.id.exo_skip, R.id.exo_next_ep,
            R.id.exo_prev_ep,
            androidx.media3.ui.R.id.exo_playback_speed,
            R.id.exo_fast_forward_button, R.id.exo_fast_rewind_button,
            R.id.exo_fast_forward_button_cont, R.id.exo_fast_rewind_button_cont,
            R.id.exo_skip_timestamp,
            R.id.exo_ep_sel_btn, R.id.exo_tracks,
        ).forEach { id ->
            playerView.findViewById<View>(id)?.let {
                FocusEffectUtil.applyFocusListener(it, it, isCircular = it is ImageButton)
            }
        }

        playerView.findViewById<View>(R.id.exo_autoplay)?.let {
            FocusEffectUtil.applyFocusListener(it, cornerRadiusPx = 12f * resources.displayMetrics.density)
        }
        playerView.post { exoPlay.requestFocus() }

        val tracksButton = playerView.findViewById<View>(R.id.exo_tracks)
        val autoplayButton = playerView.findViewById<View>(R.id.exo_autoplay)
        autoplayButton.nextFocusLeftId = R.id.exo_back
        autoplayButton.nextFocusRightId = R.id.exo_skip_op_ed
        exoSpeed.nextFocusLeftId = R.id.exo_skip_op_ed
        exoSpeed.nextFocusRightId = R.id.exo_settings
        exoSettings.nextFocusLeftId = androidx.media3.ui.R.id.exo_playback_speed
        exoSettings.nextFocusRightId = R.id.exo_tracks
        tracksButton.nextFocusLeftId = R.id.exo_settings
        tracksButton.nextFocusRightId = R.id.exo_back
        exoBack.nextFocusRightId = R.id.exo_ep_sel_text
        exoBack.nextFocusLeftId = R.id.exo_autoplay
        playerView.findViewById<View>(R.id.exo_prev_ep).nextFocusLeftId = R.id.exo_back
        playerView.findViewById<View>(R.id.exo_prev_ep).nextFocusRightId = androidx.media3.ui.R.id.exo_play
        exoPlay.nextFocusLeftId = R.id.exo_prev_ep
        exoPlay.nextFocusRightId = R.id.exo_next_ep
        playerView.findViewById<View>(R.id.exo_next_ep).nextFocusLeftId = androidx.media3.ui.R.id.exo_play
        exoPlay.nextFocusUpId = R.id.exo_autoplay
        exoSpeed.nextFocusDownId = androidx.media3.ui.R.id.exo_play
        autoplayButton.nextFocusDownId = androidx.media3.ui.R.id.exo_play

        val progressBar = playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
        progressBar.isFocusable = true
        progressBar.nextFocusUpId = R.id.exo_next_ep
        
        
        progressBar.nextFocusDownId = if (exoSubtitle.isVisible) {
            R.id.exo_sub
        } else {
            R.id.exo_source
        }
        exoPlay.nextFocusDownId = androidx.media3.ui.R.id.exo_progress
        playerView.findViewById<View>(R.id.exo_prev_ep).nextFocusDownId = androidx.media3.ui.R.id.exo_progress
        playerView.findViewById<View>(R.id.exo_next_ep).nextFocusDownId = androidx.media3.ui.R.id.exo_progress
        val bottomIds = listOf(R.id.exo_source, R.id.exo_sub)
        for (id in bottomIds) {
            playerView.findViewById<View>(id)?.nextFocusUpId = androidx.media3.ui.R.id.exo_progress
        }
        exoRotate.nextFocusUpId = R.id.exo_skip
        exoScreen.nextFocusUpId = R.id.exo_skip
        val skipView = playerView.findViewById<View>(R.id.exo_skip)
        skipView.nextFocusDownId = R.id.exo_rotate
        skipView.nextFocusRightId = R.id.exo_rotate
        skipView.nextFocusLeftId = R.id.exo_skip_op_ed
        skipView.nextFocusUpId = R.id.exo_settings
        skipTimeButton.nextFocusDownId = R.id.exo_rotate
        skipTimeButton.nextFocusRightId = R.id.exo_rotate
        skipTimeButton.nextFocusLeftId = R.id.exo_skip_op_ed
        skipTimeButton.nextFocusUpId = R.id.exo_settings
        playerView.findViewById<View>(R.id.exo_ep_sel_btn).nextFocusLeftId = R.id.exo_screen
        playerView.findViewById<View>(R.id.exo_ep_sel_btn).nextFocusUpId = R.id.exo_skip
        progressBar.setOnFocusChangeListener { _, hasFocus ->
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.SeekBarAnimations)) {
                progressBar.animate().scaleY(if (hasFocus) 2.5f else 1f).setDuration(150).start()
            } else {
                progressBar.scaleY = if (hasFocus) 2.5f else 1f
            }
        }

        pauseOverlay = playerView.findViewById(R.id.exo_pause_overlay)
        pauseOverlay.setOnClickListener {
            pauseOverlay.visibility = View.GONE
            if (!playerView.isControllerFullyVisible) playerView.showController()
            playerView.controllerShowTimeoutMs =
                PrefManager.getVal<Int>(PrefName.AutoHideTimeout) * 1000
            exoPlay.requestFocus()
        }
        pauseTitle = playerView.findViewById(R.id.exo_pause_title)
        pauseSynopsis = playerView.findViewById(R.id.exo_pause_synopsis)
        pauseGenres = playerView.findViewById(R.id.exo_pause_genres)
        pauseRating = playerView.findViewById(R.id.exo_pause_rating)
        pauseLogo = playerView.findViewById(R.id.exo_pause_logo)
        pauseSynopsis.post {
            pauseSynopsis.maxWidth = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 560f,
                resources.displayMetrics
            ).toInt()
        }

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    if (pauseOverlay.visibility == View.VISIBLE) {
                        pauseOverlay.visibility = View.GONE
                    }
                    val controllerButtonIds = setOf(
                        androidx.media3.ui.R.id.exo_play, R.id.exo_source, R.id.exo_settings, R.id.exo_sub,
                        R.id.exo_audio, R.id.exo_screen, R.id.exo_rotate, R.id.exo_skip_op_ed,
                        R.id.exo_back, R.id.exo_skip, R.id.exo_next_ep, R.id.exo_prev_ep,
                        R.id.exo_fast_forward_button, R.id.exo_fast_rewind_button,
                        R.id.exo_skip_timestamp, R.id.exo_ep_sel_btn, R.id.exo_tracks, R.id.exo_autoplay
                    )
                    if (currentFocus?.id !in controllerButtonIds) {
                        exoPlay.requestFocus()
                    }
                }
                if (visibility == View.GONE) {
                    playerView.findViewById<View>(R.id.exo_controller).clearFocus()

                    playerView.post {

                        if (!playerView.isControllerFullyVisible) {
                            playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            playerView.isFocusable = true
                            playerView.requestFocus()
                            playerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        }
                    }
                    hideSystemBars()
                    brightnessRunnable.run()
                    volumeRunnable.run()
                }
            },
        )
        val overshoot = AnimationUtils.loadInterpolator(this, R.anim.over_shoot)
        val controllerDuration = if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.PlayerControllerAnimations)) (300 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong() else 0L

        fun handleController() {
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) !isInPictureInPictureMode else true) {
                if (playerView.isControllerFullyVisible) {
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_controller),
                            "alpha",
                            1f,
                            0f,
                        ).setDuration(controllerDuration)
                        .start()
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_bottom_cont),
                            "translationY",
                            0f,
                            128f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_timeline_cont),
                            "translationY",
                            0f,
                            128f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_top_cont),
                            "translationY",
                            0f,
                            -128f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    playerView.postDelayed({ playerView.hideController() }, controllerDuration)
                } else {
                    checkNotch()
                    playerView.showController()
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_controller),
                            "alpha",
                            0f,
                            1f,
                        ).setDuration(controllerDuration)
                        .start()
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_bottom_cont),
                            "translationY",
                            128f,
                            0f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_timeline_cont),
                            "translationY",
                            128f,
                            0f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_top_cont),
                            "translationY",
                            -128f,
                            0f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                }
            }
        }

        playerView.findViewById<View>(R.id.exo_full_area).setOnClickListener {
            handleController()
        }

        val rewindText = playerView.findViewById<TextView>(R.id.exo_fast_rewind_anim)
        val forwardText = playerView.findViewById<TextView>(R.id.exo_fast_forward_anim)
        val fastForwardCard = playerView.findViewById<View>(R.id.exo_fast_forward)
        val fastRewindCard = playerView.findViewById<View>(R.id.exo_fast_rewind)

        val seekTimerF = ResettableTimer()
        val seekTimerR = ResettableTimer()
        var seekTimesF = 0
        var seekTimesR = 0

        fun seek(
            forward: Boolean,
            event: MotionEvent? = null,
        ) {
            val seekTime = PrefManager.getVal<Int>(PrefName.SeekTime)
            val (card, text) =
                if (forward) {
                    val text = "+${seekTime * ++seekTimesF}"
                    forwardText.text = text
                    handler.post { exoPlayer.seekTo(exoPlayer.currentPosition + seekTime * 1000) }
                    fastForwardCard to forwardText
                } else {
                    val text = "-${seekTime * ++seekTimesR}"
                    rewindText.text = text
                    handler.post { exoPlayer.seekTo(exoPlayer.currentPosition - seekTime * 1000) }
                    fastRewindCard to rewindText
                }

            val showCardAnim = ObjectAnimator.ofFloat(card, "alpha", 0f, 1f).setDuration(300)
            val showTextAnim = ObjectAnimator.ofFloat(text, "alpha", 0f, 1f).setDuration(150)

            fun startAnim() {
                if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.DoubleTapAnimations)) {
                    showTextAnim.start()

                    (text.compoundDrawables[1] as? Animatable)?.apply {
                        if (!isRunning) start()
                    }

                    if (!isSeeking && event != null) {
                        playerView.hideController()
                        card.circularReveal(event.x.toInt(), event.y.toInt(), !forward, 800)
                        showCardAnim.start()
                    }
                } else {
                    card.alpha = 1f
                    text.alpha = 1f
                }
            }

            fun stopAnim() {
                handler.post {
                    showCardAnim.cancel()
                    showTextAnim.cancel()
                    if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.DoubleTapAnimations)) {
                        ObjectAnimator.ofFloat(card, "alpha", card.alpha, 0f).setDuration(150).start()
                        ObjectAnimator.ofFloat(text, "alpha", 1f, 0f).setDuration(150).start()
                    } else {
                        card.alpha = 0f
                        text.alpha = 0f
                    }
                }
            }

            startAnim()

            isSeeking = true

            if (forward) {
                seekTimerR.reset(
                    object : TimerTask() {
                        override fun run() {
                            isSeeking = false
                            stopAnim()
                            seekTimesF = 0
                        }
                    },
                    850,
                )
            } else {
                seekTimerF.reset(
                    object : TimerTask() {
                        override fun run() {
                            isSeeking = false
                            stopAnim()
                            seekTimesR = 0
                        }
                    },
                    850,
                )
            }
        }

        if (!PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
            playerView.findViewById<View>(R.id.exo_fast_forward_button_cont).visibility =
                View.VISIBLE
            playerView.findViewById<View>(R.id.exo_fast_rewind_button_cont).visibility =
                View.VISIBLE
            val ffButton = playerView.findViewById<ImageButton>(R.id.exo_fast_forward_button)
            ffButton.setOnClickListener {
                if (isInitialized) {
                    seek(true)
                }
            }
            ffButton.setOnLongClickListener {
                if (isInitialized) startSeekRepeat(true)
                true
            }
            ffButton.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    stopSeekRepeat()
                }
                false
            }
            val rewButton = playerView.findViewById<ImageButton>(R.id.exo_fast_rewind_button)
            rewButton.setOnClickListener {
                if (isInitialized) {
                    seek(false)
                }
            }
            rewButton.setOnLongClickListener {
                if (isInitialized) startSeekRepeat(false)
                true
            }
            rewButton.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    stopSeekRepeat()
                }
                false
            }
        }

        if (PrefManager.getVal<Boolean>(PrefName.Gestures) || PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
            fun doubleTap(
                forward: Boolean,
                event: MotionEvent,
            ) {
                if (isInitialized && PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
                    seek(forward, event)
                }
            }

            var brightnessTimer = Timer()
            exoBrightnessCont.visibility = View.GONE

            fun brightnessHide() {
                brightnessTimer.cancel()
                brightnessTimer.purge()
                val timerTask: TimerTask =
                    object : TimerTask() {
                        override fun run() {
                            handler.post(brightnessRunnable)
                        }
                    }
                brightnessTimer = Timer()
                brightnessTimer.schedule(timerTask, 3000)
            }
            exoBrightness.value = (getCurrentBrightnessValue(this) * 10f)

            exoBrightness.addOnChangeListener { _, value, _ ->
                val lp = window.attributes
                lp.screenBrightness =
                    brightnessConverter((value.takeIf { !it.isNaN() } ?: 0f) / 10, false)
                window.attributes = lp
                brightnessHide()
            }

            var volumeTimer = Timer()
            exoVolumeCont.visibility = View.GONE

            val volumeMax = audioManager.getStreamMaxVolume(STREAM_MUSIC)
            exoVolume.value = audioManager.getStreamVolume(STREAM_MUSIC).toFloat() / volumeMax * 10

            fun volumeHide() {
                volumeTimer.cancel()
                volumeTimer.purge()
                val timerTask: TimerTask =
                    object : TimerTask() {
                        override fun run() {
                            handler.post(volumeRunnable)
                        }
                    }
                volumeTimer = Timer()
                volumeTimer.schedule(timerTask, 3000)
            }
            exoVolume.addOnChangeListener { _, value, _ ->
                val volume = ((value.takeIf { !it.isNaN() } ?: 0f) / 10 * volumeMax).roundToInt()
                audioManager.setStreamVolume(STREAM_MUSIC, volume, 0)
                volumeHide()
            }
            val fastForward = playerView.findViewById<TextView>(R.id.exo_fast_forward_text)
            val minLongPressSpeed = 0.25f
            val maxLongPressSpeed = 4f
            val dragSpeedSensitivity = 4f
            val minSpeedUpdateDelta = 0.01f
            val horizontalDeadZoneRatio = 0.03f
            var fastForwardStartX = 0f
            var fastForwardInitialSpeed = 1f
            var fastForwardOriginalSpeed = 1f
            var lastFastForwardSpeed = 1f

            fun currentPlaybackSpeed(): Float {
                return exoPlayer.playbackParameters.speed
            }

            fun updateFastForwardText(speed: Float) {
                fastForward.text = String.format(Locale.US, "%.2fx", speed)
            }

            fun fastForward(event: MotionEvent) {
                isFastForwarding = true
                fastForwardStartX = event.rawX
                fastForwardOriginalSpeed = currentPlaybackSpeed()
                fastForwardInitialSpeed = clamp(fastForwardOriginalSpeed * 2f, minLongPressSpeed, maxLongPressSpeed)
                exoPlayer.setPlaybackSpeed(fastForwardInitialSpeed)
                lastFastForwardSpeed = fastForwardInitialSpeed
                fastForward.visibility = View.VISIBLE
                updateFastForwardText(fastForwardInitialSpeed)
            }

            fun updateFastForwardSpeed(event: MotionEvent) {
                if (!isFastForwarding) return
                val width = playerView.width.toFloat().takeIf { it > 0f } ?: return
                val deltaX = event.rawX - fastForwardStartX
                if (abs(deltaX) < width * horizontalDeadZoneRatio) return
                val deltaRatio = deltaX / width
                val targetSpeed =
                    clamp(
                        fastForwardInitialSpeed + (deltaRatio * dragSpeedSensitivity),
                        minLongPressSpeed,
                        maxLongPressSpeed,
                    )
                if (abs(targetSpeed - lastFastForwardSpeed) < minSpeedUpdateDelta) return
                exoPlayer.setPlaybackSpeed(targetSpeed)
                lastFastForwardSpeed = targetSpeed
                updateFastForwardText(targetSpeed)
            }

            fun stopFastForward() {
                if (isFastForwarding) {
                    isFastForwarding = false
                    exoPlayer.setPlaybackSpeed(fastForwardOriginalSpeed)
                    fastForward.visibility = View.GONE
                }
            }

            val fastRewindDetector =
                GestureDetector(
                    this,
                    object : GesturesListener() {
                        override fun onLongClick(event: MotionEvent) {
                            if (PrefManager.getVal(PrefName.FastForward)) fastForward(event)
                        }

                        override fun onDoubleClick(event: MotionEvent) {
                            doubleTap(false, event)
                        }

                        override fun onScrollYClick(y: Float) {
                            if (PrefManager.getVal(PrefName.Gestures)) {
                                exoBrightness.value = clamp(exoBrightness.value + y / 100, 0f, 10f)
                                if (PrefManager.getVal(PrefName.GestureSliders)) {
                                    if (exoBrightnessCont.visibility != View.VISIBLE) {
                                        exoBrightnessCont.visibility = View.VISIBLE
                                    }
                                    exoBrightnessCont.alpha = 1f
                                }
                            }
                        }

                        override fun onSingleClick(event: MotionEvent) =
                            if (isSeeking) doubleTap(false, event) else handleController()
                    },
                )
            val rewindArea = playerView.findViewById<View>(R.id.exo_rewind_area)
            rewindArea.isClickable = true
            rewindArea.setOnTouchListener { v, event ->
                fastRewindDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> updateFastForwardSpeed(event)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopFastForward()
                }
                v.performClick()
                true
            }

            val fastForwardDetector =
                GestureDetector(
                    this,
                    object : GesturesListener() {
                        override fun onLongClick(event: MotionEvent) {
                            if (PrefManager.getVal(PrefName.FastForward)) fastForward(event)
                        }

                        override fun onDoubleClick(event: MotionEvent) {
                            doubleTap(true, event)
                        }

                        override fun onScrollYClick(y: Float) {
                            if (PrefManager.getVal(PrefName.Gestures)) {
                                exoVolume.value = clamp(exoVolume.value + y / 100, 0f, 10f)
                                if (PrefManager.getVal(PrefName.GestureSliders)) {
                                    if (exoVolumeCont.visibility != View.VISIBLE) {
                                        exoVolumeCont.visibility = View.VISIBLE
                                    }
                                    exoVolumeCont.alpha = 1f
                                }
                            }
                        }

                        override fun onSingleClick(event: MotionEvent) =
                            if (isSeeking) doubleTap(true, event) else handleController()
                    },
                )
            val forwardArea = playerView.findViewById<View>(R.id.exo_forward_area)
            forwardArea.isClickable = true
            forwardArea.setOnTouchListener { v, event ->
                fastForwardDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> updateFastForwardSpeed(event)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopFastForward()
                }
                v.performClick()
                true
            }
        }

        if (!initialized) return startMainActivity(this)
        Logger.log(
            "Player: ExoplayerView opened media='${media.userPreferredName}' id=${media.id} " +
                "selectedEp=${media.anime?.selectedEpisode} eps=${media.anime?.episodes?.size}"
        )
        model.setMedia(media)
        title = media.userPreferredName
        episodes = media.anime?.episodes ?: return startMainActivity(this)

        videoInfo = playerView.findViewById(R.id.exo_video_info)
        audioLangInfo = playerView.findViewById(R.id.exo_audio_lang)

        model.watchSources = if (media.isAdult) HAnimeSources else AnimeSources

        model.epChanged.observe(this) {
            epChanging = !it
        }

        animeTitle.text = media.userPreferredName

        pauseRating.text = media.meanScore?.let { "$it% ★" } ?: ""
        pauseSynopsis.text = media.description?.let {
            if (it.isBlank()) null else android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        } ?: ""
        pauseGenres.removeAllViews()
        media.genres?.filter { it.isNotBlank() }?.forEach { genre ->
            val chip = Chip(this).apply {
                text = genre
                isClickable = false
                isFocusable = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                chipStrokeColor = android.content.res.ColorStateList.valueOf(TypedValue().also {
                    theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, it, true)
                }.data)
                chipStrokeWidth = resources.displayMetrics.density
                setTextColor(TypedValue().also {
                    theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, it, true)
                }.data)
                textSize = 12f
            }
            pauseGenres.addView(chip)
        }
        lifecycleScope.launch(Dispatchers.Main) {
            val logoUrl = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) media.logoUrl ?: LogoApi.getLogoUrl(media.id) else null
            if (!logoUrl.isNullOrBlank()) {
                pauseLogo.visibility = View.VISIBLE
                pauseTitle.visibility = View.GONE
                Glide.with(this@ExoplayerView).load(logoUrl).into(pauseLogo)
            } else {
                pauseLogo.visibility = View.GONE
                pauseTitle.visibility = View.VISIBLE
                pauseTitle.text = media.userPreferredName
            }
        }

        episodeArr = episodes.keys.toList()
        currentEpisodeIndex = episodeArr.indexOf(episodes.getEpisodeKey(media.anime?.selectedEpisode)).coerceAtLeast(0)

        episodeTitleArr = arrayListOf()
        episodes.forEach {
            val episode = it.value
            val cleanedTitle = MediaNameAdapter.removeEpisodeNumberCompletely(episode.title ?: "")
            val season = episode.extra?.get("season")
            val epNumber = episode.extra?.get("episode")
            val label = if (!season.isNullOrBlank() && !epNumber.isNullOrBlank()) {
                "Ssn$season Episode $epNumber"
            } else {
                "Episode ${episode.number}"
            }
            episodeTitleArr.add(
                "$label${if (episode.filler) " [Filler]" else ""}${if (cleanedTitle.isNotBlank() && cleanedTitle != "null") ": $cleanedTitle" else ""}",
            )
        }

        seasonArr = episodes.values.mapNotNull { it.extra?.get("season") }.distinct()
            .sortedWith(compareBy { it.toIntOrNull() ?: 0 })
        currentRailSeason = episodes.getEpisode(media.anime?.selectedEpisode)?.extra?.get("season")
            ?: seasonArr.firstOrNull()
        buildRailSeasonChips()

        fun change(index: Int) {
            if (isInitialized) {
                changingServer = false
                PrefManager.setCustomVal(
                    "${media.id}_${episodeArr[currentEpisodeIndex]}",
                    exoPlayer.currentPosition,
                )
                val prev = episodeArr[currentEpisodeIndex]

                clearAllTransientSubtitleCache()
                isTimeStampsLoaded = false
                timeStampsLoading = false
                lastTimeStampAttempt = 0L
                lastLoggedStampId = null
                Logger.log("Player: episode change -> reset timestamps state for ep '${episodeArr[index]}'")
                episodeLength = 0f
                simklAddedToWatchlist = false
                media.anime!!.selectedEpisode = episodeArr[index]
                model.setMedia(media)
                model.epChanged.postValue(false)
                model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "change")
                model.onEpisodeClick(
                    media,
                    media.anime!!.selectedEpisode!!,
                    this.supportFragmentManager,
                    false,
                    prev,
                )
            }
        }

        episodeTitle.adapter = NoPaddingArrayAdapter(this, R.layout.item_dropdown, episodeTitleArr)
        episodeTitle.setSelection(currentEpisodeIndex)
        episodeTitleText.text = episodeTitleArr.getOrElse(currentEpisodeIndex) { "" }
        episodeTitle.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    position: Int,
                    p3: Long,
                ) {
                    episodeTitleText.text = episodeTitleArr.getOrElse(position) { "" }
                    if (position != currentEpisodeIndex) {
                        disappeared = false
                        functionstarted = false
                        change(position)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }

        episodeDrawerList.layoutManager = LinearLayoutManager(this)
        episodeDrawerAdapter = EpisodeRailAdapter(
            episodes = railEpisodes(),
            onEpisodeClick = { epKey ->
                val idx = episodeArr.indexOf(epKey)
                if (idx >= 0 && idx != currentEpisodeIndex) {
                    episodeDrawer.closeDrawer(episodeDrawerContent)
                    disappeared = false
                    functionstarted = false
                    currentEpisodeIndex = idx
                    change(idx)
                }
            },
            onCommentClick = { epKey ->
                openEpisodeComments(epKey)
            },
        )
        episodeDrawerList.adapter = episodeDrawerAdapter

        exoNext = playerView.findViewById(R.id.exo_next_ep)
        exoNext.setOnClickListener {
            if (isInitialized) {
                Logger.log("Player: NEXT EPISODE pressed (idx=$currentEpisodeIndex of ${episodeArr.size})")
                nextEpisode { i ->
                    updateAniProgress()
                    disappeared = false
                    functionstarted = false
                    change(currentEpisodeIndex + i)
                }
            }
        }

        exoPrev = playerView.findViewById(R.id.exo_prev_ep)
        exoPrev.setOnClickListener {
            if (currentEpisodeIndex > 0) {
                Logger.log("Player: PREV EPISODE pressed (idx=$currentEpisodeIndex)")
                disappeared = false
                change(currentEpisodeIndex - 1)
            } else {
                snackString(getString(R.string.first_episode))
            }
        }

        model.getEpisode().observe(this) { ep ->
            if (ep != null && !epChanging) onEpisodeReady(ep)
        }

        if (offlinePlayback) {
            offlinePlayback = false
            window.decorView.post {
                if (!isFinishing && !isDestroyed) {
                    media.anime?.episodes?.getEpisode(media.anime?.selectedEpisode)
                        ?.let { onEpisodeReady(it) }
                }
            }
        }

        isFullscreen = PrefManager.getCustomVal("${media.id}_fullscreenInt", isFullscreen)
        playerView.resizeMode =
            when (isFullscreen) {
                0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

        exoScreen.setOnClickListener {
            if (isFullscreen < 2) isFullscreen += 1 else isFullscreen = 0
            Logger.log("Player: SCREEN MODE pressed — now $isFullscreen (0=fit,1=zoom,2=stretch)")
            playerView.resizeMode =
                when (isFullscreen) {
                    0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            snackString(
                when (isFullscreen) {
                    0 -> "Original"
                    1 -> "Zoom"
                    2 -> "Stretch"
                    else -> "Original"
                },
            )
            PrefManager.setCustomVal("${media.id}_fullscreenInt", isFullscreen)
        }

        exoRotate.setOnClickListener {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            Logger.log("Player: ROTATE pressed — switching from ${if (isLandscape) "landscape" else "portrait"}")
            requestedOrientation =
                if (isLandscape) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
        }

        exoSettings.setOnClickListener {
            Logger.log("Player: SETTINGS pressed (ep=${media.anime!!.selectedEpisode})")
            PrefManager.setCustomVal(
                "${media.id}_${media.anime!!.selectedEpisode}",
                exoPlayer.currentPosition,
            )
            val intent =
                Intent(this, PlayerSettingsActivity::class.java).apply {
                    putExtra("subtitle", subtitle)
                }
            exoPlayer.pause()
            onChangeSettings.launch(intent)
        }

        val speeds =
            if (PrefManager.getVal(PrefName.CursedSpeeds)) {
                arrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
            } else {
                arrayOf(
                    0.25f,
                    0.33f,
                    0.5f,
                    0.66f,
                    0.75f,
                    1f,
                    1.15f,
                    1.25f,
                    1.33f,
                    1.5f,
                    1.66f,
                    1.75f,
                    2f,
                )
            }

        val speedsName = speeds.map { "${it}x" }.toTypedArray()

        val speedsLength = speeds.size
        val savedIndex = PrefManager.getCustomVal(
            "${media.id}_speed",
            PrefManager.getVal<Int>(PrefName.DefaultSpeed),
        )
        var curSpeed = savedIndex.coerceIn(0, speedsLength - 1)

        playbackParameters = PlaybackParameters(speeds[curSpeed])
        var speed: Float
        exoSpeed.setOnClickListener {
            customAlertDialog().apply {
                setTitle(R.string.speed)
                singleChoiceItems(speedsName, curSpeed) { i ->
                    Logger.log("Player: SPEED selected — ${speeds.getOrNull(i) ?: 1f}x")
                    PrefManager.setCustomVal("${media.id}_speed", i)
                    speed = speeds.getOrNull(i) ?: 1f
                    curSpeed = i
                    playbackParameters = PlaybackParameters(speed)
                    exoPlayer.playbackParameters = playbackParameters
                    hideSystemBars()
                }
                setOnCancelListener { hideSystemBars() }
                show()
            }
        }

        if (PrefManager.getVal(PrefName.AutoPlay)) {
            playerView.findViewById<View>(R.id.exo_touch_view).setOnTouchListener { _, _ ->
                markInteracted()
                false
            }
            playerView.setOnClickListener { markInteracted() }
            playerView.isFocusable = true
            playerView.setOnKeyListener { _, _, _ ->
                markInteracted()
                false
            }
        }

        isFullscreen = PrefManager.getVal(PrefName.Resize)
        playerView.resizeMode =
            when (isFullscreen) {
                0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

        preloading = false
        val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
        val showProgressDialog =
            if (PrefManager.getVal<Boolean>(PrefName.AutoTrackNewShows)) {
                false
            } else if (PrefManager.getVal(PrefName.AskIndividualPlayer)) {
                PrefManager.getCustomVal(
                    "${media.id}_progressDialog",
                    true,
                )
            } else {
                false
            }

        val isTmdbContent = media.id < 0
        val shouldShowDialog = if (isLiveStream()) {
            false
        } else if (isTmdbContent) {
            !incognito && showProgressDialog && Simkl.token != null
        } else {

            val trackerLoggedIn =
                if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) MAL.token != null
                else Anilist.userid != null
            !incognito && showProgressDialog && trackerLoggedIn &&
                if (media.isAdult) PrefManager.getVal(PrefName.UpdateForHPlayer) else true
        }
        if (shouldShowDialog) {
            customAlertDialog().apply {
                setTitle(getString(R.string.auto_update, media.userPreferredName))
                setCancelable(false)
                setPosButton(R.string.yes) {
                    PrefManager.setCustomVal(
                        "${media.id}_progressDialog",
                        false,
                    )
                    PrefManager.setCustomVal(
                        "${media.id}_save_progress",
                        true,
                    )
                    model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")
                }
                setNegButton(R.string.no) {
                    PrefManager.setCustomVal(
                        "${media.id}_progressDialog",
                        false,
                    )
                    PrefManager.setCustomVal(
                        "${media.id}_save_progress",
                        false,
                    )
                    toast(getString(R.string.reset_auto_update))
                    model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")
                }
                setOnCancelListener { hideSystemBars() }
                show()
            }
        } else {
            model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")
        }

        if (PrefManager.getVal(PrefName.TimeStampsEnabled)) {
            Logger.log("Player: updateTimeStamp loop starting")
            updateTimeStamp()
        }

    }

    private fun onEpisodeReady(ep: Episode) {
        hideSystemBars()
        episode = ep
        media.selected = model.loadSelected(media)
        model.setMedia(media)
        val epKey = episodes.getEpisodeKey(ep.number)
            ?: episodeArr.find { episodes[it] == ep || episodes[it]?.number == ep.number }
            ?: episodeArr.firstOrNull()
        currentEpisodeIndex = if (epKey != null) max(0, episodeArr.indexOf(epKey)) else 0
        if (currentEpisodeIndex in 0 until episodeTitleArr.size) {
            episodeTitle.setSelection(currentEpisodeIndex)
        }
        episodeTitleText.text = episodeTitleArr.getOrElse(currentEpisodeIndex) { "" }
        if (isInitialized) releasePlayer()
        playbackPosition = if (changingServer) {
            PrefManager.getCustomVal("${media.id}_${ep.number}", 0L)
        } else {
            val cleanEp = ep.number.let { MediaNameAdapter.findEpisodeNumber(it) }?.let {
                if (it % 1 == 0f) it.toInt().toString() else it.toString()
            }
            val savedEpPos = PrefManager.getCustomVal("${media.id}_${ep.number}", 0L)
            if (savedEpPos > 0L) savedEpPos else cleanEp?.let { PrefManager.getCustomVal("${media.id}_${it}", 0L) } ?: 0L
        }
        initPlayer()
        preloading = false
        updateProgress()
    }

    private fun initPlayer() {
        checkNotch()
        liveDetectedFromPlayer = false
        liveSupportRegistered = false
        if (isDeclaredLiveStream()) {
            Logger.log(
                Log.INFO,
                "Exo LIVE: init media=${media.id} episode=${media.anime?.selectedEpisode ?: "?"} " +
                    "server=${media.selected?.server ?: "?"} declared=true"
            )
        }

        synchronized(storedSyncCues) {
            storedSyncCues.clear()
            seenCueTexts.clear()
            cueTextStartTimes.clear()
        }

        PrefManager.setCustomVal(
            "${media.id}_current_ep",
            media.anime!!.selectedEpisode!!,
        )

        @Suppress("UNCHECKED_CAST")
        val list =
            (
                    PrefManager.getNullableCustomVal(
                        "continueAnimeList",
                        listOf<Int>(),
                        List::class.java,
                    ) as List<Int>
                    ).toMutableList()
        if (list.contains(media.id)) list.remove(media.id)
        list.add(media.id)
        PrefManager.setCustomVal("continueAnimeList", list)

        lifecycleScope.launch(Dispatchers.IO) {
            extractor?.onVideoStopped(video)
        }

        val availableExtractors = episode.extractors?.filterNotNull()
            ?.filter { it.videos.isNotEmpty() }
            .orEmpty()

        val ext = availableExtractors.find { it.server.name == episode.selectedExtractor }
            ?: availableExtractors.firstOrNull { it.isPreferred }
            ?: availableExtractors.firstOrNull()
        if (ext == null) {
            Logger.log(
                Log.ERROR,
                "Player: no extractor '${episode.selectedExtractor}' on ep '${episode.number}' " +
                    "(have ${episode.extractors?.filterNotNull()?.size ?: 0}) — aborting media build"
            )
            return
        }
        extractor = ext
        video = ext.videos.getOrNull(episode.selectedVideo)
            ?: ext.videos.firstOrNull { it.preferred }
            ?: ext.videos.firstOrNull()
        if (video == null) {
            Logger.log(
                Log.ERROR,
                "Player: no video at index ${episode.selectedVideo} for server '${ext.server.name}' " +
                    "(videos=${ext.videos.size}) on ep '${episode.number}' — aborting media build"
            )
            return
        }

        if (episode.selectedExtractor != ext.server.name) {
            episode.selectedExtractor = ext.server.name
        }
        val chosenIndex = ext.videos.indexOf(video)
        if (chosenIndex >= 0 && episode.selectedVideo != chosenIndex) {
            episode.selectedVideo = chosenIndex
        }
        val subLanguages =
            arrayOf(
                "Albanian",
                "Arabic",
                "Bosnian",
                "Bulgarian",
                "Chinese",
                "Croatian",
                "Czech",
                "Danish",
                "Dutch",
                "English",
                "Estonian",
                "Finnish",
                "French",
                "Georgian",
                "German",
                "Greek",
                "Hebrew",
                "Hindi",
                "Indonesian",
                "Irish",
                "Italian",
                "Japanese",
                "Korean",
                "Lithuanian",
                "Luxembourgish",
                "Macedonian",
                "Mongolian",
                "Norwegian",
                "Polish",
                "Portuguese",
                "Punjabi",
                "Romanian",
                "Russian",
                "Serbian",
                "Slovak",
                "Slovenian",
                "Spanish",
                "Turkish",
                "Ukrainian",
                "Urdu",
                "Vietnamese",
            )
        val lang = subLanguages[PrefManager.getVal(PrefName.SubLanguage)]
        subtitle = intent.getSerialized("subtitle")
            ?: if (!PrefManager.getVal<Boolean>(PrefName.AutoSubtitleDownload) ||
                !PrefManager.getVal<Boolean>(PrefName.Subtitles)
            ) {
                null
            } else when (
                val subLang: String? =
                    PrefManager.getNullableCustomVal(
                        "subLang_${media.id}",
                        null,
                        String::class.java
                    )
            ) {
                null -> {
                    when (episode.selectedSubtitle) {
                        null -> null
                        -1 ->
                            ext.subtitles.find {
                                it.language.contains(lang, ignoreCase = true) ||
                                        it.language.contains(
                                            getLanguageCode(lang),
                                            ignoreCase = true
                                        )
                            }

                        else -> ext.subtitles.getOrNull(episode.selectedSubtitle!!)
                    }
                }

                "None" -> ext.subtitles.let { null }
                else -> ext.subtitles.find { it.language == subLang }
            }

        hasExtSubtitles = ext.subtitles.isNotEmpty()

        if (subtitle == null && hasExtSubtitles &&
            PrefManager.getVal<Boolean>(PrefName.AutoSubtitleDownload) &&
            PrefManager.getVal<Boolean>(PrefName.Subtitles)
        ) {
            val savedLang = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)
            if (savedLang != "None") {
                subtitle = ext.subtitles.getOrNull(0)
            }
        }
        initialSubtitleLabel = subtitle?.language

        if (isOnline(this)) {
             lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                 try {
                     if (media.idIMDB == null) {
                         media.idIMDB = IdMappers.getImdbId(media.id)
                     }

                     val selectedEpisodeStr = media.anime?.selectedEpisode ?: "1"
                     val currentEpisode = media.anime?.episodes?.get(selectedEpisodeStr)
                     val episodeNum = EpisodeMapper.episodeNumberOf(selectedEpisodeStr, currentEpisode)
                     EpisodeMapper.mapEpisode(media, episodeNum, currentEpisode)
                 } catch (e: Exception) {
                     e.printStackTrace()
                 }
             }
        }

        if (hasExtSubtitles || media.idIMDB != null) {
            exoSubtitle.isVisible = true
            exoSubtitle.setOnClickListener {
                subClick()
            }
            
            
            playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
                ?.nextFocusDownId = R.id.exo_sub
        }
        val sub: MutableList<MediaItem.SubtitleConfiguration> =
            emptyList<MediaItem.SubtitleConfiguration>().toMutableList()
        val currentVideoUrl = video!!.file.url
        val embedUrl = ext.server.embed.url

        if (subtitle != null && hasExtSubtitles &&
            PrefManager.getVal<Boolean>(PrefName.AutoSubtitleDownload) &&
            PrefManager.getVal<Boolean>(PrefName.Subtitles)
        ) {
            val extSubUrl = resolveSubtitleUrl(subtitle!!.file.url, embedUrl, currentVideoUrl)
            if (extSubUrl.isNotBlank()) {
                val extSubType = subtitle!!.type
                val fmt = when (extSubType) {
                    SubtitleType.VTT -> "VTT"
                    SubtitleType.ASS -> "ASS"
                    SubtitleType.SRT -> "SRT"
                    SubtitleType.UNKNOWN -> null
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val client = okhttp3.OkHttpClient()
                        val request = okhttp3.Request.Builder().url(extSubUrl).build()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            val content = response.body?.string()
                            if (!content.isNullOrEmpty()) {
                                val parsedCues = parseSubtitleContent(content, fmt)
                                val preparedCues = prepareParsedCues(parsedCues)
                                withContext(Dispatchers.Main) { storePreparedCues(preparedCues) }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ExoplayerView", "Failed to load extractor subtitle cues: ${e.message}")
                    }
                }
            }
        }

        ext.subtitles.forEachIndexed { index, subtitle ->
            val subtitleUrl = if (!hasExtSubtitles) currentVideoUrl else subtitle.file.url
            val resolvedSubtitleUrl = resolveSubtitleUrl(subtitleUrl, embedUrl, currentVideoUrl)
            val subtitleId = buildSubtitleId(index, subtitle.language, resolvedSubtitleUrl)
            val subtitleLangCodeRaw = LanguageMapper.getLanguageCode(subtitle.language)
            val subtitleLanguageCode =

                subtitleLangCodeRaw.takeUnless { it.equals("all", ignoreCase = true) || it.isBlank() } ?: "und"
            val subtitleMime =
                when (subtitle.type) {
                    SubtitleType.VTT -> MimeTypes.TEXT_VTT
                    SubtitleType.ASS -> MimeTypes.TEXT_SSA
                    SubtitleType.SRT -> MimeTypes.APPLICATION_SUBRIP
                    SubtitleType.UNKNOWN -> {
                        Logger.log("Warning: subtitle type unknown for '$resolvedSubtitleUrl', defaulting to SRT")
                        MimeTypes.APPLICATION_SUBRIP
                    }
                }
            sub +=
                MediaItem.SubtitleConfiguration
                    .Builder(resolvedSubtitleUrl.toUri())
                    .setMimeType(subtitleMime)
                    .setId(subtitleId)
                    .setLanguage(subtitleLanguageCode)
                    .setLabel(subtitle.language)
                    .build()
            Logger.log(
                "ExoplayerView: ext sub[$index] '${subtitle.language}' type=${subtitle.type} " +
                    "mime=$subtitleMime host=${runCatching { URI(resolvedSubtitleUrl).host }.getOrNull()}"
            )
        }

        lifecycleScope.launch(Dispatchers.IO) {
            ext.onVideoPlayed(video)
        }

        val streamCookie = video?.file?.headers?.get("Cookie")
        val streamHost = video?.file?.url?.let { url ->
            runCatching { java.net.URI(url).host }.getOrNull()
        }
        val httpClient =
            okhttp3.OkHttpClient.Builder()
                .apply {

                    cookieJar(okHttpClient.cookieJar)
                    ignoreAllSSLErrors()
                    followRedirects(true)
                    followSslRedirects(true)

                    connectionPool(
                        okhttp3.ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES)
                    )
                    connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                }
                .let { base ->

                    if (streamCookie.isNullOrBlank() || streamHost.isNullOrBlank()) {
                        base
                    } else {
                        base.addNetworkInterceptor { chain ->
                            val request = chain.request()
                            val sameHost = request.url.host == streamHost
                            if (sameHost && request.header("Cookie") != streamCookie) {
                                chain.proceed(
                                    request.newBuilder().header("Cookie", streamCookie).build()
                                )
                            } else {
                                chain.proceed(request)
                            }
                        }
                    }
                }
                .build()

        val streamHeaders = video?.file?.headers
        val requestHeaders =
            if (streamHeaders?.containsKey("Cookie") == true) {
                HashMap(defaultHeaders).apply { putAll(streamHeaders) }
            } else {
                streamHeaders ?: defaultHeaders
            }
        val httpDataSourceFactory =
            OkHttpDataSource.Factory(httpClient).apply {
                setDefaultRequestProperties(requestHeaders)
            }
        val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val isOfflineAdaptiveDownload =
            media.format == "LOCAL" &&
                (video?.format == VideoType.M3U8 || video?.format == VideoType.DASH)
        cacheFactory = if (isOfflineAdaptiveDownload) {

            DownloadHlsCache.offlineDataSourceFactory(this)
        } else {
            CacheDataSource.Factory().apply {
                setCache(VideoCache.getInstance(this@ExoplayerView))
                setUpstreamDataSourceFactory(defaultDataSourceFactory)

                setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }
        }

        val subtitleRenderMode = PrefManager.getVal<Int>(PrefName.SubtitleRenderMode)
        val assRenderType = if (subtitleRenderMode == 1) AssRenderType.OVERLAY_OPEN_GL else AssRenderType.OVERLAY_CANVAS
        if (assHandler == null) {
            Logger.log("Libass: Creating AssHandler with $assRenderType")
            assHandler = AssHandler(assRenderType)

            Logger.log("Libass: Injecting AssSubtitleView into exo_content_frame")
            val contentFrame = playerView.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            val assView = io.github.peerless2012.ass.media.widget.AssSubtitleView(this, assHandler!!)
            assView.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            contentFrame?.addView(assView)
            assSubtitleView = assView
        }
        val handler = assHandler!!
        val assSubtitleParserFactory = AssSubtitleParserFactory(handler)
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            )
            .setTsExtractorTimestampSearchBytes(1500 * androidx.media3.extractor.ts.TsExtractor.TS_PACKET_SIZE)
            .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            .setMatroskaExtractorFlags(androidx.media3.extractor.mkv.MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            .withAssMkvSupport(assSubtitleParserFactory, handler)

        val contentDataSourceFactory: DataSource.Factory =
            if (isOfflineAdaptiveDownload) cacheFactory
            else if (isDeclaredLiveStream()) defaultDataSourceFactory else cacheFactory

        assMediaSourceFactory = DefaultMediaSourceFactory(contentDataSourceFactory, extractorsFactory)
        assMediaSourceFactory.setSubtitleParserFactory(assSubtitleParserFactory)
        if (isTmdbMovieMode()) {
            assMediaSourceFactory.setLiveTargetOffsetMs(PREFERRED_LIVE_OFFSET)
        }

        video?.drm?.let { drm ->
            val uuid = drm.uuid
            val licenseUrl = drm.licenseUrl?.takeIf { it.isNotBlank() }
            val hasKidKey = !drm.kid.isNullOrEmpty() && !drm.key.isNullOrEmpty()
            if (uuid != null && (licenseUrl != null || hasKidKey)) {
                val manager = if (licenseUrl != null) {
                    Logger.log(
                        "Player: Setting up remote license DRM uuid=$uuid " +
                            "license=${licenseUrl.take(80)}"
                    )
                    DefaultDrmSessionManager.Builder()
                        .setPlayClearSamplesWithoutKeys(true)
                        .setMultiSession(true)
                        .setKeyRequestParameters(drm.keyRequestParameters)
                        .setUuidAndExoMediaDrmProvider(
                            uuid,
                            FrameworkMediaDrm.DEFAULT_PROVIDER
                        )
                        .build(
                            HttpMediaDrmCallback(
                                licenseUrl,
                                DefaultHttpDataSource.Factory()
                                    .setAllowCrossProtocolRedirects(true)
                            )
                        )
                } else {
                    val kty = drm.kty ?: "oct"
                    val json = "{\"keys\":[{\"kty\":\"$kty\",\"k\":\"" +
                        (drm.key ?: "") + "\",\"kid\":\"" + (drm.kid ?: "") +
                        "\"}],\"type\":\"temporary\"}"
                    Logger.log(
                        "Player: Setting up local ClearKey DRM uuid=$uuid kty=$kty " +
                            "kid=${drm.kid?.take(20)}"
                    )
                    DefaultDrmSessionManager.Builder()
                        .setPlayClearSamplesWithoutKeys(true)
                        .setMultiSession(false)
                        .setKeyRequestParameters(drm.keyRequestParameters)
                        .setUuidAndExoMediaDrmProvider(
                            uuid,
                            FrameworkMediaDrm.DEFAULT_PROVIDER
                        )
                        .build(LocalMediaDrmCallback(json.toByteArray(Charsets.UTF_8)))
                }
                assMediaSourceFactory.setDrmSessionManagerProvider { manager }
            }
        }

        val mimeType =
            when (video?.format) {
                VideoType.M3U8 -> MimeTypes.APPLICATION_M3U8
                VideoType.DASH -> MimeTypes.APPLICATION_MPD
                VideoType.CONTAINER -> {

                    val url = video?.file?.url ?: ""
                    if (url.startsWith("content://") || url.startsWith("file://")) {
                        val decoded = java.net.URLDecoder.decode(url, "UTF-8").lowercase()
                        when {
                            decoded.endsWith(".mkv") -> MimeTypes.APPLICATION_MATROSKA
                            decoded.endsWith(".webm") -> MimeTypes.APPLICATION_WEBM
                            else -> MimeTypes.APPLICATION_MP4
                        }
                    } else {
                        null
                    }
                }
                else -> MimeTypes.APPLICATION_MP4
            }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(video!!.file.url)
            .setMimeType(mimeType)
            .setSubtitleConfigurations(sub)

        if (isDeclaredLiveStream()) {
            mediaItemBuilder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(PREFERRED_LIVE_OFFSET)
                    .build()
            )
        }

        mediaItem = mediaItemBuilder.build()

        Logger.log(
            "Player: building media source url=${video!!.file.url.take(200)} " +
                "mime=$mimeType headers=${video!!.file.headers} " +
                "server=${ext.server.name} embed=${ext.server.embed.url.take(100)}"
        )

        val audioMediaItem = mutableListOf<MediaItem>()
        audioLanguages.clear()
        ext.audioTracks.forEach {
            var code = LanguageMapper.getLanguageCode(it.lang)
            if (code == "all") code = "un"
            audioLanguages.add(Pair(it.lang, code))
            audioMediaItem.add(
                MediaItem
                    .Builder()
                    .setUri(it.url)
                    .setMimeType(MimeTypes.AUDIO_UNKNOWN)
                    .setTag(code)
                    .build(),
            )
        }

        val audioSources =
            audioMediaItem
                .map { mediaItem ->
                    if (mediaItem.localConfiguration
                            ?.uri
                            .toString()
                            .contains(".m3u8")
                    ) {
                        HlsMediaSource.Factory(contentDataSourceFactory).createMediaSource(mediaItem)
                    } else {
                        DefaultMediaSourceFactory(contentDataSourceFactory).createMediaSource(mediaItem)
                    }
                }.toTypedArray()

        val isContentUri = video?.file?.url?.let {
            it.startsWith("content://") || it.startsWith("file://")
        } == true
        val videoMediaSource = if (isContentUri) {
            val localDataSourceFactory = DefaultDataSource.Factory(this)
            val localExtractorsFactory = DefaultExtractorsFactory()
                .setTsExtractorFlags(
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                )
                .setTsExtractorTimestampSearchBytes(1500 * androidx.media3.extractor.ts.TsExtractor.TS_PACKET_SIZE)
                .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
                .setMatroskaExtractorFlags(androidx.media3.extractor.mkv.MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
                .withAssMkvSupport(AssSubtitleParserFactory(assHandler!!), assHandler!!)
            DefaultMediaSourceFactory(localDataSourceFactory, localExtractorsFactory)
                .createMediaSource(mediaItem)
        } else {
            assMediaSourceFactory
                .createMediaSource(mediaItem)
        }
        mediaSource = MergingMediaSource(videoMediaSource, *audioSources)

        exoSource.setOnClickListener {
            Logger.log("Player: SOURCE pressed (ep=${media.anime?.selectedEpisode})")
            sourceClick()
        }

        trackSelector = DefaultTrackSelector(this)
        val parameters =
            trackSelector
                .buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setSelectUndeterminedTextLanguage(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setAllowMultipleAdaptiveSelections(true)
                .setPreferredTextLanguage(subtitle?.language ?: Locale.getDefault().language)
                .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .setRendererDisabled(TRACK_TYPE_VIDEO, false)
                .setRendererDisabled(TRACK_TYPE_AUDIO, false)
                .setRendererDisabled(TRACK_TYPE_TEXT, false)
                .setMaxVideoSize(3840, 2160)
        if (PrefManager.getVal(PrefName.DataSaver)) {

            parameters.setMaxVideoBitrate(1_500_000)
        }

        val activeParser = (if (media.isAdult) HAnimeSources else AnimeSources)[media.selected?.sourceIndex ?: 0]
        if (activeParser.selectDub) {

            val sourceLangCode = runCatching { LanguageMapper.getLanguageCode(activeParser.language) }
                .getOrDefault(Locale.getDefault().language)
            val preferred = if (sourceLangCode.isNotBlank() && sourceLangCode != "all") sourceLangCode
                else Locale.getDefault().language
            parameters.setPreferredAudioLanguage(preferred)
        }
        trackSelector.setParameters(parameters)

        if (playbackPosition != 0L && !changingServer && !PrefManager.getVal<Boolean>(PrefName.AlwaysContinue)) {
            val time =
                String.format(
                    "%02d:%02d:%02d",
                    TimeUnit.MILLISECONDS.toHours(playbackPosition),
                    TimeUnit.MILLISECONDS.toMinutes(playbackPosition) -
                            TimeUnit.HOURS.toMinutes(
                                TimeUnit.MILLISECONDS.toHours(
                                    playbackPosition,
                                ),
                            ),
                    TimeUnit.MILLISECONDS.toSeconds(playbackPosition) -
                            TimeUnit.MINUTES.toSeconds(
                                TimeUnit.MILLISECONDS.toMinutes(
                                    playbackPosition,
                                ),
                            ),
                )
            customAlertDialog().apply {
                setTitle(getString(R.string.continue_from, time))
                setCancelable(false)
                setPosButton(getString(R.string.yes)) {
                    buildExoplayer()
                }
                setNegButton(getString(R.string.no)) {
                    playbackPosition = 0L
                    buildExoplayer()
                }
                show()
            }
        } else {
            buildExoplayer()
        }
    }

    private fun buildExoplayer() {

        renderCustomSubtitle("", "")
        exoSubtitleView.visibility = View.GONE

        playerErrorRetryCount = 0
        opaqueFormatRetryStage = 0
        liveReconnectCount = 0
        liveRecoveryFailed = false

        val bufferSize = PrefManager.getVal<Int>(PrefName.BufferSize)
        val minBufferMs = bufferSize * 1000
        val maxBufferMs = bufferSize * 2000
        val loadControl =
            DefaultLoadControl
                .Builder()
                .setBackBuffer(BACK_BUFFER_DURATION_MS, false)
                .setBufferDurationsMs(
                    minBufferMs,
                    maxBufferMs,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .setTargetBufferBytes(androidx.media3.common.C.LENGTH_UNSET)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

        hideSystemBars()

        val handler = assHandler!!
        val syncKey = currentSubtitleSyncKey()
        val autoSyncPrefEnabled =
            PrefManager.getVal<Boolean>(PrefName.SubtitleAutoSyncVad) && !isLiveStream()
        cancelAutoSyncWatcher()
        if (speechRecorderKey != syncKey) {
            speechRecorderKey = syncKey
            speechRecorder.clearSegments()
            autoSyncCues = emptyList()
            autoSyncCueKey = null
        }
        val storedSource = if (PrefManager.getVal<String>(PrefName.SubtitleDelayKey) == syncKey) {
            PrefManager.getVal<String>(PrefName.SubtitleSyncSource)
        } else {
            ""
        }
        val alreadyHandled = storedSource == "manual" || storedSource == "auto"
        speechRecorder.setEnabled(autoSyncPrefEnabled && !alreadyHandled)
        Logger.log("Libass: Calling SubtitleSyncRenderersFactory.withAssSupport()")
        val renderersFactory = SubtitleSyncRenderersFactory(this, speechRecorder)
            .setEnableDecoderFallback(false)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .withAssSupport(handler)

        exoPlayer =
            ExoPlayer
                .Builder(this, renderersFactory)
                .setMediaSourceFactory(assMediaSourceFactory)

                .setLivePlaybackSpeedControl(
                    DefaultLivePlaybackSpeedControl.Builder()
                        .setFallbackMaxPlaybackSpeed(1.03f)
                        .setFallbackMinPlaybackSpeed(0.97f)
                        .build()
                )
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .build()
        playerView.player = exoPlayer
        if (isLiveStream()) {
            logLiveEvent(
                "preparing source server=${media.selected?.server ?: "?"} format=${video?.format} " +
                    "targetOffsetMs=$PREFERRED_LIVE_OFFSET"
            )
            registerLiveSupport()
        }

        Logger.log("Libass: Calling handler.init(exoPlayer)")
        handler.init(exoPlayer)

        exoPlayer.addListener(this)
        exoPlayer.apply {
            playWhenReady = true
            this.playbackParameters = this@ExoplayerView.playbackParameters
            setMediaSource(mediaSource)
            prepare()
            val localIsLive = isLiveStream()
            if (localIsLive) {
                playbackPosition = 0L
                Logger.log("Player: LIVE stream detected — skipping resume seek (source=${if (liveDetectedFromPlayer) "timeline" else "plugin"})")
            } else {
                PrefManager
                    .getCustomVal(
                        "${media.id}_${media.anime!!.selectedEpisode}_max",
                        Long.MAX_VALUE,
                    ).takeIf { it != Long.MAX_VALUE }
                    ?.let { if (it <= playbackPosition) playbackPosition = max(0, it - 5) }
                seekTo(playbackPosition)
            }
            updateLiveBadge()

        }

        if (autoSyncPrefEnabled && !alreadyHandled &&
            PrefManager.getVal<Boolean>(PrefName.Subtitles) &&
            autoSyncCueKey == syncKey && autoSyncCues.isNotEmpty()
        ) {
            startAutoSyncWatcher()
        }

        exoPlayer.addListener(
            object : Player.Listener {
                var activeSubtitles = ArrayDeque<String>(3)
                var lastSubtitle: String? = null
                var lastPosition: Long = 0

                var lastPeriodShiftMs = 0L

                override fun onCues(cueGroup: CueGroup) {
                    if (!PrefManager.getVal<Boolean>(PrefName.Subtitles)) return
                    val cueCallbackStartedNs = java.lang.System.nanoTime()
                    val cueCallbackThread = Thread.currentThread().name
                    val cueCount = cueGroup.cues.size
                    try {
                    val selectedTextMime = exoPlayer.currentTracks.groups
                        .firstOrNull { it.type == TRACK_TYPE_TEXT && it.isSelected }
                        ?.getTrackFormat(0)?.sampleMimeType
                    val libassActive = selectedTextMime == MimeTypes.TEXT_SSA ||
                        selectedTextMime == "text/x-ass" ||
                        (selectedTextMime?.contains("ssa", ignoreCase = true) == true)
                    val newCueTexts = cueGroup.cues.mapNotNull { it.text?.toString() }

                    val cueStartMs = cueGroup.presentationTimeUs / 1000 + lastPeriodShiftMs

                    synchronized(storedSyncCues) {
                        if (newCueTexts.isNotEmpty()) {
                            val lines = newCueTexts
                                .map(::cleanSubtitleText)
                                .filter { it.isNotBlank() }
                                .distinct()
                            for (text in lines) {
                                val dedupKey = "p${cueGroup.presentationTimeUs}|$text"
                                val isWindowRepeat = cueTextStartTimes[text]?.any { startMs ->
                                    cueStartMs - startMs in 0..15_000
                                } ?: false
                                if (dedupKey in seenCueTexts || isWindowRepeat || isTitleSignCue(text)) {
                                    continue
                                }
                                seenCueTexts.add(dedupKey)

                                var fillIndex = storedSyncCues.size - 1
                                while (fillIndex >= 0) {
                                    val previous = storedSyncCues[fillIndex]
                                    if (previous.durationMs != 3000L) break
                                    val gap = cueStartMs - previous.startTimeMs
                                    val maxGap = if (fillIndex == storedSyncCues.size - 1) {
                                        60_000L
                                    } else {
                                        15_000L
                                    }
                                    if (gap !in 1001..maxGap) break
                                    storedSyncCues[fillIndex] =
                                        previous.copy(durationMs = gap)
                                    fillIndex--
                                }
                                storedSyncCues.add(
                                    SyncCue(text = text, startTimeMs = cueStartMs, durationMs = 3000L)
                                )
                                cueTextStartTimes.getOrPut(text) { mutableListOf() }.add(cueStartMs)
                            }
                            if (!areSyncCuesSortedByStart(storedSyncCues)) {
                                storedSyncCues.sortBy { it.startTimeMs }
                            }
                        }
                    }
                    lastPeriodShiftMs = exoPlayer.currentPosition - cueGroup.presentationTimeUs / 1000
                    if (libassActive) {
                        exoSubtitleView.visibility = View.GONE
                        renderCustomSubtitle("", "")
                        return
                    }
                    val currentPosition = exoPlayer.currentPosition

                    val subtitleOffset = activeSubtitleOffset()
                    val subtitleRate = activeSubtitleRate()
                    val syncActive = subtitleSyncActive(subtitleOffset, subtitleRate)
                    val cuePosition = if (syncActive) {
                        ((currentPosition - subtitleOffset) / subtitleRate).roundToLong()
                    } else {
                        currentPosition
                    }
                    val (hasStoredCues, activeCues) = synchronized(storedSyncCues) {
                        storedSyncCues.isNotEmpty() to syncCuesAt(storedSyncCues, cuePosition)
                    }
                    val hasOverlappingCues = activeCues.size >= 2

                    val useCustomRendering = PrefManager.getVal<Boolean>(PrefName.TextviewSubtitles) ||
                        (syncActive && hasStoredCues) ||
                        hasOverlappingCues
                    if (useCustomRendering) {
                        exoSubtitleView.visibility = View.GONE

                        if (syncActive || hasOverlappingCues) {
                            renderCustomSubtitleCues(activeCues)
                            return
                        }

                        if (newCueTexts.isEmpty()) {
                            renderCustomSubtitleTexts(emptyList())
                            activeSubtitles.clear()
                            lastSubtitle = null
                            lastPosition = 0
                            return
                        }

                        if ((lastSubtitle?.length
                                ?: 0) < 20 || (lastPosition != 0L && currentPosition - lastPosition > 1500)
                        ) {
                            activeSubtitles.clear()
                        }

                        for (newCue in newCueTexts) {
                            if (newCue !in activeSubtitles) {
                                if (activeSubtitles.size >= 2) {
                                    activeSubtitles.removeLast()
                                }
                                activeSubtitles.addFirst(newCue)
                                lastSubtitle = newCue
                                lastPosition = currentPosition
                            }
                        }

                        renderCustomSubtitleTexts(activeSubtitles.toList())
                    } else {
                        renderCustomSubtitle("", "")
                        exoSubtitleView.visibility = View.VISIBLE
                    }
                    } finally {
                        val elapsedMs = (java.lang.System.nanoTime() - cueCallbackStartedNs) / 1_000_000L
                        if (elapsedMs >= 16L) {
                            Logger.log(
                                Log.WARN,
                                "PlayerPerf: onCues slow durationMs=$elapsedMs cues=$cueCount " +
                                    "storedSyncCues=${synchronized(storedSyncCues) { storedSyncCues.size }} " +
                                    "thread=$cueCallbackThread mediaId=${media.id} ep=${media.anime?.selectedEpisode}"
                            )
                        }
                    }
                }
            },
        )

        applySubtitleStyles(customSubtitleView)
        applyTopSubtitleStyles(customSubtitleTopView)
        setupSubFormatting(playerView)

        try {
            val rightNow = Calendar.getInstance()
            mediaSession =
                MediaSession
                    .Builder(this, exoPlayer)
                    .setId(rightNow.timeInMillis.toString())
                    .build()
        } catch (e: Exception) {
            toast(e.toString())
        }

        isInitialized = true

        if (!hasExtSubtitles && !PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
            onSetTrackGroupOverride(dummyTrack, TRACK_TYPE_TEXT)
        }

        val savedLang = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)
        val subMasterEnabled = PrefManager.getVal<Boolean>(PrefName.Subtitles)
        val isDisabled = if (hasExtSubtitles) {
            savedLang == "None" || !subMasterEnabled
        } else {
            subtitle == null && !subMasterEnabled
        }
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(TRACK_TYPE_TEXT, isDisabled)
                .build()
    }

    private fun releasePlayer() {
        val wasLive = liveSupportRegistered || liveDetectedFromPlayer || isDeclaredLiveStream()
        if (wasLive) {
            Logger.log(
                Log.INFO,
                "Exo LIVE: release media=${media.id} episode=${media.anime?.selectedEpisode ?: "?"} " +
                    "position=${exoPlayer.currentPosition} duration=${exoPlayer.duration}"
            )
        }
        isPlayerPlaying = exoPlayer.playWhenReady
        playbackPosition = exoPlayer.currentPosition
        disappeared = false
        functionstarted = false
        exoSubtitleView.setCues(emptyList())
        LiveHelper.unregisterPlayer(exoPlayer)
        liveSupportRegistered = false
        exoPlayer.release()
        VideoCache.release()
        mediaSession?.release()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (isInitialized) {
            outState.putInt(resumeWindow, exoPlayer.currentMediaItemIndex)
                outState.putLong(resumePosition, exoPlayer.currentPosition)
        }
        outState.putInt(playerFullscreen, isFullscreen)
        outState.putBoolean(playerOnPlay, isPlayerPlaying)
        super.onSaveInstanceState(outState)
    }

    private fun sourceClick() {
        Logger.log("Player: sourceClick — opening source selector (server=${media.selected?.server ?: "none"}, ep=${episode.number})")
        changingServer = true

        media.selected!!.server = null
        PrefManager.setCustomVal(
            "${media.id}_${media.anime!!.selectedEpisode}",
            exoPlayer.currentPosition,
        )
        model.saveSelected(media.id, media.selected!!)
        model.onEpisodeClick(
            media,
            episode.number,
            this.supportFragmentManager,
            launch = false,
        )
    }

    private fun silentLiveReconnect() {
        val epLabel = episode.number
        val previousServer = extractor?.server?.name
        val oldUrl = video?.file?.url
        Logger.log(
            "Player: silent live reconnect — re-fetching URLs " +
                "(previous server='${previousServer ?: "none"}', ep=$epLabel)"
        )

        lifecycleScope.launch(Dispatchers.IO) {

            val session = TmdbStreamResolver.sessionFor(media.id)
            session?.mediaId?.let { TmdbStreamResolver.invalidateLinks(it) }

            episode.extractors = null
            episode.extractorsSource = null
            episode.allStreams = false

            val ok = TmdbStreamResolver.populateSyntheticEpisode(
                this@ExoplayerView, media, episode
            )
            if (!ok) {
                withContext(Dispatchers.Main) {
                    Logger.log(
                        Log.ERROR,
                        "Player: silent reconnect failed — plugin returned no links (ep=$epLabel)"
                    )
                    if (isLiveStream()) {
                        abortLiveRecovery("plugin returned no links")
                    } else {
                        toast("Reconnect failed — no fresh URLs")
                        isPlayerPlaying = true
                        sourceClick()
                    }
                }
                return@launch
            }

            val freshExtractors = episode.extractors?.filterNotNull()
            if (freshExtractors.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    Logger.log(
                        Log.ERROR,
                        "Player: silent reconnect failed — empty extractors (ep=$epLabel)"
                    )
                    if (isLiveStream()) {
                        abortLiveRecovery("plugin returned no servers")
                    } else {
                        toast("Reconnect failed — no servers")
                        isPlayerPlaying = true
                        sourceClick()
                    }
                }
                return@launch
            }

            val freshExt = freshExtractors.firstNotNullOfOrNull { ext ->
                val url = ext.videos.firstOrNull()?.file?.url
                if (url != null && url != oldUrl) ext else null
            }

            if (freshExt == null) {
                withContext(Dispatchers.Main) {
                    Logger.log(
                        Log.WARN,
                        "Player: silent reconnect — every server returned " +
                            "the same URL as the expired one; " +
                            "cannot silently recover (ep=$epLabel)"
                    )
                    if (isLiveStream()) {
                        abortLiveRecovery("same URLs returned on every server")
                    } else {
                        toast("Reconnect failed — same URLs")
                        isPlayerPlaying = true
                        sourceClick()
                    }
                }
                return@launch
            }

            val freshVideo = freshExt.videos.getOrNull(episode.selectedVideo)
                ?: freshExt.videos.firstOrNull { it.preferred }
                ?: freshExt.videos.firstOrNull()

            if (freshVideo == null) {
                withContext(Dispatchers.Main) {
                    Logger.log(
                        Log.ERROR,
                        "Player: silent reconnect failed — no video in '${freshExt.server.name}' (ep=$epLabel)"
                    )
                    toast("Reconnect failed — no playable URL")
                    isPlayerPlaying = true
                    sourceClick()
                }
                return@launch
            }

            extractor = freshExt
            video = freshVideo
            episode.extractors = freshExtractors.toMutableList()
            episode.selectedExtractor = freshExt.server.name
            episode.selectedVideo =
                freshExt.videos.indexOf(freshVideo).coerceAtLeast(0)

            val mimeType = when (freshVideo.format) {
                VideoType.M3U8 -> MimeTypes.APPLICATION_M3U8
                VideoType.DASH -> MimeTypes.APPLICATION_MPD
                VideoType.CONTAINER -> {
                    val url = freshVideo.file.url
                    if (url.startsWith("content://") || url.startsWith("file://")) {
                        val decoded = java.net.URLDecoder.decode(url, "UTF-8").lowercase()
                        when {
                            decoded.endsWith(".mkv") -> MimeTypes.APPLICATION_MATROSKA
                            decoded.endsWith(".webm") -> MimeTypes.APPLICATION_WEBM
                            else -> MimeTypes.APPLICATION_MP4
                        }
                    } else null
                }
                else -> MimeTypes.APPLICATION_MP4
            }

            val freshItemBuilder = MediaItem.Builder()
                .setUri(freshVideo.file.url)
                .setMimeType(mimeType)

            if (isDeclaredLiveStream()) {
                freshItemBuilder.setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(PREFERRED_LIVE_OFFSET)
                        .build()
                )
            }
            mediaItem = freshItemBuilder.build()

            val videoMediaSource =
                assMediaSourceFactory.createMediaSource(mediaItem)

            val audioSources = freshExt.audioTracks.map { track ->
                val audioItem = MediaItem.Builder()
                    .setUri(track.url)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                HlsMediaSource.Factory(cacheFactory).createMediaSource(audioItem)
            }.toTypedArray()

            mediaSource = MergingMediaSource(videoMediaSource, *audioSources)

            withContext(Dispatchers.Main) {
                if (!isInitialized || exoPlayer.isReleased) {
                    Logger.log(
                        Log.WARN,
                        "Player: silent reconnect — player released during fetch, " +
                            "falling back to episode re-init (ep=$epLabel)"
                    )
                    model.setEpisode(episode, "live-reconnect-fallback")
                    return@withContext
                }

                Logger.log(
                    "Player: silent reconnect OK — " +
                        "url=${freshVideo.file.url.take(120)} " +
                        "server='${freshExt.server.name}' ep=$epLabel"
                )
                toast("Reconnected ✓")
                liveRecoveryFailed = false
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                exoPlayer.play()
            }
        }
    }

    override fun getSyncCues(): List<SyncCue> {
        synchronized(storedSyncCues) {
            return storedSyncCues.toList()
        }
    }

    fun getPlayerPosition(): Long {
        return if (::exoPlayer.isInitialized) exoPlayer.currentPosition else 0L
    }

    private fun applyWrapButtonsStyle() {
        val wrapMode = PrefManager.getVal<Int>(PrefName.WrapButtons)
        val pillLeft = playerView.findViewById<View>(R.id.pillBottomLeft)
        val pillTop = playerView.findViewById<View>(R.id.pillTopRight)
        val pillRight = playerView.findViewById<View>(R.id.pillBottomRight)
        val pills = listOfNotNull(pillLeft, pillTop, pillRight)

        when (wrapMode) {
            0 -> {
                pills.forEach { it.background = null }
            }
            else -> {
                val radius = 26f * resources.displayMetrics.density
                val fill = when (wrapMode) {
                    1 -> (Math.round(0.70f * 255f).toInt() shl 24) or 0x161922
                    2 -> (Math.round(0.70f * 255f).toInt() shl 24) or 0xFFFFFF
                    3 -> (Math.round(0.14f * 255f).toInt() shl 24) or 0xFFFFFF
                    else -> {
                        val typedVal = android.util.TypedValue()
                        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedVal, true)
                        android.graphics.Color.argb(
                            (0.48f * 255).toInt(),
                            android.graphics.Color.red(typedVal.data),
                            android.graphics.Color.green(typedVal.data),
                            android.graphics.Color.blue(typedVal.data)
                        )
                    }
                }
                pills.forEach {
                    ani.sanin.ui.LensButtonBackground.apply(it, tint = fill, cornerRadiusPx = radius)
                }
            }
        }
    }

    private fun currentSubtitleSyncKey(): String =
        "${media.id}-${media.anime?.selectedEpisode ?: ""}"

    private fun activeSubtitleOffset(): Long {
        if (PrefManager.getVal<String>(PrefName.SubtitleDelayKey) != currentSubtitleSyncKey()) return 0L
        return PrefManager.getVal<Long>(PrefName.SubtitleDelay)
    }

    private fun activeSubtitleRateFor(key: String): Double {
        if (PrefManager.getVal<String>(PrefName.SubtitleDelayKey) != key) return 1.0
        return PrefManager.getVal<Float>(PrefName.SubtitleSyncRate)
            .toDouble()
            .coerceIn(0.90, 1.10)
    }

    private fun activeSubtitleRate(): Double = activeSubtitleRateFor(currentSubtitleSyncKey())

    private fun subtitleSyncActive(offsetMs: Long, rate: Double): Boolean {
        return PrefManager.getVal<Boolean>(PrefName.SubtitleSyncEnabled) &&
            (offsetMs != 0L || abs(rate - 1.0) > 0.0005)
    }

    private fun storeSubtitleOffset(offsetMs: Long, source: String = "", rate: Double? = null) {
        val key = currentSubtitleSyncKey()
        val effectiveRate = rate ?: activeSubtitleRateFor(key)
        PrefManager.setVal(PrefName.SubtitleDelayKey, key)
        PrefManager.setVal(PrefName.SubtitleDelay, offsetMs)
        PrefManager.setVal(PrefName.SubtitleSyncRate, effectiveRate.toFloat())
        PrefManager.setVal(
            PrefName.SubtitleSyncEnabled,
            offsetMs != 0L || abs(effectiveRate - 1.0) > 0.0005,
        )
        PrefManager.setVal(PrefName.SubtitleSyncSource, source)
    }

    fun applySubtitleOffset(offsetMs: Long) {

        storeSubtitleOffset(offsetMs, "manual")
        cancelAutoSyncWatcher()
        speechRecorder.setEnabled(false)

        refreshSyncRender()
    }

    private fun refreshSyncRender() {
        if (!::exoPlayer.isInitialized) return
        val subtitleOffset = activeSubtitleOffset()
        val subtitleRate = activeSubtitleRate()
        val syncActive = subtitleSyncActive(subtitleOffset, subtitleRate)
        val cues = synchronized(storedSyncCues) { storedSyncCues.toList() }
        if (syncActive && cues.isNotEmpty()) {

            val selectedTextMime = exoPlayer.currentTracks.groups
                .firstOrNull { it.type == TRACK_TYPE_TEXT && it.isSelected }
                ?.getTrackFormat(0)?.sampleMimeType
            val libassActive = selectedTextMime == MimeTypes.TEXT_SSA ||
                selectedTextMime == "text/x-ass" ||
                (selectedTextMime?.contains("ssa", ignoreCase = true) == true)
            if (libassActive) return

            val pos = exoPlayer.currentPosition
            val adjustedPos = ((pos - subtitleOffset) / subtitleRate).roundToLong()
            exoSubtitleView.visibility = View.GONE
            renderCustomSubtitleTexts(syncCuesAt(cues, adjustedPos).map { it.text })
        } else if (!PrefManager.getVal<Boolean>(PrefName.TextviewSubtitles)) {

            renderCustomSubtitle("", "")
            exoSubtitleView.visibility = View.VISIBLE
        }
    }

    private fun beginSubtitleAutoSync(cues: List<SyncCue>, format: String) {
        if (!PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
            speechRecorder.setEnabled(false)
            return
        }
        cancelAutoSyncWatcher()
        autoSyncCues = emptyList()
        autoSyncCueKey = null
        val key = currentSubtitleSyncKey()
        if (speechRecorderKey != key) {

            speechRecorderKey = key
            speechRecorder.clearSegments()
        }
        storeSubtitleOffset(0L, "", 1.0)
        if (format == "ASS" || cues.size < 8 || isLiveStream()) {
            speechRecorder.setEnabled(false)
            return
        }
        val usable = getSyncCues()
        if (usable.size < 8) {
            speechRecorder.setEnabled(false)
            return
        }
        if (!PrefManager.getVal<Boolean>(PrefName.SubtitleAutoSyncVad)) {
            speechRecorder.setEnabled(false)
            return
        }
        autoSyncCues = usable
        autoSyncCueKey = currentSubtitleSyncKey()
        startAutoSyncWatcher()
    }

    private fun startAutoSyncWatcher() {
        val key = autoSyncCueKey ?: return
        if (autoSyncCues.isEmpty()) return
        cancelAutoSyncWatcher()
        val generation = autoSyncGeneration
        speechRecorder.setEnabled(true)
        autoSyncJob = lifecycleScope.launch {
            var lastSpeechMs = 0L
            var bestResult: SubtitleAudioSync.Result? = null
            var appliedOffset: Long? = null
            var appliedRate = 1.0
            var lastOffset = Long.MIN_VALUE
            var lastRate = 0.0
            var stableResults = 0
            try {
                while (isActive) {
                    delay(AUTO_SYNC_INTERVAL_MS)
                    if (!PrefManager.getVal<Boolean>(PrefName.SubtitleAutoSyncVad)) break
                    if (currentSubtitleSyncKey() != key) break
                    if (hasManualSyncForCurrentEpisode()) break
                    val segments = speechRecorder.snapshotSegments()
                    val speechMs = segments.sumOf { it.endMs - it.startMs }
                    if (speechMs < AUTO_SYNC_MIN_SPEECH_MS) continue
                    if (speechMs <= lastSpeechMs + 2_000L) continue
                    lastSpeechMs = speechMs
                    val result = withContext(Dispatchers.Default) {
                        val offsetOnly = SubtitleAudioSync.correlate(autoSyncCues, segments)
                        if (offsetOnly != null && offsetOnly.score >= AUTO_SYNC_STRONG_SCORE) {
                            offsetOnly
                        } else {
                            val affine = SubtitleAudioSync.correlateAffine(autoSyncCues, segments)
                            when {
                                affine == null -> offsetOnly
                                offsetOnly == null -> affine
                                affine.score > offsetOnly.score + AUTO_SYNC_MIN_GAIN -> affine
                                else -> offsetOnly
                            }
                        }
                    }
                    if (result == null) {
                        if (speechMs >= AUTO_SYNC_MAX_CAPTURE_MS) break
                        continue
                    }
                    if (!PrefManager.getVal<Boolean>(PrefName.SubtitleAutoSyncVad)) break
                    if (currentSubtitleSyncKey() != key) break
                    if (hasManualSyncForCurrentEpisode()) break

                    val previousBest = bestResult
                    val improved = previousBest == null ||
                        result.score > previousBest.score + AUTO_SYNC_MIN_GAIN
                    if (improved) {
                        bestResult = result
                        val offsetChanged = appliedOffset == null ||
                            abs(result.offsetMs - appliedOffset!!) >= AUTO_SYNC_MIN_OFFSET_CHANGE_MS
                        val rateChanged = abs(result.rate - appliedRate) >= AUTO_SYNC_MIN_RATE_CHANGE
                        if (offsetChanged || rateChanged) {
                            val firstApplication = appliedOffset == null
                            storeSubtitleOffset(result.offsetMs, "auto", result.rate)
                            refreshSyncRender()
                            appliedOffset = result.offsetMs
                            appliedRate = result.rate
                            Logger.log(
                                "SubtitleAutoSync: offset=${result.offsetMs}ms " +
                                    "rate=${"%.4f".format(result.rate)} " +
                                    "onset=${"%.2f".format(result.onsetShare)} " +
                                    "presence=${"%.2f".format(result.presenceShare)} " +
                                    "cues=${result.consideredCues}"
                            )
                            if (firstApplication && (result.offsetMs != 0L ||
                                    abs(result.rate - 1.0) > 0.0005)
                            ) {
                                snackString(
                                    "Auto-synced subtitles from audio (%+.1fs%s)"
                                        .format(
                                            result.offsetMs / 1000.0,
                                            if (abs(result.rate - 1.0) > 0.0005) {
                                                ", %.3fx speed".format(result.rate)
                                            } else {
                                                ""
                                            },
                                        )
                                )
                            }
                        }
                    }

                    stableResults = if (
                        abs(result.offsetMs - lastOffset) <= AUTO_SYNC_MIN_OFFSET_CHANGE_MS &&
                        abs(result.rate - lastRate) <= AUTO_SYNC_MIN_RATE_CHANGE
                    ) {
                        stableResults + 1
                    } else {
                        0
                    }
                    lastOffset = result.offsetMs
                    lastRate = result.rate
                    if (result.score >= AUTO_SYNC_STABLE_SCORE && stableResults >= 2) break
                    if (speechMs >= AUTO_SYNC_MAX_CAPTURE_MS) break
                }
            } finally {
                if (generation == autoSyncGeneration) {
                    speechRecorder.setEnabled(false)
                    autoSyncJob = null
                }
            }
        }
    }

    private fun cancelAutoSyncWatcher() {
        autoSyncGeneration++
        autoSyncJob?.cancel()
        autoSyncJob = null
    }

    private fun hasManualSyncForCurrentEpisode(): Boolean {
        val key = currentSubtitleSyncKey()
        return PrefManager.getVal<String>(PrefName.SubtitleDelayKey) == key &&
            PrefManager.getVal<String>(PrefName.SubtitleSyncSource) == "manual"
    }

    override fun getSyncPlayerPosition(): Long = getPlayerPosition()
    override fun getSubtitleOffset(): Long = activeSubtitleOffset()
    override fun getSubtitleRate(): Double = activeSubtitleRate()
    override fun setSubtitleOffset(offsetMs: Long) = applySubtitleOffset(offsetMs)

    private fun parseSubtitleContent(content: String, detectedFormat: String? = null): List<SyncCue> {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        val format = detectedFormat ?: detectSubtitleFormat(normalized)
        return when (format) {
            "VTT" -> parseVTT(normalized)
            "ASS" -> parseASS(normalized)
            "SRT" -> parseSRT(normalized)
            else -> emptyList()
        }
    }

    private fun detectSubtitleFormat(content: String): String = when {
        content.trimStart().startsWith("WEBVTT") -> "VTT"
        content.contains("[Script Info]") || content.contains("[Events]") -> "ASS"
        content.contains("<tt ") || content.contains("<tt>") -> "TTML"
        else -> "SRT"
    }

    private fun parseSRTTime(time: String): Long {
        val t = time.replace(",", ".")
        val parts = t.split(":", ".")
        val h = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val m = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val s = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val ms = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        return h * 3600000 + m * 60000 + s * 1000 + ms
    }

    private val timeLineRegex = Regex("""(\d{2}:\d{2}:\d{2})[,\.](\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2})[,\.](\d{3})""")

    private fun parseSRT(content: String): List<SyncCue> {
        val cues = mutableListOf<SyncCue>()
        for (block in content.split(Regex("\n\n+"))) {
            val lines = block.trim().lines()
            if (lines.size < 2) continue
            val timeIdx = lines.indexOfFirst { it.trim().matches(Regex("""\d{2}:\d{2}:\d{2}[,\.]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[,\.]\d{3}""")) }
            if (timeIdx == -1) continue
            val m = timeLineRegex.find(lines[timeIdx]) ?: continue
            val start = parseSRTTime("${m.groupValues[1]},${m.groupValues[2]}")
            val end = parseSRTTime("${m.groupValues[3]},${m.groupValues[4]}")
            val text = lines.drop(timeIdx + 1).joinToString("\n").trim()
            if (text.isNotBlank()) {
                cues.add(SyncCue(text = cleanSubtitleText(text), startTimeMs = start, durationMs = (end - start).coerceAtLeast(1000)))
            }
        }
        return cues
    }

    private fun parseVTT(content: String): List<SyncCue> {
        val cues = mutableListOf<SyncCue>()
        for (block in content.split(Regex("\n\n+"))) {
            val lines = block.trim().lines()
            if (lines.size < 2) continue
            val timeIdx = lines.indexOfFirst { it.contains("-->") }
            if (timeIdx == -1) continue
            val m = timeLineRegex.find(lines[timeIdx]) ?: continue
            val start = parseSRTTime("${m.groupValues[1]},${m.groupValues[2]}")
            val end = parseSRTTime("${m.groupValues[3]},${m.groupValues[4]}")
            val text = lines.drop(timeIdx + 1).joinToString("\n").trim()
            if (text.isBlank()) continue
            val settings = lines[timeIdx].substringAfter("-->", "").trim().substringAfter(' ', "")
            cues.add(
                SyncCue(
                    text = cleanSubtitleText(text),
                    startTimeMs = start,
                    durationMs = (end - start).coerceAtLeast(1000),
                    isTop = vttCueIsTop(settings),
                )
            )
        }
        return cues
    }

    private fun vttCueIsTop(settings: String): Boolean {
        val line = Regex("""line:(\d+(?:\.\d+)?)%""").find(settings)?.groupValues?.getOrNull(1)
            ?.toFloatOrNull()
        return line != null && line <= 40f
    }

    private fun parseASS(content: String): List<SyncCue> {
        val cues = mutableListOf<SyncCue>()
        val eventSection = content.substringAfter("[Events]", "")
            .substringBefore("\n[")

        val formatLine = eventSection.lines()
            .firstOrNull { it.trimStart().startsWith("Format:", ignoreCase = true) }
            ?: return cues
        val formatParts = formatLine.substringAfter("Format:").split(",").map { it.trim() }
        val startIdx = formatParts.indexOf("Start")
        val endIdx = formatParts.indexOf("End")
        val textIdx = formatParts.indexOf("Text")
        if (startIdx == -1 || endIdx == -1 || textIdx == -1) return cues

        for (line in eventSection.lines()) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue
            val parts = trimmed.substringAfter("Dialogue:").split(',', limit = textIdx + 1)
            if (parts.size < 3) continue
            val start = parts.getOrNull(startIdx)?.trim() ?: continue
            val end = parts.getOrNull(endIdx)?.trim() ?: continue
            val rawText = parts.getOrNull(textIdx)?.trim() ?: continue
            val cleanText = cleanSubtitleText(rawText)
            if (cleanText.isBlank()) continue
            cues.add(SyncCue(
                text = cleanText,
                startTimeMs = parseASSTime(start),
                durationMs = (parseASSTime(end) - parseASSTime(start)).coerceAtLeast(1000)
            ))
        }
        return cues
    }

    private fun parseASSTime(time: String): Long {
        val parts = time.split(":", ".")
        val h = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val m = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val s = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val frac = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        val ms = if (frac > 99) frac else frac * 10
        return h * 3600000 + m * 60000 + s * 1000 + ms
    }

    private data class PreparedSubtitleCues(
        val cues: List<SyncCue>,
        val seenTexts: Set<String>,
    )

    
    private fun prepareParsedCues(cues: List<SyncCue>): PreparedSubtitleCues {
        val usable = cues.filterNot { isTitleSignCue(it.text) }.sortedBy { it.startTimeMs }
        return PreparedSubtitleCues(usable, usable.mapTo(mutableSetOf()) { it.text })
    }

    private fun storePreparedCues(prepared: PreparedSubtitleCues) {
        synchronized(storedSyncCues) {
            storedSyncCues.clear()
            storedSyncCues.addAll(prepared.cues)
            seenCueTexts.clear()
            seenCueTexts.addAll(prepared.seenTexts)
            rebuildCueTextStartTimesLocked()
        }
    }

    private fun rebuildCueTextStartTimesLocked() {
        cueTextStartTimes.clear()
        for (cue in storedSyncCues) {
            cueTextStartTimes.getOrPut(cue.text) { mutableListOf() }.add(cue.startTimeMs)
        }
    }

    private fun areSyncCuesSortedByStart(cues: List<SyncCue>): Boolean {
        for (i in 1 until cues.size) {
            val prev = cues[i - 1]
            val cur = cues[i]
            if (prev.startTimeMs > cur.startTimeMs) return false
        }
        return true
    }

    private fun syncCuesAt(cues: List<SyncCue>, posMs: Long): List<SyncCue> {
        val active = ArrayList<SyncCue>(2)
        for (cue in cues) {
            if (cue.startTimeMs > posMs) break
            if (posMs < cue.startTimeMs + cue.durationMs) active.add(cue)
        }
        return active
    }

    private fun renderCustomSubtitleCues(cues: List<SyncCue>) {
        val top = cues.filter { it.isTop }.map { it.text.trim() }.filter { it.isNotEmpty() }
        val bottom = cues.filterNot { it.isTop }.map { it.text.trim() }.filter { it.isNotEmpty() }
        renderCustomSubtitle(top.joinToString("\n"), bottom.joinToString("\n"))
    }

    private fun renderCustomSubtitleTexts(texts: List<String>) {
        val unique = LinkedHashSet<String>()
        for (text in texts) {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) unique.add(trimmed)
        }
        val topText = if (unique.size >= 2) unique.first() else ""
        val bottomText = if (unique.size >= 2) {
            unique.drop(1).joinToString("\n")
        } else {
            unique.firstOrNull() ?: ""
        }
        renderCustomSubtitle(topText, bottomText)
    }

    private fun renderCustomSubtitle(topText: String, bottomText: String) {
        customSubtitleTopView.text = topText
        customSubtitleTopView.visibility = if (topText.isEmpty()) View.GONE else View.VISIBLE
        customSubtitleView.text = bottomText
        customSubtitleView.visibility = if (bottomText.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun storeParsedCues(cues: List<SyncCue>) {
        storePreparedCues(prepareParsedCues(cues))
    }

    private val cueTagJunkRegex = Regex("\\{[^}]*\\}")
    private val cueAnTagRegex = Regex("(?i)[\\\\/]+\\s*an\\d{1,2}\\b")
    private val cueNewlineTagRegex = Regex("(?i)[\\\\/]n\\b")
    private val cueWhitespaceRegex = Regex("\\s{2,}")
    private val cueHtmlTagRegex = Regex("<[^>]+>")
    private val cuePunctRegex = Regex("[\\p{Punct}\\s\\u3000\\u300c\\u300d\\u300e\\u300f]")
    private val cueTitlePrefixRegex = Regex("^(?:s\\d{1,3}e\\d{1,3}|\\d{1,3}e|\\d{1,3}|第\\d+話?)+")

    private fun isTitleSignCue(rawText: String): Boolean {
        fun tidy(s: String) = s
            .replace(cuePunctRegex, "")
            .lowercase(Locale.ROOT)
        val candidates = listOfNotNull(
            media.name, media.nameMAL, media.nameRomaji, media.userPreferredName
        )
        var text = tidy(rawText)

        text = text.replace(cueTitlePrefixRegex, "")
        return candidates.any { candidate ->
            val t = tidy(candidate)
            t.isNotBlank() && (text == t || (text.startsWith(t) && text.length - t.length <= 8))
        }
    }

    private fun cleanSubtitleText(text: String): String {
        return stripSubtitleTagJunk(text.replace(cueHtmlTagRegex, ""))
    }

    private fun stripSubtitleTagJunk(text: String): String {
        return text

            .replace(cueTagJunkRegex, "")

            .replace(cueAnTagRegex, "")

            .replace(cueNewlineTagRegex, "\n")
            .replace(cueWhitespaceRegex, " ")
            .trim()
    }

    private fun subClick() {
        Logger.log("subClick: Opening subtitle dialog (ep=${episode.number}, extractor=${episode.selectedExtractor}, selectedSubtitle=${episode.selectedSubtitle})")
        PrefManager.setCustomVal(
            "${media.id}_${media.anime!!.selectedEpisode}",
            exoPlayer.currentPosition,
        )
        model.saveSelected(media.id, media.selected!!)
        Logger.log("subClick: Opening subtitle rail")
        subtitleRailController?.open()
    }

    fun requestLocalSubtitle() {
        getContent.launch(
            arrayOf(
                "application/x-subrip",
                "text/vtt",
                "text/x-ssa",
                "application/x-ass",
                "text/plain",
                "application/octet-stream"
            )
        )
    }

    fun reApplyLocalSubtitle(uriString: String) {
        android.util.Log.d("LocalSubDebug", "reApplyLocalSubtitle called with: $uriString")
        Logger.log("reApplyLocalSubtitle: uriString=$uriString")
        try {
            val uri = android.net.Uri.parse(uriString)
            android.util.Log.d("LocalSubDebug", "reApplyLocalSubtitle: parsed URI=$uri, calling applyLocalSubtitle")
            applyLocalSubtitle(uri)
        } catch (e: Exception) {
            android.util.Log.e("LocalSubDebug", "reApplyLocalSubtitle: EXCEPTION - ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun applyLocalSubtitle(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
            val label = "Local Subtitle"
            Logger.log("applyLocalSubtitle: uri=$uri")
            val contentResolver = applicationContext.contentResolver

            val rawMime = contentResolver.getType(uri)
            val uriStr = uri.toString().lowercase()
            val finalMimeType = when {
                rawMime == "application/octet-stream" || rawMime == null -> when {
                    uriStr.contains(".vtt") -> MimeTypes.TEXT_VTT
                    uriStr.contains(".ssa") || uriStr.contains(".ass") -> MimeTypes.TEXT_SSA
                    uriStr.contains(".ttml") || uriStr.contains(".xml") -> MimeTypes.APPLICATION_TTML
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
                else -> rawMime
            }
            android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: uri=$uri mime=$finalMimeType")

            val subtitleBytes = try {
                if (uri.scheme == "file") {
                    java.io.File(uri.path!!).readBytes()
                } else {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
            } catch (e: Exception) {
                android.util.Log.e("LocalSubDebug", "applyLocalSubtitle: failed to read URI $uri", e)
                null
            }

            if (subtitleBytes == null) {
                android.util.Log.e("LocalSubDebug", "applyLocalSubtitle: subtitleBytes null, aborting")
                withContext(Dispatchers.Main) {
                    snackString("Failed to read subtitle file")
                }
                return@launch
            }

            val ext = when (finalMimeType) {
                MimeTypes.TEXT_VTT -> "vtt"
                MimeTypes.TEXT_SSA -> "ass"
                MimeTypes.APPLICATION_TTML -> "ttml"
                else -> "srt"
            }
            val cacheFile = File(cacheDir, "local_sub_${uri.toString().hashCode()}.$ext")

            if (finalMimeType == MimeTypes.TEXT_SSA) {
                val cleaned = stripAssPositioning(subtitleBytes.toString(Charsets.UTF_8))
                cacheFile.writeText(cleaned)
            } else {
                cacheFile.writeBytes(subtitleBytes)
            }

            val formatForParse = when (finalMimeType) {
                MimeTypes.TEXT_VTT -> "VTT"
                MimeTypes.TEXT_SSA -> "ASS"
                MimeTypes.APPLICATION_SUBRIP -> "SRT"
                else -> null
            }
            if (formatForParse != null) {
                val contentString = subtitleBytes.toString(Charsets.UTF_8)
                val parsedCues = parseSubtitleContent(contentString, formatForParse)
                val preparedCues = prepareParsedCues(parsedCues)

            withContext(Dispatchers.Main) {
                storePreparedCues(preparedCues)
                Logger.log(
                    "applyLocalSubtitle: cues=${parsedCues.size} fmt=$formatForParse"
                )
                beginSubtitleAutoSync(parsedCues, formatForParse)
            }
            } else {

            withContext(Dispatchers.Main) {
                storePreparedCues(prepareParsedCues(emptyList()))
                beginSubtitleAutoSync(emptyList(), "unknown")
            }
            }

            withContext(Dispatchers.Main) {
                val finalSubUri = android.net.Uri.fromFile(cacheFile)
                android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: cacheFile=$cacheFile mime=$finalMimeType")

                val stableId = "local_sub_${uri.toString().hashCode()}"

                val currentMediaItem = exoPlayer.currentMediaItem
                android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: currentMediaItem=${currentMediaItem?.mediaId ?: "NULL"}, playerState=${exoPlayer.playbackState}")
                if (currentMediaItem != null) {
                    val existingSubtitles = currentMediaItem.localConfiguration
                        ?.subtitleConfigurations?.toMutableList() ?: mutableListOf()
                    android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: existingSubtitles ids=${existingSubtitles.map { it.id }}")

                    val alreadyAdded = existingSubtitles.any { it.id == stableId }
                    android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: alreadyAdded=$alreadyAdded")
                    if (alreadyAdded) {
                        android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: already present, pendingLabel + selectNow")
                        pendingSubtitleLabel = label
                        selectSubtitleTrack("", label)
                    } else {
                        val subConfig = MediaItem.SubtitleConfiguration.Builder(finalSubUri)
                            .setMimeType(finalMimeType)
                            .setLanguage("und")
                            .setLabel(label)
                            .setId(stableId)
                            .build()

                        existingSubtitles.add(subConfig)

                        val mediaId = media.id
                        val episodeId = media.anime?.selectedEpisode ?: "1"
                        val newLocalSub = Subtitle(
                            language = "[Local] ${uri.lastPathSegment ?: "Custom"}",
                            url = uri.toString()
                        )
                        model.saveLocalSubtitle("$mediaId-$episodeId", newLocalSub)
                        PrefManager.setCustomVal("subLang_$mediaId", newLocalSub.language)

                        val newMediaItem = currentMediaItem.buildUpon()
                            .setSubtitleConfigurations(existingSubtitles)
                            .build()

                        Logger.log("applyLocalSubtitle: pendingLabel='$label', setMediaItem+prepare, uri=$finalSubUri")
                        android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: pendingLabel='$label', setMediaItem+prepare, uri=$finalSubUri")
                        pendingSubtitleLabel = label
                        val currentPos = exoPlayer.currentPosition
                        exoPlayer.setMediaItem(newMediaItem, currentPos)
                        exoPlayer.prepare()
                        android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: prepare() called")
                    }
                } else {
                    android.util.Log.e("LocalSubDebug", "applyLocalSubtitle: currentMediaItem NULL, aborting")
                }
            }
            } catch (e: Exception) {
                android.util.Log.e("LocalSubDebug", "applyLocalSubtitle: EXCEPTION ${e.message}", e)
            withContext(Dispatchers.Main) {
                snackString("Failed to load subtitle: ${e.message}")
            }
            }
        }
    }

    private fun stripAssPositioning(assContent: String): String {
        android.util.Log.d("ExoplayerView", "stripAssPositioning: Stripping positioning from ASS subtitle")

        val lines = assContent.lines().toMutableList()
        var inEvents = false
        var inStyles = false
        val styleFormatMap = mutableMapOf<String, Int>()

        for (i in lines.indices) {
            val line = lines[i]
            val trimmedLine = line.trim()

            if (trimmedLine.equals("[Events]", ignoreCase = true)) {
                inEvents = true
                inStyles = false
                continue
            } else if (trimmedLine.equals("[V4+ Styles]", ignoreCase = true) ||
                       trimmedLine.equals("[V4 Styles]", ignoreCase = true)) {
                inStyles = true
                inEvents = false
                continue
            } else if (trimmedLine.startsWith("[") && trimmedLine.endsWith("]")) {
                inEvents = false
                inStyles = false
                continue
            }

            if (inStyles) {
                if (trimmedLine.startsWith("Format:", ignoreCase = true)) {

                    val parts = trimmedLine.substringAfter(":").split(",")
                    styleFormatMap.clear()
                    parts.forEachIndexed { index, name ->
                        styleFormatMap[name.trim().lowercase()] = index
                    }
                } else if (trimmedLine.startsWith("Style:", ignoreCase = true) && styleFormatMap.isNotEmpty()) {

                    val styleContent = trimmedLine.substringAfter("Style:")
                    val parts = styleContent.split(",").toMutableList()

                    val alignIdx = styleFormatMap["alignment"]
                    if (alignIdx != null && alignIdx < parts.size) {
                        parts[alignIdx] = "2"
                    }

                    val marginVIdx = styleFormatMap["marginv"]
                    if (marginVIdx != null && marginVIdx < parts.size) {
                        parts[marginVIdx] = "0"
                    }

                    lines[i] = "Style: ${parts.joinToString(",")}"
                }
            }

            if (inEvents && (trimmedLine.startsWith("Dialogue:", ignoreCase = true) ||
                             trimmedLine.startsWith("Comment:", ignoreCase = true))) {
                var modifiedLine = line

                modifiedLine = modifiedLine.replace(Regex("\\\\pos\\([^)]*\\)"), "")

                modifiedLine = modifiedLine.replace(Regex("\\\\move\\([^)]*\\)"), "")

                modifiedLine = modifiedLine.replace(Regex("\\\\an[1-9]"), "")

                modifiedLine = modifiedLine.replace(Regex("\\\\a[1-9]+"), "")

                modifiedLine = modifiedLine.replace(Regex("\\\\org\\([^)]*\\)"), "")

                lines[i] = modifiedLine
            }
        }

        val result = lines.joinToString("\n")
        android.util.Log.d("ExoplayerView", "stripAssPositioning: Done")
        return result
    }

    private fun clearAllTransientSubtitleCache() {
        model.clearAllFetchedSubtitles()
        model.clearAllLocalSubtitles()
        val dir = cacheDir
        
        
        val cutoff = java.lang.System.currentTimeMillis()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                dir.listFiles()?.forEach { file ->
                    if ((file.name.startsWith("online_subtitle_") || file.name.startsWith("local_sub_")) &&
                        file.lastModified() < cutoff
                    ) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ExoplayerView", "clearAllTransientSubtitleCache: error deleting files - ${e.message}")
            }
        }
    }

    fun applyOnlineSubtitle(
        subtitle: ani.sanin.connections.subtitles.StremioSub,
        headers: Map<String, String> = emptyMap(),
        baseUrls: List<String> = emptyList(),
    ) {
        android.util.Log.d("ExoplayerView", "=== applyOnlineSubtitle CALLED ===")
        android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: lang=${subtitle.lang}, url=${subtitle.url}")
        Logger.log("applyOnlineSubtitle: lang=${subtitle.lang}, url=${subtitle.url}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {

                val actualUrl = if (subtitle.url.startsWith(OpenSubtitles.URL_PREFIX)) {
                    val fileId = subtitle.url.removePrefix(OpenSubtitles.URL_PREFIX).toIntOrNull()
                    if (fileId != null) {
                        android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: Resolving OpenSubtitles fileId=$fileId")
                        OpenSubtitles.getDownloadUrl(fileId)
                    } else null
                } else {
                    subtitle.url
                }
                if (actualUrl == null) {
                    withContext(Dispatchers.Main) {
                        snackString("Failed to get OpenSubtitles download link")
                    }
                    return@launch
                }
                val resolvedUrl = resolveSubtitleUrl(
                    actualUrl,
                    *baseUrls.ifEmpty { listOfNotNull(video?.file?.url) }.toTypedArray()
                )
                android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: Downloading subtitle from $resolvedUrl")
                Logger.log("applyOnlineSubtitle: resolved url=$resolvedUrl")

                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder()
                    .url(resolvedUrl)
                    .apply {
                        defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                        headers.forEach { (k, v) -> addHeader(k, v) }
                    }
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Logger.log("applyOnlineSubtitle: download FAILED http=${response.code} for $resolvedUrl")
                    withContext(Dispatchers.Main) {
                        android.util.Log.e("ExoplayerView", "applyOnlineSubtitle: Download failed with code ${response.code}")
                        snackString("Failed to download subtitle: HTTP ${response.code}")
                    }
                    return@launch
                }

                val subtitleContent = response.body?.string()
                if (subtitleContent.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        android.util.Log.e("ExoplayerView", "applyOnlineSubtitle: Subtitle content is empty")
                        snackString("Subtitle file is empty")
                    }
                    return@launch
                }

                android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: Downloaded ${subtitleContent.length} bytes")
                Logger.log("applyOnlineSubtitle: downloaded ${subtitleContent.length} bytes")

                val detectedFormat = when {
                    subtitleContent.trimStart().startsWith("WEBVTT") -> "VTT"
                    subtitleContent.contains("[Script Info]") || subtitleContent.contains("\\[Events\\]") -> "ASS"
                    subtitleContent.contains("<tt ") || subtitleContent.contains("<tt>") -> "TTML"
                    else -> "SRT"
                }

                android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: Detected format: $detectedFormat")
                Logger.log("applyOnlineSubtitle: detected format=$detectedFormat")

                val cleanedContent = if (detectedFormat == "ASS") {
                    stripAssPositioning(subtitleContent)
                } else {
                    subtitleContent
                }

                val mimeType = when (detectedFormat) {
                    "VTT" -> MimeTypes.TEXT_VTT
                    "ASS" -> MimeTypes.TEXT_SSA
                    "TTML" -> MimeTypes.APPLICATION_TTML
                    else -> MimeTypes.APPLICATION_SUBRIP
                }

                val extension = when (detectedFormat) {
                    "VTT" -> "vtt"
                    "ASS" -> "ass"
                    "TTML" -> "ttml"
                    else -> "srt"
                }

                android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: Using MIME type: $mimeType, extension: $extension")

                val cacheDir = this@ExoplayerView.cacheDir
                val safeId = subtitle.id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "sub" }
                val subtitleFile = File(cacheDir, "online_subtitle_${safeId}.$extension")
                subtitleFile.writeText(cleanedContent)

                android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: Saved to ${subtitleFile.absolutePath}")

                val parsedCues = parseSubtitleContent(subtitleContent, detectedFormat)
                val preparedCues = prepareParsedCues(parsedCues)

                val label = "${subtitle.source}:${subtitle.lang}"
                Logger.log("applyOnlineSubtitle: saved ${subtitleFile.absolutePath}, parsed ${parsedCues.size} cues, label=$label, applying")

                withContext(Dispatchers.Main) {
                    storePreparedCues(preparedCues)
                    applySubtitleFromFile(subtitleFile, subtitle.lang, mimeType, label)
                    beginSubtitleAutoSync(parsedCues, detectedFormat)
                }

            } catch (e: Exception) {
                android.util.Log.e("ExoplayerView", "applyOnlineSubtitle: ERROR - ${e.message}", e)
                withContext(Dispatchers.Main) {
                    snackString("Failed to load subtitle: ${e.message}")
                }
            }
        }
    }

    private fun applySubtitleFromFile(file: File, lang: String, mimeType: String, label: String = "Online: $lang") {
        try {
            val subUri = android.net.Uri.fromFile(file)

            android.util.Log.d("ExoplayerView", "applySubtitleFromFile: URI=$subUri, MIME=$mimeType, label=$label")
            Logger.log("applySubtitleFromFile: file=${file.absolutePath} (${file.length()}B), lang=$lang, mime=$mimeType, label=$label")

            val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(mimeType)
                .setLanguage(lang)
                .setLabel(label)
                .setId(file.name)
                .build()

            val currentMediaItem = exoPlayer.currentMediaItem ?: return
            val existingSubtitles = currentMediaItem.localConfiguration?.subtitleConfigurations?.toMutableList() ?: mutableListOf()

            val alreadyExists = existingSubtitles.any { it.id == file.name }
            if (alreadyExists) {
                Logger.log("applySubtitleFromFile: config already in media item, pendingLabel=$label")
                android.util.Log.d("ExoplayerView", "applySubtitleFromFile: Subtitle already exists, selecting via pendingLabel")

                pendingSubtitleLabel = label

                selectSubtitleTrack(lang, label)
                return
            }

            existingSubtitles.add(subConfig)
            Logger.log("applySubtitleFromFile: added config, total=${existingSubtitles.size}, pendingLabel=$label")
            android.util.Log.d("ExoplayerView", "applySubtitleFromFile: Added subtitle, total: ${existingSubtitles.size}")

            val newMediaItem = currentMediaItem.buildUpon()
                .setSubtitleConfigurations(existingSubtitles)
                .build()

            pendingSubtitleLabel = label

            val currentPos = exoPlayer.currentPosition
            exoPlayer.setMediaItem(newMediaItem, currentPos)
            exoPlayer.prepare()

        } catch (e: Exception) {
            android.util.Log.e("ExoplayerView", "applySubtitleFromFile: ERROR - ${e.message}", e)
            snackString("Failed to apply subtitle: ${e.message}")
        }
    }

    private fun mapLanguageCode(isoCode: String): String = when (isoCode.lowercase()) {
        "eng" -> "english"
        "spa" -> "spanish"
        "fra" -> "french"
        "deu" -> "german"
        "ita" -> "italian"
        "por" -> "portuguese"
        "rus" -> "russian"
        "jpn" -> "japanese"
        "zho", "chi" -> "chinese"
        "ara" -> "arabic"
        "hin" -> "hindi"
        "kor" -> "korean"
        "pol" -> "polish"
        "tur" -> "turkish"
        "hun" -> "hungarian"
        "ron" -> "romanian"
        "ell" -> "greek"
        "cze" -> "czech"
        "swe" -> "swedish"
        "dan" -> "danish"
        "fin" -> "finnish"
        "nor" -> "norwegian"
        "nld" -> "dutch"
        "tha" -> "thai"
        "vie" -> "vietnamese"
        "ind" -> "indonesian"
        "ukr" -> "ukrainian"
        "heb" -> "hebrew"
        "bul" -> "bulgarian"
        "hrv" -> "croatian"
        "slk" -> "slovak"
        "slv" -> "slovenian"
        else -> isoCode
    }

    private fun resolveSubtitleUrl(subtitleUrl: String, vararg baseUrls: String): String {
        val subtitleUri = runCatching { URI(subtitleUrl) }.getOrElse {
            Logger.log("Failed to parse subtitle URL '$subtitleUrl': ${it.message}")
            return subtitleUrl
        }
        if (subtitleUri.isAbsolute) return subtitleUri.toString()

        baseUrls.forEach { baseUrl ->
            val resolved =
                runCatching {
                    if (baseUrl.isBlank()) null else URI(baseUrl).resolve(subtitleUri).takeIf { it.isAbsolute }?.toString()
                }.getOrNull()
            if (!resolved.isNullOrBlank()) return resolved
        }

        Logger.log("Failed to resolve relative subtitle URL '$subtitleUrl' with bases: ${baseUrls.joinToString()}")
        return subtitleUrl
    }

    private fun buildSubtitleId(index: Int, language: String, url: String): String {
        val normalizedLanguage = language.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")
        val normalizedUrlTail =
            runCatching { URI(url).path.substringAfterLast('/').ifBlank { "track" } }
                .getOrDefault("track")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "_")
        return "ext_sub_${index}_${normalizedLanguage}_${normalizedUrlTail}"
    }

    fun selectExistingSubByLabel(label: String) {
        Logger.log("selectExistingSubByLabel: label=$label")
        selectSubtitleTrack(label, label)
    }

    private fun selectSubtitleTrack(langCode: String, targetLabel: String? = null) {
        android.util.Log.d("ExoplayerView", "selectSubtitleTrack: Looking for lang=$langCode, targetLabel=$targetLabel")
        Logger.log("selectSubtitleTrack: lang=$langCode, targetLabel=$targetLabel")

        val mappedLang = mapLanguageCode(langCode)
        android.util.Log.d("ExoplayerView", "selectSubtitleTrack: Mapped '$langCode' to '$mappedLang'")

        try {
            val tracks = exoPlayer.currentTracks
            android.util.Log.d("ExoplayerView", "selectSubtitleTrack: Total track groups: ${tracks.groups.size}")

            for (groupIndex in 0 until tracks.groups.size) {
                val group = tracks.groups[groupIndex]

                if (group.type == TRACK_TYPE_TEXT) {
                    android.util.Log.d("ExoplayerView", "selectSubtitleTrack: Found TEXT group at index $groupIndex with ${group.length} tracks")
                    Logger.log("selectSubtitleTrack: TEXT group $groupIndex has ${group.length} tracks")

                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val trackLang = format.language?.lowercase() ?: ""
                        val trackLabel = format.label ?: ""
                        android.util.Log.d("ExoplayerView", "selectSubtitleTrack: Track $trackIndex - lang=$trackLang, label=$trackLabel")

                        if (targetLabel != null && trackLabel == targetLabel) {
                            android.util.Log.d("ExoplayerView", "selectSubtitleTrack: FOUND matching track by label! Selecting index $trackIndex")
                            Logger.log("selectSubtitleTrack: MATCH by label -> group $groupIndex track $trackIndex")
                            onSetTrackGroupOverride(group, TRACK_TYPE_TEXT, trackIndex)
                            snackString("Subtitle loaded: $trackLabel")
                            return
                        }

                        if (targetLabel == null && (trackLang == mappedLang || trackLang == langCode || trackLang.startsWith(langCode) || trackLang.startsWith(mappedLang))) {
                            android.util.Log.d("ExoplayerView", "selectSubtitleTrack: FOUND matching track by language! Selecting index $trackIndex")
                            Logger.log("selectSubtitleTrack: MATCH by language -> group $groupIndex track $trackIndex")
                            onSetTrackGroupOverride(group, TRACK_TYPE_TEXT, trackIndex)
                            snackString("Subtitle loaded: ${mappedLang.replaceFirstChar { it.uppercase() }}")
                            return
                        }
                    }
                }
            }
            android.util.Log.d("ExoplayerView", "selectSubtitleTrack: No matching track found for lang=$langCode, targetLabel=$targetLabel")
            Logger.log("selectSubtitleTrack: NO MATCH for lang=$langCode, targetLabel=$targetLabel")
        } catch (e: Exception) {
            android.util.Log.e("ExoplayerView", "selectSubtitleTrack: ERROR - ${e.message}", e)
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
        if (isInitialized && !isLiveStream()) {
            val pos = exoPlayer.currentPosition
            if (pos > 5000) {
                PrefManager.setCustomVal(
                    "${media.id}_${media.anime!!.selectedEpisode}",
                    pos,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.enable()
        hideSystemBars()
        if (isInitialized) {
            playerView.onResume()
            playerView.useController = true
        }
    }

    override fun onStop() {
        val shouldPausePlayback =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                !isInPictureInPictureMode
            } else {
                true
            }
        if (shouldPausePlayback && !isLiveStream()) {
            playerView.player?.pause()
        }

        if (shouldPausePlayback && !isChangingConfigurations) {
            clearAllTransientSubtitleCache()
        }
        super.onStop()
    }

    private var wasPlaying = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (PrefManager.getVal(PrefName.FocusPause) && !epChanging && !isLiveStream()) {
            if (isInitialized && !hasFocus) wasPlaying = exoPlayer.playWhenReady
            if (hasFocus) {
                if (isInitialized && wasPlaying) exoPlayer.play()
            } else {
                if (isInitialized) exoPlayer.pause()
            }
        }
        super.onWindowFocusChanged(hasFocus)
    }

    private fun schedulePauseOverlayTimer() {
        pauseMetadataTimer?.let { handler.removeCallbacks(it) }
        if (!isPlayerPlaying) {
            val timer = Runnable {
                if (!isPlayerPlaying && !isBuffering) {
                    pauseOverlay.visibility = View.VISIBLE
                    pauseOverlay.alpha = 0f
                    if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.PlayerOverlayAnimations)) {
                        pauseOverlay.animate().alpha(1f).setDuration(300).start()
                    } else {
                        pauseOverlay.alpha = 1f
                    }
                    playerView.findViewById<View>(R.id.exo_controller)?.visibility = View.GONE
                    pauseOverlay.requestFocus()
                }
            }
            pauseMetadataTimer = timer
            handler.postDelayed(timer, 4500L)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isBuffering) {
            isPlayerPlaying = isPlaying
            playerView.keepScreenOn = isPlaying
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.AnimatedVectorDrawables)) (exoPlay.drawable as Animatable?)?.start()
            if (!this.isDestroyed) {
                Glide
                    .with(this)
                    .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                    .into(exoPlay)
            }
            if (!isPlaying) {
                schedulePauseOverlayTimer()
            } else {
                pauseMetadataTimer?.let { handler.removeCallbacks(it) }
                pauseMetadataTimer = null
                if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.PlayerOverlayAnimations)) {
                    pauseOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                        pauseOverlay.visibility = View.GONE
                    }.start()
                } else {
                    pauseOverlay.alpha = 0f
                    pauseOverlay.visibility = View.GONE
                }
            }
        }

        val incognito = PrefManager.getVal<Boolean>(PrefName.Incognito)
        val simklProgressEnabled = PrefManager.getCustomVal("${media.id}_save_progress", true)
        if (!isLiveStream() && Simkl.token != null && media.id < 0 && !incognito && simklProgressEnabled) {

            val playerCurrentPosition = if (::exoPlayer.isInitialized) exoPlayer.currentPosition else 0L
            val playerDuration = if (::exoPlayer.isInitialized) exoPlayer.duration else 0L
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val epStr = media.anime?.selectedEpisode
                val parts = epStr?.split("E") ?: emptyList()
                val season = if (parts.size == 2) parts[0].removePrefix("S").toIntOrNull() else null
                val episode = epStr?.substringAfter("E")?.toIntOrNull()

                val session = TmdbStreamResolver.sessionFor(media.id) ?: return@launch
                val d = session.detail
                val title = d.displayTitle
                val year = d.year.toIntOrNull()
                val tmdbId = d.id
                val imdbId = d.externalIds?.imdbId
                val type = if (session.mediaType == "tv") "tv" else "movie"
                if (isPlaying) {
                    Logger.log("Simkl: scrobbleStart type=$type title=$title s=$season e=$episode")
                    Simkl.scrobbleStart(type, title, year, tmdbId, imdbId, season, episode)
                    if (!simklAddedToWatchlist) {
                        val currentStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            Simkl.getMediaStatus(type, tmdbId = tmdbId, imdbId = imdbId)
                        }
                        if (currentStatus != "completed" && currentStatus != "dropped") {
                            simklAddedToWatchlist = true
                            Logger.log("Simkl: addToWatchlist type=$type tmdb=$tmdbId (was $currentStatus)")
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                Simkl.addToWatchlist(type, tmdbId, imdbId)
                            }
                        } else {
                            simklAddedToWatchlist = true
                            Logger.log("Simkl: skipping addToWatchlist, already $currentStatus")
                        }
                    }
                } else {
                    Logger.log("Simkl: scrobbleStop type=$type title=$title s=$season e=$episode")
                    Simkl.scrobbleStop(type, title, year, tmdbId, imdbId, season, episode)
                    val pos = playerCurrentPosition
                    val dur = playerDuration
                    if (dur > 0 && pos.toFloat() / dur.toFloat() > PrefManager.getVal<Float>(PrefName.WatchPercentage)) {
                        Logger.log("Simkl: addToHistory on scrobbleStop (pos=$pos/dur=$dur)")
                        Simkl.addToHistory(type, title, year, tmdbId, imdbId, season, episode)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Refresh.all()
                        }
                    }
                }
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        if (isLiveStream()) {
            val aheadOfLive = LiveHelper.getLiveManager(exoPlayer)
                ?.getTimeAheadOfLive(newPosition.positionMs)
            Logger.log(
                Log.INFO,
                "Exo LIVE: discontinuity reason=$reason old=${oldPosition.positionMs} " +
                    "new=${newPosition.positionMs} aheadOfLive=${aheadOfLive ?: -1L} " +
                    "duration=${exoPlayer.duration}"
            )
            return
        }

        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            if (!userPaused) {
                exoPlayer.play()
            }
        }
    }

    override fun onRenderedFirstFrame() {
        super.onRenderedFirstFrame()

        maybeLoadTimeStamps("firstFrame")

        updateAudioLanguageInfo()

        if (!isLiveStream()) {
            PrefManager.setCustomVal(
                "${media.id}_${media.anime!!.selectedEpisode}_max",
                exoPlayer.duration,
            )
        }

        val format = exoPlayer.videoFormat
        if (format == null || format.height <= 0) {
            videoInfo.visibility = View.GONE
        } else {
            var height = format.height
            var width = format.width
            val rotation = format.rotationDegrees

            if (rotation == 90 || rotation == 270) {
                val temp = width
                width = height
                height = temp
            }

            aspectRatio = Rational(width, height)

            videoInfo.text = getString(R.string.video_quality, height)
            videoInfo.visibility = View.VISIBLE
        }

        if (!isLiveStream()) {
            if (exoPlayer.duration < playbackPosition) {
                exoPlayer.seekTo(0)
            }

            if (playbackPosition > exoPlayer.duration.toFloat() * 0.92) {
                playbackPosition = 0
                exoPlayer.seekTo(0)
            }
        }

    }

    private var preloading = false

    private fun updateProgress() {
        if (isLiveStream()) {
            return
        }
        if (isInitialized) {
            val duration = exoPlayer.duration
            if (duration > 0L &&
                exoPlayer.currentPosition.toFloat() / duration >
                    PrefManager.getVal<Float>(
                        PrefName.WatchPercentage,
                    )
            ) {
                preloading = true
                nextEpisode(false) { i ->
                    val ep = episodes[episodeArr[currentEpisodeIndex + i]] ?: return@nextEpisode
                    val selected = media.selected ?: return@nextEpisode
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (TmdbStreamResolver.sessionFor(media.id) != null) {

                            ani.sanin.cloudstream.TmdbStreamResolver.populateSyntheticEpisode(
                                this@ExoplayerView, media, ep
                            )
                        } else if (media.selected!!.server != null) {
                            model.loadEpisodeSingleVideo(ep, selected, false)
                        } else {
                            model.loadEpisodeVideos(ep, selected.sourceIndex, false)
                        }
                    }
                }
            }
        }
        if (!preloading) {
            handler.postDelayed({
                updateProgress()
            }, 2500)
        }
    }

    private fun railEpisodes(): Map<String, Episode> =
        if (currentRailSeason == null) episodes
        else episodes.filterValues { it.extra?.get("season") == currentRailSeason }

    private fun buildRailSeasonChips() {
        railSeasonChips.removeAllViews()
        if (seasonArr.size <= 1) {
            railSeasonScroll.isVisible = false
            return
        }
        railSeasonScroll.isVisible = true
        seasonArr.forEach { season ->
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.item_tmdb_chip, railSeasonChips, false) as Chip
            chip.text = getString(R.string.tmdb_watch_season_chip, season)
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            chip.tag = season
            chip.isChecked = season == currentRailSeason
            chip.setOnClickListener {
                currentRailSeason = season
                episodeDrawerAdapter?.showSeason(railEpisodes())
                val currentKey = episodes.getEpisodeKey(media.anime?.selectedEpisode)
                val pos = railEpisodes().keys.indexOfFirst { it == currentKey }
                if (pos >= 0) episodeDrawerList.scrollToPosition(pos)
            }
            FocusEffectUtil.applyFocusListener(chip)
            railSeasonChips.addView(chip)
        }
    }

    private var currentTimeStamp: AniSkip.Stamp? = null
    private var skippedTimeStamps: MutableList<AniSkip.Stamp> = mutableListOf()
    private var lastLoggedStampId: String? = null

    private fun maybeLoadTimeStamps(source: String) {
        if (!isInitialized || isTimeStampsLoaded || timeStampsLoading) return
        if (!PrefManager.getVal<Boolean>(PrefName.TimeStampsEnabled)) return

        val now = java.lang.System.currentTimeMillis()
        val sinceLastAttempt = now - lastTimeStampAttempt
        if (sinceLastAttempt < 10_000L) return
        lastTimeStampAttempt = now
        timeStampsLoading = true
        val dur = exoPlayer.duration
        val extTimestamps =
            ((extractor?.server?.video?.timestamps ?: emptyList()) +
                (extractor?.timestamps ?: emptyList())).distinct()
        val isTmdbMode = TmdbStreamResolver.sessionFor(media.id) != null
        val episodeNum = media.anime?.selectedEpisode?.trim()?.toIntOrNull()
        val episodeKey = if (isTmdbMode) {
            media.anime?.selectedEpisode?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val tmdbSession = if (isTmdbMode) TmdbStreamResolver.sessionFor(media.id) else null
        val isTmdbMovie = isTmdbMovieMode()
        val serverVideo = extractor?.server?.video
        val extServerCount = serverVideo?.timestamps?.size ?: -1
        val extCount = extractor?.timestamps?.size ?: -1
        Logger.log(
            "Player: timestamps attempt for ep '${media.anime?.selectedEpisode}' " +
                "source=$source episodeNum=$episodeNum episodeKey=$episodeKey " +
                "malId=${media.idMAL} durMs=$dur durSec=${dur / 1000} " +
                "tmdbMode=$isTmdbMode tmdbMovie=$isTmdbMovie imdbId=${media.idIMDB} tmdbId=${tmdbSession?.mediaId} " +
                "isInitialized=$isInitialized isTimeStampsLoaded=$isTimeStampsLoaded " +
                "timeStampsLoading=$timeStampsLoading " +
                "timeStampsEnabled=${PrefManager.getVal<Boolean>(PrefName.TimeStampsEnabled)} " +
                "showButton=${PrefManager.getVal<Boolean>(PrefName.ShowTimeStampButton)} " +
                "autoHide=${PrefManager.getVal<Boolean>(PrefName.AutoHideTimeStamps)} " +
                "autoSkipOpEd=${PrefManager.getVal<Boolean>(PrefName.AutoSkipOPED)} " +
                "proxy=${PrefManager.getVal<Boolean>(PrefName.UseProxyForTimeStamps)} " +
                "extractorNull=${extractor == null} extractor=${extractor?.javaClass?.simpleName} " +
                "server=${extractor?.server?.name} serverVideoNull=${serverVideo == null} " +
                "extServerTimestamps=$extServerCount extractorTimestamps=$extCount " +
                "currentTimeStamps=${model.timeStamps.value?.size ?: -1} " +
                "playerState=${exoPlayer.playbackState} posMs=${exoPlayer.currentPosition} " +
                "sinceLastAttemptMs=$sinceLastAttempt"
        )
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                model.loadTimeStamps(
                    media.idMAL,
                    episodeNum,
                    dur / 1000,
                    PrefManager.getVal<Boolean>(PrefName.UseProxyForTimeStamps),
                    extTimestamps,
                    episodeKey = episodeKey,
                    imdbId = media.idIMDB,
                    tmdbId = tmdbSession?.mediaId,
                    isMovie = isTmdbMovie,
                    tmdbMode = isTmdbMode
                )
                Logger.log(
                    "Player: timestamps attempt finished for ep '${media.anime?.selectedEpisode}' " +
                        "episodeNum=$episodeNum malId=${media.idMAL} durMs=$dur " +
                        "extServerTimestamps=$extServerCount extractorTimestamps=$extCount " +
                        "extTotal=${extTimestamps.size} " +
                        "result=${model.timeStamps.value?.size ?: "null"} " +
                        "loaded=${model.timeStamps.value != null}"
                )
            } finally {
                timeStampsLoading = false
            }
        }
    }

    private fun updateTimeStamp() {
        if (isLiveStream()) {
            currentTimeStamp = null
            lastLoggedStampId = null
            timeStampText.text = ""
            exoSkipOpEd.visibility = View.GONE
            (exoSkipOpEd.parent as? ViewGroup)?.requestLayout()
            hideSkipTimestampButton()
            updateTimelineUi()
            handler.postDelayed({
                updateTimeStamp()
            }, 500)
            return
        }

        maybeLoadTimeStamps("tick")
        if (isInitialized) {
            val playerCurrentTime = exoPlayer.currentPosition / 1000.0
            currentTimeStamp =
                model.timeStamps.value?.find { timestamp ->
                    timestamp.interval.startTime <= playerCurrentTime &&
                            playerCurrentTime < (timestamp.interval.endTime - 1)
                }

            val new = currentTimeStamp
            if (new?.skipId != lastLoggedStampId) {
                lastLoggedStampId = new?.skipId
                Logger.log(
                    "Player: stamp active=${new?.skipType} id=${new?.skipId} " +
                        "start=${new?.interval?.startTime}s end=${new?.interval?.endTime}s " +
                        "pos=${playerCurrentTime}s timeStamps=${model.timeStamps.value?.size}"
                )
            }
            timeStampText.text =
                if (new != null) {
                    fun disappearSkip() {
                        functionstarted = true
                        Logger.log(
                            "Player: skip button SHOWN (auto-hide) type=${new.skipType} " +
                                "at ${playerCurrentTime}s"
                        )
                        showSkipTimestampButton()
                        skipTimeText.text = new.skipType.getType()
                        skipTimeButton.setOnClickListener {
                            seekToMs((new.interval.endTime * 1000).toLong())
                        }
                        var timer: CountDownTimer? = null

                        fun cancelTimer() {
                            timer?.cancel()
                            timer = null
                            return
                        }
                        timer =
                            object : CountDownTimer(5000, 1000) {
                                override fun onTick(millisUntilFinished: Long) {
                                    if (new == null) {
                                        hideSkipTimestampButton()
                                        disappeared = false
                                        functionstarted = false
                                        cancelTimer()
                                    }
                                }

                                override fun onFinish() {
                                    hideSkipTimestampButton()
                                    disappeared = true
                                    functionstarted = false
                                    cancelTimer()
                                }
                            }
                        timer?.start()
                    }
                    if (PrefManager.getVal(PrefName.ShowTimeStampButton)) {
                        if (!functionstarted && !disappeared && PrefManager.getVal(PrefName.AutoHideTimeStamps)) {
                            disappearSkip()
                        } else if (!PrefManager.getVal<Boolean>(PrefName.AutoHideTimeStamps)) {
                            Logger.log(
                                "Player: skip button SHOWN (persistent) type=${new.skipType} " +
                                    "at ${playerCurrentTime}s"
                            )
                            showSkipTimestampButton()
                            skipTimeText.text = new.skipType.getType()
                            skipTimeButton.setOnClickListener {
                                seekToMs((new.interval.endTime * 1000).toLong())
                            }
                        }
                    }
                    if (PrefManager.getVal(PrefName.AutoSkipOPED) &&
                        (new.skipType == "op" || new.skipType == "ed") &&
                        !skippedTimeStamps.contains(new)
                    ) {
                        Logger.log(
                            "Player: AUTO-SKIP op/ed type=${new.skipType} " +
                                "seek=${new.interval.endTime}s at ${playerCurrentTime}s"
                        )
                        seekToMs((new.interval.endTime * 1000).toLong())
                        skippedTimeStamps.add(new)
                    }
                    if (PrefManager.getVal(PrefName.AutoSkipRecap) &&
                        new.skipType == "recap" &&
                        !skippedTimeStamps.contains(
                            new,
                        )
                    ) {
                        Logger.log(
                            "Player: AUTO-SKIP recap type=${new.skipType} " +
                                "seek=${new.interval.endTime}s at ${playerCurrentTime}s"
                        )
                        seekToMs((new.interval.endTime * 1000).toLong())
                        skippedTimeStamps.add(new)
                    }
                    new.skipType.getType()
                } else {
                    if (lastLoggedStampId != null) {
                        Logger.log("Player: stamp ended at ${playerCurrentTime}s")
                        lastLoggedStampId = null
                    }
                    disappeared = false
                    functionstarted = false
                    hideSkipTimestampButton()
                    ""
                }
        }

        val forceSeekbarUpdate = PrefManager.getVal<Boolean>(PrefName.AlwaysUpdateSeekbar)
        if (::playerView.isInitialized &&
            (playerView.isControllerFullyVisible || forceSeekbarUpdate)
        ) {
            updateTimelineUi()
        }

        handler.postDelayed({
            updateTimeStamp()
        }, 500)
    }

    private fun showSkipTimestampButton() {
        skipTimeButton.visibility = View.VISIBLE
        exoSkip.visibility = View.GONE
        exoRotate.nextFocusUpId = R.id.exo_skip_timestamp
        exoScreen.nextFocusUpId = R.id.exo_skip_timestamp
        playerView.findViewById<View>(R.id.exo_ep_sel_btn)?.nextFocusUpId =
            R.id.exo_skip_timestamp
    }

    private fun hideSkipTimestampButton() {
        skipTimeButton.visibility = View.GONE
        exoSkip.isVisible = PrefManager.getVal<Int>(PrefName.SkipTime) > 0
        if (skipTimeButton.hasFocus()) {
            if (exoSkip.visibility == View.VISIBLE) {
                exoSkip.requestFocus()
            } else {
                exoPlay.requestFocus()
            }
        }
        exoRotate.nextFocusUpId = R.id.exo_skip
        exoScreen.nextFocusUpId = R.id.exo_skip
        playerView.findViewById<View>(R.id.exo_ep_sel_btn)?.nextFocusUpId =
            if (exoSkip.visibility == View.VISIBLE) R.id.exo_skip else androidx.media3.ui.R.id.exo_progress
    }

    fun onSetTrackGroupOverride(
        trackGroup: Tracks.Group,
        type: @C.TrackType Int,
        index: Int = 0,
    ) {
        val isDisabled = trackGroup.getTrackFormat(0).language == "none"
        Logger.log("onSetTrackGroupOverride: type=$type index=$index isDisabled=$isDisabled")
        val paramsBuilder = exoPlayer.trackSelectionParameters.buildUpon()
        if (type == TRACK_TYPE_TEXT) {

            paramsBuilder.setTrackTypeDisabled(TRACK_TYPE_TEXT, isDisabled)
        }
        paramsBuilder.setOverrideForType(
            TrackSelectionOverride(trackGroup.mediaTrackGroup, index),
        )
        exoPlayer.trackSelectionParameters = paramsBuilder.build()
        if (type == TRACK_TYPE_TEXT) {
            setupSubFormatting(playerView)
            applySubtitleStyles(customSubtitleView)
            applyTopSubtitleStyles(customSubtitleTopView)
        }
        playerView.subtitleView?.alpha =
            when (isDisabled) {
                false -> 1f
                true -> 0f
            }
    }

    private val dummyTrack =
        Tracks.Group(
            TrackGroup("Dummy Track", Format.Builder().apply { setLanguage("none") }.build()),
            true,
            intArrayOf(1),
            booleanArrayOf(false),
        )

    fun playerCurrentTracks(): Tracks = if (isInitialized) exoPlayer.currentTracks else Tracks.EMPTY

    fun closeTracksRail() {
        playerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        playerView.isFocusable = true
        trackRailController?.close()
        if (!tracksHidden) {
            if (playerTracksBtn?.requestFocus() != true) exoPlay.requestFocus()
        } else {
            exoPlay.requestFocus()
        }
    }

    fun subtitleRailHasExtSubtitles(): Boolean = hasExtSubtitles

    fun subtitleRailEmbeddedTracks(): List<Tracks.Group> = embeddedSubTracks

    fun subtitleRailDummyTrack(): Tracks.Group = dummyTrack

    private fun updateTracksButton(tracks: Tracks) {
        val videoCount = tracks.groups.sumOf { group ->
            if (group.type != TRACK_TYPE_VIDEO) return@sumOf 0
            (0 until group.length).count { i ->
                val f = group.getTrackFormat(i)
                group.isTrackSupported(i, true) &&
                    f.height != Format.NO_VALUE && f.height > 0
            }
        }
        val audioCount = tracks.groups.sumOf { group ->
            if (group.type != TRACK_TYPE_AUDIO) return@sumOf 0
            (0 until group.length).count { i ->
                group.isTrackSupported(i, true)
            }
        }
        val show = videoCount > 1 || audioCount > 1
        tracksHidden = !show
        val btn = playerTracksBtn ?: return
        btn.isVisible = show
        btn.isFocusable = show

        (btn.parent as? ViewGroup)?.requestLayout()

        val skipUpId = if (show) R.id.exo_tracks else R.id.exo_settings
        playerView.findViewById<View>(R.id.exo_skip)?.nextFocusUpId = skipUpId
        playerView.findViewById<View>(R.id.exo_skip_timestamp)?.nextFocusUpId = skipUpId
        val epBtn = playerView.findViewById<View>(R.id.exo_ep_sel_btn)
        if (show) {
            epBtn.nextFocusRightId = R.id.exo_tracks
        } else {
            epBtn.nextFocusRightId = View.NO_ID
            if (btn.hasFocus()) {
                epBtn.requestFocus()
            }
        }
    }

    fun setSubtitlesEnabled(enabled: Boolean) {
        PrefManager.setVal(PrefName.Subtitles, enabled)
        if (!isInitialized) return
        if (!hasExtSubtitles) {
            if (enabled) {
                exoPlayer.currentTracks.groups.forEach { group ->
                    if (group.type == TRACK_TYPE_TEXT) {
                        onSetTrackGroupOverride(group, TRACK_TYPE_TEXT)
                    }
                }
            } else {
                onSetTrackGroupOverride(dummyTrack, TRACK_TYPE_TEXT, 0)
            }
        } else {
            exoPlayer.trackSelectionParameters =
                exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(TRACK_TYPE_TEXT, !enabled)
                    .build()
        }
        if (!enabled) {
            cancelAutoSyncWatcher()
            speechRecorder.setEnabled(false)
            autoSyncCues = emptyList()
            autoSyncCueKey = null
            renderCustomSubtitle("", "")
        }
        setupSubFormatting(playerView)
        applySubtitleStyles(customSubtitleView)
        applyTopSubtitleStyles(customSubtitleTopView)
    }

    override fun onTracksChanged(tracks: Tracks) {

        val userLabel = pendingSubtitleLabel
        val pendingLabel = userLabel ?: initialSubtitleLabel
        android.util.Log.d("LocalSubDebug", "onTracksChanged: pendingLabel=$pendingLabel, totalGroups=${tracks.groups.size}")
        Logger.log("onTracksChanged: pendingLabel=$pendingLabel, totalGroups=${tracks.groups.size}")
        if (pendingLabel != null) {
            var matched = false
            tracks.groups.forEachIndexed { groupIndex, group ->
                android.util.Log.d("LocalSubDebug", "onTracksChanged: group[$groupIndex] type=${group.type}, length=${group.length}")
                if (group.type == TRACK_TYPE_TEXT) {
                    for (trackIndex in 0 until group.length) {
                        val trackLabel = group.getTrackFormat(trackIndex).label
                        android.util.Log.d("LocalSubDebug", "onTracksChanged: TEXT track[$trackIndex] label='$trackLabel', isSupported=${group.isTrackSupported(trackIndex, true)}")
                        if (trackLabel == pendingLabel) {
                            android.util.Log.d("LocalSubDebug", "onTracksChanged: MATCH FOUND for '$pendingLabel' at group=$groupIndex track=$trackIndex, selecting")
                            Logger.log("onTracksChanged: MATCH '$pendingLabel' -> group $groupIndex track $trackIndex")
                            pendingSubtitleLabel = null
                            initialSubtitleLabel = null
                            matched = true
                            onSetTrackGroupOverride(group, TRACK_TYPE_TEXT, trackIndex)
                            if (userLabel != null) snackString("Subtitle loaded: $pendingLabel")
                            break
                        }
                    }
                }
                if (matched) return@forEachIndexed
            }
            if (!matched) {
                android.util.Log.w("LocalSubDebug", "onTracksChanged: NO MATCH found for '$pendingLabel' — will retry on next onTracksChanged")
                Logger.log("onTracksChanged: NO MATCH for '$pendingLabel' (retrying on next onTracksChanged)")
            }
        }

        val audioTracks: ArrayList<Tracks.Group> = arrayListOf()
        val subTracks: ArrayList<Tracks.Group> = arrayListOf(dummyTrack)
        tracks.groups.forEach {
            println(
                "Track__: $it\nTrack__: ${it.length}\nTrack__: ${it.isSelected}\n" +
                        "Track__: ${it.type}\nTrack__: ${it.mediaTrackGroup.id}",
            )
            when (it.type) {
                TRACK_TYPE_AUDIO -> {
                    if (it.isSupported(true)) audioTracks.add(it)
                }

                TRACK_TYPE_TEXT -> {
                    if (!hasExtSubtitles) {
                        if (it.isSupported(true)) subTracks.add(it)
                        return@forEach
                    }
                }
            }
        }
        embeddedSubTracks = if (!hasExtSubtitles) {
            subTracks.filter { it.mediaTrackGroup.id != "Dummy Track" }
        } else {
            emptyList()
        }
        updateTracksButton(tracks)
        trackRailController?.rebuild()
        exoAudioTrack.isVisible = audioTracks.size > 1
        exoAudioTrack.setOnClickListener {
            Logger.log("Player: AUDIO TRACK pressed — ${audioTracks.size} tracks available")
            TrackGroupDialogFragment(this, audioTracks, TRACK_TYPE_AUDIO, audioLanguages)
                .show(supportFragmentManager, "dialog")
        }
        audioTrackGroups.clear()
        audioTrackGroups.addAll(audioTracks)
        if (audioTracks.size > 1) {
            val currentParams = exoPlayer.trackSelectionParameters
            val currentOverride = currentParams.overrides.values.firstOrNull()
            val currentIdx = audioTrackGroups.indexOfFirst { group ->
                currentOverride != null && group.mediaTrackGroup.id == currentOverride.mediaTrackGroup.id
            }
        }
        if (!hasExtSubtitles) {
            exoSubtitle.isVisible = subTracks.size > 1 || media.idIMDB != null
            (exoSubtitle.parent as? ViewGroup)?.requestLayout()
            exoSubtitle.setOnClickListener {
                subClick()
            }
        }
        updateAudioLanguageInfo()
    }

    private fun updateAudioLanguageInfo() {
        var language: String? = null
        exoPlayer.currentTracks.groups.forEach { group ->
            if (group.type != TRACK_TYPE_AUDIO) return@forEach
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    language = group.getTrackFormat(i).language
                }
            }
        }
        if (language == null) language = exoPlayer.audioFormat?.language

        val code = language?.trim()?.lowercase(Locale.ROOT)
        if (code.isNullOrEmpty() || code == "und" || code == "un" || code == "zxx") {
            audioLangInfo.visibility = View.GONE
            return
        }

        val display = Locale.forLanguageTag(code).displayName
            .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?: code.uppercase(Locale.ROOT)
        audioLangInfo.text = getString(R.string.video_language, display)
        audioLangInfo.visibility = View.VISIBLE
    }

    private val onChangeSettings =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { _: ActivityResult ->
            if (!hasExtSubtitles) {
                exoPlayer.currentTracks.groups.forEach { trackGroup ->
                    when (trackGroup.type) {
                        TRACK_TYPE_TEXT -> {
                            if (PrefManager.getVal(PrefName.Subtitles)) {
                                onSetTrackGroupOverride(trackGroup, TRACK_TYPE_TEXT)
                            } else {
                                onSetTrackGroupOverride(dummyTrack, TRACK_TYPE_TEXT)
                            }
                        }

                        else -> {}
                    }
                }
            }
            if (isInitialized) exoPlayer.play()
        }

    private fun isTmdbMovieMode(): Boolean =
        media.id < 0 &&
            TmdbStreamResolver.sessionFor(media.id)?.mediaType
                ?.equals("movie", ignoreCase = true) == true

    private fun isDeclaredLiveStream(): Boolean =
        isTmdbMovieMode() && TmdbStreamResolver.sessionFor(media.id)?.isLive == true

    private fun isLiveStream(): Boolean =
        isTmdbMovieMode() &&
            (
                isDeclaredLiveStream() ||
                    liveDetectedFromPlayer
                )

    private fun logLiveEvent(message: String, level: Int = Log.INFO) {
        if (!isLiveStream()) return
        val playerReady = ::exoPlayer.isInitialized && !exoPlayer.isReleased
        val position = if (playerReady) exoPlayer.currentPosition else playbackPosition
        val duration = if (playerReady) exoPlayer.duration else C.TIME_UNSET
        Logger.log(
            level,
            "Exo LIVE: $message (media=${media.id}, episode=${media.anime?.selectedEpisode ?: "?"}, " +
                "position=$position, duration=$duration, reconnects=$liveReconnectCount)"
        )
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    private fun registerLiveSupport() {
        if (!isTmdbMovieMode() || !::exoPlayer.isInitialized || exoPlayer.isReleased || liveSupportRegistered) {
            return
        }
        LiveHelper.registerPlayer(exoPlayer)
        liveSupportRegistered = true
        logLiveEvent("live support registered server=${media.selected?.server ?: "?"}")
    }

    private fun updateTimelineUi() {
        if (!::exoPlayer.isInitialized || exoPlayer.isReleased) {
            return
        }
        updateLiveOffsetText()
        if (exoPlayer.duration <= 0L) {
            return
        }
        try {
            val timeBar = playerView.findViewById<DefaultTimeBar>(
                androidx.media3.ui.R.id.exo_progress
            ) ?: return
            if (isLiveStream()) {

                val liveOffset = exoPlayer.currentLiveOffset
                val windowDuration = exoPlayer.duration
                if (liveOffset == C.TIME_UNSET || windowDuration <= 0L) {

                    return
                }
                timeBar.setDuration(windowDuration)
                timeBar.setPosition(
                    (windowDuration - liveOffset).coerceIn(0L, windowDuration)
                )
                timeBar.setBufferedPosition(windowDuration)
                return
            }
            timeBar.setDuration(exoPlayer.duration)
            timeBar.setPosition(exoPlayer.currentPosition)
            timeBar.setBufferedPosition(exoPlayer.bufferedPosition)
        } catch (_: Exception) {
        }
    }

    private fun updateLiveBadge() {
        val live = isLiveStream()
        playerView.findViewById<View>(R.id.exo_live_badge)?.visibility =
            if (live) View.VISIBLE else View.GONE
        playerView.findViewById<View>(R.id.exo_live_offset)?.visibility =
            if (live) View.VISIBLE else View.GONE
        listOf(
            androidx.media3.ui.R.id.exo_position,
            R.id.exo_time_sep,
            androidx.media3.ui.R.id.exo_duration,
            R.id.exo_dot_sep
        )
            .forEach { id ->
                playerView.findViewById<View>(id)?.visibility =
                    if (live) View.GONE else View.VISIBLE
            }
    }

    private fun updateLiveOffsetText() {
        val view = playerView.findViewById<TextView>(R.id.exo_live_offset) ?: return
        val offset = if (isLiveStream() && ::exoPlayer.isInitialized && !exoPlayer.isReleased) {
            exoPlayer.currentLiveOffset
        } else {
            C.TIME_UNSET
        }
        view.text = if (offset == C.TIME_UNSET) {
            ""
        } else {
            val totalSeconds = (offset / 1000L).coerceAtLeast(0L)
            String.format(Locale.US, "-%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
        }
    }

    private var liveRecoveryFailed = false

    private fun abortLiveRecovery(reason: String) {
        liveRecoveryFailed = true
        Logger.log(
            Log.ERROR,
            "Exo LIVE: giving up after $liveReconnectCount/$MAX_LIVE_RECONNECTS attempts " +
                "on '${media.anime?.selectedEpisode ?: "?"}' ($reason)"
        )
        toast("Live stream unavailable")
        if (::exoPlayer.isInitialized && !exoPlayer.isReleased) {
            exoPlayer.pause()
        }
    }

    private fun retryLiveRecovery(error: PlaybackException) {
        logLiveEvent(
            "recovery requested code=${error.errorCode} name=${error.errorCodeName} " +
                "cause=${error.cause?.javaClass?.simpleName ?: "none"}"
        )
        if (liveRecoveryFailed) {
            Logger.log(
                Log.WARN,
                "Exo LIVE: ignoring ${error.errorCodeName} — recovery already exhausted"
            )
            return
        }
        if (liveReconnectCount >= MAX_LIVE_RECONNECTS) {
            abortLiveRecovery(error.errorCodeName)
            return
        }

        liveReconnectCount++
        Logger.log(
            Log.WARN,
            "Exo LIVE: live-edge recovery $liveReconnectCount/$MAX_LIVE_RECONNECTS " +
                "(${error.errorCodeName})"
        )
        toast("Reconnecting… ($liveReconnectCount/$MAX_LIVE_RECONNECTS)")

        if (::exoPlayer.isInitialized && exoPlayer.isReleased.not()) {
            exoPlayer.seekToDefaultPosition()
            exoPlayer.prepare()
            exoPlayer.play()
        } else {
            abortLiveRecovery("player unavailable")
        }
    }

    private fun retryOpaqueSource(format: VideoType, errorName: String): Boolean {
        val current = video ?: return false
        if (current.format != VideoType.CONTAINER || current.drm != null) return false
        if (extractor?.audioTracks?.isNotEmpty() == true) return false
        val url = current.file.url
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (!::assMediaSourceFactory.isInitialized) return false
        if (!::exoPlayer.isInitialized || exoPlayer.isReleased) return false
        val mime = when (format) {
            VideoType.M3U8 -> MimeTypes.APPLICATION_M3U8
            VideoType.DASH -> MimeTypes.APPLICATION_MPD
            else -> return false
        }
        val item = mediaItem.buildUpon().setMimeType(mime).build()
        val source = assMediaSourceFactory.createMediaSource(item)
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        Logger.log(
            Log.WARN,
            "Player: $errorName on opaque container — retrying as ${format.name.lowercase()} " +
                "url=${url.take(140)}"
        )
        exoPlayer.setMediaSource(source, position)
        exoPlayer.prepare()
        exoPlayer.play()
        return true
    }

    override fun onPlayerError(error: PlaybackException) {
        val epLabel = media.anime?.selectedEpisode ?: "?"
        if (isLiveStream()) {
            Logger.log(
                Log.ERROR,
                "Exo LIVE: player error code=${error.errorCode} name=${error.errorCodeName} " +
                    "cause=${error.cause?.javaClass?.simpleName ?: "none"}"
            )
        }

        if (error.errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED) {
            Logger.log(Log.WARN, "Player: DRM license failed (${error.errorCodeName}) on '$epLabel' — no retry without DRM (Zangetsu approach)")
        }

        if (isLiveStream() &&
            (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                error.cause is HlsPlaylistTracker.PlaylistStuckException)
        ) {
            retryLiveRecovery(error)
            return
        }

        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                -> {
                if (isLiveStream() && !liveRecoveryFailed) {
                    if (liveReconnectCount < MAX_LIVE_RECONNECTS) {
                        liveReconnectCount++
                        Logger.log(Log.WARN, "Player: live reconnect $liveReconnectCount/$MAX_LIVE_RECONNECTS " +
                            "on ep '$epLabel' (${error.errorCodeName}): ${error.message}")
                        toast("Reconnecting… ($liveReconnectCount/$MAX_LIVE_RECONNECTS)")

                        isPlayerPlaying = true
                        silentLiveReconnect()
                    } else {
                        abortLiveRecovery(error.errorCodeName)
                    }
                } else if (isLiveStream()) {
                    Logger.log(Log.WARN, "Player: ignoring ${error.errorCodeName} on ep '$epLabel' — recovery exhausted")
                } else {
                    Logger.log(Log.ERROR, "Player: source exception (${error.errorCode}) on ep '$epLabel': ${error.message}")
                    toast("Source Exception : ${error.message}")
                    isPlayerPlaying = true
                    sourceClick()
                }
            }

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
                -> {
                if (playerErrorRetryCount < MAX_PLAYER_ERROR_RETRIES) {
                    playerErrorRetryCount++
                    Logger.log(
                        Log.WARN,
                        "Player: retry ${playerErrorRetryCount}/$MAX_PLAYER_ERROR_RETRIES " +
                            "on ep '$epLabel' (${error.errorCode}) ${error.errorCodeName}: ${error.message}"
                    )
                    val savedPosition = exoPlayer.currentPosition.takeIf { it > 0 }
                        ?: playbackPosition
                    exoPlayer.setMediaSource(mediaSource, savedPosition)
                    exoPlayer.prepare()
                    exoPlayer.play()
                } else {
                    playerErrorRetryCount = 0
                    Logger.log(Log.ERROR, "Player: fatal error on ep '$epLabel' (${error.errorCode}) ${error.errorCodeName}: ${error.message}")
                    toast("Player Error ${error.errorCode} (${error.errorCodeName}) : ${error.message}")
                    Injekt.get<CrashlyticsInterface>().logException(error)
                }
            }

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                -> {
                val nextAdaptive = when (opaqueFormatRetryStage) {
                    0 -> VideoType.M3U8
                    1 -> VideoType.DASH
                    else -> null
                }
                if (nextAdaptive != null && retryOpaqueSource(nextAdaptive, error.errorCodeName)) {
                    opaqueFormatRetryStage++
                } else {
                    Logger.log(
                        Log.ERROR,
                        "Player: unhandled error on ep '$epLabel' (${error.errorCode}) " +
                            "${error.errorCodeName}: ${error.message}"
                    )
                    toast("Player Error ${error.errorCode} (${error.errorCodeName}) : ${error.message}")
                    Injekt.get<CrashlyticsInterface>().logException(error)
                }
            }

            else -> {
                Logger.log(Log.ERROR, "Player: unhandled error on ep '$epLabel' (${error.errorCode}) ${error.errorCodeName}: ${error.message}")
                toast("Player Error ${error.errorCode} (${error.errorCodeName}) : ${error.message}")
                Injekt.get<CrashlyticsInterface>().logException(error)
            }
        }
    }

    private var isBuffering = true
    private var userPaused = false
    private var interactionTimer: Timer? = null

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (::exoPlayer.isInitialized && !exoPlayer.isReleased && isTmdbMovieMode() && timeline.windowCount > 0) {
            val window = Timeline.Window()
            val windowIndex = exoPlayer.currentMediaItemIndex
                .takeIf { it in 0 until timeline.windowCount } ?: 0
            timeline.getWindow(windowIndex, window)

            val settledLiveWindow =
                window.isDynamic &&
                    (isDeclaredLiveStream() || window.durationMs != C.TIME_UNSET)
            if (settledLiveWindow) {
                val detected = !liveDetectedFromPlayer
                if (detected) {
                    liveDetectedFromPlayer = true
                    if (!userPaused) {
                        exoPlayer.seekToDefaultPosition()
                    }
                }
                registerLiveSupport()
                if (window.durationMs > 0L) {
                    LiveHelper.getLiveManager(exoPlayer)?.submitLivestreamChunk(
                        LivestreamChunk(window.durationMs)
                    )
                }
                updateLiveBadge()
                logLiveEvent(
                    "timeline updated reason=$reason window=$windowIndex dynamic=true " +
                        "durationMs=${window.durationMs} detected=$detected"
                )
            }
        }
        if (isLiveStream()) {
            updateTimelineUi()
        }
        super.onTimelineChanged(timeline, reason)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val epLabel = media.anime?.selectedEpisode ?: "?"
        if (isLiveStream()) {
            logLiveEvent(
                "playback state=${playbackStateName(playbackState)} " +
                    "playWhenReady=${exoPlayer.playWhenReady} buffered=${exoPlayer.bufferedPosition}"
            )
        }
        if (playbackState == ExoPlayer.STATE_READY) {
            Logger.log("Player: READY on ep '$epLabel' duration=${exoPlayer.duration} pos=${exoPlayer.currentPosition}")
            if (!userPaused) exoPlayer.play()
            if (episodeLength == 0f && !isLiveStream()) {
                episodeLength = exoPlayer.duration.toFloat()
            }

            maybeLoadTimeStamps("ready")
            updateLiveBadge()
            if (isLiveStream()) updateTimelineUi()
        }
        isBuffering = playbackState == Player.STATE_BUFFERING
        if (isBuffering) {
            Logger.log(Log.WARN, "Player: BUFFERING on ep '$epLabel' pos=${exoPlayer.currentPosition} (${exoPlayer.playbackState})")
            if (pauseOverlay.visibility == View.VISIBLE) pauseOverlay.visibility =
                View.GONE
        } else if (playbackState == Player.STATE_READY) {
            if (!exoPlayer.playWhenReady && !isLiveStream()) schedulePauseOverlayTimer()
        }
        if (playbackState == Player.STATE_ENDED) {
            Logger.log("Player: ENDED on ep '$epLabel'")

            if (!isLiveStream()) clearAllTransientSubtitleCache()
            if (!isLiveStream() && PrefManager.getVal(PrefName.AutoPlay)) {
                val browsingEpisodes =
                    episodeDrawer.isDrawerOpen(episodeDrawerContent) ||
                        episodeCommentPanel.visibility == View.VISIBLE
                if (browsingEpisodes) {
                    Logger.log("Player: ENDED while browsing episodes, autoplay deferred")
                } else if (interacted) {
                    exoNext.performClick()
                } else {
                    toast(getString(R.string.autoplay_cancelled))
                }
            }
        }
        super.onPlaybackStateChanged(playbackState)
    }

    private fun updateAniProgress() {
        val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)

        if (isLiveStream()) {
            return
        }

        if (media.id < 0) {
            val saveProgress = PrefManager.getCustomVal("${media.id}_save_progress", true)
            if (episodeLength <= 0f || incognito || Simkl.token == null || !saveProgress) return
            val currentPos = exoPlayer.currentPosition
            val episodeEnd = currentPos / episodeLength > PrefManager.getVal<Float>(PrefName.WatchPercentage)
            if (episodeEnd) {
                val alreadyTracked = PrefManager.getCustomVal("${media.id}_simkl_tracked_${media.anime?.selectedEpisode}", false)
                if (!alreadyTracked) {
                    PrefManager.setCustomVal("${media.id}_simkl_tracked_${media.anime?.selectedEpisode}", true)
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val session = TmdbStreamResolver.sessionFor(media.id) ?: return@launch
                        val d = session.detail
                        val title = d.displayTitle
                        val year = d.year.toIntOrNull()
                        val tmdbId = d.id
                        val imdbId = d.externalIds?.imdbId
                        val epStr = media.anime?.selectedEpisode
                        val parts = epStr?.split("E") ?: emptyList()
                        val season = if (parts.size == 2) parts[0].removePrefix("S").toIntOrNull() else null
                        val episode = epStr?.substringAfter("E")?.toIntOrNull()
                        val type = if (session.mediaType == "tv") "tv" else "movie"
                        Logger.log("Simkl: addToHistory type=$type title=$title s=$season e=$episode")
                        Simkl.addToHistory(type, title, year, tmdbId, imdbId, season, episode)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            toast("${title} marked as watched on Simkl")
                            Refresh.all()
                        }
                    }
                }
            }
            return
        }

        if (episodeLength <= 0f) {
            maybeHandleSubscriptionAfterEpisodeCompletion(false, incognito)
            return
        }
        val currentPos = exoPlayer.currentPosition
        val episodeEnd =
            currentPos / episodeLength >
                    PrefManager.getVal<Float>(
                        PrefName.WatchPercentage,
                    )
        val episode0 = currentEpisodeIndex == 0 && PrefManager.getVal(PrefName.ChapterZeroPlayer)

        val trackerReady =
            if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) MAL.token != null
            else Anilist.userid != null
        if (!incognito && (episodeEnd || episode0) && trackerReady
        ) {
            if (PrefManager.getCustomVal(
                    "${media.id}_save_progress",
                    true,
                ) &&
                (if (media.isAdult) PrefManager.getVal(PrefName.UpdateForHPlayer) else true)
            ) {
                if (episode0 && !episodeEnd) {
                    updateProgress(media, "0")
                } else {
                    media.anime!!.selectedEpisode?.apply {
                        updateProgress(media, this)
                    }
                }
            }
        }
        maybeHandleSubscriptionAfterEpisodeCompletion(episodeEnd, incognito)
    }

    private var lastSubscriptionPromptEpisode: String? = null

    private fun maybeHandleSubscriptionAfterEpisodeCompletion(episodeEnd: Boolean, incognito: Boolean) {
        if (!episodeEnd || incognito) return
        val currentEpisode = media.anime?.selectedEpisode ?: return
        if (lastSubscriptionPromptEpisode == currentEpisode) return
        lastSubscriptionPromptEpisode = currentEpisode

        val subscriptionsEnabled = PrefManager.getVal<Boolean>(PrefName.SubscriptionPromptAtEnd)
        if (!subscriptionsEnabled) return

        val isCompleted = isAnimeCompleted()
        val alreadySubscribed = SubscriptionHelper.getSubscriptions().containsKey(media.id)
        if (isCompleted) {
            if (alreadySubscribed) {
                SubscriptionHelper.saveSubscription(media, false)
                toast(getString(R.string.unsubscribed_notification))
            }
            return
        }
        if (alreadySubscribed) return

        customAlertDialog().apply {
            setTitle(getString(R.string.subscribe_prompt_title))
            setMessage(getString(R.string.subscribe_prompt_anime_message, media.userPreferredName))
            setPosButton(R.string.yes) {
                SubscriptionHelper.saveSubscription(media, true)
                toast(getString(R.string.subscribed_notification, getString(R.string.anime)))
            }
            setNegButton(R.string.no)
            show()
        }
    }

    private fun isAnimeCompleted(): Boolean {
        if (media.status == "FINISHED") return true
        if (media.userStatus == "COMPLETED") return true
        val totalEpisodes = media.anime?.totalEpisodes ?: return false
        val currentEpisodeNumber = media.anime?.selectedEpisode?.toFloatOrNull() ?: return false
        return currentEpisodeNumber >= totalEpisodes
    }

    private fun nextEpisode(
        toast: Boolean = true,
        runnable: ((Int) -> Unit),
    ) {
        var isFiller = true
        var i = 1
        while (isFiller) {
            if (episodeArr.size > currentEpisodeIndex + i) {
                isFiller =
                    if (PrefManager.getVal(PrefName.AutoSkipFiller)) {
                        episodes[episodeArr[currentEpisodeIndex + i]]?.filler
                            ?: false
                    } else {
                        false
                    }
                if (!isFiller) runnable.invoke(i)
                i++
            } else {
                if (toast) {
                    toast(getString(R.string.no_next_episode))
                }
                isFiller = false
            }
        }
    }

    @SuppressLint("UnsafeIntentLaunch")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finishAndRemoveTask()
        startActivity(intent)
    }

    override fun onDestroy() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        CoroutineScope(Dispatchers.IO).launch {
            tryWithSuspend(true) {
                extractor?.onVideoStopped(video)
            }
        }

        if (isInitialized) {
            updateAniProgress()

            clearAllTransientSubtitleCache()
            synchronized(storedSyncCues) {
                storedSyncCues.clear()
                seenCueTexts.clear()
                cueTextStartTimes.clear()
            }

            disappeared = false
            functionstarted = false
            releasePlayer()
        }
        cancelAutoSyncWatcher()
        speechRecorder.release()

        super.onDestroy()
        finishAndRemoveTask()
    }

    @Suppress("DEPRECATION")
    private fun enterPipMode() {
        wasPlaying = isPlayerPlaying
        if (!pipEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(
                    PictureInPictureParams
                        .Builder()
                        .setAspectRatio(aspectRatio)
                        .build(),
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                enterPictureInPictureMode()
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun onPiPChanged(isInPictureInPictureMode: Boolean) {
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            orientationListener?.disable()

            val pipFontSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat() * 0.55f
            playerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, pipFontSize)
            if (this::customSubtitleView.isInitialized) {
                customSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, pipFontSize)
            }
            if (this::customSubtitleTopView.isInitialized) {
                customSubtitleTopView.setTextSize(TypedValue.COMPLEX_UNIT_SP, pipFontSize)
            }
        } else {
            orientationListener?.enable()

            setupSubFormatting(playerView)
            if (this::customSubtitleView.isInitialized) {
                applySubtitleStyles(customSubtitleView)
            }
            if (this::customSubtitleTopView.isInitialized) {
                applyTopSubtitleStyles(customSubtitleTopView)
            }
        }
        if (isInitialized) {
            PrefManager.setCustomVal(
                "${media.id}_${episode.number}",
                exoPlayer.currentPosition,
            )
            if (!isFinishing && wasPlaying) {
                exoPlayer.play()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        onPiPChanged(isInPictureInPictureMode)
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        onPiPChanged(isInPictureInPictureMode)
        super.onPictureInPictureUiStateChanged(pipState)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        onPiPChanged(isInPictureInPictureMode)
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    private fun ensureControllerVisible() {
        if (pauseOverlay.visibility == View.VISIBLE) {
            pauseOverlay.visibility = View.GONE
            if (!playerView.isControllerFullyVisible) playerView.showController()
            playerView.controllerShowTimeoutMs = PrefManager.getVal<Int>(PrefName.AutoHideTimeout) * 1000
            playerView.post { exoPlay.requestFocus() }
            return
        }
        val wasHidden = !playerView.isControllerFullyVisible
        if (wasHidden) {
            playerView.showController()
            playerView.post { exoPlay.requestFocus() }
        } else {
            playerView.showController()
        }
        playerView.controllerShowTimeoutMs = PrefManager.getVal<Int>(PrefName.AutoHideTimeout) * 1000
    }

    private fun handleBackPress(): Boolean {
        val now = java.lang.System.currentTimeMillis()
        if (episodeCommentPanel.visibility == View.VISIBLE) {
            closeEpisodeCommentPanel(returnToRail = true)
            backPressTime = now
            return true
        }

        if (this::subtitleDrawerContent.isInitialized && binding.root.isDrawerOpen(subtitleDrawerContent)) {
            Logger.log("Player handleBackPress: closing subtitle rail")
            binding.root.closeDrawer(subtitleDrawerContent)
            exoSubtitle.requestFocus()
            backPressTime = now
            return true
        }
        if (this::episodeDrawerContent.isInitialized && episodeDrawer.isDrawerOpen(episodeDrawerContent)) {
            Logger.log("Player handleBackPress: closing episode rail")
            episodeDrawer.closeDrawer(episodeDrawerContent)
            episodeTitleBtn.requestFocus()
            backPressTime = now
            return true
        }
        if (this::tracksDrawerContent.isInitialized && trackRailController?.isOpen() == true) {
            Logger.log("Player handleBackPress: closing tracks rail")
            closeTracksRail()
            backPressTime = now
            return true
        }
        if (pauseOverlay.visibility == View.VISIBLE) {
            pauseOverlay.visibility = View.GONE
            if (!playerView.isControllerFullyVisible) playerView.showController()
            exoPlay.requestFocus()
            backPressTime = now
            return true
        }
        if (PrefManager.getVal<Boolean>(PrefName.ConfirmPlayerExit)) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_exit_player, null)
            val dialog = AlertDialog.Builder(this, R.style.MyPopup)
                .setView(dialogView)
                .create()
            dialogView.findViewById<View>(R.id.exitYes).setOnClickListener {
                dialog.dismiss()
                finishAndRemoveTask()
            }
            dialogView.findViewById<View>(R.id.exitNo).setOnClickListener { dialog.dismiss() }
            dialog.setOnShowListener { dialogView.findViewById<View>(R.id.exitYes).requestFocus() }
            dialog.window?.apply {
                setDimAmount(0.5f)
                attributes.windowAnimations = android.R.style.Animation_Dialog
            }
            dialog.show()
            return true
        }
        if (playerView.isControllerFullyVisible) {
            playerView.hideController()
            return true
        }
        finishAndRemoveTask()
        return true
    }

    private fun openEpisodeComments(epKey: String) {
        if (!isInitialized) return
        commentPanelEpisode = epKey
        episodeDrawer.closeDrawer(episodeDrawerContent)
        episodeCommentTitle.text = getString(R.string.episode_comments, epKey)
        episodeCommentAdapter?.submitList(emptyList())
        episodeCommentProgress.visibility = View.VISIBLE
        episodeCommentList.visibility = View.GONE
        episodeCommentPanel.visibility = View.VISIBLE

        playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        playerView.isFocusable = false
        episodeCommentPanel.requestFocus()
        loadEpisodeComments(epKey)
    }

    private fun loadEpisodeComments(epKey: String) {
        episodeCommentJob?.cancel()
        episodeCommentJob = lifecycleScope.launch {
            try {
                val epNumber = epKey.toIntOrNull()
                val sort = PrefManager.getVal(PrefName.CommentSortOrder, "newest")
                val saninDeferred = async {
                    try {
                        withTimeoutOrNull(12_000L) {
                            withContext(Dispatchers.IO) {
                                CommentsAPI.getCommentsForId(media.id, page = 1, tag = epNumber, sort = sort)
                            }?.comments ?: emptyList()
                        } ?: emptyList()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.log(Log.ERROR, "Player: Sanin episode comments failed: ${e.message}")
                        emptyList()
                    }
                }
                val anikotoDeferred = async {
                    if (epNumber == null || PrefManager.getVal<Int>(PrefName.AnikotoCommentsEnabled) != 1) {
                        return@async emptyList()
                    }
                    val batch = mutableListOf<Comment>()
                    try {
                        withTimeoutOrNull(12_000L) {
                            withContext(Dispatchers.IO) {
                                AnikotoAPI.fetchAnikotoChunk(
                                    media.id,
                                    media.userPreferredName,
                                    epNumber,
                                    0,
                                    1,
                                    filterEpisode = epNumber,
                                ) { newComments ->
                                    batch.addAll(newComments)
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.log(Log.ERROR, "Player: Anikoto episode comments failed: ${e.message}")
                    }
                    batch
                }
                val results = saninDeferred.await() + anikotoDeferred.await()
                if (isFinishing || episodeCommentPanel.visibility != View.VISIBLE) return@launch
                episodeCommentProgress.visibility = View.GONE
                episodeCommentList.visibility = View.VISIBLE
                episodeCommentAdapter?.submitList(results)
                if (results.isEmpty()) {
                    snackString(getString(R.string.no_episode_comments, epKey))
                } else {
                    episodeCommentList.post {
                        episodeCommentList.findViewHolderForAdapterPosition(0)
                            ?.itemView?.requestFocus()
                            ?: episodeCommentList.requestFocus()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log(Log.ERROR, "Player: episode comments load failed: ${e.message}")
                episodeCommentProgress.visibility = View.GONE
            }
        }
    }

    private fun closeEpisodeCommentPanel(returnToRail: Boolean) {
        episodeCommentJob?.cancel()
        playerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        playerView.isFocusable = true
        episodeCommentPanel.visibility = View.GONE
        episodeCommentList.visibility = View.GONE
        episodeCommentProgress.visibility = View.GONE
        val epKey = commentPanelEpisode
        commentPanelEpisode = null
        if (returnToRail && epKey != null) {
            episodeDrawer.openDrawer(episodeDrawerContent)
            focusRailCommentButton(epKey)
        }
    }

    private fun focusRailCommentButton(epKey: String) {
        val pos = episodeArr.indexOf(epKey)
        if (pos < 0) return
        episodeDrawerList.postDelayed({
            episodeDrawerList.scrollToPosition(pos)
            episodeDrawerList.post {
                val holder = episodeDrawerList.findViewHolderForAdapterPosition(pos)
                val commentBtn = holder?.itemView?.findViewById<View>(R.id.episodeRailComment)
                if (commentBtn != null) {
                    commentBtn.requestFocus()
                } else {
                    holder?.itemView?.requestFocus() ?: episodeDrawerList.requestFocus()
                }
            }
        }, 300L)
    }

    private fun openPlayerCommentZoom(comment: Comment) {
        if (supportFragmentManager.findFragmentByTag("playerCommentZoom") != null) return
        val dialog = CommentZoomDialog()
        dialog.arguments = Bundle().apply {
            putInt("commentId", comment.commentId)
            putString("content", comment.content)
            putString("username", comment.username)
            putString("avatarUrl", comment.profilePictureUrl)
            putString("timestamp", comment.timestamp)
            putInt("upvotes", comment.upvotes)
            putInt("downvotes", comment.downvotes)
            putInt("userVoteType", comment.userVoteType ?: 0)
            putInt("replyCount", comment.replyCount ?: 0)
            putBoolean("isAnikoto", comment.isAnikoto)
            putInt("mediaId", media.id)
            putInt("anikotoEpisode", comment.anikotoEpisode ?: 0)
        }

        dialog.dismissCallback = {
            if (episodeCommentPanel.visibility == View.VISIBLE) {
                episodeCommentList.post {
                    val lm = episodeCommentList.layoutManager as? LinearLayoutManager
                    val first = lm?.findFirstVisibleItemPosition() ?: 0
                    episodeCommentList.findViewHolderForAdapterPosition(first)
                        ?.itemView?.requestFocus()
                        ?: episodeCommentList.requestFocus()
                }
            }
        }
        dialog.show(supportFragmentManager, "playerCommentZoom")
    }

    private var seekRepeatHandler: Handler? = null
    private var seekRepeatRunnable: Runnable? = null

    private fun startSeekRepeat(forward: Boolean) {
        stopSeekRepeat()
        val sensitivity = PrefManager.getVal<Int>(PrefName.SeekSensitivity).coerceAtLeast(50)
        seekRepeatHandler = Handler(Looper.getMainLooper())
        seekRepeatRunnable = object : Runnable {
            override fun run() {
                if (!isInitialized) return
                val seekTime = PrefManager.getVal<Int>(PrefName.SeekTime)
                val currentPos = exoPlayer.currentPosition
                if (forward) exoPlayer.seekTo(currentPos + seekTime * 1000) else exoPlayer.seekTo(currentPos - seekTime * 1000)
                seekRepeatHandler?.postDelayed(this, sensitivity.toLong())
            }
        }
        seekRepeatHandler?.post(seekRepeatRunnable!!)
    }

    private fun stopSeekRepeat() {
        seekRepeatRunnable?.let { seekRepeatHandler?.removeCallbacks(it) }
        seekRepeatRunnable = null
        seekRepeatHandler = null
    }

    private fun markInteracted() {
        if (!PrefManager.getVal<Boolean>(PrefName.AutoPlay)) return
        interacted = true
        interactionTimer?.cancel()
        interactionTimer = Timer().apply {
            schedule(
                object : TimerTask() {
                    override fun run() {
                        interacted = false
                    }
                },
                1000L * 60 * 60,
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) markInteracted()
        if (!isInitialized) return super.dispatchKeyEvent(event)

        if (this::tracksDrawerContent.isInitialized &&
            trackRailController?.isOpen() == true
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        closeTracksRail()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        closeTracksRail()
                    }
                    return true
                }
            }
        }

        if (this::subtitleDrawerContent.isInitialized && binding.root.isDrawerOpen(subtitleDrawerContent)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        binding.root.closeDrawer(subtitleDrawerContent)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        binding.root.closeDrawer(subtitleDrawerContent)
                    }
                    return true
                }
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
            (episodeDrawer.isDrawerOpen(episodeDrawerContent) ||
                episodeCommentPanel.visibility == View.VISIBLE)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (episodeDrawer.isDrawerOpen(episodeDrawerContent) &&
                    currentFocus?.id == R.id.episodeRailComment
                ) {

                    currentFocus?.focusSearch(View.FOCUS_LEFT)?.requestFocus()
                } else if (episodeDrawer.isDrawerOpen(episodeDrawerContent)) {
                    episodeDrawer.closeDrawer(episodeDrawerContent)
                } else {
                    closeEpisodeCommentPanel(returnToRail = true)
                }
            }
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
            (episodeDrawer.isDrawerOpen(episodeDrawerContent) ||
                binding.root.isDrawerOpen(subtitleDrawerContent) ||
                trackRailController?.isOpen() == true)
        ) {

            val focused = currentFocus
            val next = focused?.focusSearch(View.FOCUS_RIGHT)
            if (next == null || !isDescendantOf(next, episodeDrawerContent) && !isDescendantOf(next, subtitleDrawerContent) && !isDescendantOf(next, tracksDrawerContent)) {
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (binding.root.isDrawerOpen(subtitleDrawerContent)) {
                        binding.root.closeDrawer(subtitleDrawerContent)
                        return true
                    } else if (episodeDrawer.isDrawerOpen(episodeDrawerContent)) {
                        episodeDrawer.closeDrawer(episodeDrawerContent)
                        return true
                    } else if (episodeCommentPanel.visibility == View.VISIBLE) {
                        closeEpisodeCommentPanel(returnToRail = true)
                        return true
                    } else if (trackRailController?.isOpen() == true) {
                        closeTracksRail()
                        return true
                    }
                }
            }
            schedulePauseOverlayTimer()
        }
        val progressFocused = currentFocus?.id == androidx.media3.ui.R.id.exo_progress
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) ensureControllerVisible()

                val epOpen = episodeDrawer.isDrawerOpen(episodeDrawerContent)
                val subOpen = binding.root.isDrawerOpen(subtitleDrawerContent)
                val tracksOpen = trackRailController?.isOpen() == true
                val commentOpen = episodeCommentPanel.visibility == View.VISIBLE
                if (epOpen || subOpen || tracksOpen || commentOpen) {
                    val focused = currentFocus
                    if (focused != null) {
                        val insideRail = isDescendantOf(focused, episodeDrawerContent) ||
                            isDescendantOf(focused, subtitleDrawerContent) ||
                            isDescendantOf(focused, tracksDrawerContent) ||
                            isDescendantOf(focused, episodeCommentPanel)
                        if (!insideRail) {

                            Logger.log("Player rail focus escaped on ${if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) "UP" else "DOWN"}, refocusing")
                            val target: View? = when {
                                subOpen -> subtitleDrawerList.findViewHolderForAdapterPosition(0)?.itemView ?: subtitleDrawerClose
                                epOpen -> {
                                    val lm = episodeDrawerList.layoutManager as? LinearLayoutManager
                                    val pos = lm?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
                                    episodeDrawerList.findViewHolderForAdapterPosition(pos)?.itemView ?: episodeDrawerClose
                                }
                                commentOpen -> episodeCommentList.findViewHolderForAdapterPosition(0)?.itemView ?: episodeCommentClose
                                tracksOpen -> tracksDrawerList.findViewHolderForAdapterPosition(0)?.itemView ?: tracksDrawerClose
                                else -> null
                            }
                            target?.post { target.requestFocus() }
                            return true
                        }

                        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            val rv: RecyclerView? = when {
                                subOpen && isDescendantOf(focused, subtitleDrawerContent) -> subtitleDrawerList
                                epOpen && isDescendantOf(focused, episodeDrawerContent) -> episodeDrawerList
                                commentOpen && isDescendantOf(focused, episodeCommentPanel) -> episodeCommentList
                                tracksOpen && isDescendantOf(focused, tracksDrawerContent) -> tracksDrawerList
                                else -> null
                            }
                            if (rv != null) {

                                var item: View? = focused
                                while (item != null && item.parent !== rv) {
                                    item = item.parent as? View
                                }
                                val pos = item?.let { rv.getChildAdapterPosition(it) }
                                    ?: RecyclerView.NO_POSITION
                                if (pos != RecyclerView.NO_POSITION) {
                                    val last = (rv.adapter?.itemCount ?: 0) - 1
                                    if (pos == last) {

                                        Logger.log("Player rail at bottom, DPAD_DOWN consumed to prevent wrap to top")
                                        if (rv.canScrollVertically(1)) rv.smoothScrollBy(0, 80)
                                        return true
                                    }

                                    val lm = rv.layoutManager as? LinearLayoutManager
                                    val lastVisible = lm?.findLastVisibleItemPosition() ?: -1
                                    if (pos >= lastVisible && rv.canScrollVertically(1)) {
                                        rv.smoothScrollToPosition((pos + 1).coerceAtMost(last))
                                    }
                                }
                            }
                        }
                    } else if (event.action == KeyEvent.ACTION_DOWN) {

                        Logger.log("Player rail no focus on ${if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) "UP" else "DOWN"}, restoring")
                        when {
                            subOpen -> subtitleRailController?.focusFirst() ?: subtitleDrawerClose.requestFocus()
                            epOpen -> {
                                val lm = episodeDrawerList.layoutManager as? LinearLayoutManager
                                val pos = lm?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
                                episodeDrawerList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                                    ?: episodeDrawerClose.requestFocus()
                            }
                            commentOpen -> episodeCommentClose.requestFocus()
                            tracksOpen -> tracksDrawerClose.requestFocus()
                        }
                        return true
                    }
                }
                return false
            }
            KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT -> {
                if (progressFocused) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        ensureControllerVisible()
                        val forward = event.keyCode == KEYCODE_DPAD_RIGHT
                        val seekTime = PrefManager.getVal<Int>(PrefName.SeekTime)
                        val currentPos = exoPlayer.currentPosition
                        if (forward) {
                            seekToMs(currentPos + seekTime * 1000)
                        } else {
                            seekToMs(currentPos - seekTime * 1000)
                        }
                    }
                    return true
                }
                if (event.action == KeyEvent.ACTION_DOWN) {
                    ensureControllerVisible()
                    dpadPressTime = java.lang.System.currentTimeMillis()
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val elapsed = java.lang.System.currentTimeMillis() - dpadPressTime
                    if (elapsed >= 3000L && PrefManager.getVal<Boolean>(PrefName.DpadEpisodeSkip)) {
                        if (event.keyCode == KEYCODE_DPAD_LEFT) exoPrev.performClick()
                        else exoNext.performClick()
                        return true
                    }
                }
                return false
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (playerView.isControllerFullyVisible) {
                        currentFocus?.performClick() ?: exoPlay.performClick()
                    } else ensureControllerVisible()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action == KeyEvent.ACTION_UP) exoPlay.performClick()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (event.action == KeyEvent.ACTION_UP && isInitialized) {
                    exoPlayer.stop()
                    exoPlayer.seekTo(0)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    startSeekRepeat(true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    stopSeekRepeat()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    startSeekRepeat(false)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    stopSeekRepeat()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (event.action == KeyEvent.ACTION_UP) exoNext.performClick()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (event.action == KeyEvent.ACTION_UP) exoPrev.performClick()
                return true
            }
            KEYCODE_SPACE -> {
                if (event.action == KeyEvent.ACTION_UP && isInitialized) exoPlay.performClick()
                return true
            }
            KEYCODE_N -> {
                if (event.action == KeyEvent.ACTION_UP) exoNext.performClick()
                return true
            }
            KEYCODE_B -> {
                if (event.action == KeyEvent.ACTION_UP) exoPrev.performClick()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDescendantOf(view: View, parent: View): Boolean {
        var v: android.view.ViewParent? = view.parent
        while (v != null) {
            if (v === parent) return true
            v = v.parent
        }
        return false
    }

    private fun startExoPlayer() {
        Logger.log(
            "Player: starting ep '${media.anime?.selectedEpisode}' server='${media.selected?.server}' " +
                "source='${media.selected?.sourceIndex}' uri=${mediaItem?.localConfiguration?.uri}"
        )
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        playerView.player = exoPlayer
    }

    private fun seekToMs(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    @SuppressLint("ViewConstructor")
    class ExtendedTimeBar(
        context: Context,
        attrs: AttributeSet?,
    ) : DefaultTimeBar(context, attrs) {
        private var enabled = false
        private var forceDisabled = false

        override fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
            super.setEnabled(!forceDisabled)
        }

        fun setForceDisabled(forceDisabled: Boolean) {
            this.forceDisabled = forceDisabled
            isEnabled = enabled
        }
    }
}

private class EpisodeRailDiff : DiffUtil.ItemCallback<Map.Entry<String, Episode>>() {
    override fun areItemsTheSame(a: Map.Entry<String, Episode>, b: Map.Entry<String, Episode>) = a.key == b.key
    override fun areContentsTheSame(a: Map.Entry<String, Episode>, b: Map.Entry<String, Episode>) = a.key == b.key
}

private class EpisodeRailAdapter(
    private val episodes: Map<String, Episode>,
    private val onEpisodeClick: (String) -> Unit,
    private val onCommentClick: (String) -> Unit,
) : ListAdapter<Map.Entry<String, Episode>, EpisodeRailViewHolder>(EpisodeRailDiff()) {

    init {
        submitList(episodes.entries.toList())
    }

    fun showSeason(map: Map<String, Episode>) {
        submitList(map.entries.toList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeRailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_rail, parent, false) as CardView
        return EpisodeRailViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeRailViewHolder, position: Int) {
        val entry = getItem(position)
        holder.bind(entry.key, entry.value)
        holder.itemView.setOnClickListener { onEpisodeClick(entry.key) }
        holder.commentButton.setOnClickListener { onCommentClick(entry.key) }
    }
}

private class EpisodeRailViewHolder(val card: CardView) : ViewHolder(card) {
    private val thumb = card.findViewById<ImageView>(R.id.episodeRailThumb)
    private val number = card.findViewById<TextView>(R.id.episodeRailNumber)
    private val title = card.findViewById<TextView>(R.id.episodeRailTitle)
    private val desc = card.findViewById<TextView>(R.id.episodeRailDesc)
    private val date = card.findViewById<TextView>(R.id.episodeRailDate)
    private val rating = card.findViewById<TextView>(R.id.episodeRailRating)
    val commentButton = card.findViewById<ImageButton>(R.id.episodeRailComment)

    init {
        FocusEffectUtil.applyFocusListener(card, borderDp = 5f)
        FocusEffectUtil.applyFocusListener(commentButton)
    }

    fun bind(epKey: String, ep: Episode) {
        number.text = epKey
        title.text = if (ep.filler) "[FILLER] ${ep.title ?: ""}" else ep.title ?: "Episode $epKey"
        desc.text = ep.desc ?: ""
        date.text = ep.date ?: ""
        date.visibility = if (ep.date != null) View.VISIBLE else View.GONE
        rating.text = ep.rating?.let { "★ $it" } ?: ""
        rating.visibility = if (ep.rating != null) View.VISIBLE else View.GONE

        val url = ep.thumb?.url?.takeIf { it.isNotEmpty() }
        if (url != null) {
            Glide.with(card).load(url)
                .into(thumb)
        } else {
            thumb.setImageResource(android.R.color.transparent)
        }
    }
}

private class EpisodeCommentPillAdapter(
    private val onCommentClick: (Comment) -> Unit,
) : RecyclerView.Adapter<EpisodeCommentPillViewHolder>() {

    private val items = mutableListOf<Comment>()

    fun submitList(list: List<Comment>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeCommentPillViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_comment_pill, parent, false) as MaterialCardView
        return EpisodeCommentPillViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: EpisodeCommentPillViewHolder, position: Int) {
        val comment = items[position]
        holder.bind(comment)
        holder.itemView.setOnClickListener { onCommentClick(comment) }
    }
}

private class EpisodeCommentPillViewHolder(val card: MaterialCardView) : ViewHolder(card) {
    private val avatar = card.findViewById<ShapeableImageView>(R.id.pillAvatar)
    private val username = card.findViewById<TextView>(R.id.pillUserName)
    private val time = card.findViewById<TextView>(R.id.pillTime)
    private val content = card.findViewById<TextView>(R.id.pillContent)
    private val sourceBadge = card.findViewById<TextView>(R.id.pillSourceBadge)
    private val gifAboveView = card.findViewById<ImageView>(R.id.pillGifAbove)
    private val gifBelowView = card.findViewById<ImageView>(R.id.pillGifBelow)

    init {
        FocusEffectUtil.applyFocusListener(card, borderDp = 4f)
    }

    fun bind(comment: Comment) {
        username.text = comment.username
        time.text = formatCommentTime(comment.timestamp)
        val parsed = parseGifCommentContent(comment.content)
        val gifUrl = parsed.gifUrl
        if (gifUrl != null) {

            content.text = parsed.text.replace(Regex("\\s+"), " ").trim()
            content.maxLines = 1
            content.ellipsize = TextUtils.TruncateAt.END
            val gifAbove = parsed.gifAbove
            gifAboveView.visibility = if (gifAbove) View.VISIBLE else View.GONE
            gifBelowView.visibility = if (!gifAbove) View.VISIBLE else View.GONE
            (if (gifAbove) gifAboveView else gifBelowView).loadImage(gifUrl)
        } else {
            content.text = parsed.text.replace(Regex("\\s+"), " ").trim()
            content.maxLines = 3
            content.ellipsize = TextUtils.TruncateAt.END
            gifAboveView.visibility = View.GONE
            gifBelowView.visibility = View.GONE
        }
        if (comment.profilePictureUrl != null) {
            avatar.loadImage(comment.profilePictureUrl)
        } else {
            avatar.setImageResource(R.drawable.ic_round_add_circle_24)
        }
        if (comment.isAnikoto) {
            sourceBadge.text = "anikoto"
            sourceBadge.setTextColor(0xFF00E5FF.toInt())
        } else {
            sourceBadge.text = "dantotsu"
            sourceBadge.setTextColor(0xFFBB86FC.toInt())
        }
    }
}

private fun formatCommentTime(timestamp: String): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val parsed = sdf.parse(timestamp) ?: return "now"
        val diff = java.lang.System.currentTimeMillis() - parsed.time
        val days = diff / (24L * 60 * 60 * 1000)
        val hours = diff / (60L * 60 * 1000) % 24L
        val minutes = diff / (60L * 1000) % 60L
        when {
            days > 0 -> "${days}d"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "now"
        }
    } catch (_: Exception) {
        "now"
    }
}
