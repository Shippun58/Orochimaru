package ani.sanin.media
import ani.sanin.settings.AnimUtils

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.text.bold
import androidx.core.text.color
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.connections.LogoApi
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anilist.AnilistMutations
import ani.sanin.connections.anilist.api.FuzzyDate
import ani.sanin.connections.anilist.GenresViewModel
import ani.sanin.connections.mal.MAL
import ani.sanin.copyToClipboard
import ani.sanin.currActivity
import ani.sanin.databinding.ActivityGenreBinding
import ani.sanin.databinding.FragmentMediaInfoBinding
import ani.sanin.databinding.ItemChipBinding
import ani.sanin.databinding.ItemQuelsBinding
import ani.sanin.databinding.ItemTitleChipgroupBinding
import ani.sanin.databinding.ItemTitleRecyclerBinding
import ani.sanin.databinding.ItemTitleTextBinding
import ani.sanin.databinding.ItemTitleTrailerBinding
import ani.sanin.isOnline
import ani.sanin.loadImage
import ani.sanin.getThemeColor
import ani.sanin.openLinkInBrowser
import ani.sanin.profile.User
import ani.sanin.setSafeOnClickListener
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import com.xwray.groupie.GroupieAdapter
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.net.URLEncoder

class MediaInfoFragment : Fragment() {
    private var _binding: FragmentMediaInfoBinding? = null
    private val binding get() = _binding!!
    private var loaded = false
    private var type = "ANIME"
    private var infoTimer: CountDownTimer? = null
    private val genreModel: GenresViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView();_binding = null
    }

    @Suppress("UNUSED_PRIVATE_FUNCTION")
    private fun displayAnimeAdaptation(adaptation: Any?) {
    }

    @Suppress("UNUSED_PRIVATE_FUNCTION")
    private fun displayMangaChapterPrediction(oldPrediction: Any?) {
    }

    @Suppress("UNUSED_PRIVATE_FUNCTION")
    private fun displayNextChapterPrediction(prediction: Any?) {
    }

    fun View.fadeIn(duration: Long = 250) {
        if (isVisible) return
        alpha = 0f
        translationY = 20f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .start()
    }

    fun View.fadeOut(duration: Long = 200) {
        if (visibility != View.VISIBLE) return
        animate()
            .alpha(0f)
            .translationY(20f)
            .setDuration(duration)
            .withEndAction {
                visibility = View.GONE
                alpha = 1f
                translationY = 0f
            }
            .start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val model: MediaDetailsViewModel by activityViewModels()
        val offline: Boolean =
            PrefManager.getVal(PrefName.OfflineMode) || !isOnline(requireContext())
        binding.mediaInfoProgressBar.isGone = loaded
        binding.mediaInfoContainer.isVisible = loaded
        val activity = requireActivity() as MediaDetailsActivity

        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaInfoScroll.scrollTo(0, 0)
        }

        model.getMedia().observe(viewLifecycleOwner) { media ->
            if (_binding == null || !isAdded) return@observe
            if (media != null) {
                loaded = true
                infoTimer?.cancel()
                infoTimer = null
                binding.mediaInfoGenreContainer.removeAllViews()
                binding.mediaInfoExternalLinksContainer.removeAllViews()
                binding.mediaInfoSynonymsContainer.removeAllViews()
                binding.mediaInfoTrailerHost.removeAllViews()
                binding.mediaInfoOpEdContainer.removeAllViews()
                binding.mediaInfoTagsContainer.removeAllViews()
                binding.mediaInfoOpEdContainer.visibility = View.VISIBLE
                binding.mediaInfoTagsContainer.visibility = View.VISIBLE
                binding.mediaInfoFinalContainer.minimumHeight = 0

                binding.mediaInfoProgressBar.visibility = View.GONE
                binding.mediaInfoContainer.visibility = View.VISIBLE

                val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                binding.mediaInfoTrailerRow.orientation =
                    if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

                val opEdLp = binding.mediaInfoOpEdContainer.layoutParams as LinearLayout.LayoutParams
                if (isPortrait) {
                    opEdLp.width = LinearLayout.LayoutParams.MATCH_PARENT
                    opEdLp.weight = 0f
                } else {
                    opEdLp.width = 0
                    opEdLp.weight = 1f
                }
                binding.mediaInfoOpEdContainer.layoutParams = opEdLp

                (binding.mediaInfoMetaSquare.parent as? ViewGroup)?.let { row ->
                    if (row.indexOfChild(binding.mediaInfoMetaIcons) != 0) {
                        row.removeView(binding.mediaInfoMetaIcons)
                        row.addView(binding.mediaInfoMetaIcons, 0)
                    }
                }

                binding.mediaInfoLogo.visibility = View.GONE
                binding.mediaInfoTitle.visibility = View.GONE
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    var logoUrl = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) LogoApi.getLogoUrl(media.id) else null
                    if (_binding == null || !isAdded) return@launch
                    if (logoUrl.isNullOrBlank()) {

                        val title = media.userPreferredName ?: media.name
                        if (!title.isNullOrBlank()) {
                            logoUrl = runCatching {
                                val results = ani.sanin.connections.tmdb.Tmdb.search(title)
                                val match = results.firstOrNull()
                                if (match != null) ani.sanin.connections.tmdb.Tmdb.logoUrl(match.type, match.id) else null
                            }.getOrNull()
                        }
                        if (_binding == null || !isAdded) return@launch
                    }
                    _binding?.let { b ->
                        if (!logoUrl.isNullOrBlank()) {
                            b.mediaInfoLogo.visibility = View.VISIBLE
                            b.mediaInfoLogo.loadImage(logoUrl)
                        } else {
                            b.mediaInfoTitle.visibility = View.VISIBLE
                            b.mediaInfoTitle.text = media.userPreferredName ?: media.name
                        }
                    }
                }
                binding.mediaInfoTitle.setOnLongClickListener {
                    copyToClipboard(media.userPreferredName ?: media.name ?: "")
                    true
                }
                binding.mediaInfoPlayCard.setOnClickListener {
                    (requireActivity() as? MediaDetailsActivity)?.watchTabOpener?.invoke()
                }
                FocusEffectUtil.applyFocusListener(
                    binding.mediaInfoPlayCard,
                    cornerRadiusPx = 24f * resources.displayMetrics.density
                )

                binding.mediaInfoStatus.text = media.status ?: ""
                val statusColor = when {
                    media.status?.equals("RELEASING", true) == true
                            || media.status == getString(R.string.status_releasing) ->
                        Color.parseColor("#76FF03")
                    media.status?.equals("FINISHED", true) == true
                            || media.status == getString(R.string.status_finished) ->
                        Color.parseColor("#F44336")
                    media.status?.equals("NOT_YET_RELEASED", true) == true
                            || media.status == getString(R.string.status_not_yet_released) ->
                        Color.parseColor("#00E5FF")
                    else -> Color.WHITE
                }
                binding.mediaInfoStatus.setTextColor(statusColor)

                val startFmt = media.startDate?.let { formatFuzzyDate(it) }
                val endFmt = media.endDate?.let { formatFuzzyDate(it) }
                val airedStr = buildString {
                    if (startFmt != null) append("Aired: ").append(startFmt)
                    if (startFmt != null) append("\n")
                    append("To: ").append(endFmt ?: "???")
                }
                if (airedStr.isNotBlank()) {
                    binding.mediaInfoAired.text = airedStr
                    binding.mediaInfoAired.visibility = View.VISIBLE
                } else {
                    binding.mediaInfoAired.visibility = View.GONE
                }

                val desc = HtmlCompat.fromHtml(
                    (media.description ?: "null").replace("\\n", "<br>").replace("\\\"", "\""),
                    HtmlCompat.FROM_HTML_MODE_LEGACY
                )
                val infoDesc = if (desc.toString() != "null") desc else getString(R.string.no_description_available)
                binding.mediaInfoDescription.text = infoDesc
                binding.mediaInfoShowMore.setOnClickListener {
                    if (binding.mediaInfoDescription.maxLines > 5) {
                        binding.mediaInfoDescription.maxLines = 5
                        binding.mediaInfoShowMore.setText(R.string.show_more)
                    } else {
                        binding.mediaInfoDescription.maxLines = Int.MAX_VALUE
                        binding.mediaInfoShowMore.setText(R.string.show_less)
                    }
                    binding.mediaInfoDescription.requestLayout()
                }
                binding.mediaInfoDescription.post {
                    if (_binding == null) return@post
                    val tv = binding.mediaInfoDescription
                    val oldMax = tv.maxLines
                    tv.maxLines = Int.MAX_VALUE
                    tv.requestLayout()
                    tv.post {
                        if (_binding == null) return@post
                        val fullLines = tv.lineCount
                        tv.maxLines = oldMax
                        tv.requestLayout()
                        binding.mediaInfoShowMore.visibility =
                            if (fullLines > 5) View.VISIBLE else View.GONE
                    }
                }

                val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
                fun updateAddToList() {
                    val statuses: Array<String> = resources.getStringArray(R.array.status)
                    val statusStrings = resources.getStringArray(R.array.status_anime)
                    val userStatus =
                        if (media.userStatus != null) statusStrings[statuses.indexOf(media.userStatus).coerceAtLeast(0)] else statusStrings[0]
                    if (media.userStatus != null) {
                        binding.mediaInfoAddToList.text = userStatus
                    } else {
                        binding.mediaInfoAddToList.setText(R.string.add_list)
                    }
                }
                updateAddToList()
                val fm = requireActivity().supportFragmentManager
                binding.mediaInfoAddToList.setOnClickListener {
                    if (rescueMode) {
                        if (MAL.token != null) {
                            if (fm.findFragmentByTag("dialog") == null)
                                MediaListDialogFragment().show(fm, "dialog")
                        } else snackString("Please login to MAL")
                    } else if (Anilist.userid != null) {
                        if (fm.findFragmentByTag("dialog") == null)
                            MediaListDialogFragment().show(fm, "dialog")
                    } else snackString(getString(R.string.please_login_anilist))
                }
                binding.mediaInfoAddToList.setOnLongClickListener {
                    PrefManager.setCustomVal(
                        "${media.id}_progressDialog",
                        true,
                    )
                    snackString(getString(R.string.auto_update_reset))
                    true
                }

                fun updateQuickInfo() {
                    val totalEps = media.anime?.totalEpisodes
                    val nextEp = media.anime?.nextAiringEpisode
                    val released = if (nextEp != null) (nextEp - 1).coerceAtLeast(0) else (totalEps ?: 0)
                    val total = totalEps ?: released
                    val watched = media.userProgress ?: 0

                    binding.mediaInfoReleased.text = "$released of $total"

                    val primary = requireActivity().getThemeColor(com.google.android.material.R.attr.colorPrimary)
                    binding.mediaInfoWatchProgress.text = SpannableStringBuilder().apply {
                        bold { color(primary) { append("$watched") } }
                        append(" of $released")
                    }

                    if (nextEp != null) {
                        binding.mediaInfoNextEpisode.text = "Episode $nextEp"
                        startAiringTimer(media)
                    } else {
                        binding.mediaInfoNextEpisode.text = media.status ?: "—"
                        binding.mediaInfoNextTimer.visibility = View.GONE
                    }
                }
                updateQuickInfo()

                if (media.format?.startsWith("LOCAL") == true) {
                    binding.mediaInfoMapping.visibility = View.VISIBLE
                    binding.mediaInfoMapping.setOnClickListener {
                        val isAnime = media.anime != null
                        val isNovel = media.format == "LOCAL_NOVEL"
                        val folderName = media.folderName ?: media.name ?: media.nameRomaji
                        val dialog = LocalMappingSearchDialog.newInstance(
                            folderName = folderName,
                            isAnime = isAnime,
                            isNovel = isNovel
                        ) { _ ->
                            val updatedMedia = media.copy(id = 0)
                            model.loading = false
                            model.loadMedia(updatedMedia)
                        }
                        dialog.show(fm, "localMapping")
                    }
                }

                var isFavSyncRunning = false
                fun syncMediaFavStateIfNeeded() {
                    if (rescueMode || Anilist.userid == null || media.isFav || isFavSyncRunning) return
                    isFavSyncRunning = true
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val favType = if (media.anime != null) {
                                AnilistMutations.FavType.ANIME
                            } else {
                                AnilistMutations.FavType.MANGA
                            }
                            val isUserFav = withContext(Dispatchers.IO) {
                                Anilist.query.isUserFav(favType, media.id)
                            }
                            if (isUserFav) {
                                media.isFav = true
                            }
                        } finally {
                            isFavSyncRunning = false
                        }
                    }
                }
                if (Anilist.userid != null && !rescueMode) {
                    if (media.isFav) binding.mediaInfoFav.setImageDrawable(
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_round_favorite_24)
                    )
                    binding.mediaInfoFav.visibility = View.VISIBLE
                    binding.mediaInfoFav.setOnClickListener {
                        media.isFav = !media.isFav
                        lifecycleScope.launch(Dispatchers.IO) {
                            Anilist.mutation.toggleFav(media.anime != null, media.id)
                            Refresh.all()
                        }
                        if (media.isFav) {
                            binding.mediaInfoFav.setImageDrawable(
                                ContextCompat.getDrawable(requireContext(), R.drawable.ic_round_favorite_24)
                            )
                        } else {
                            binding.mediaInfoFav.setImageDrawable(
                                ContextCompat.getDrawable(requireContext(), R.drawable.ic_round_favorite_border_24)
                            )
                        }
                    }
                    syncMediaFavStateIfNeeded()
                } else {
                    binding.mediaInfoFav.visibility = View.VISIBLE
                }

                binding.mediaInfoShare.setOnClickListener {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_TEXT, media.shareLink)
                    startActivity(Intent.createChooser(i, media.userPreferredName))
                }
                binding.mediaInfoShare.setOnLongClickListener {
                    openLinkInBrowser(media.shareLink)
                    true
                }

                binding.mediaInfoComment.setOnClickListener {
                    (requireActivity() as? MediaDetailsActivity)?.commentTabOpener?.invoke()
                }

                FocusEffectUtil.applyFocusListener(
                    binding.mediaInfoAddToList,
                    binding.mediaInfoFav,
                    binding.mediaInfoShare,
                    binding.mediaInfoMapping,
                )

                val infoName = media.name ?: media.nameRomaji
                binding.mediaInfoName.text = infoName
                binding.mediaInfoName.setOnLongClickListener {
                    copyToClipboard(media.name ?: media.nameRomaji)
                    true
                }
                if (media.name != null) binding.mediaInfoNameRomajiContainer.visibility =
                    View.VISIBLE
                val infoNameRomaji = media.nameRomaji
                binding.mediaInfoNameRomaji.text = infoNameRomaji
                binding.mediaInfoNameRomaji.setOnLongClickListener {
                    copyToClipboard(media.nameRomaji)
                    true
                }
                val scoreVal = media.meanScore?.let { (it / 10.0).toString() } ?: "??"
                val scoreSpan = SpannableString("★ $scoreVal")
                scoreSpan.setSpan(
                    ForegroundColorSpan(Color.parseColor("#FFD700")),
                    0, 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                binding.mediaInfoMeanScore.text = scoreSpan
                binding.mediaInfoFormat.text = media.format ?: "—"
                binding.mediaInfoSource.text = media.source ?: "—"
                binding.mediaInfoStudio.text = "—"
                binding.mediaInfoAuthor.text = "—"
                if (media.anime != null) {
                    if (media.anime.mainStudio != null) {
                        binding.mediaInfoStudio.text = media.anime.mainStudio!!.name
                        binding.mediaInfoStudio.setOnClickListener {
                            ContextCompat.startActivity(
                                requireActivity(),
                                Intent(requireContext(), StudioActivity::class.java).putExtra(
                                    "studio",
                                    media.anime.mainStudio!! as Serializable
                                ),
                                null
                            )
                        }
                    }
                    if (media.anime.author != null) {
                        binding.mediaInfoAuthor.text = media.anime.author!!.name
                        binding.mediaInfoAuthor.setOnClickListener {
                            ContextCompat.startActivity(
                                requireActivity(),
                                Intent(requireContext(), AuthorActivity::class.java).putExtra(
                                    "author",
                                    media.anime.author!! as Serializable
                                ),
                                null
                            )
                        }
                    }
                    if (!media.anime.producers.isNullOrEmpty()) {
                        val validProducers = media.anime.producers!!.filter { it.id != "null" }
                        if (validProducers.isNotEmpty()) {
                            val bind = ItemTitleChipgroupBinding.inflate(
                                LayoutInflater.from(context),
                                binding.mediaInfoContainer,
                                false
                            )
                            bind.itemTitle.text = getString(R.string.producers)
                            bind.root.tag = "dynamic_view"
                            binding.mediaInfoContainer.addView(bind.root, 1)
                            validProducers.forEach { producer ->
                                val chip = ItemChipBinding.inflate(
                                    LayoutInflater.from(context),
                                    bind.itemChipGroup,
                                    false
                                ).root
                                chip.text = producer.name ?: ""
                                chip.setSafeOnClickListener {
                                    ContextCompat.startActivity(
                                        requireActivity(),
                                        Intent(activity, StudioActivity::class.java).putExtra(
                                            "studio",
                                            producer as Serializable
                                        ),
                                        null
                                    )
                                }
                                bind.itemChipGroup.addView(chip)
                            }
                        }
                    }
                }

                val parent = _binding?.mediaInfoContainer!!
                for (i in parent.childCount - 1 downTo 0) {
                    val child = parent.getChildAt(i)
                    if (child.tag == "dynamic_view") {
                        parent.removeViewAt(i)
                    }
                }

                val screenWidth = resources.displayMetrics.run { widthPixels / density }

                if (media.synonyms.isNotEmpty()) {
                    val bind = ItemTitleChipgroupBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    )
                    bind.root.tag = "dynamic_view"
                    for (position in media.synonyms.indices) {
                        val chip = ItemChipBinding.inflate(
                            LayoutInflater.from(context),
                            bind.itemChipGroup,
                            false
                        ).root
                        chip.text = media.synonyms[position]
                        chip.setOnLongClickListener { copyToClipboard(media.synonyms[position]);true }
                        bind.itemChipGroup.addView(chip)
                    }
                    binding.mediaInfoSynonymsContainer.addView(bind.root)
                }
                if (!media.users.isNullOrEmpty() && !offline) {
                    val users: ArrayList<User> = media.users ?: arrayListOf()
                    val currentUserId = Anilist.userid
                    if (Anilist.token != null && currentUserId != null && media.userStatus != null) {
                        users.add(
                            0,
                            User(
                                id = currentUserId,
                                name = getString(R.string.you),
                                pfp = Anilist.avatar,
                                banner = "",
                                status = media.userStatus,
                                score = media.userScore.toFloat(),
                                progress = media.userProgress,
                                totalEpisodes = media.anime?.totalEpisodes,
                                nextAiringEpisode = media.anime?.nextAiringEpisode
                            )
                        )
                    }
                    ItemTitleRecyclerBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    ).apply {
                        itemTitle.visibility = View.GONE
                        itemRecycler.adapter =
                            MediaSocialAdapter(users, type, requireActivity())
                        itemRecycler.layoutManager = LinearLayoutManager(
                            requireContext(),
                            LinearLayoutManager.HORIZONTAL,
                            false
                        )
                        root.tag = "dynamic_view"
                        parent.addView(root)
                    }
                }
                if (media.trailer != null && !offline) {
                    @Suppress("DEPRECATION")
                    class MyChrome : WebChromeClient() {
                        private var mCustomView: View? = null
                        private var mCustomViewCallback: CustomViewCallback? = null
                        private var mOriginalSystemUiVisibility = 0
                        override fun onHideCustomView() {
                            (requireActivity().window.decorView as FrameLayout).removeView(mCustomView)
                            mCustomView = null
                            requireActivity().window.decorView.systemUiVisibility = mOriginalSystemUiVisibility
                            mCustomViewCallback!!.onCustomViewHidden()
                            mCustomViewCallback = null
                        }
                        override fun onShowCustomView(
                            paramView: View,
                            paramCustomViewCallback: CustomViewCallback
                        ) {
                            if (mCustomView != null) { onHideCustomView(); return }
                            mCustomView = paramView
                            mOriginalSystemUiVisibility = requireActivity().window.decorView.systemUiVisibility
                            mCustomViewCallback = paramCustomViewCallback
                            (requireActivity().window.decorView as FrameLayout).addView(
                                mCustomView,
                                FrameLayout.LayoutParams(-1, -1)
                            )
                            requireActivity().window.decorView.systemUiVisibility =
                                3846 or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        }
                    }
                    val bind = ItemTitleTrailerBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    )
                    bind.mediaInfoTrailer.apply {
                        visibility = View.VISIBLE
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                        settings.userAgentString = null
                        isSoundEffectsEnabled = true
                        webChromeClient = MyChrome()
                        val trailerId = media.trailer!!
                        var expanded = false
                        fun expandTrailer() {
                            expanded = true
                            binding.mediaInfoOpEdContainer.visibility = View.GONE
                            bind.mediaInfoTrailerText.visibility = View.GONE
                            val w = resources.displayMetrics.widthPixels
                            binding.mediaInfoFinalContainer.minimumHeight = 0
                            binding.mediaInfoTrailerRow.orientation = LinearLayout.VERTICAL
                            binding.mediaInfoTrailerRow.layoutParams =
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                            binding.mediaInfoTrailerHost.layoutParams =
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    (w * 9f / 16f).toInt()
                                )
                        }
                        fun shrinkTrailer() {
                            expanded = false
                            binding.mediaInfoOpEdContainer.visibility = View.VISIBLE
                            bind.mediaInfoTrailerText.visibility = View.VISIBLE
                            binding.mediaInfoFinalContainer.minimumHeight = 0
                            val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                            binding.mediaInfoTrailerRow.orientation =
                                if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                            val opEdLp = binding.mediaInfoOpEdContainer.layoutParams as LinearLayout.LayoutParams
                            if (isPortrait) {
                                opEdLp.width = LinearLayout.LayoutParams.MATCH_PARENT
                                opEdLp.weight = 0f
                            } else {
                                opEdLp.width = 0
                                opEdLp.weight = 1f
                            }
                            binding.mediaInfoOpEdContainer.layoutParams = opEdLp
                            binding.mediaInfoTrailerRow.layoutParams =
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                            binding.mediaInfoTrailerHost.layoutParams =
                                LinearLayout.LayoutParams(
                                    if (isPortrait) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    if (isPortrait) 0f else 1f
                                )
                        }
                        addJavascriptInterface(object {
                            @android.webkit.JavascriptInterface
                            fun loadVideo() {
                                context.let {
                                    (it as? android.app.Activity)?.runOnUiThread {
                                        expandTrailer()
                                        val trailerHtml = """
                                            <!DOCTYPE html>
                                            <html><head>
                                                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                                <style>*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent;}
                                                html,body{width:100%;height:100%;background:#000;overflow:hidden;}
                                                iframe{width:100%;height:100%;border:none;display:block;}</style>
                                            </head><body>
                                                <iframe id="ytplayer" src="https://www.youtube-nocookie.com/embed/$trailerId?autoplay=1&rel=0&modestbranding=1&controls=1&fs=0&enablejsapi=1"
                                                allow="accelerometer;autoplay;clipboard-write;encrypted-media;gyroscope;picture-in-picture" frameborder="0"></iframe>
                                                <script>
                                                window.addEventListener('message', function(e) {
                                                    try {
                                                        var d = JSON.parse(e.data);
                                                        if (d.event === 'onStateChange' && (d.info === 2 || d.info === 0)) {
                                                            Android.pauseVideo();
                                                        }
                                                    } catch(ex) {}
                                                });
                                                </script>
                                            </body></html>
                                        """.trimIndent()
                                        loadDataWithBaseURL("https://www.youtube-nocookie.com", trailerHtml, "text/html", "utf-8", null)
                                    }
                                }
                            }
                            @android.webkit.JavascriptInterface
                            fun pauseVideo() {
                                if (!expanded) return
                                context.let {
                                    (it as? android.app.Activity)?.runOnUiThread {
                                        shrinkTrailer()
                                    }
                                }
                            }
                        }, "Android")
                        loadDataWithBaseURL("https://www.youtube-nocookie.com", placeholderHtml(trailerId), "text/html", "utf-8", null)
                    }
                    bind.root.tag = "dynamic_view"
                    binding.mediaInfoTrailerHost.addView(bind.root)
                }

                if (media.anime != null && (media.anime.op.isNotEmpty() || media.anime.ed.isNotEmpty()) && !offline) {
                    val markWon = Markwon.builder(requireContext())
                        .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                    fun makeLink(a: String): String {
                        val first = a.indexOf('"').let { if (it != -1) it else return a } + 1
                        val end = a.indexOf('"', first).let { if (it != -1) it else return a }
                        val name = a.subSequence(first, end).toString()
                        return "${a.subSequence(0, first)}[${name}](https://www.youtube.com/results?search_query=${
                            URLEncoder.encode(name, "utf-8")
                        })${a.subSequence(end, a.length)}"
                    }
                    fun makeText(textView: TextView, arr: ArrayList<String>) {
                        var op = ""
                        arr.forEach { op += "\n" + makeLink(it) }
                        op = op.removePrefix("\n")
                        textView.setOnClickListener {
                            if (textView.maxLines == 4) ObjectAnimator.ofInt(textView, "maxLines", 100).setDuration(950).start()
                            else ObjectAnimator.ofInt(textView, "maxLines", 4).setDuration(400).start()
                        }
                        markWon.setMarkdown(textView, op)
                    }
                    if (media.anime.op.isNotEmpty()) {
                        ItemTitleTextBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                            itemTitle.setText(R.string.opening)
                            makeText(itemText, media.anime.op)
                            root.tag = "dynamic_view"
                            binding.mediaInfoOpEdContainer.addView(root)
                        }
                    }
                    if (media.anime.ed.isNotEmpty()) {
                        ItemTitleTextBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                            itemTitle.setText(R.string.ending)
                            makeText(itemText, media.anime.ed)
                            root.tag = "dynamic_view"
                            binding.mediaInfoOpEdContainer.addView(root)
                        }
                    }
                }

                if (media.genres.isNotEmpty()) {
                    val bind = ActivityGenreBinding.inflate(LayoutInflater.from(context), parent, false)
                    bind.root.tag = "dynamic_view"
                    val adapter = GenreAdapter(type)
                    bind.mediaInfoGenresRecyclerView.adapter = adapter
                    bind.mediaInfoGenresRecyclerView.layoutManager = GridLayoutManager(requireActivity(), (screenWidth / 95f).toInt().coerceAtLeast(3))
                    if (!offline) {
                        genreModel.doneListener = { MainScope().launch { bind.mediaInfoGenresProgressBar.visibility = View.GONE } }
                        if (genreModel.genres != null) {
                            adapter.genres = genreModel.genres!!
                            adapter.pos = ArrayList(genreModel.genres!!.keys)
                            if (genreModel.done) genreModel.doneListener?.invoke()
                        }
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { genreModel.loadGenres(media.genres) { MainScope().launch { adapter.addGenre(it) } } }
                    } else {
                        bind.mediaInfoGenresProgressBar.visibility = View.GONE
                        media.genres.forEach { adapter.addGenre(Pair(it, "")) }
                    }
                    binding.mediaInfoGenreContainer.addView(bind.root)
                }

                if (media.tags.isNotEmpty() && !offline) {
                    val bind = ItemTitleChipgroupBinding.inflate(LayoutInflater.from(context), parent, false)
                    bind.root.tag = "dynamic_view"
                    bind.itemTitle.setText(R.string.tags)
                    for (position in media.tags.indices) {
                        val chip = ItemChipBinding.inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                        chip.text = media.tags[position]
                        chip.setSafeOnClickListener {
                            ContextCompat.startActivity(chip.context, Intent(chip.context, SearchActivity::class.java)
                                .putExtra("type", type).putExtra("sortBy", Anilist.sortBy[2])
                                .putExtra("tag", media.tags[position].substringBefore(" :"))
                                .putExtra("search", true).also {
                                    if (media.isAdult) { if (!Anilist.adult) Toast.makeText(chip.context, currActivity()?.getString(R.string.content_18), Toast.LENGTH_SHORT).show(); it.putExtra("hentai", true) }
                                }, null)
                        }
                        chip.setOnLongClickListener { copyToClipboard(media.tags[position]);true }
                        bind.itemChipGroup.addView(chip)
                    }
                    binding.mediaInfoTagsContainer.addView(bind.root)
                }

                if (!media.externalLinks.isNullOrEmpty() && !offline) {
                    val bind = ItemTitleChipgroupBinding.inflate(LayoutInflater.from(context), parent, false)
                    bind.itemTitle.setText(R.string.external_links)
                    for (link in media.externalLinks!!) {
                        val url = link.url ?: continue
                        val chip = ItemChipBinding.inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                        chip.text = link.site
                        chip.setSafeOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        chip.setOnLongClickListener { copyToClipboard(url); true }
                        bind.itemChipGroup.addView(chip)
                    }
                    bind.root.tag = "dynamic_view"
                    binding.mediaInfoExternalLinksContainer.addView(bind.root)
                }

                if ((!media.relations.isNullOrEmpty() || media.sequel != null || media.prequel != null) && !offline) {
                    if (media.sequel != null || media.prequel != null) {
                        ItemQuelsBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                            if (media.sequel != null) {
                                mediaInfoSequel.visibility = View.VISIBLE
                                mediaInfoSequelImage.loadImage(media.sequel!!.banner ?: media.sequel!!.cover)
                                mediaInfoSequel.setSafeOnClickListener {
                                    ContextCompat.startActivity(requireContext(), Intent(requireContext(), MediaDetailsActivity::class.java).putExtra("media", media.sequel as Serializable), null)
                                }
                            }
                            if (media.prequel != null) {
                                mediaInfoPrequel.visibility = View.VISIBLE
                                mediaInfoPrequelImage.loadImage(media.prequel!!.banner ?: media.prequel!!.cover)
                                mediaInfoPrequel.setSafeOnClickListener {
                                    ContextCompat.startActivity(requireContext(), Intent(requireContext(), MediaDetailsActivity::class.java).putExtra("media", media.prequel as Serializable), null)
                                }
                            }
                            root.tag = "dynamic_view"
                            parent.addView(root)
                        }
                    }
                    if (!media.review.isNullOrEmpty()) {
                        ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                            val adapter = GroupieAdapter()
                            media.review!!.forEach { adapter.add(ReviewAdapter(it)) }
                            itemTitle.setText(R.string.reviews)
                            itemRecycler.adapter = adapter
                            itemRecycler.layoutManager = LinearLayoutManager(requireContext())
                            itemMore.visibility = View.VISIBLE
                            itemMore.setSafeOnClickListener { startActivity(Intent(requireContext(), ReviewActivity::class.java).putExtra("mediaId", media.id)) }
                            root.tag = "dynamic_view"
                            parent.addView(root)
                        }
                    }
                    val animeRelations = media.relations?.filter { it.anime != null }
                    if (!animeRelations.isNullOrEmpty()) {
                        ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                            itemRecycler.adapter = MediaAdaptor(0, ArrayList(animeRelations), requireActivity())
                            itemRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                            root.tag = "dynamic_view"
                            parent.addView(root)
                        }
                    }
                }
                if (!media.characters.isNullOrEmpty() && !offline) {
                    ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                        itemTitle.setText(R.string.characters)
                        itemRecycler.adapter = CharacterAdapter(media.characters!!)
                        itemRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                        root.tag = "dynamic_view"
                        parent.addView(root)
                    }
                }
                if (!media.staff.isNullOrEmpty() && !offline) {
                    ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                        itemTitle.setText(R.string.staff)
                        itemRecycler.adapter = AuthorAdapter(media.staff!!)
                        itemRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                        root.tag = "dynamic_view"
                        parent.addView(root)
                    }
                }
                if (!media.recommendations.isNullOrEmpty() && !offline) {
                    ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                        itemTitle.setText(R.string.recommended)
                        itemRecycler.adapter = MediaAdaptor(0, media.recommendations!!, requireActivity())
                        itemRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                        root.tag = "dynamic_view"
                        parent.addView(root)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cornerTop = ObjectAnimator.ofFloat(binding.root, "radius", 0f, 32f).setDuration(200)
            val cornerNotTop = ObjectAnimator.ofFloat(binding.root, "radius", 32f, 0f).setDuration(200)
            var cornered = true
            cornerTop.start()
            binding.mediaInfoScroll.setOnScrollChangeListener { v, _, _, _, _ ->
                if (!v.canScrollVertically(-1)) {
                    if (!cornered) { cornered = true; cornerTop.start() }
                } else {
                    if (cornered) { cornered = false; cornerNotTop.start() }
                }
            }
        }

        super.onViewCreated(view, null)
    }

    override fun onResume() {
        binding.mediaInfoProgressBar.isGone = loaded
        super.onResume()
    }

    override fun onDestroy() {
        infoTimer?.cancel()
        infoTimer = null
        super.onDestroy()
    }

    private fun startAiringTimer(media: Media) {
        if (_binding == null || !isAdded) return
        val nextTime = media.anime?.nextAiringEpisodeTime ?: return
        val millisUntil = nextTime * 1000 - System.currentTimeMillis()
        if (millisUntil <= 0) {
            _binding?.mediaInfoNextTimer?.text = getString(R.string.time_format, 0, 0, 0, 0)
            return
        }
        _binding?.mediaInfoNextTimer?.visibility = View.VISIBLE
        infoTimer = object : CountDownTimer(millisUntil, 1000) {
            override fun onTick(millis: Long) {
                if (_binding == null || !isAdded) {
                    cancel()
                    return
                }
                val a = millis / 1000
                _binding?.mediaInfoNextTimer?.text = getString(
                    R.string.time_format,
                    a / 86400,
                    a % 86400 / 3600,
                    a % 86400 % 3600 / 60,
                    a % 86400 % 3600 % 60
                )
            }

            override fun onFinish() {
                if (_binding == null || !isAdded) return
                _binding?.mediaInfoNextTimer?.text = getString(R.string.time_format, 0, 0, 0, 0)
            }
        }.start()
    }

    private fun placeholderHtml(trailerId: String): String = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent;-webkit-touch-callout:none;-webkit-user-select:none;user-select:none;}
        body,html{width:100%;height:100%;background:#000;overflow:hidden;}
        .thumbnail-container{position:relative;width:100%;height:100%;display:flex;align-items:center;justify-content:center;background:#000;}
        .thumbnail{width:100%;height:100%;object-fit:contain;}
        .play-button{position:absolute;width:68px;height:48px;background:rgba(255,0,0,0.8);border-radius:12px;display:flex;align-items:center;justify-content:center;transition:transform 0.2s;}
        .thumbnail-container:active .play-button{transform:scale(0.95);}
        .play-icon{width:0;height:0;border-left:20px solid white;border-top:12px solid transparent;border-bottom:12px solid transparent;margin-left:4px;}</style>
        </head><body>
        <div class="thumbnail-container" onclick="Android.loadVideo()">
        <img class="thumbnail" src="https://img.youtube.com/vi/$trailerId/maxresdefault.jpg"
             onerror="this.src='https://img.youtube.com/vi/$trailerId/hqdefault.jpg'" alt="Trailer">
        <div class="play-button"><div class="play-icon"></div></div></div></body></html>
    """.trimIndent()

    private fun formatFuzzyDate(date: FuzzyDate): String? {
        val y = date.year ?: return date.toStringOrEmpty().takeIf { it.isNotBlank() }
        val m = date.month
        val d = date.day
        val months = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        val monthName = if (m != null && m in 1..12) months[m - 1] else return y.toString()
        if (d == null) return "$monthName $y"
        val suffix = when {
            d in 11..13 -> "th"
            d % 10 == 1 -> "st"
            d % 10 == 2 -> "nd"
            d % 10 == 3 -> "rd"
            else -> "th"
        }
        return "${d}${suffix} $monthName $y"
    }
}
