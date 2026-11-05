package com.lagradost.cloudstream3.utils
import ani.sanin.R

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.tv.TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.util.Log
import android.view.View
import android.view.View.LAYOUT_DIRECTION_LTR
import android.view.View.LAYOUT_DIRECTION_RTL
import android.view.animation.DecelerateInterpolator
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.core.text.toSpanned
import androidx.core.widget.ContentLoadingProgressBar
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.tvprovider.media.tv.WatchNextProgram.fromCursor
import androidx.viewpager2.widget.ViewPager2
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.wrappers.Wrappers
import com.google.android.material.bottomsheet.BottomSheetDialog
import ani.sanin.media.model.ResumeWatching
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_RESUME_WATCHING
import ani.sanin.media.model.SubtitleData
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getLastWatched
import com.lagradost.cloudstream3.utils.FillerEpisodeCheck.toClassDir
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cache
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object AppContextUtils {
    fun RecyclerView.isRecyclerScrollable(): Boolean {
        val layoutManager =
            this.layoutManager as? LinearLayoutManager?
        val adapter = adapter
        return if (layoutManager == null || adapter == null) false else layoutManager.findLastCompletelyVisibleItemPosition() < adapter.itemCount - 7
    }

    fun View.isLtr() = this.layoutDirection == LAYOUT_DIRECTION_LTR
    fun View.isRtl() = this.layoutDirection == LAYOUT_DIRECTION_RTL

    fun BottomSheetDialog?.ownHide() {
        this?.hide()
    }

    fun BottomSheetDialog?.ownShow() {

        this?.window?.setWindowAnimations(-1)
        this?.show()
        Handler(Looper.getMainLooper()).postDelayed({
            this?.window?.setWindowAnimations(com.google.android.material.R.style.Animation_Design_BottomSheetDialog)
        }, 200)
    }

    fun String?.html(): Spanned {
        return getHtmlText(this ?: return "".toSpanned())
    }

    private fun getHtmlText(text: String): Spanned {
        return try {

            HtmlCompat.fromHtml(
                text, HtmlCompat.FROM_HTML_MODE_LEGACY
            )
        } catch (e: Exception) {
            logError(e)
            text.toSpanned()
        }
    }

    @SuppressLint("RestrictedApi")
    private fun buildWatchNextProgramUri(
        context: Context,
        card: DataStoreHelper.ResumeWatchingResult,
        resumeWatching: ResumeWatching?
    ): WatchNextProgram {
        val isSeries = card.type?.isMovieType() == false
        val title = if (isSeries) {
            context.getNameFull(card.name, card.episode, card.season)
        } else {
            card.name
        }

        val builder = WatchNextProgram.Builder()
            .setEpisodeTitle(title)
            .setType(
                if (isSeries) {
                    TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
                } else TvContractCompat.WatchNextPrograms.TYPE_MOVIE
            )
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setTitle(title)
            .setPosterArtUri(card.posterUrl?.toUri())
            .setIntentUri((card.id?.let {
                "$APP_STRING_RESUME_WATCHING://$it"
            } ?: card.url).toUri())
            .setInternalProviderId(card.url)
            .setLastEngagementTimeUtcMillis(
                resumeWatching?.updateTime ?: System.currentTimeMillis()
            )

        card.watchPos?.let {
            builder.setDurationMillis(it.duration.toInt())
            builder.setLastPlaybackPositionMillis(it.position.toInt())
        }

        if (isSeries)
            card.episode?.let {
                builder.setEpisodeNumber(it)
            }

        return builder.build()
    }

    fun ViewPager2.reduceDragSensitivity(f: Int = 4) {
        val recyclerViewField = ViewPager2::class.java.getDeclaredField("mRecyclerView")
        recyclerViewField.isAccessible = true
        val recyclerView = recyclerViewField.get(this) as RecyclerView

        val touchSlopField = RecyclerView::class.java.getDeclaredField("mTouchSlop")
        touchSlopField.isAccessible = true
        val touchSlop = touchSlopField.get(recyclerView) as Int
        touchSlopField.set(recyclerView, touchSlop * f)
    }

    fun ContentLoadingProgressBar?.animateProgressTo(to: Int) {
        if (this == null) return
        val animation: ObjectAnimator = ObjectAnimator.ofInt(
            this,
            "progress",
            this.progress,
            to
        )
        animation.duration = 500
        animation.setAutoCancel(true)
        animation.interpolator = DecelerateInterpolator()
        animation.start()
    }

    fun Context.createNotificationChannel(
        channelId: String,
        channelName: String,
        description: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel =
                NotificationChannel(channelId, channelName, importance).apply {
                    this.description = description
                }

            val notificationManager: NotificationManager =
                this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            notificationManager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("RestrictedApi")
    fun getAllWatchNextPrograms(context: Context): Set<Long> {
        val COLUMN_WATCH_NEXT_ID_INDEX = 0
        val cursor = context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WatchNextProgram.PROJECTION,
             null,
             null,
             null
        )
        val set = mutableSetOf<Long>()
        cursor?.use {
            if (it.moveToFirst()) {
                do {
                    set.add(cursor.getLong(COLUMN_WATCH_NEXT_ID_INDEX))
                } while (it.moveToNext())
            }
        }
        return set
    }

    @SuppressLint("RestrictedApi")

    fun findFirstWatchNextProgram(context: Context, predicate: (Cursor) -> Boolean):
            Pair<WatchNextProgram?, Long?> {
        val COLUMN_WATCH_NEXT_ID_INDEX = 0

        val cursor = context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WatchNextProgram.PROJECTION,
             null,
             null,
             null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                do {
                    if (predicate(cursor)) {
                        return fromCursor(cursor) to cursor.getLong(COLUMN_WATCH_NEXT_ID_INDEX)
                    }
                } while (it.moveToNext())
            }
        }
        return null to null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("Range")
    @Synchronized
    private fun getWatchNextProgramByVideoId(
        id: String,
        context: Context
    ): Pair<WatchNextProgram?, Long?> {
        return findFirstWatchNextProgram(context) { cursor ->
            (cursor.getString(cursor.getColumnIndex(COLUMN_INTERNAL_PROVIDER_ID)) == id)
        }
    }

    private val continueWatchingLock = Mutex()

    @SuppressLint("RestrictedApi")
    @Throws
    @WorkerThread
    suspend fun Context.addProgramsToContinueWatching(data: List<DataStoreHelper.ResumeWatchingResult>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val context = this
        continueWatchingLock.withLock {

            val timeStampHashMap = HashMap<Int, ResumeWatching>()
            getAllResumeStateIds()?.forEach { id ->
                val lastWatched = getLastWatched(id) ?: return@forEach
                timeStampHashMap[lastWatched.parentId] = lastWatched
            }

            val currentProgramIds = data.mapNotNull { episodeInfo ->
                try {
                    val customId = "${episodeInfo.id}|${episodeInfo.apiName}|${episodeInfo.url}"
                    val (program, id) = getWatchNextProgramByVideoId(customId, context)
                    val nextProgram = buildWatchNextProgramUri(
                        context,
                        episodeInfo,
                        timeStampHashMap[episodeInfo.id]
                    )

                    if (program != null && id != null) {
                        PreviewChannelHelper(context).updateWatchNextProgram(
                            nextProgram,
                            id,
                        )
                        id
                    } else {
                        PreviewChannelHelper(context)
                            .publishWatchNextProgram(nextProgram)
                    }
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }.toSet()

            val allOldPrograms = getAllWatchNextPrograms(context) - currentProgramIds

            allOldPrograms.forEach {
                context.contentResolver.delete(
                    TvContractCompat.buildWatchNextProgramUri(it),
                    null, null
                )
            }
        }
    }

    fun sortSubs(subs: Set<SubtitleData>): List<SubtitleData> {

        return subs
            .sortedWith(
                compareBy { subtitle: SubtitleData -> subtitle.originalName }
                    .thenBy { subtitle: SubtitleData -> subtitle.nameSuffix })
    }

    fun Context.getApiSettings(): HashSet<String> {
        val hashSet = HashSet<String>()
        val activeLangs = getApiProviderLangSettings()
        val hasUniversal = activeLangs.contains(AllLanguagesName)
        hashSet.addAll(apis.filter { hasUniversal || activeLangs.contains(it.lang) }
            .map { it.name })
        return hashSet
    }

    fun Context.getApiDubstatusSettings(): HashSet<DubStatus> {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val hashSet = HashSet<DubStatus>()
        hashSet.addAll(DubStatus.values())
        val list = settingsManager.getStringSet(
            this.getString(R.string.display_sub_key),
            hashSet.map { it.name }.toMutableSet()
        ) ?: return hashSet

        val names = DubStatus.values().map { it.name }.toHashSet()

        return list.filter { names.contains(it) }.map { DubStatus.valueOf(it) }.toHashSet()
    }

    fun Context.getApiProviderLangSettings(): HashSet<String> {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val hashSet = hashSetOf(AllLanguagesName)

        val list = settingsManager.getStringSet(
            this.getString(R.string.provider_lang_key),
            hashSet
        )

        if (list.isNullOrEmpty()) return hashSet
        return list.toHashSet()
    }

    fun Context.getApiTypeSettings(): HashSet<TvType> {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val hashSet = HashSet<TvType>()
        hashSet.addAll(TvType.values())
        val list = settingsManager.getStringSet(
            this.getString(R.string.search_types_list_key),
            hashSet.map { it.name }.toMutableSet()
        )

        if (list.isNullOrEmpty()) return hashSet

        val names = TvType.values().map { it.name }.toHashSet()
        val realSet = list.filter { names.contains(it) }.map { TvType.valueOf(it) }.toHashSet()
        if (realSet.isEmpty()) return hashSet

        return realSet
    }

    fun Context.updateHasTrailers() {
        LoadResponse.isTrailersEnabled = getHasTrailers()
    }

    private fun Context.getHasTrailers(): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        return settingsManager.getBoolean(this.getString(R.string.show_trailers_key), true)
    }

    fun Context.shouldShowPlayerMetadata(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean(
            getString(R.string.show_player_metadata_key),
            true
        )
    }

    fun Context.filterProviderByPreferredMedia(hasHomePageIsRequired: Boolean = true): List<MainAPI> {

        val oldLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = TvType::class.java.classLoader

        val default = TvType.values()
            .sorted()
            .filter { it != TvType.NSFW }
            .map { it.ordinal }

        Thread.currentThread().contextClassLoader = oldLoader

        val defaultSet = default.map { it.toString() }.toSet()
        val currentPrefMedia = try {
            PreferenceManager.getDefaultSharedPreferences(this)
                .getStringSet(this.getString(R.string.prefer_media_type_key), defaultSet)
                ?.mapNotNull { it.toIntOrNull() ?: return@mapNotNull null }
        } catch (e: Throwable) {
            null
        } ?: default
        val langs = this.getApiProviderLangSettings()
        val hasUniversal = langs.contains(AllLanguagesName)
        val allApis =
            apis.filter { api -> (hasUniversal || langs.contains(api.lang)) && (api.hasMainPage || !hasHomePageIsRequired) }
        return if (currentPrefMedia.isEmpty()) {
            allApis
        } else {

            allApis.filter { api -> api.supportedTypes.any { currentPrefMedia.contains(it.ordinal) } }
        }
    }

    fun Context.filterSearchResultByFilmQuality(data: List<SearchResponse>): List<SearchResponse> {

        if (data.isNotEmpty()) {
            val filteredSearchQuality = PreferenceManager.getDefaultSharedPreferences(this)
                ?.getStringSet(getString(R.string.pref_filter_search_quality_key), setOf())
                ?.mapNotNull { entry ->
                    entry.toIntOrNull() ?: return@mapNotNull null
                } ?: listOf()
            if (filteredSearchQuality.isNotEmpty()) {
                return data.filter { item ->
                    val searchQualVal = item.quality?.ordinal ?: -1

                    !filteredSearchQuality.contains(searchQualVal)
                }
            }
        }
        return data
    }

    fun Context.filterHomePageListByFilmQuality(data: HomePageList): HomePageList {

        if (data.list.isNotEmpty()) {
            val filteredSearchQuality = PreferenceManager.getDefaultSharedPreferences(this)
                ?.getStringSet(getString(R.string.pref_filter_search_quality_key), setOf())
                ?.mapNotNull { entry ->
                    entry.toIntOrNull() ?: return@mapNotNull null
                } ?: listOf()
            if (filteredSearchQuality.isNotEmpty()) {
                return HomePageList(
                    name = data.name,
                    isHorizontalImages = data.isHorizontalImages,
                    list = data.list.filter { item ->
                        val searchQualVal = item.quality?.ordinal ?: -1

                        !filteredSearchQuality.contains(searchQualVal)
                    }
                )
            }
        }
        return data
    }

    fun Activity.requestLocalAudioFocus(focusRequest: AudioFocusRequest?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                Log.e("TAG", "focusRequest was null")
                return
            }

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(focusRequest)
        } else {
            val audioManager: AudioManager =
                getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private var currentAudioFocusRequest: AudioFocusRequest? = null
    private var currentAudioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    var onAudioFocusEvent = Event<Boolean>()

    private fun getAudioListener(): AudioManager.OnAudioFocusChangeListener? {
        if (currentAudioFocusChangeListener != null) return currentAudioFocusChangeListener
        currentAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener {
            onAudioFocusEvent.invoke(
                when (it) {
                    AudioManager.AUDIOFOCUS_GAIN -> false
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> false
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> false
                    else -> true
                }
            )
        }
        return currentAudioFocusChangeListener
    }

    fun Context.isCastApiAvailable(): Boolean {
        val isCastApiAvailable =
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(applicationContext) == ConnectionResult.SUCCESS

        try {
            applicationContext?.let {
                val task = CastContext.getSharedInstance(it) { it.run() }
                task.result
            }
        } catch (e: Exception) {
            println(e)

            return false
        }

        return isCastApiAvailable
    }

    fun Context.isConnectedToChromecast(): Boolean {
        if (isCastApiAvailable()) {
            val executor: Executor = Executors.newSingleThreadExecutor()
            val castContext = CastContext.getSharedInstance(this, executor)
            if (castContext.result.castState == CastState.CONNECTED) {
                return true
            }
        }
        return false
    }

    fun AlertDialog.setDefaultFocus(buttonFocus: Int = DialogInterface.BUTTON_NEGATIVE) {
        if (!Globals.isLayout(Globals.TV or Globals.EMULATOR)) return
        this.getButton(buttonFocus).run {
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    fun Context.isUsingMobileData(): Boolean {
        val connectionManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork: Network? = connectionManager.activeNetwork
            val networkCapabilities = connectionManager.getNetworkCapabilities(activeNetwork)
            networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                    !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            connectionManager.activeNetworkInfo?.type == ConnectivityManager.TYPE_MOBILE
        }
    }

    private fun Activity?.cacheClass(clazz: String?) {
        clazz?.let { c ->
            this?.cacheDir?.let {
                Cache(
                    directory = File(it, c.toClassDir()),
                    maxSize = 20L * 1024L * 1024L
                )
            }
        }
    }

    fun Context.isAppInstalled(uri: String): Boolean {
        val pm = Wrappers.packageManager(this)

        return try {
            pm.getPackageInfo(uri, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun Context.openBrowser(
        url: String,
        fallbackWebview: Boolean = false,
        fragment: Fragment? = null,
    ) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = url.toUri()
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            this.startActivity(intent)
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun Context.getNameFull(name: String?, episode: Int?, season: Int?): String {
        val rEpisode = if (episode == 0) null else episode
        val rSeason = if (season == 0) null else season

        val seasonName = getString(R.string.season)
        val episodeName = getString(R.string.episode)
        val seasonNameShort = getString(R.string.season_short)
        val episodeNameShort = getString(R.string.episode_short)

        if (name != null) {
            return if (rEpisode != null && rSeason != null) {
                "$seasonNameShort${rSeason}:$episodeNameShort${rEpisode} $name"
            } else if (rEpisode != null) {
                "$episodeName $rEpisode. $name"
            } else {
                name
            }
        } else {
            if (rEpisode != null && rSeason != null) {
                return "$seasonName $rSeason - $episodeName $rEpisode"
            } else if (rSeason == null) {
                return "$episodeName $rEpisode"
            }
        }
        return ""
    }

    fun getFocusRequest(): AudioFocusRequest? {
        if (currentAudioFocusRequest != null) return currentAudioFocusRequest
        currentAudioFocusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(AudioAttributes.Builder().run {
                    setUsage(AudioAttributes.USAGE_MEDIA)
                    setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    build()
                })
                setAcceptsDelayedFocusGain(true)
                getAudioListener()?.let {
                    setOnAudioFocusChangeListener(it)
                }
                build()
            }
        } else null
        return currentAudioFocusRequest
    }
}
