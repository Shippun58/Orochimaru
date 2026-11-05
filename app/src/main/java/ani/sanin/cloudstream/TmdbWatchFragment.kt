package ani.sanin.cloudstream

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.bannerFallbackColor
import ani.sanin.isDarkTheme
import ani.sanin.dp
import ani.sanin.connections.simkl.Simkl
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.connections.tmdb.TmdbEpisode
import ani.sanin.connections.tmdb.TmdbGenre
import ani.sanin.connections.tmdb.TmdbImage
import ani.sanin.connections.tmdb.TmdbImages
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.connections.tmdb.TmdbSeason
import ani.sanin.databinding.FragmentTmdbWatchBinding
import ani.sanin.databinding.ItemEpisodeGridBinding
import ani.sanin.databinding.ItemEpisodeStripBinding
import ani.sanin.databinding.ItemEpisodeCompactBinding
import ani.sanin.getThemeColor
import kotlin.math.max
import kotlin.math.roundToInt
import ani.sanin.stripDividerGradient
import ani.sanin.databinding.ItemTmdbWatchHeaderBinding
import ani.sanin.databinding.ItemCountDownBinding
import ani.sanin.media.SheetSourceSelector
import ani.sanin.loadImage
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import ani.sanin.util.EPISODE_BLUR_RADIUS
import ani.sanin.util.EPISODE_BLUR_SAMPLING
import ani.sanin.util.StackBlurTransformation
import ani.sanin.settings.saving.PrefManager
import ani.sanin.notifications.subscription.TmdbSubscriptionHelper
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.toast
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import ani.sanin.settings.FAQActivity
import android.widget.LinearLayout
import android.widget.ImageButton
import com.google.android.material.chip.Chip
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import ani.sanin.media.anime.Episode as AnimeEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class TmdbWatchFragment : Fragment() {

    interface Host {
        fun onWatchBackPressed()
        fun onWatchOpenTitle(type: String, id: Int)
    }

    companion object {
        const val ARG_MEDIA_TYPE = "mediaType"
        const val ARG_MEDIA_ID = "mediaId"
        const val ARG_PLUGIN_SOURCE = "pluginSource"
        const val ARG_PLUGIN_URL = "pluginUrl"
        private const val EPISODE_CAP = 24

        private const val HEADER_ITEM_COUNT = 1

        fun newInstance(
            mediaType: String,
            mediaId: Int,
            pluginSourceId: String?,
            pluginUrl: String?
        ): TmdbWatchFragment = TmdbWatchFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MEDIA_TYPE, mediaType)
                putInt(ARG_MEDIA_ID, mediaId)
                pluginSourceId?.let { putString(ARG_PLUGIN_SOURCE, it) }
                pluginUrl?.let { putString(ARG_PLUGIN_URL, it) }
            }
        }
    }

    private var host: Host? = null

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        host = context as? Host
    }

    override fun onDetach() {
        host = null
        super.onDetach()
    }

    private lateinit var binding: FragmentTmdbWatchBinding
    private lateinit var headerBinding: ItemTmdbWatchHeaderBinding
    private var headerCountdownView: View? = null
    private var headerCountdownTimer: CountDownTimer? = null

    private var mediaType: String = "movie"
    private var mediaId: Int = -1
    private var pluginSourceId: String? = null
    private var pluginUrl: String? = null
    private var pluginLoad: LoadResponse? = null
    private val pluginEpisodes = mutableMapOf<Int, List<TmdbEpisode>>()
    private var detail: TmdbDetail? = null
    private var seasons: List<TmdbSeason> = emptyList()
    private var selectedSeason = 1
    private var episodes: List<TmdbEpisode> = emptyList()
    private var movieEpisodes: List<TmdbEpisode> = emptyList()

    private val sources by lazy { CsRepos.installed(requireContext()) }

    private var selectedSourceIndex = -1

    private var episodeStyle = 2
    private var reversed = false
    private var isResolving = false

    private var prequel: TmdbMedia? = null
    private var sequel: TmdbMedia? = null

    private val pluginMode get() = pluginUrl != null

    private lateinit var episodeAdapter: EpisodeListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentTmdbWatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mediaType = arguments?.getString(ARG_MEDIA_TYPE) ?: "movie"
        mediaId = arguments?.getInt(ARG_MEDIA_ID, -1) ?: -1
        pluginSourceId = arguments?.getString(ARG_PLUGIN_SOURCE)
        pluginUrl = arguments?.getString(ARG_PLUGIN_URL)

        if (pluginUrl != null) mediaId = pluginUrl.hashCode()
        episodeStyle = (PrefManager.getNullableCustomVal("tmdb_style", 2, Int::class.java)
            ?: 2).coerceIn(0, 3)
        if (episodeStyle == 1) episodeStyle = 2
        reversed = PrefManager.getNullableCustomVal("tmdb_reversed_$mediaId", false, Boolean::class.java)
            ?: false
        Logger.log("TMDB_WATCH: opened mediaType=$mediaType mediaId=$mediaId style=$episodeStyle reversed=$reversed")

        val hideScrollTop = PrefManager.getVal<Boolean>(PrefName.HideScrollTopButtons)
        binding.tmdbWatchBack.setOnClickListener { goBack() }
        FocusEffectUtil.applyFocusListener(binding.tmdbWatchBack)
        binding.tmdbWatchScrollTop.setOnClickListener {
            binding.tmdbWatchRecycler.scrollToPosition(0)
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbWatchScrollTop, binding.tmdbWatchScrollTop)

        var maxGridSize = (resources.displayMetrics.widthPixels.dp / 100f).roundToInt()
        maxGridSize = max(4, maxGridSize - (maxGridSize % 2))
        binding.tmdbWatchRecycler.layoutManager = GridLayoutManager(requireContext(), maxGridSize).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = when {

                    position == 0 -> maxGridSize
                    episodeStyle == 3 -> 1
                    else -> maxGridSize
                }
            }
        }
        binding.tmdbWatchRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val logo = binding.tmdbWatchLogo
                val title = binding.tmdbWatchTitle
                val offset = recyclerView.computeVerticalScrollOffset().toFloat()
                val maxTranslate = 200f * resources.displayMetrics.density
                val translation = -minOf(offset, maxTranslate)
                logo.translationY = translation
                logo.alpha = 1f - (translation / -maxTranslate)
                title.translationY = translation
                title.alpha = 1f - (translation / -maxTranslate)
                binding.tmdbWatchScrollTop.isVisible =
                    !hideScrollTop && recyclerView.computeVerticalScrollOffset() > 0
            }
        })

        load()
    }

    override fun onDestroyView() {
        cancelHeaderCountdown()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()

        if (::episodeAdapter.isInitialized) {
            episodeAdapter.refreshCache()
            loadSimklWatched()

            updateContinueCard()
        }
    }

    private fun goBack() {
        host?.onWatchBackPressed()
            ?: requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun bannerUrl(d: TmdbDetail): String? {
        val isPortrait =
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        return if (isPortrait)
            Tmdb.imageUrl(d.posterPath, 780) ?: Tmdb.imageUrl(d.backdropPath, 780)
        else
            Tmdb.imageUrl(d.backdropPath, 1280) ?: Tmdb.imageUrl(d.posterPath, 780)
    }

    private fun applyBannerDim() {
        if (!PrefManager.getVal<Boolean>(PrefName.ShowMediaBanner)) {
            binding.mediaBg?.setImageDrawable(null)
            binding.mediaBg?.visibility = View.GONE
            binding.mediaBgGradient?.visibility = View.GONE
            return
        }
        binding.mediaBg?.alpha = 0.14f
        binding.mediaBg?.visibility = View.VISIBLE
        binding.mediaBgGradient?.visibility = View.VISIBLE
    }

    private fun load() {
        lifecycleScope.launch {
            if (pluginMode) {
                loadPlugin()
                return@launch
            }
            val d = Tmdb.detail(mediaType, mediaId) ?: run {
                snackString("Could not load details")
                goBack()
                return@launch
            }
            detail = d
            if (PrefManager.getVal<Boolean>(PrefName.ShowMediaBanner)) {
                binding.mediaBg?.loadImage(bannerUrl(d))
            } else {
                binding.mediaBg?.setImageDrawable(null)
            }
            applyBannerDim()

            val logo = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) Tmdb.logoUrl(d) else null
            if (logo != null) {
                binding.tmdbWatchLogo.isVisible = true
                binding.tmdbWatchLogo.loadImage(logo)
            } else {
                binding.tmdbWatchTitle.isVisible = true
                binding.tmdbWatchTitle.text = d.displayTitle
            }

            headerBinding = ItemTmdbWatchHeaderBinding.inflate(LayoutInflater.from(requireContext()))

            if (mediaType == "tv") {
                seasons = Tmdb.seasons(mediaType, mediaId)
                selectedSeason = restoreSeason()
                episodes = Tmdb.episodes(mediaType, mediaId, selectedSeason)
            } else {

                movieEpisodes = listOf(
                    TmdbEpisode(
                        id = mediaId,
                        name = d.displayTitle,
                        episodeNumber = 1,
                        seasonNumber = 1,
                        stillPath = d.backdropPath ?: d.posterPath,
                        airDate = d.releaseDate,
                        voteAverage = d.voteAverage
                    )
                )
                loadCollectionParts(d)
            }

            buildHeader()
            buildAdapter()
            updateContinueCard()
            loadSimklWatched()

            autoSearchOnOpen()
        }
    }

    private suspend fun loadPlugin() {
        val url = pluginUrl ?: return
        val source = sources.firstOrNull { it.id == pluginSourceId } ?: run {
            snackString("Plugin not installed anymore")
            goBack()
            return
        }
        val api = withContext(Dispatchers.IO) {
            CsRuntime.apisFor(requireContext(), source).firstOrNull()
        } ?: run {
            snackString("Could not load ${source.name}")
            goBack()
            return
        }
        Logger.log("TMDB_WATCH: plugin mode, loading '$url' via ${source.name}")
        val load = withContext(Dispatchers.IO) {
            runCatching { api.load(url) }.getOrNull()
        }
        if (load == null) {
            Logger.log(android.util.Log.ERROR, "TMDB_WATCH: plugin load returned null for $url")
            snackString("Plugin returned nothing for this title")
            goBack()
            return
        }
        pluginLoad = load
        Logger.log(
            "TMDB_WATCH: plugin load -> ${load.javaClass.simpleName} '${load.name}' " +
                "(mediaId=$mediaId, api=${load.apiName})"
        )

        val poster = load.posterUrl
        val backdrop = load.backgroundPosterUrl ?: load.posterUrl
        val rating = load.score?.toFloat(10)?.toDouble() ?: 0.0
        val genres = load.tags.orEmpty().map { TmdbGenre(0, it) }
        val year = load.year?.toString()
        val images = load.logoUrl?.let { TmdbImages(logos = listOf(TmdbImage(it))) }

        if (load is TvSeriesLoadResponse) {
            mediaType = "tv"
            seasons = buildPluginSeasons(load)
            selectedSeason = restoreSeason()
            pluginEpisodes.clear()
            val tmdbEpRatings = seasons.associate { s ->
                s.seasonNumber to Tmdb.episodes("tv", mediaId, s.seasonNumber)
                    .associate { it.episodeNumber to it.voteAverage }
            }
            seasons.forEach { season ->
                pluginEpisodes[season.seasonNumber] = load.episodes
                    .filter { (it.season ?: 1) == season.seasonNumber }
                    .map { toPluginEpisode(it, tmdbEpRatings[season.seasonNumber]) }
            }
            episodes = pluginEpisodes[selectedSeason].orEmpty()
            detail = TmdbDetail(
                id = mediaId,
                name = load.name,
                overview = load.plot,
                voteAverage = rating,
                backdropPath = backdrop,
                posterPath = poster,
                firstAirDate = year?.let { "$it-01-01" },
                numberOfSeasons = seasons.size,
                genres = genres,
                images = images,
                seasons = seasons
            )
        } else {
            mediaType = "movie"
            movieEpisodes = listOf(
                TmdbEpisode(
                    id = mediaId,
                    name = load.name,
                    episodeNumber = 1,
                    seasonNumber = 1,
                    stillPath = backdrop,
                    airDate = year?.let { "$it-01-01" },
                    voteAverage = rating
                )
            )
            detail = TmdbDetail(
                id = mediaId,
                title = load.name,
                overview = load.plot,
                voteAverage = rating,
                backdropPath = backdrop,
                posterPath = poster,
                releaseDate = year?.let { "$it-01-01" },
                genres = genres,
                images = images
            )
        }

        val d = detail ?: return
        if (PrefManager.getVal<Boolean>(PrefName.ShowMediaBanner)) {
            binding.mediaBg?.loadImage(bannerUrl(d))
        } else {
            binding.mediaBg?.setImageDrawable(null)
        }
        applyBannerDim()
        val logo = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) Tmdb.logoUrl(d) else null
        if (logo != null) {
            binding.tmdbWatchLogo.isVisible = true
            binding.tmdbWatchLogo.loadImage(logo)
        } else {
            binding.tmdbWatchTitle.isVisible = true
            binding.tmdbWatchTitle.text = d.displayTitle
        }
        headerBinding = ItemTmdbWatchHeaderBinding.inflate(LayoutInflater.from(requireContext()))
        buildHeader()
        buildAdapter()
        updateContinueCard()
        loadSimklWatched()
        autoSearchOnOpen()
    }

    private fun buildPluginSeasons(load: TvSeriesLoadResponse): List<TmdbSeason> {
        val numbers = load.episodes.mapNotNull { it.season }.distinct().sorted()
            .ifEmpty { listOf(1) }
        val names = load.seasonNames.orEmpty().associate { it.season to it.name }
        return numbers.map { num ->
            TmdbSeason(
                id = num,
                name = names[num],
                seasonNumber = num,
                episodeCount = load.episodes.count { (it.season ?: 1) == num }
            )
        }
    }

    private fun toPluginEpisode(
        ep: com.lagradost.cloudstream3.Episode,
        tmdbRatings: Map<Int, Double>? = null
    ): TmdbEpisode = TmdbEpisode(
        id = ep.data.hashCode(),
        name = ep.name,
        episodeNumber = ep.episode ?: 0,
        seasonNumber = ep.season ?: 1,
        stillPath = ep.posterUrl,
        airDate = formatEpochDate(ep.date),
        voteAverage = tmdbRatings?.get(ep.episode ?: 0)?.takeIf { it > 0 } ?: 0.0
    )

    private fun formatEpochDate(epoch: Long?): String? {
        if (epoch == null || epoch <= 0L) return null
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epoch }
        return String.format(
            java.util.Locale.US,
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun pluginShells(): Map<String, AnimeEpisode>? = if (!pluginMode) {
        null
    } else if (mediaType == "tv") {
        TmdbStreamResolver.pluginEpisodeShells(mediaId, pluginEpisodes)
    } else {
        detail?.let { TmdbStreamResolver.pluginMovieShell(mediaId, it) }
    }

    private suspend fun loadCollectionParts(d: TmdbDetail) {
        val collectionId = d.collection?.id ?: return
        val parts = Tmdb.collection(collectionId)
        val idx = parts.indexOfFirst { it.id == mediaId }
        if (idx > 0) prequel = parts[idx - 1]
        if (idx >= 0 && idx < parts.lastIndex) sequel = parts[idx + 1]
    }

    private fun parseAirDateMillis(raw: String): Long? {
        val parts = raw.split("-")
        if (parts.size < 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        return try {
            val cal = java.util.Calendar.getInstance().apply {
                set(year, month - 1, day, 20, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        } catch (_: Exception) { null }
    }

    private fun cancelHeaderCountdown() {
        headerCountdownTimer?.cancel()
        headerCountdownTimer = null
    }

    private fun startHeaderCountdown(d: TmdbDetail) {
        cancelHeaderCountdown()
        headerCountdownView = null
        if (mediaType != "tv") return
        val nextEp = d.nextEpisodeToAir ?: return
        val airMillis = nextEp.airDate?.let { parseAirDateMillis(it) } ?: return
        val diff = airMillis - System.currentTimeMillis()
        if (diff <= 0L || diff > 1000L * 86400 * 28) return

        val b = ItemCountDownBinding.inflate(LayoutInflater.from(requireContext()))
        val secs = diff / 1000
        fun tickText(millis: Long): String {
            val a = millis / 1000
            return getString(
                R.string.time_format,
                a / 86400,
                a % 86400 / 3600,
                a % 86400 % 3600 / 60,
                a % 86400 % 3600 % 60
            )
        }
        b.mediaCountdownText.text = getString(R.string.episode_release_countdown, nextEp.episodeNumber)
        b.mediaCountdown.text = tickText(diff)
        headerCountdownView = b.root
        headerCountdownTimer = object : CountDownTimer(diff, 1000) {
            override fun onTick(millis: Long) {
                b.mediaCountdown.text = tickText(millis)
            }
            override fun onFinish() {
                b.mediaCountdownContainer.visibility = View.GONE
            }
        }.start()
    }

    private fun buildHeader() {
        val h = headerBinding

        if (selectedSourceIndex == -1) selectedSourceIndex = defaultSourceIndex()
        h.tmdbWatchSourceChips.removeAllViews()
        h.tmdbWatchSourceTitle.text = when {
            sources.isEmpty() -> getString(R.string.tmdb_watch_no_sources)
            selectedSourceIndex == -1 -> getString(R.string.tmdb_watch_auto_search)
            else -> getString(R.string.tmdb_watch_sources)
        }
        sources.forEachIndexed { index, source ->
            val chip = LayoutInflater.from(requireContext()).inflate(R.layout.item_tmdb_chip, h.tmdbWatchSourceChips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = source.name
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            chip.setTextColor(
                androidx.core.content.ContextCompat.getColorStateList(
                    requireContext(), ani.sanin.R.color.chip_text_color
                )
            )
            chip.tag = index
            if (index == selectedSourceIndex) chip.isChecked = true
            chip.setOnClickListener {
                val nowChecked = chip.isChecked
                selectedSourceIndex = if (nowChecked) index else -1
                saveSourcePref()
                lastAutoSource = null
                TmdbStreamResolver.invalidateLinks(mediaId)
                resolveJob?.cancel()
                resolveJob = null
                isResolving = false
                setSourceStatus(getString(R.string.tmdb_watch_sources))
                refreshChips(h.tmdbWatchSourceChips)
                centerOn(h.tmdbWatchSourceScroll, chip)
                episodeAdapter.submitEpisodes(episodesOrMovie())
                if (nowChecked) refreshSelected() else autoSearchOnOpen()
            }
            FocusEffectUtil.applyFocusListener(chip)
            h.tmdbWatchSourceChips.addView(chip)
        }

        if (mediaType == "tv") {
            if (seasons.isNotEmpty()) {
                h.tmdbWatchSeasonScroll.isVisible = true
                h.tmdbWatchSingleSeason.isVisible = false
                h.tmdbWatchSeasonChips.removeAllViews()
                seasons.forEach { season ->
                    val chip = LayoutInflater.from(requireContext()).inflate(R.layout.item_tmdb_chip, h.tmdbWatchSeasonChips, false) as Chip
                    chip.id = View.generateViewId()
                    chip.text = "Season ${season.seasonNumber}"
                    chip.isCheckable = true
                    chip.isClickable = true
                    chip.isFocusable = true
                    chip.setTextColor(
                        androidx.core.content.ContextCompat.getColorStateList(
                            requireContext(), ani.sanin.R.color.chip_text_color
                        )
                    )
                    chip.tag = season.seasonNumber
                    if (season.seasonNumber == selectedSeason) chip.isChecked = true
                    chip.setOnClickListener {
                        selectedSeason = season.seasonNumber
                        refreshChips(h.tmdbWatchSeasonChips)
                        centerOn(h.tmdbWatchSeasonScroll, chip)
                        loadEpisodesForSeason()
                    }
                    FocusEffectUtil.applyFocusListener(chip)
                    h.tmdbWatchSeasonChips.addView(chip)
                }
            } else {
                h.tmdbWatchSeasonScroll.isVisible = false
                h.tmdbWatchSingleSeason.isVisible = true
                h.tmdbWatchSingleSeason.text = getString(R.string.tmdb_watch_one_season)
            }
        } else {
            h.tmdbWatchMovieRow.isVisible = !pluginMode
            h.tmdbWatchPrequel.isVisible = !pluginMode && prequel != null
            h.tmdbWatchPrequel.setOnClickListener {
                prequel?.let { openWatch(it.type, it.id) }
            }
            FocusEffectUtil.applyFocusListener(h.tmdbWatchPrequel)
            h.tmdbWatchSequel.isVisible = !pluginMode && sequel != null
            h.tmdbWatchSequel.setOnClickListener {
                sequel?.let { openWatch(it.type, it.id) }
            }
            FocusEffectUtil.applyFocusListener(h.tmdbWatchSequel)
        }

        h.tmdbWatchRefresh.setOnClickListener { refreshSelected() }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchRefresh)

        h.tmdbWatchSettings.setOnClickListener {
            toast(getString(R.string.tmdb_watch_no_settings))
        }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchSettings, h.tmdbWatchSettings, true)

        h.tmdbWatchGlobe.setOnClickListener {
            snackString(R.string.anime_watch_no_webpage)
        }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchGlobe, h.tmdbWatchGlobe, true)

        h.tmdbWatchSort.setOnClickListener {
            reversed = !reversed
            h.tmdbWatchSort.rotation = if (reversed) 180f else 0f
            snackString(
                if (reversed) R.string.tmdb_watch_down_to_up
                else R.string.tmdb_watch_up_to_down
            )
            applyStyle(episodeStyle, reversed)
        }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchSort, h.tmdbWatchSort, true)
        h.tmdbWatchSort.rotation = if (reversed) 180f else 0f

        h.tmdbWatchDownload.setOnClickListener {
            snackString("Download is coming soon")
        }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchDownload, h.tmdbWatchDownload, true)

        h.tmdbWatchFaq.setOnClickListener {
            startActivity(Intent(requireContext(), FAQActivity::class.java))
        }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchFaq, h.tmdbWatchFaq, true)

        updateNotifyIcon()
        h.tmdbWatchNotify.setOnClickListener {
            val currentlySubscribed = TmdbSubscriptionHelper.isSubscribed(mediaId)
            val d = detail
            if (currentlySubscribed) {
                TmdbSubscriptionHelper.delete(mediaId)
                updateNotifyIcon()
                toast(getString(R.string.tmdb_watch_notify_off))
            } else {

                val latest = d?.lastEpisodeToAir
                TmdbSubscriptionHelper.save(
                    TmdbSubscriptionHelper.Companion.TmdbSubscriptionItem(
                        id = mediaId,
                        name = d?.displayTitle ?: "",
                        type = mediaType,
                        image = d?.let { t -> Tmdb.imageUrl(t.posterPath ?: t.backdropPath, 342) },
                        banner = d?.let { t -> Tmdb.imageUrl(t.backdropPath ?: t.posterPath, 780) },

                        lastSeason = latest?.seasonNumber ?: -1,
                        lastEpisode = latest?.episodeNumber ?: -1
                    )
                )
                updateNotifyIcon()
                toast(getString(R.string.tmdb_watch_notify_on))
            }
        }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchNotify)

        h.tmdbWatchAppearance.setImageResource(
            when (episodeStyle) {
                0 -> R.drawable.ic_round_view_array_24
                2 -> R.drawable.ic_round_view_list_24
                else -> R.drawable.ic_round_view_comfy_24
            }
        )
        h.tmdbWatchAppearance.setOnClickListener {

            episodeStyle = when (episodeStyle) {
                0 -> 3
                3 -> 2
                else -> 0
            }
            h.tmdbWatchAppearance.setImageResource(
                when (episodeStyle) {
                    0 -> R.drawable.ic_round_view_array_24
                    2 -> R.drawable.ic_round_view_list_24
                    else -> R.drawable.ic_round_view_comfy_24
                }
            )
            snackString(
                when (episodeStyle) {
                    0 -> R.string.tmdb_watch_style_bars
                    2 -> R.string.tmdb_watch_style_strips
                    else -> R.string.compact
                }
            )
            applyStyle(episodeStyle, reversed)
        }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchAppearance)

        h.tmdbWatchContinueCard.setOnClickListener { onContinueClick() }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchContinueCard)

        h.tmdbWatchEpisodeCount.text = "${episodesOrMovie().size} ${getString(R.string.episodes).trim()}"

        wireHeaderFocusChain(h)

        h.root.doOnLayout {
            if (!isAdded || !binding.root.isShown) return@doOnLayout
            val headerFocus = h.root.findFocus()
            val fragFocus = binding.root.findFocus()
            val entryFocus = (headerFocus != null && headerFocus !is Chip) ||
                    fragFocus === binding.tmdbWatchBack ||
                    fragFocus === binding.tmdbWatchScrollTop ||
                    (fragFocus == null && activity is TmdbWatchActivity)
            if (entryFocus) focusSelectedSourceChip()
        }
    }

    private fun wireHeaderFocusChain(h: ItemTmdbWatchHeaderBinding) {
        val left = arrayOf(
            R.id.tmdbWatchRefresh, R.id.tmdbWatchSettings,
            R.id.tmdbWatchGlobe, R.id.tmdbWatchNotify
        )
        val right = arrayOf(
            R.id.tmdbWatchAppearance, R.id.tmdbWatchSort,
            R.id.tmdbWatchDownload, R.id.tmdbWatchFaq
        )
        val leftViews = left.map { h.root.findViewById<View>(it) }
        val rightViews = right.map { h.root.findViewById<View>(it) }

        val sourceChips = (0 until h.tmdbWatchSourceChips.childCount)
            .mapNotNull { h.tmdbWatchSourceChips.getChildAt(it) as? Chip }
        val firstSource = sourceChips.firstOrNull()
        val lastSource = sourceChips.lastOrNull()
        val selectedSource = sourceChips.firstOrNull { it.isChecked } ?: firstSource
        val sourceTarget = selectedSource?.id ?: firstSource?.id ?: R.id.tmdbWatchSourceChips
        val rightSourceTarget = lastSource?.id ?: sourceTarget

        val seasonChips = (0 until h.tmdbWatchSeasonChips.childCount)
            .mapNotNull { h.tmdbWatchSeasonChips.getChildAt(it) as? Chip }

        h.tmdbWatchSourceScroll.isFocusable = false
        h.tmdbWatchSourceScroll.isFocusableInTouchMode = false
        h.tmdbWatchSourceChips.isFocusable = false
        h.tmdbWatchSourceChips.isFocusableInTouchMode = false
        h.tmdbWatchSeasonScroll.isFocusable = false
        h.tmdbWatchSeasonScroll.isFocusableInTouchMode = false
        h.tmdbWatchSeasonChips.isFocusable = false
        h.tmdbWatchSeasonChips.isFocusableInTouchMode = false

        leftViews.forEachIndexed { i, v ->
            v.nextFocusUpId = left[(i - 1 + left.size) % left.size]
            v.nextFocusDownId = left[(i + 1) % left.size]
            v.nextFocusLeftId = View.NO_ID
            v.nextFocusRightId = sourceTarget
        }
        sourceChips.forEachIndexed { idx, chip ->
            chip.nextFocusLeftId = if (idx == 0) R.id.tmdbWatchRefresh else sourceChips[idx - 1].id
            chip.nextFocusRightId = if (idx == sourceChips.lastIndex) R.id.tmdbWatchAppearance else sourceChips[idx + 1].id
            chip.nextFocusUpId = View.NO_ID
        }
        h.tmdbWatchSourceScroll.nextFocusLeftId = R.id.tmdbWatchRefresh
        h.tmdbWatchSourceScroll.nextFocusRightId = R.id.tmdbWatchAppearance
        h.tmdbWatchSourceChips.nextFocusLeftId = R.id.tmdbWatchRefresh
        h.tmdbWatchSourceChips.nextFocusRightId = R.id.tmdbWatchAppearance

        if (seasonChips.isNotEmpty()) {
            seasonChips.forEachIndexed { idx, chip ->
                chip.nextFocusLeftId = if (idx == 0) R.id.tmdbWatchRefresh else seasonChips[idx - 1].id
                chip.nextFocusRightId = if (idx == seasonChips.lastIndex) R.id.tmdbWatchAppearance else seasonChips[idx + 1].id
                chip.nextFocusUpId = sourceTarget
            }
            h.tmdbWatchSeasonScroll.nextFocusLeftId = R.id.tmdbWatchRefresh
            h.tmdbWatchSeasonScroll.nextFocusRightId = R.id.tmdbWatchAppearance
            h.tmdbWatchSeasonScroll.nextFocusUpId = R.id.tmdbWatchSourceChips
            h.tmdbWatchSeasonChips.nextFocusLeftId = R.id.tmdbWatchRefresh
            h.tmdbWatchSeasonChips.nextFocusRightId = R.id.tmdbWatchAppearance
            h.tmdbWatchSeasonChips.nextFocusUpId = R.id.tmdbWatchSourceChips
        }

        rightViews.forEachIndexed { i, v ->
            v.nextFocusUpId = right[(i - 1 + right.size) % right.size]
            v.nextFocusDownId = right[(i + 1) % right.size]
            v.nextFocusLeftId = rightSourceTarget
            v.nextFocusRightId = right[i]
        }

        syncHeaderDownTargets(h)

        applyDownIntoEpisodes(
            h.tmdbWatchSourceScroll,
            h.tmdbWatchSourceChips,
            h.tmdbWatchSeasonScroll,
            h.tmdbWatchSeasonChips,
            h.tmdbWatchContinueCard,
            h.tmdbWatchNotify,
            h.tmdbWatchFaq,
            *sourceChips.toTypedArray(),
            *seasonChips.toTypedArray(),
        )
    }

    private fun syncHeaderDownTargets(h: ItemTmdbWatchHeaderBinding) {
        val sourceChips = (0 until h.tmdbWatchSourceChips.childCount)
            .mapNotNull { h.tmdbWatchSourceChips.getChildAt(it) as? Chip }
        val seasonChips = (0 until h.tmdbWatchSeasonChips.childCount)
            .mapNotNull { h.tmdbWatchSeasonChips.getChildAt(it) as? Chip }
        val firstSeason = seasonChips.firstOrNull()
        val continueId = if (h.tmdbWatchContinueCard.isVisible) R.id.tmdbWatchContinueCard else View.NO_ID

        sourceChips.forEach { chip ->
            chip.nextFocusDownId = if (h.tmdbWatchSeasonScroll.isVisible && firstSeason != null) {
                firstSeason.id
            } else {
                continueId
            }
        }
        h.tmdbWatchSourceScroll.nextFocusDownId =
            if (h.tmdbWatchSeasonScroll.isVisible) R.id.tmdbWatchSeasonChips else continueId
        h.tmdbWatchSourceChips.nextFocusDownId = h.tmdbWatchSourceScroll.nextFocusDownId
        seasonChips.forEach { it.nextFocusDownId = continueId }
        h.tmdbWatchSeasonScroll.nextFocusDownId = continueId
        h.tmdbWatchSeasonChips.nextFocusDownId = continueId
        h.tmdbWatchNotify.nextFocusDownId = continueId
        h.tmdbWatchFaq.nextFocusDownId = continueId
    }

    private fun applyDownIntoEpisodes(vararg views: View?) {
        for (view in views) {
            view ?: continue
            view.setOnKeyListener { v, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN || event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                if (v.nextFocusDownId != View.NO_ID) return@setOnKeyListener false
                focusFirstEpisode()
            }
        }
    }

    private fun focusFirstEpisode(): Boolean {
        if (!::binding.isInitialized) return false
        val recycler = binding.tmdbWatchRecycler
        val adapter = recycler.adapter ?: return false
        if (adapter.itemCount <= HEADER_ITEM_COUNT) return false
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            if (recycler.getChildAdapterPosition(child) >= HEADER_ITEM_COUNT) {
                child.requestFocus()
                return true
            }
        }
        recycler.scrollToPosition(HEADER_ITEM_COUNT)
        focusEpisodeAfterLayout(recycler)
        return true
    }

    private fun focusEpisodeAfterLayout(recycler: RecyclerView) {
        val focus = {
            recycler.findViewHolderForAdapterPosition(HEADER_ITEM_COUNT)
                ?.itemView?.requestFocus()
        }
        val observer = recycler.viewTreeObserver
        if (!observer.isAlive) {
            recycler.post { focus() }
            return
        }
        observer.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val obs = recycler.viewTreeObserver
                    if (obs.isAlive) obs.removeOnPreDrawListener(this)
                    recycler.post { focus() }
                    return true
                }
            }
        )
    }

    private fun episodeUpTargetId(): Int {
        if (!::headerBinding.isInitialized) return View.NO_ID
        val h = headerBinding
        val seasonChips = (0 until h.tmdbWatchSeasonChips.childCount)
            .mapNotNull { h.tmdbWatchSeasonChips.getChildAt(it) as? Chip }
        return seasonChips.firstOrNull()?.id
            ?: if (h.tmdbWatchContinueCard.isVisible) R.id.tmdbWatchContinueCard
            else View.NO_ID
    }

    private fun centerOn(scroll: android.widget.HorizontalScrollView, chip: Chip) {
        chip.doOnLayout {
            val w = scroll.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            scroll.scrollTo(((chip.left - w / 2) + (chip.width / 2)).coerceAtLeast(0), 0)
        }
    }

    private fun centerCheckedChip(
        scroll: android.widget.HorizontalScrollView,
        group: com.google.android.material.chip.ChipGroup
    ) {
        val checked = (0 until group.childCount)
            .mapNotNull { group.getChildAt(it) as? Chip }
            .firstOrNull { it.isChecked } ?: return
        centerOn(scroll, checked)
    }

    fun focusSelectedSourceChip(): Boolean {
        if (!::headerBinding.isInitialized || view == null || !binding.root.isShown) return false
        centerCheckedChip(headerBinding.tmdbWatchSourceScroll, headerBinding.tmdbWatchSourceChips)
        centerCheckedChip(headerBinding.tmdbWatchSeasonScroll, headerBinding.tmdbWatchSeasonChips)
        val target = (0 until headerBinding.tmdbWatchSourceChips.childCount)
            .mapNotNull { headerBinding.tmdbWatchSourceChips.getChildAt(it) as? Chip }
            .firstOrNull { it.isChecked } ?: return false
        target.requestFocus()
        return true
    }

    private fun refreshChips(group: com.google.android.material.chip.ChipGroup) {
        group.post {
            for (i in 0 until group.childCount) {
                val chip = group.getChildAt(i) as Chip
                chip.isChecked = chip.tag == if (group === headerBinding.tmdbWatchSeasonChips) selectedSeason else selectedSourceIndex
            }
        }
    }

    private fun defaultSourceIndex(): Int {

        val savedId = PrefManager.getNullableCustomVal("tmdb_source_$mediaId", null, String::class.java)
        if (!savedId.isNullOrEmpty()) {
            val idx = sources.indexOfFirst { it.id == savedId }
            if (idx >= 0) return idx
        }
        val preferredId = pluginSourceId
            ?: PrefManager.getVal<String>(PrefName.ContentSource).takeIf { it != "simkl" && it != "tmdb" }
        if (preferredId != null) {
            val idx = sources.indexOfFirst { it.id == preferredId }
            if (idx >= 0) return idx
        }
        return if (sources.isNotEmpty()) 0 else -1
    }

    private fun updateNotifyIcon() {
        val enabled = TmdbSubscriptionHelper.isSubscribed(mediaId)
        headerBinding.tmdbWatchNotify.setImageResource(
            if (enabled) R.drawable.ic_round_notifications_active_24
            else R.drawable.ic_round_notifications_none_24
        )
    }

    private fun setSourceStatus(text: String) {

        val filler = "\u2800".repeat(120)
        headerBinding.tmdbWatchSourceTitle.ellipsize = TextUtils.TruncateAt.END
        headerBinding.tmdbWatchSourceTitle.isSelected = false
        headerBinding.tmdbWatchSourceTitle.text = text + filler
        headerBinding.tmdbWatchSpinner.isVisible = text.startsWith("Searching")
    }

    private fun applyStyle(style: Int, rev: Boolean) {
        episodeStyle = style.coerceIn(0, 3)
        reversed = rev
        PrefManager.setCustomVal("tmdb_style", style)
        PrefManager.setCustomVal("tmdb_reversed_$mediaId", rev)
        Logger.log("TMDB_WATCH: layout style=$style reversed=$rev")
        episodeAdapter.updateStyle(style)
        episodeAdapter.submitEpisodes(episodesOrMovie())
    }

    private fun displayList(eps: List<TmdbEpisode>): List<TmdbEpisode> =
        if (reversed) eps.reversed() else eps

    private fun episodesOrMovie(): List<TmdbEpisode> =
        displayList(if (mediaType == "tv") episodes.take(EPISODE_CAP) else movieEpisodes)

    private fun loadEpisodesForSeason() {

        val cumulativeOffset = seasons.filter { it.seasonNumber < selectedSeason }
            .sumOf { it.episodeCount }

        if (pluginMode) {
            episodes = pluginEpisodes[selectedSeason].orEmpty()
            headerBinding.tmdbWatchEpisodeCount.text = "${episodes.size} ${getString(R.string.episodes).trim()}"
            episodeAdapter.setCumulativeOffset(cumulativeOffset)
            episodeAdapter.submitEpisodes(displayList(episodes.take(EPISODE_CAP)))
            updateContinueCard()
            return
        }
        lifecycleScope.launch {
            val eps = Tmdb.episodes(mediaType, mediaId, selectedSeason)
            episodes = eps
            headerBinding.tmdbWatchEpisodeCount.text = "${eps.size} ${getString(R.string.episodes).trim()}"
            episodeAdapter.setCumulativeOffset(cumulativeOffset)
            episodeAdapter.submitEpisodes(displayList(eps.take(EPISODE_CAP)))
            updateContinueCard()
        }
    }

    private fun buildAdapter() {
        episodeAdapter = EpisodeListAdapter(episodeStyle, episodesOrMovie(), { episode ->
            onEpisodeClick(episode)
        }, { cumulativeEp ->

            lifecycleScope.launch(Dispatchers.IO) {
                Simkl.setProgress(type = mediaType, title = detail?.displayTitle ?: "", year = detail?.releaseDate?.take(4)?.toIntOrNull(),
                    tmdbId = if (!pluginMode) mediaId else null, imdbId = detail?.externalIds?.imdbId,
                    anilistId = null, episodeNum = cumulativeEp)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    loadSimklWatched()
                    snackString("Progress set to $cumulativeEp")
                }
            }
        }, { episodeUpTargetId() })
        binding.tmdbWatchRecycler.adapter = episodeAdapter

        val d = detail
        val header: View
        if (d != null) {
            startHeaderCountdown(d)
            header = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                headerCountdownView?.let { addView(it) }
                addView(headerBinding.root)
            }
        } else {
            header = headerBinding.root
        }
        episodeAdapter.setHeader(header)
    }

    private fun loadSimklWatched() {
        val realTmdbId = if (!pluginMode) mediaId else null
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val watched = if (realTmdbId != null && realTmdbId > 0 && mediaType == "tv") {
                runCatching {
                    val item = Simkl.getShowLibrary()
                        .firstOrNull { it.ids?.tmdb == realTmdbId }
                    if (item == null) return@runCatching emptySet()

                    val totalWatched = item.totalWatched
                    val completedEps = item.episodes
                        ?.filter { it.completed == true }
                        ?.mapNotNull { it.number }
                        ?.toSet() ?: emptySet()
                    if (totalWatched > 0) {

                        var cumulative = 0
                        val result = mutableSetOf<Int>()
                        for (s in 1..50) {
                            val eps = try { Tmdb.episodes(mediaType, realTmdbId, s) } catch (_: Exception) { emptyList() }
                            if (eps.isEmpty()) break
                            for (ep in eps) {
                                cumulative++
                                if (cumulative <= totalWatched) {
                                    result.add(ep.episodeNumber)
                                }
                            }

                            if (cumulative >= totalWatched) break
                        }
                        if (completedEps.isNotEmpty()) result.addAll(completedEps)
                        result
                    } else {

                        completedEps
                    }
                }.getOrDefault(emptySet())
            } else emptySet()
            withContext(Dispatchers.Main) {
                if (::episodeAdapter.isInitialized) {
                    episodeAdapter.setWatchedEpisodes(watched)
                }
            }
        }
    }

    private fun onEpisodeClick(episode: TmdbEpisode) {

        if (isResolving && autoSearchJob?.isActive != true) return

        if (!isAdded || childFragmentManager.isStateSaved) return
        autoSearchJob?.cancel()
        autoSearchJob = null
        isResolving = false
        val d = detail ?: return
        val season = if (mediaType == "tv") episode.seasonNumber.takeIf { it > 0 } ?: selectedSeason else null
        val ep = if (mediaType == "tv") episode.episodeNumber else null
        Logger.log(
            "TMDB_WATCH: episode click '${d.displayTitle}' season=$season ep=$ep " +
                "sourceIdx=$selectedSourceIndex (${currentSourceName()})"
        )
        val sourceName = currentSourceName()
        val fetchingLabel = getString(R.string.tmdb_watch_fetching, sourceName)
        val groupingEnabled = PrefManager.getVal<Boolean>(PrefName.GroupServersByHostAndLanguageTags)
        val picker = SheetSourceSelector.newInstanceLoading(fetchingLabel, grouped = groupingEnabled)
        fun fillPicker(result: TmdbStreamResolver.StreamResult.Success) {
            val labels = ArrayList(result.links.map { link ->
                val host = link.host.takeIf { it.isNotBlank() }
                val row = if (groupingEnabled && host != null && host != link.label) "$host - ${link.label}" else link.label
                "$row  •  $sourceName"
            })
            picker.setOnSelect { idx ->
                if (isAdded) {
                    val link = result.links[idx]
                    Logger.log(
                        "TMDB_WATCH: opening player url=${link.url} " +
                            "host=${runCatching { java.net.URI(link.url).host }.getOrNull() ?: "unknown"} " +
                            "referer=${link.referer} headers=${link.headers}"
                    )
                    saveLastPlayed(season, ep)
                    val source = sources.getOrNull(selectedSourceIndex) ?: lastAutoSource
                    if (source == null) {
                        snackString(getString(R.string.tmdb_watch_no_sources))
                        return@setOnSelect
                    }
                    lifecycleScope.launch {
                        TmdbStreamResolver.launchPlayer(
                            requireContext(),
                            mediaId,
                            mediaType,
                            d,
                            source,
                            season,
                            ep,
                            result.links,
                            link.label,
                            episodesOverride = pluginShells(),
                            load = pluginLoad
                        )
                    }
                }
            }
            picker.updateSources(labels)
        }

        var cached = TmdbStreamResolver.cachedLinks(mediaId, sourceName, season, ep)

        if (cached == null) {
            for (src in sources) {
                val c = TmdbStreamResolver.cachedLinks(mediaId, src.name, season, ep)
                if (c != null && c.links.isNotEmpty()) {
                    Logger.log("TMDB_WATCH: fallback cached ${c.links.size} links via ${src.name} for S${season}E$ep (requested $sourceName)")
                    cached = c
                    break
                }
            }
        }
        if (cached != null && cached.links.isNotEmpty()) {
            Logger.log(
                "TMDB_WATCH: cached ${cached.links.size} links for " +
                    "'${d.displayTitle}' S${season}E$ep via $sourceName"
            )
            setSourceStatus(
                "${getString(R.string.found)} : ${cached.matchName ?: d.displayTitle} from $sourceName"
            )
            fillPicker(cached)
            picker.show(childFragmentManager, "tmdbWatchServerSelector")
            return
        }
        setSourceStatus(getString(R.string.tmdb_watch_searching, d.displayTitle))

        picker.show(childFragmentManager, "tmdbWatchServerSelector")
        isResolving = true
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { resolve(season, ep) }
            isResolving = false
            if (!isAdded || childFragmentManager.isStateSaved) {
                Logger.log("TMDB_WATCH: discarding links, activity not showable")
                return@launch
            }
            when (result) {
                is TmdbStreamResolver.StreamResult.Error -> {
                    setSourceStatus(getString(R.string.tmdb_watch_sources))
                    Logger.log(android.util.Log.ERROR, "TMDB_WATCH: failed: ${result.message}")
                    picker.updateSources(listOf("─── ${result.message} ───"))
                }
                is TmdbStreamResolver.StreamResult.Success -> {
                    TmdbStreamResolver.cacheLinks(mediaId, sourceName, season, ep, result)
                    setSourceStatus(
                        "${if (selectedSourceIndex == -1) getString(R.string.found) else getString(R.string.selected)} : " +
                            "${result.matchName ?: d.displayTitle} from $sourceName"
                    )
                    Logger.log(
                        "TMDB_WATCH: ${result.links.size} links via $sourceName: " +
                            result.links.mapIndexed { i, l -> "$i:${l.label}" }.joinToString(" | ")
                    )
                    fillPicker(result)
                }
            }
        }
    }

    private fun autoSearchOnOpen() {
        autoSearchJob?.cancel()
        if (isResolving) return
        val d = detail ?: return
        val season = if (mediaType == "tv") selectedSeason else null

        val resumeEp = if (mediaType == "tv") lastPlayed()?.second else null
        Logger.log("TMDB_WATCH: auto search on open for '${d.displayTitle}' (season=$season ep=$resumeEp)")
        setSourceStatus(getString(R.string.tmdb_watch_searching, d.displayTitle))
        isResolving = true
        autoSearchJob = lifecycleScope.launch {
            val (source, result) = try {
                withContext(Dispatchers.IO) {
                    if (sources.isEmpty()) {
                        null to TmdbStreamResolver.StreamResult.Error(getString(R.string.tmdb_watch_no_sources))
                    } else if (pluginMode) {
                        val home = sources.firstOrNull { it.id == pluginSourceId }
                        val load = pluginLoad
                        if (home != null && load != null) {
                            home to TmdbStreamResolver.resolvePluginStreams(
                                requireContext(), home, load, season, resumeEp
                            )
                        } else {
                            null to TmdbStreamResolver.StreamResult.Error("Plugin data missing")
                        }
                    } else {
                        TmdbStreamResolver.resolveAuto(requireContext(), sources, d, season, resumeEp)
                    }
                }
            } finally {

                if (autoSearchJob == coroutineContext[Job]) {
                    autoSearchJob = null
                    isResolving = false
                }
            }
            lastAutoSource = source
            when (result) {
                is TmdbStreamResolver.StreamResult.Error -> {
                    setSourceStatus(getString(R.string.tmdb_watch_sources))
                    Logger.log(android.util.Log.ERROR, "TMDB_WATCH: auto search failed: ${result.message}")
                    snackString(result.message)
                }
                is TmdbStreamResolver.StreamResult.Success -> {
                    val foundName = source?.name ?: currentSourceName()
                    setSourceStatus(
                        "${getString(R.string.found)} : ${result.matchName ?: d.displayTitle} from $foundName"
                    )
                    source?.let { src ->
                        val idx = sources.indexOf(src)
                        if (idx >= 0) {
                            selectedSourceIndex = idx
                        }
                    }
                    refreshChips(headerBinding.tmdbWatchSourceChips)
                    Logger.log("TMDB_WATCH: auto search found ${result.links.size} links via $foundName")
                    snackString("${result.links.size} links found via $foundName")

                    TmdbStreamResolver.cacheLinks(mediaId, foundName, season, resumeEp, result)

                    if (!isAdded || childFragmentManager.isStateSaved) {
                        Logger.log("TMDB_WATCH: auto search landed after state saved, sheet skipped (links cached)")
                        return@launch
                    }
                    val epForSheet = if (mediaType == "tv") {
                        resumeEp?.let { ep ->
                            episodes.firstOrNull { it.seasonNumber == selectedSeason && it.episodeNumber == ep }
                        } ?: episodes.firstOrNull()
                    } else {
                        movieEpisodes.firstOrNull()
                    }
                    if (epForSheet != null) onEpisodeClick(epForSheet)
                }
            }
        }
    }

    private var lastAutoSource: CsInstalledSource? = null

    private var autoSearchJob: Job? = null

    private var resolveJob: Job? = null

    private suspend fun resolve(season: Int?, ep: Int?): TmdbStreamResolver.StreamResult {
        val d = detail ?: return TmdbStreamResolver.StreamResult.Error("No title loaded")
        if (selectedSourceIndex == -1) {
            if (sources.isEmpty()) {
                return TmdbStreamResolver.StreamResult.Error(getString(R.string.tmdb_watch_no_sources))
            }
            if (pluginMode) {

                val home = sources.firstOrNull { it.id == pluginSourceId }
                val load = pluginLoad
                if (home != null && load != null) {
                    val result = TmdbStreamResolver.resolvePluginStreams(requireContext(), home, load, season, ep)
                    lastAutoSource = home
                    return result
                }
            }
            val (source, result) = TmdbStreamResolver.resolveAuto(requireContext(), sources, d, season, ep)
            lastAutoSource = source
            return result
        }
        val source = sources.getOrNull(selectedSourceIndex)
            ?: return TmdbStreamResolver.StreamResult.Error("Source not found")
        val load = pluginLoad
        if (pluginMode && source.id == pluginSourceId && load != null) {
            return TmdbStreamResolver.resolvePluginStreams(requireContext(), source, load, season, ep)
        }
        return TmdbStreamResolver.resolveStreams(requireContext(), source, d, season, ep)
    }

    private fun currentSourceName(): String = when {
        selectedSourceIndex == -1 -> lastAutoSource?.name ?: getString(R.string.tmdb_watch_auto_search)
        else -> sources.getOrNull(selectedSourceIndex)?.name ?: getString(R.string.tmdb_watch_auto_search)
    }

    private fun refreshSelected() {
        if (isResolving && autoSearchJob?.isActive != true) return
        autoSearchJob?.cancel()
        autoSearchJob = null
        isResolving = false
        val d = detail ?: return
        val season = if (mediaType == "tv") selectedSeason else null
        val ep = null
        Logger.log("TMDB_WATCH: refresh pressed for '${d.displayTitle}' (sourceIdx=$selectedSourceIndex)")
        snackString(getString(R.string.tmdb_watch_loading, d.displayTitle))
        setSourceStatus(getString(R.string.tmdb_watch_searching, d.displayTitle))
        isResolving = true
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { resolve(season, ep) }
            isResolving = false
            when (result) {
                is TmdbStreamResolver.StreamResult.Error -> {
                    setSourceStatus(getString(R.string.tmdb_watch_sources))
                    snackString(result.message)
                }
                is TmdbStreamResolver.StreamResult.Success -> {
                    setSourceStatus(
                        "${if (selectedSourceIndex == -1) getString(R.string.found) else getString(R.string.selected)} : " +
                            "${result.matchName ?: d.displayTitle} from ${currentSourceName()}"
                    )
                    snackString("${result.links.size} links found via ${currentSourceName()}")
                }
            }
        }
    }

    private fun onContinueClick() {
        val d = detail ?: return
        val (season, ep) = lastPlayed() ?: run {
            if (mediaType == "tv") onEpisodeClick(episodes.firstOrNull() ?: return)
            else onEpisodeClick(movieEpisodes.firstOrNull() ?: return)
            return
        }
        val episode = if (mediaType == "tv") {
            episodes.firstOrNull { it.seasonNumber == season && it.episodeNumber == ep }
                ?: TmdbEpisode(id = 0, name = "S${season}E$ep", episodeNumber = ep, seasonNumber = season)
        } else {
            movieEpisodes.firstOrNull() ?: return
        }
        onEpisodeClick(episode)
    }

    private fun restoreSeason(): Int {
        val saved = lastPlayed()?.first ?: return seasons.firstOrNull()?.seasonNumber ?: 1
        return seasons.firstOrNull { it.seasonNumber == saved }?.seasonNumber ?: 1
    }

    private fun saveLastPlayed(season: Int?, ep: Int?) {
        val savedSeason = season ?: 1
        val savedEp = ep ?: 1
        PrefManager.setCustomVal("tmdb_last_${mediaId}", "$savedSeason:$savedEp")
    }

    private fun lastPlayed(): Pair<Int, Int>? {
        val raw = PrefManager.getNullableCustomVal("tmdb_last_${mediaId}", null, String::class.java)
        if (raw != null) {
            val parts = raw.split(":")
            if (parts.size == 2) {
                val s = parts[0].toIntOrNull()
                val e = parts[1].toIntOrNull()
                if (s != null && e != null) return s to e
            }
        }

        val realTmdbId = if (!pluginMode) mediaId else null
        if (realTmdbId != null && realTmdbId > 0 && mediaType == "tv") {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    Simkl.getShowLibrary()
                        .firstOrNull { it.ids?.tmdb == realTmdbId }
                        ?.lastWatchedSeasonEp
                }
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun saveSourcePref() {

        if (selectedSourceIndex == -1) {
            PrefManager.setCustomVal("tmdb_source_$mediaId", "")
            return
        }
        val source = sources.getOrNull(selectedSourceIndex) ?: return
        PrefManager.setCustomVal("tmdb_source_$mediaId", source.id)
    }

    private fun updateContinueCard() {
        val h = headerBinding
        val syntheticId = TmdbStreamResolver.syntheticId(mediaId)

        val (s, e) = lastPlayed() ?: (if (mediaType == "tv") selectedSeason to 1 else 1 to 1)
        val epKey = if (mediaType == "tv") "S${s}E$e" else "1"
        val pos = PrefManager.getNullableCustomVal("${syntheticId}_$epKey", 0L, Long::class.java) ?: 0L
        val max = PrefManager.getNullableCustomVal("${syntheticId}_${epKey}_max", 0L, Long::class.java) ?: 0L
        if (pos <= 0 || max <= 0) {
            h.tmdbWatchContinueCard.isVisible = false
            syncHeaderDownTargets(h)
            return
        }
        val detail = detail ?: return
        h.tmdbWatchContinueCard.isVisible = true
        h.tmdbWatchContinueImage.loadImage(Tmdb.imageUrl(detail.backdropPath ?: detail.posterPath, 780))
        val mm = TimeUnit.MILLISECONDS.toMinutes(pos)
        val ss = TimeUnit.MILLISECONDS.toSeconds(pos) % 60
        val episodeTitle = episodes.firstOrNull { it.seasonNumber == s && it.episodeNumber == e }?.name
            ?: (if (mediaType == "tv") "S${s} E$e" else detail.displayTitle)
        h.tmdbWatchContinueText.text = episodeTitle
        h.tmdbWatchContinueDetail.text = getString(R.string.tmdb_watch_continue_detail, s, e, mm, ss)
        val div = (pos.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        val barParams = h.tmdbWatchContinueProgress.layoutParams as LinearLayout.LayoutParams
        barParams.weight = div
        h.tmdbWatchContinueProgress.layoutParams = barParams
        val emptyParams = h.tmdbWatchContinueProgressEmpty.layoutParams as LinearLayout.LayoutParams
        emptyParams.weight = 1f - div
        h.tmdbWatchContinueProgressEmpty.layoutParams = emptyParams

        syncHeaderDownTargets(h)
    }

    private fun openWatch(type: String, id: Int) {
        host?.onWatchOpenTitle(type, id)
    }

    private class EpisodeListAdapter(
        private var style: Int,
        private var items: List<TmdbEpisode>,
        private val onClick: (TmdbEpisode) -> Unit,
        private val onLongClick: (Int) -> Unit = {},

        private val firstEpisodeUpTarget: () -> Int = { View.NO_ID }
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var header: View? = null
        private var watchedEpisodes: Set<Int> = emptySet()
        private var cumulativeOffset = 0

        fun setCumulativeOffset(offset: Int) {
            cumulativeOffset = offset
        }

        private val blurUnwatched: Boolean get() = PrefManager.getVal(PrefName.BlurUnwatchedEpisodes)
        private val greyWatched: Boolean get() = PrefManager.getVal(PrefName.GreyWatchedEpisodes)

        fun setHeader(view: View) {
            header = view
            notifyItemInserted(0)
        }

        fun setWatchedEpisodes(eps: Set<Int>) {
            watchedEpisodes = eps
            notifyDataSetChanged()
        }

        fun refreshCache() {
            notifyDataSetChanged()
        }

        fun updateStyle(newStyle: Int) {
            style = newStyle
            notifyDataSetChanged()
        }

        fun submitEpisodes(newItems: List<TmdbEpisode>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size + (if (header != null) 1 else 0)

        override fun getItemViewType(position: Int): Int =
            if (header != null && position == 0) 0
            else when (style) {
                2 -> 2
                3 -> 3
                else -> 1
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                0 -> HeaderVH(header!!)
                2 -> StripVH(ItemEpisodeStripBinding.inflate(LayoutInflater.from(parent.context), parent, false))
                3 -> CompactVH(ItemEpisodeCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false))
                else -> GridVH(ItemEpisodeGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) == 0) return
            val epIndex = position - (if (header != null) 1 else 0)
            val ep = items[epIndex]

            if (epIndex == 0) holder.itemView.nextFocusUpId = firstEpisodeUpTarget()
            val title = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber}"
            val date = ep.airDate.orEmpty()
            val image = Tmdb.imageUrl(ep.stillPath, 500)
            val isWatched = watchedEpisodes.contains(ep.episodeNumber)
            when (holder) {
                is GridVH -> {
                    holder.binding.itemEpisodeTitle.text = title
                    holder.binding.itemEpisodeNumber.text = ep.episodeNumber.toString()
                    holder.binding.itemEpisodeDate.text = date
                    holder.binding.itemEpisodeDate.isVisible = date.isNotBlank()
                    if (ep.voteAverage > 0) {
                        holder.binding.itemEpisodeRating.isVisible = true
                        holder.binding.itemEpisodeRating.text =
                            "★ " + String.format("%.1f", ep.voteAverage)
                    } else {
                        holder.binding.itemEpisodeRating.isVisible = false
                    }
                    loadEpisodeImage(holder.binding.itemMediaImage, image, isWatched)
                    holder.binding.itemMediaProgressCont.isVisible = false
                    holder.binding.itemEpisodeSparkle1.isVisible = false
                    holder.binding.itemEpisodeSparkle2.isVisible = false
                    applyWatchedState(
                        holder.binding.itemEpisodeViewed,
                        holder.binding.itemEpisodeViewedCover,
                        holder.binding.itemMediaImage,
                        holder.binding.itemEpisodeTitle,
                        holder.binding.itemEpisodeDate,
                        isWatched,
                        holder.binding.itemEpisodeNumber
                    )
                    holder.binding.root.setOnClickListener { onClick(ep) }
                    holder.binding.root.setOnLongClickListener {
                        onLongClick(cumulativeOffset + ep.episodeNumber)
                        true
                    }
                    FocusEffectUtil.applyFocusListener(holder.binding.root)
                }
                is StripVH -> {
                    holder.binding.itemEpisodeTitle.text = title
                    holder.binding.itemEpisodeDate.text = date
                    holder.binding.itemEpisodeDate.isVisible = date.isNotBlank()
                    if (ep.voteAverage > 0) {
                        holder.binding.itemEpisodeRating.isVisible = true
                        holder.binding.itemEpisodeRating.text =
                            "★ " + String.format("%.1f", ep.voteAverage)
                    } else {
                        holder.binding.itemEpisodeRating.isVisible = false
                    }
                    val desc = ep.overview.orEmpty()
                    holder.binding.itemEpisodeDesc.text = desc
                    holder.binding.itemEpisodeDesc.isVisible = desc.isNotBlank()
                    holder.binding.itemEpisodeDesc.maxLines = 2
                    holder.binding.itemEpisodeDesc.alpha = 0.58f
                    var expanded = false
                    holder.binding.itemEpisodeMore.setText(R.string.strips_more)
                    holder.binding.itemEpisodeMore.setOnClickListener {
                        expanded = !expanded
                        holder.binding.itemEpisodeDesc.maxLines = if (expanded) 100 else 2
                        holder.binding.itemEpisodeMore.setText(
                            if (expanded) R.string.strips_less else R.string.strips_more
                        )
                        holder.binding.itemEpisodeDesc.post {
                            val layout = holder.binding.itemEpisodeDesc.layout
                            val truncated = layout != null && layout.lineCount > 0 &&
                                (layout.getEllipsisCount(layout.lineCount - 1) > 0)
                            holder.binding.itemEpisodeMore.isVisible = expanded || truncated
                        }
                    }
                    holder.binding.itemEpisodeDesc.post {
                        val layout = holder.binding.itemEpisodeDesc.layout
                        val truncated = layout != null && layout.lineCount > 0 &&
                            (layout.getEllipsisCount(layout.lineCount - 1) > 0)
                        holder.binding.itemEpisodeMore.isVisible = truncated
                    }
                    holder.binding.itemEpisodeFiller.isVisible = false
                    holder.binding.itemEpisodeFillerView.isVisible = false
                    loadEpisodeImage(holder.binding.itemMediaImage, image, isWatched)
                    holder.binding.itemMediaProgressCont.isVisible = false
                    holder.binding.itemEpisodeDivider.background =
                        holder.itemView.context.stripDividerGradient()
                    applyWatchedState(
                        holder.binding.itemEpisodeViewed,
                        holder.binding.itemEpisodeViewedCover,
                        holder.binding.itemMediaImage,
                        holder.binding.itemEpisodeTitle,
                        holder.binding.itemEpisodeDate,
                        isWatched
                    )
                    holder.binding.root.setOnClickListener { onClick(ep) }
                    holder.binding.root.setOnLongClickListener {
                        onLongClick(cumulativeOffset + ep.episodeNumber)
                        true
                    }
                    FocusEffectUtil.applyFocusListener(holder.binding.root)
                }
                is CompactVH -> {
                    holder.binding.itemEpisodeNumber.text = ep.episodeNumber.toString()
                    holder.binding.itemEpisodeFillerView.isVisible = false
                    holder.binding.itemEpisodeViewedCover.isVisible = isWatched
                    holder.binding.itemEpisodeNumber.alpha = when {
                        isWatched && greyWatched -> 0.5f
                        !isWatched && blurUnwatched -> 0.5f
                        else -> 1f
                    }
                    holder.binding.itemMediaProgressCont.isVisible = false
                    holder.binding.root.setOnClickListener { onClick(ep) }
                    holder.binding.root.setOnLongClickListener {
                        onLongClick(cumulativeOffset + ep.episodeNumber)
                        true
                    }
                    FocusEffectUtil.applyFocusListener(holder.binding.root)
                }
                else -> {}
            }
        }

        private fun applyWatchedState(
            viewedIcon: View,
            viewedCover: View,
            image: android.widget.ImageView,
            title: android.widget.TextView,
            date: android.widget.TextView,
            isWatched: Boolean,
            number: android.widget.TextView? = null
        ) {
            if (isWatched) {
                viewedCover.isVisible = true
                viewedIcon.isVisible = true
                if (greyWatched) {
                    val cm = ColorMatrix().apply { setSaturation(0f) }
                    image.colorFilter = ColorMatrixColorFilter(cm)
                    title.alpha = 0.5f
                    date.alpha = 0.5f
                    number?.alpha = 0.5f
                } else {
                    image.colorFilter = null
                    title.alpha = 1f
                    date.alpha = 1f
                    number?.alpha = 1f
                }
            } else {
                viewedCover.isVisible = false
                viewedIcon.isVisible = false
                image.colorFilter = null
                if (blurUnwatched) {
                    title.alpha = 0.5f
                    date.alpha = 0.5f
                    number?.alpha = 0.5f
                } else {
                    title.alpha = 1f
                    date.alpha = 1f
                    number?.alpha = 1f
                }
            }
        }

        private fun loadEpisodeImage(
            image: android.widget.ImageView,
            url: String?,
            isWatched: Boolean
        ) {
            if (url.isNullOrEmpty()) {

                image.setBackgroundColor(0xFF000000.toInt())
                image.setImageResource(R.drawable.hourglass_24)
                image.scaleType = android.widget.ImageView.ScaleType.CENTER
                return
            }
            if (!isWatched && blurUnwatched) {
                val ctx = image.context
                val glideUrl = com.bumptech.glide.load.model.GlideUrl(url)
                Glide.with(ctx).load(glideUrl)
                    .override(400, 0)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .transform(StackBlurTransformation(EPISODE_BLUR_RADIUS, EPISODE_BLUR_SAMPLING))
                    .into(image)
            } else {
                image.loadImage(url)
            }
        }

        class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView)
        class GridVH(val binding: ItemEpisodeGridBinding) : RecyclerView.ViewHolder(binding.root)
        class StripVH(val binding: ItemEpisodeStripBinding) : RecyclerView.ViewHolder(binding.root)
        class CompactVH(val binding: ItemEpisodeCompactBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
