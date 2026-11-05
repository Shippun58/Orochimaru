package ani.sanin.media.anime

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Color.TRANSPARENT
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.graphics.ColorUtils
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.subtitles.StremioSub
import ani.sanin.connections.subtitles.StremioSubtitles
import ani.sanin.connections.subtitles.WyzieSub
import ani.sanin.databinding.ItemSubtitleTextBinding
import ani.sanin.media.EpisodeMapper
import ani.sanin.media.Media
import ani.sanin.media.MediaDetailsViewModel
import ani.sanin.others.IdMappers
import ani.sanin.parsers.Subtitle
import ani.sanin.parsers.VideoExtractor
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.toast
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.LanguageFlag
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubtitleRailController(
    private val activity: ExoplayerView,
    private val model: MediaDetailsViewModel,
    private val drawer: DrawerLayout,
    private val content: View,
    private val languageButton: ImageButton,
    private val closeButton: ImageButton,
    private val recycler: RecyclerView,
) {

    data class RailItem(
        val label: CharSequence,
        val isHeader: Boolean = false,
        val isCollapsible: Boolean = false,
        val isStatus: Boolean = false,
        val isToggle: Boolean = false,
        val badge: String? = null,
        val globe: Boolean = false,
        val language: String? = null,
        val selectedKey: String? = null,
        val selectedWhen: (String?) -> Boolean = { it == selectedKey },
        val onClick: (() -> Unit)? = null,
    )

    private val rows = mutableListOf<RailItem>()
    private val adapter = RailAdapter()
    private val attemptedServers = mutableSetOf<String>()
    private var crossServerExpanded = false
    private var crossServerEpisodeId: String? = null
    private var searchingOnline = false
    private var languageFilter: String? = null

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        languageButton.nextFocusDownId = R.id.subtitleDrawerList
        closeButton.nextFocusDownId = R.id.subtitleDrawerList
        recycler.nextFocusUpId = R.id.subtitleDrawerClose
        FocusEffectUtil.applyFocusListener(languageButton)
        FocusEffectUtil.applyFocusListener(closeButton)
        languageButton.setOnClickListener { showLanguageDialog() }
        closeButton.setOnClickListener { close() }
    }

    fun open() {
        rebuild()
        if (drawer.isDrawerOpen(content)) {
            focusFirst()
        } else {
            drawer.openDrawer(content)
        }
    }

    fun close() {
        drawer.closeDrawer(content)
    }

    fun focusFirst() {
        val enabled = PrefManager.getVal<Boolean>(PrefName.Subtitles)
        val focusable = (0 until rows.size).firstOrNull { index ->
            rows[index].onClick != null && (rows[index].isToggle || enabled)
        }
        if (focusable != null) {
            recycler.post {
                recycler.scrollToPosition(focusable)
                recycler.post {
                    val holder = recycler.findViewHolderForAdapterPosition(focusable)
                    if (holder != null) holder.itemView.requestFocus()
                    else closeButton.requestFocus()
                }
            }
        } else {
            closeButton.requestFocus()
        }
    }

    private fun focusToggle() {
        recycler.post {
            recycler.scrollToPosition(0)
            recycler.post {
                val holder = recycler.findViewHolderForAdapterPosition(0) as? RailAdapter.RailViewHolder
                val toggle = holder?.binding?.subtitleToggle
                if (toggle != null) toggle.requestFocus() else closeButton.requestFocus()
            }
        }
    }

    private fun toggleCrossServer() {
        crossServerExpanded = !crossServerExpanded
        rebuild()
        val headerIndex = rows.indexOfFirst { it.isHeader && it.isCollapsible }
        if (headerIndex >= 0) {
            recycler.post {
                recycler.scrollToPosition(headerIndex)
                recycler.post {
                    val holder = recycler.findViewHolderForAdapterPosition(headerIndex)
                    holder?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun rebuild() {
        try {
            val media = model.getMedia().value ?: return
            val episode = media.anime?.episodes?.get(media.anime.selectedEpisode) ?: return
        val prefKey = "subLang_${media.id}"
        val episodeId = "${media.id}-${episode.number}"

        if (crossServerEpisodeId != episodeId) {
            crossServerEpisodeId = episodeId
            crossServerExpanded = false
        }

        rows.clear()

        rows.add(
            RailItem(
                label = "Subtitles",
                isToggle = true,
                onClick = { toggleSubtitles() },
            )
        )

        rows.add(
            RailItem(
                label = syncLabel(),
                onClick = {
                    close()
                    SubtitleSyncDialogFragment().show(activity.supportFragmentManager, "subtitle_sync")
                }
            )
        )

        val safeExtractors = episode.extractors.orEmpty().filterNotNull().filter { it.server != null }
        val currentExtractor = safeExtractors.find { it.server.name == episode.selectedExtractor }
        if (currentExtractor != null && currentExtractor.subtitles.isNotEmpty()) {
            rows.add(RailItem("Current Server", isHeader = true))
            currentExtractor.subtitles.forEachIndexed { index, sub ->
                rows.add(
                    RailItem(
                        label = languageLabel(sub.language),
                        badge = serverAbbrev(currentExtractor.server.name),
                        language = sub.language,
                        selectedKey = sub.language,
                        onClick = { selectServerSub(media, episode, prefKey, index, sub.language) },
                    )
                )
            }
        }

        val otherExtractors = safeExtractors.filter { it.server.name != episode.selectedExtractor }
        if (otherExtractors.isNotEmpty()) {
            rows.add(
                RailItem(
                    label = "Cross Server",
                    isHeader = true,
                    isCollapsible = true,
                    onClick = { toggleCrossServer() },
                )
            )
            if (crossServerExpanded) {
                otherExtractors.forEach { ex ->
                    if (ex.subtitles.isNotEmpty()) {
                        ex.subtitles.forEach { sub ->
                            rows.add(
                                RailItem(
                                    label = languageLabel(sub.language),
                                    badge = serverAbbrev(ex.server.name),
                                    language = sub.language,
                                    selectedKey = "Online:${sub.file.url}",
                                    onClick = { selectRemoteSub(media, episode, prefKey, ex, sub) },
                                )
                            )
                        }
                    } else if ("${episode.number}|${ex.server.name}" !in attemptedServers) {
                        rows.add(RailItem("Loading ${ex.server.name}…", isStatus = true))
                        attemptedServers.add("${episode.number}|${ex.server.name}")
                        fetchOtherServer(ex)
                    }
                }
            }
        }

        val embeddedTracks = activity.subtitleRailEmbeddedTracks()
        if (embeddedTracks.isNotEmpty()) {
            rows.add(RailItem("Embedded Tracks", isHeader = true))
            rows.add(
                RailItem(
                    label = "Off (embedded)",
                    onClick = {
                        activity.onSetTrackGroupOverride(activity.subtitleRailDummyTrack(), C.TRACK_TYPE_TEXT, 0)
                        close()
                    },
                )
            )
            embeddedTracks.forEach { group ->
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val label = format.label?.takeIf { it.isNotBlank() }
                        ?: format.language
                        ?: "Track ${trackIndex + 1}"
                    rows.add(
                        RailItem(
                            label = label,
                            badge = "EM",
                            language = format.language,
                            onClick = {
                                activity.onSetTrackGroupOverride(group, C.TRACK_TYPE_TEXT, trackIndex)
                                close()
                            },
                        )
                    )
                }
            }
        }

        rows.add(RailItem("Online", isHeader = true))
        val cached = model.getFetchedSubtitles(episodeId)
        val hasCached = cached != null && cached.isNotEmpty()
        if (hasCached) {
            cached!!.forEach { item ->
                when (item) {
                    is StremioSub -> rows.add(
                        RailItem(
                            label = item.label?.takeIf { it.isNotBlank() } ?: languageLabel(item.lang),
                            badge = sourceAbbrev(item.source),
                            globe = true,
                            language = item.lang,
                            selectedKey = "Online:${item.id}",
                            onClick = { selectOnline(media, episode, prefKey, item) },
                        )
                    )
                    is WyzieSub -> rows.add(
                        RailItem(
                            label = item.displayLabel,
                            badge = "WY",
                            globe = true,
                            language = item.language,
                            selectedKey = "Online:${item.url}",
                            onClick = { selectWyzie(media, episode, prefKey, item) },
                        )
                    )
                    else -> Unit
                }
            }
        }
        if (searchingOnline) {
            rows.add(RailItem(" ", isStatus = true))
        } else if (!hasCached) {
            val onlineEnabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
            if (onlineEnabled) {
                rows.add(RailItem("+ Search Online Subtitles", onClick = { searchOnline(media, episode, episodeId) }))
            }
        }

        rows.add(RailItem("Local", isHeader = true))
        val localSubs = model.getLocalSubtitles(episodeId)
        localSubs.forEach { item ->
            if (item is Subtitle) {
                rows.add(
                    RailItem(
                        label = item.language,
                        badge = "LO",
                        language = item.language,
                        selectedKey = item.language,
                        onClick = { selectLocal(media, prefKey, item) },
                    )
                )
            }
        }
        rows.add(
            RailItem("+ Add Local Subtitle", onClick = {
                activity.requestLocalSubtitle()
                close()
            })
        )

        val filter = languageFilter
        if (filter != null) {
            rows.removeAll { it.language != null && !it.language.equals(filter, ignoreCase = true) }
        }

        languageButton.imageTintList = ColorStateList.valueOf(
            if (filter != null) PrefManager.getVal<Int>(PrefName.PrimaryColor) else Color.WHITE
        )

        adapter.notifyDataSetChanged()
        } catch (e: Exception) {
            Logger.log("SubtitleRail rebuild failed: ${e.message}")

            e.printStackTrace()
        }
    }

    private fun toggleSubtitles() {
        val enabled = !PrefManager.getVal<Boolean>(PrefName.Subtitles)
        activity.setSubtitlesEnabled(enabled)
        rebuild()
        focusToggle()
    }

    private fun selectServerSub(media: Media, episode: Episode, prefKey: String, index: Int, language: String) {
        Logger.log("SubtitleRail selectServerSub: lang=$language idx=$index ep=${episode.number}")
        PrefManager.setCustomVal(prefKey, language)
        episode.selectedSubtitle = index
        model.setEpisode(episode, "Subtitle")

        activity.selectExistingSubByLabel(language)
        close()
    }

    private fun selectRemoteSub(media: Media, episode: Episode, prefKey: String, ex: VideoExtractor, sub: Subtitle) {
        Logger.log("SubtitleRail selectRemoteSub: lang=${sub.language} server=${ex.server.name} url=${sub.file.url}")
        val stremioSub = StremioSub(
            id = sub.file.url,
            url = sub.file.url,
            lang = sub.language,
            source = ex.server.name,
            headers = sub.file.headers,
        )
        PrefManager.setCustomVal(prefKey, "Online:${stremioSub.id}")
        episode.selectedSubtitle = -1
        model.setEpisode(episode, "Subtitle")
        activity.applyOnlineSubtitle(
            stremioSub,
            headers = sub.file.headers,
            baseUrls = listOf(ex.server.embed.url),
        )
        close()
    }

    private fun selectOnline(media: Media, episode: Episode, prefKey: String, sub: StremioSub) {
        PrefManager.setCustomVal(prefKey, "Online:${sub.id}")
        episode.selectedSubtitle = -1
        model.setEpisode(episode, "Subtitle")
        activity.applyOnlineSubtitle(sub)
        close()
    }

    private fun selectWyzie(media: Media, episode: Episode, prefKey: String, item: WyzieSub) {
        selectOnline(
            media,
            episode,
            prefKey,
            StremioSub(id = item.url, url = item.url, lang = item.language, source = "wyzie")
        )
    }

    private fun selectLocal(media: Media, prefKey: String, item: Subtitle) {
        Logger.log("SubtitleRail selectLocal: lang=${item.language} url=${item.file.url}")
        PrefManager.setCustomVal(prefKey, item.language)
        activity.reApplyLocalSubtitle(item.file.url)
        close()
    }

    private fun showLanguageDialog() {
        val distinct = rows.mapNotNull { it.language }
            .distinctBy { it.lowercase() }
            .sortedBy { languageLabel(it).lowercase() }
        val options = mutableListOf("All Languages")
        options.addAll(distinct.map { languageLabel(it) })

        val currentIndex = languageFilter?.let { filter ->
            distinct.indexOfFirst { it.equals(filter, ignoreCase = true) }
        }?.plus(1) ?: 0

        activity.customAlertDialog().apply {
            setTitle("Subtitle Language")
            singleChoiceItems(options.toTypedArray(), currentIndex) { selected ->
                languageFilter = if (selected == 0) null else distinct[selected - 1]
                rebuild()
            }
            show()
        }
    }

    private fun fetchOtherServer(ex: VideoExtractor) {
        Logger.log("SubtitleRail: fetching subtitles from ${ex.server.name}")
        activity.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ex.load() }
            withContext(Dispatchers.Main) {
                Logger.log("SubtitleRail: ${ex.server.name} returned ${ex.subtitles.size} subs")
                rebuild()
            }
        }
    }

    private fun searchOnline(media: Media, episode: Episode, episodeId: String) {
        searchingOnline = true
        val searchIndex = rows.indexOfFirst { it.label.toString() == "+ Search Online Subtitles" }
        if (searchIndex != -1) {
            rows[searchIndex] = rows[searchIndex].copy(label = "Searching…", isStatus = true, onClick = null)
            adapter.notifyDataSetChanged()
        }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val imdbId = media.idIMDB ?: IdMappers.getImdbId(media.id) ?: return@launch
                if (media.idIMDB == null) media.idIMDB = imdbId
                val selectedEpisode = media.anime?.selectedEpisode ?: "1"
                val episodeNum = EpisodeMapper.episodeNumberOf(selectedEpisode, episode)
                val seasonEpisode = EpisodeMapper.mapEpisode(media, episodeNum, episode)
                Logger.log("SubtitleRail: streaming search imdbId=$imdbId season=${seasonEpisode.season} episode=${seasonEpisode.episode}")
                val existing = model.getFetchedSubtitles(episodeId)?.toMutableList() ?: mutableListOf()
                var anyFound = existing.isNotEmpty()
                StremioSubtitles.getSubtitlesStreaming(
                    imdbId = imdbId,
                    season = seasonEpisode.season,
                    episode = seasonEpisode.episode,
                    isTvSeries = media.format != "MOVIE",
                    queryText = media.userPreferredName,
                    onAllDone = {
                        activity.lifecycleScope.launch(Dispatchers.Main) {
                            searchingOnline = false
                            if (!anyFound) toast("No subtitles found")
                            rebuild()
                        }
                    }
                ).collect { batch ->
                    anyFound = true
                    existing.addAll(batch)
                    withContext(Dispatchers.Main) {
                        model.saveFetchedSubtitles(episodeId, existing.toList())
                        rebuild()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    searchingOnline = false
                    toast("Error fetching subtitles")
                    rebuild()
                }
            }
        }
    }

    private fun sourceAbbrev(source: String): String = when (source.lowercase()) {
        "wyzie", "wy" -> "WY"
        "stremio", "st", "online" -> "ST"
        "opensubtitles", "op" -> "OP"
        "subdl", "dl" -> "DL"
        "subsource", "ss" -> "SS"
        else -> source.take(2).uppercase()
    }

    private fun serverAbbrev(server: String): String {
        val letters = server.filter { it.isLetter() }.uppercase()
        return letters.take(2).ifEmpty { "SR" }
    }

    private fun syncLabel(): CharSequence {
        val primary = PrefManager.getVal<Int>(PrefName.PrimaryColor)
        val lowContrast = 0xFF9E9E9E.toInt()
        val text = "Subtitle Sync (online subtitles only)"
        val openIdx = text.indexOf('(')
        val closeIdx = text.indexOf(')')
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(primary), openIdx, openIdx + 1, 0)
            setSpan(RelativeSizeSpan(0.8f), openIdx + 1, closeIdx, 0)
            setSpan(StyleSpan(Typeface.ITALIC), openIdx + 1, closeIdx, 0)
            setSpan(ForegroundColorSpan(lowContrast), openIdx + 1, closeIdx, 0)
            setSpan(ForegroundColorSpan(primary), closeIdx, closeIdx + 1, 0)
        }
    }

    private fun languageLabel(lang: String): String {
        return when (lang.lowercase()) {
            "eng", "en", "en-us" -> "English"
            "spa", "es", "es-es", "es-419" -> "Spanish"
            "fra", "fr", "fr-fr" -> "French"
            "deu", "de", "de-de" -> "German"
            "ita", "it", "it-it" -> "Italian"
            "por", "pt", "pt-br", "pt-pt" -> "Portuguese"
            "rus", "ru", "ru-ru" -> "Russian"
            "jpn", "ja", "ja-jp" -> "Japanese"
            "zho", "chi", "zh", "zh-cn" -> "Chinese"
            "ara", "ar" -> "Arabic"
            "hin" -> "Hindi"
            "kor", "ko" -> "Korean"
            "pol", "pl" -> "Polish"
            "tur", "tr" -> "Turkish"
            "hun" -> "Hungarian"
            "ron", "ro" -> "Romanian"
            "ell", "el" -> "Greek"
            "cze", "cs" -> "Czech"
            "swe", "sv" -> "Swedish"
            "dan", "da" -> "Danish"
            "fin", "fi" -> "Finnish"
            "nor", "no" -> "Norwegian"
            "nld", "nl" -> "Dutch"
            "tha", "th" -> "Thai"
            "vie", "vi" -> "Vietnamese"
            "ind", "id" -> "Indonesian"
            "ukr", "uk" -> "Ukrainian"
            "heb", "he" -> "Hebrew"
            "bul", "bg" -> "Bulgarian"
            "hrv", "hr" -> "Croatian"
            "slk", "sk" -> "Slovak"
            "slv", "sl" -> "Slovenian"
            "mon", "mn" -> "Mongolian"
            "srp", "sr" -> "Serbian"
            "und" -> "Unknown"
            else -> lang
        }
    }

    private inner class RailAdapter : RecyclerView.Adapter<RailAdapter.RailViewHolder>() {

        inner class RailViewHolder(val binding: ItemSubtitleTextBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RailViewHolder =
            RailViewHolder(
                ItemSubtitleTextBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RailViewHolder, position: Int) {
            val binding = holder.binding
            val item = rows[position]
            FocusEffectUtil.applyFocusListener(binding.root)

            val subtitlesEnabled = PrefManager.getVal<Boolean>(PrefName.Subtitles)
            val enabled = item.isToggle || subtitlesEnabled
            val primary = PrefManager.getVal<Int>(PrefName.PrimaryColor)
            val grey = 0xFF808080.toInt()
            val themePrimary = MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorPrimary
            )

            if (item.isHeader) {
                binding.root.setCardBackgroundColor(Color.TRANSPARENT)
                binding.root.isClickable = item.onClick != null
                binding.root.isFocusable = item.onClick != null
                binding.root.setOnClickListener { item.onClick?.invoke() }
                binding.subtitleTitle.text = item.label
                binding.subtitleTitle.setTextColor(themePrimary)
                binding.subtitleTitle.textSize = 12f
                binding.subtitleGlobe.visibility = View.GONE
                binding.subtitleBadge.visibility = View.GONE
                binding.subtitleToggle.visibility = View.GONE
                return
            }

            binding.subtitleTitle.text = item.label
            binding.subtitleTitle.setTextColor(if (enabled) Color.WHITE else grey)

            binding.subtitleToggle.visibility = if (item.isToggle) View.VISIBLE else View.GONE
            if (item.isToggle) {
                binding.subtitleToggle.setOnCheckedChangeListener(null)
                binding.subtitleToggle.isChecked = subtitlesEnabled
                binding.subtitleToggle.setOnCheckedChangeListener { _, checked ->
                    activity.setSubtitlesEnabled(checked)
                    rebuild()
                    focusToggle()
                }
            }

                binding.subtitleGlobe.visibility = if (item.globe) View.VISIBLE else View.GONE
            if (item.globe) {

                binding.subtitleGlobe.imageTintList = null
            }

            val flag = if (item.globe && !item.language.isNullOrBlank())
                LanguageFlag.flagForLanguage(item.language) else ""
            if (flag.isNotEmpty()) {
                binding.subtitleFlag.text = flag
                binding.subtitleFlag.visibility = View.VISIBLE
            } else {
                binding.subtitleFlag.visibility = View.GONE
            }

            binding.subtitleBadge.visibility = if (item.badge != null) View.VISIBLE else View.GONE
            if (item.badge != null) {
                binding.subtitleBadge.text = item.badge
                binding.subtitleBadge.setTextColor(if (enabled) primary else grey)
                binding.subtitleBadge.backgroundTintList = ColorStateList.valueOf(
                    if (enabled) {
                        ColorUtils.setAlphaComponent(primary, 40)
                    } else {
                        ColorUtils.setAlphaComponent(grey, 40)
                    }
                )
            }

            binding.subtitleSpinner.visibility = if (item.isStatus && item.label.isBlank()) android.view.View.VISIBLE else android.view.View.GONE

            val media = model.getMedia().value
            val currentPref = media?.let {
                PrefManager.getNullableCustomVal("subLang_${it.id}", null, String::class.java)
            }
            val highlighted = enabled && item.selectedWhen(currentPref)
            binding.root.setCardBackgroundColor(
                if (highlighted) {
                    ColorUtils.setAlphaComponent(primary, 60)
                } else {
                    TRANSPARENT
                }
            )

            val clickable = item.onClick != null && enabled
            binding.root.isClickable = clickable
            binding.root.isFocusable = clickable
            binding.root.setOnClickListener { item.onClick?.invoke() }
        }
    }
}
