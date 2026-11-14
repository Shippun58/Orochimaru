package ani.sanin.media.anime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Color.TRANSPARENT
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.subtitles.StremioSub
import ani.sanin.connections.subtitles.StremioSubtitles
import ani.sanin.connections.subtitles.WyzieSub
import ani.sanin.databinding.ItemSubtitleActionsBinding
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
import ani.sanin.ui.GlassPill
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SubtitleRailController(
    private val activity: ExoplayerView,
    private val model: MediaDetailsViewModel,
    private val drawer: DrawerLayout,
    private val content: View,
    private val languageButton: ImageButton,
    private val closeButton: ImageButton,
    private val recycler: RecyclerView,
) {

    data class RailAction(
        val label: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    )

    data class RailItem(
        val label: CharSequence = "",
        val provider: String? = null,
        val isHeader: Boolean = false,
        val isStatus: Boolean = false,
        val isToggle: Boolean = false,
        val isActionsRow: Boolean = false,
        val isChoice: Boolean = false,
        val language: String? = null,
        val selected: Boolean = false,
        val actions: List<RailAction> = emptyList(),
        val onClick: (() -> Unit)? = null,
    )

    private data class SubtitleChoice(
        val language: String,
        val name: String,
        val provider: String,
        val priority: Int,
        val selected: Boolean,
        val onClick: () -> Unit,
    )

    private val rows = mutableListOf<RailItem>()
    private val adapter = RailAdapter()
    private val attemptedServers = mutableSetOf<String>()
    private val fetchingServers = mutableSetOf<String>()
    private var crossServerRequested = false
    private var crossServerEpisodeId: String? = null
    private var searchingOnline = false
    private var languageFilter: String? = null
    private var languageOptions = emptyList<String>()

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        languageButton.nextFocusDownId = R.id.subtitleDrawerList
        closeButton.nextFocusDownId = R.id.subtitleDrawerList
        languageButton.nextFocusUpId = languageButton.id
        languageButton.nextFocusLeftId = languageButton.id
        languageButton.nextFocusRightId = closeButton.id
        closeButton.nextFocusUpId = closeButton.id
        closeButton.nextFocusLeftId = languageButton.id
        closeButton.nextFocusRightId = closeButton.id
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
        val target = (0 until rows.size).firstOrNull { isRowFocusable(rows[it]) }
        if (target != null) focusRow(target) else closeButton.requestFocus()
    }

    private fun isRowFocusable(item: RailItem): Boolean {
        if (item.isActionsRow) return item.actions.any { it.enabled }
        if (item.isToggle) return true
        if (item.onClick == null) return false
        return PrefManager.getVal<Boolean>(PrefName.Subtitles)
    }

    private fun focusRow(index: Int, fallback: View? = closeButton) {
        recycler.post {
            recycler.scrollToPosition(index)
            recycler.post {
                val holder = recycler.findViewHolderForAdapterPosition(index)
                when (holder) {
                    is RailAdapter.RailViewHolder -> {
                        val item = rows.getOrNull(index)
                        val view = if (item?.isToggle == true) holder.binding.subtitleToggle else holder.itemView
                        if (view.isFocusable) view.requestFocus() else fallback?.requestFocus()
                    }
                    is RailAdapter.ActionViewHolder -> {
                        val first = (0 until holder.binding.subtitleActionRow.childCount)
                            .map { holder.binding.subtitleActionRow.getChildAt(it) }
                            .firstOrNull { it.isFocusable }
                        first?.requestFocus() ?: fallback?.requestFocus()
                    }
                    else -> fallback?.requestFocus()
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
                crossServerRequested = false
            }

            val focusPosition = focusedAdapterPosition()
            val focusKey = rows.getOrNull(focusPosition)?.let { rowKey(it) }

            val subtitlesEnabled = PrefManager.getVal<Boolean>(PrefName.Subtitles)
            val onlineEnabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
            val currentPref = PrefManager.getNullableCustomVal(prefKey, null, String::class.java)
            val safeExtractors = episode.extractors.orEmpty().filterNotNull().filter { it.server != null }
            val currentExtractor = safeExtractors.find { it.server.name == episode.selectedExtractor }
            val embeddedTracks = activity.subtitleRailEmbeddedTracks()
            val localSubs = model.getLocalSubtitles(episodeId).filterIsInstance<Subtitle>()
            val cached = model.getFetchedSubtitles(episodeId).orEmpty()
            val otherExtractors = safeExtractors.filter { it.server.name != episode.selectedExtractor }

            val choices = mutableListOf<SubtitleChoice>()

            currentExtractor?.subtitles?.forEachIndexed { index, sub ->
                choices.add(
                    SubtitleChoice(
                        language = displayLanguage(sub.language),
                        name = subtitleName(fileName(sub.file.url), sub.language, "Default"),
                        provider = "${currentExtractor.server.name} (current server)",
                        priority = 0,
                        selected = episode.selectedSubtitle == index &&
                            currentPref.equals(sub.language, ignoreCase = true),
                        onClick = { selectServerSub(media, episode, prefKey, index, sub.language) },
                    )
                )
            }

            embeddedTracks.forEach { group ->
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val rawLanguage = format.language
                    val language = displayLanguage(rawLanguage)
                    choices.add(
                        SubtitleChoice(
                            language = language,
                            name = subtitleName(format.label, rawLanguage, "Track ${trackIndex + 1}"),
                            provider = "Embedded (media track)",
                            priority = 1,
                            selected = group.isSelected && group.isTrackSelected(trackIndex),
                            onClick = {
                                activity.onSetTrackGroupOverride(group, C.TRACK_TYPE_TEXT, trackIndex)
                                close()
                            },
                        )
                    )
                }
            }

            cached.forEach { item ->
                when (item) {
                    is StremioSub -> choices.add(
                        SubtitleChoice(
                            language = displayLanguage(item.lang),
                            name = subtitleName(item.label, item.lang, "Default"),
                            provider = providerLabel(item),
                            priority = 2,
                            selected = currentPref == "Online:${item.id}",
                            onClick = { selectOnline(media, episode, prefKey, item) },
                        )
                    )

                    is WyzieSub -> choices.add(
                        SubtitleChoice(
                            language = displayLanguage(item.language),
                            name = subtitleName(item.displayLabel, item.language, "Default"),
                            provider = "Wyzie (inbuilt)",
                            priority = 2,
                            selected = currentPref == "Online:${item.url}",
                            onClick = { selectWyzie(media, episode, prefKey, item) },
                        )
                    )
                }
            }

            localSubs.forEach { item ->
                choices.add(
                    SubtitleChoice(
                        language = displayLanguage(item.language),
                        name = subtitleName(
                            null,
                            item.language,
                            fileName(item.file.url) ?: "Local file",
                        ),
                        provider = "Local (file)",
                        priority = 3,
                        selected = currentPref.equals(item.language, ignoreCase = true),
                        onClick = { selectLocal(media, prefKey, item) },
                    )
                )
            }

            if (crossServerRequested) {
                otherExtractors.forEach { ex ->
                    if (ex.subtitles.isNotEmpty()) {
                        ex.subtitles.forEach { sub ->
                            choices.add(
                                SubtitleChoice(
                                    language = displayLanguage(sub.language),
                                    name = subtitleName(fileName(sub.file.url), sub.language, "Default"),
                                    provider = "${ex.server.name} (other server)",
                                    priority = 4,
                                    selected = currentPref == "Online:${sub.file.url}",
                                    onClick = { selectRemoteSub(media, episode, prefKey, ex, sub) },
                                )
                            )
                        }
                    } else {
                        val key = "${episode.number}|${ex.server.name}"
                        if (key !in attemptedServers) {
                            attemptedServers.add(key)
                            if (fetchingServers.add(key)) {
                                fetchOtherServer(ex, key)
                            }
                        }
                    }
                }
            }

            val filteredChoices = languageFilter?.let { filter ->
                choices.filter { it.language.equals(filter, ignoreCase = true) }
            } ?: choices
            val activeLanguage = choices.firstOrNull { it.selected }?.language
            languageOptions = choices.map { it.language }
                .distinctBy { it.lowercase() }

            rows.clear()
            rows.add(
                RailItem(
                    label = "Subtitles",
                    isToggle = true,
                    onClick = { toggleSubtitles() },
                )
            )

            val actions = mutableListOf<RailAction>()
            actions.add(
                RailAction("Sync", enabled = subtitlesEnabled) {
                    close()
                    SubtitleSyncDialogFragment().show(activity.supportFragmentManager, "subtitle_sync")
                }
            )
            if (onlineEnabled) {
                actions.add(
                    RailAction(
                        label = if (searchingOnline) "Searching…" else "Search online",
                        enabled = subtitlesEnabled && !searchingOnline,
                    ) { searchOnline(media, episode, episodeId) }
                )
            }
            actions.add(
                RailAction("Add local", enabled = subtitlesEnabled) {
                    activity.requestLocalSubtitle()
                    close()
                }
            )
            if (otherExtractors.isNotEmpty() && !crossServerRequested) {
                actions.add(
                    RailAction("More sources", enabled = subtitlesEnabled) {
                        crossServerRequested = true
                        rebuild()
                    }
                )
            }
            if (embeddedTracks.isNotEmpty()) {
                actions.add(
                    RailAction("Off (embedded)", enabled = subtitlesEnabled) {
                        activity.onSetTrackGroupOverride(
                            activity.subtitleRailDummyTrack(),
                            C.TRACK_TYPE_TEXT,
                            0,
                        )
                        close()
                    }
                )
            }
            if (actions.isNotEmpty()) {
                rows.add(RailItem(isActionsRow = true, actions = actions))
            }

            val currentServerLanguages = currentExtractor?.subtitles
                ?.map { displayLanguage(it.language) }
                ?.toSet()
                .orEmpty()
            val groups = filteredChoices.groupBy { it.language }
            val sortedGroups = groups.entries.sortedWith(
                compareBy<Map.Entry<String, List<SubtitleChoice>>>(
                    { if (it.key.equals(activeLanguage, ignoreCase = true)) 0 else 1 },
                    { if (it.key in currentServerLanguages) 0 else 1 },
                    { languageLabel(it.key).lowercase() },
                )
            )
            sortedGroups.forEach { (language, groupChoices) ->
                rows.add(
                    RailItem(
                        label = headerLabel(language, groupChoices.size),
                        isHeader = true,
                        language = language,
                    )
                )
                groupChoices
                    .sortedWith(compareBy({ it.priority }, { it.name.lowercase() }))
                    .forEach { choice ->
                        rows.add(
                            RailItem(
                                label = choice.name,
                                provider = choice.provider,
                                isChoice = true,
                                language = choice.language,
                                selected = choice.selected,
                                onClick = choice.onClick,
                            )
                        )
                    }
            }

            if (searchingOnline) {
                rows.add(RailItem("Searching online subtitles…", isStatus = true))
            }
            if (fetchingServers.isNotEmpty()) {
                rows.add(RailItem("Loading other servers…", isStatus = true))
            }
            if (filteredChoices.isEmpty() && !searchingOnline && fetchingServers.isEmpty()) {
                rows.add(
                    RailItem(
                        label = if (languageFilter != null) {
                            "No subtitles in this language"
                        } else {
                            "No subtitles found"
                        },
                        isStatus = true,
                    )
                )
            }

            languageButton.imageTintList = ColorStateList.valueOf(
                if (languageFilter != null) PrefManager.getVal<Int>(PrefName.PrimaryColor) else Color.WHITE
            )

            adapter.notifyDataSetChanged()
            restoreFocus(focusPosition, focusKey)
        } catch (e: Exception) {
            Logger.log("SubtitleRail rebuild failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun focusedAdapterPosition(): Int {
        val focused = recycler.findFocus() ?: return RecyclerView.NO_POSITION
        var view: View? = focused
        while (view != null && view.parent !== recycler) view = view.parent as? View
        return view?.let { recycler.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
    }

    private fun rowKey(item: RailItem): String =
        "${item.isHeader}|${item.isToggle}|${item.isActionsRow}|${item.label}|${item.provider}|${item.language}|${item.selected}"

    private fun restoreFocus(position: Int, key: String?) {
        if (position == RecyclerView.NO_POSITION) return
        recycler.post {
            if (rows.isEmpty()) return@post
            val current = activity.currentFocus
            if (current != null && current.isAttachedToWindow) return@post
            val match = key?.let { k -> rows.indexOfFirst { rowKey(it) == k } } ?: -1
            var target = if (match >= 0) match else position.coerceIn(0, rows.lastIndex)
            if (!isRowFocusable(rows[target])) {
                target = (target until rows.size).firstOrNull { isRowFocusable(rows[it]) }
                    ?: (target - 1 downTo 0).firstOrNull { isRowFocusable(rows[it]) }
                    ?: return@post
            }
            focusRow(target)
        }
    }

    private fun toggleSubtitles() {
        val enabled = !PrefManager.getVal<Boolean>(PrefName.Subtitles)
        activity.setSubtitlesEnabled(enabled)
        rebuild()
        focusFirst()
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
        val distinct = languageOptions.sortedBy { languageLabel(it).lowercase() }
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

    private fun fetchOtherServer(ex: VideoExtractor, key: String) {
        Logger.log("SubtitleRail: fetching subtitles from ${ex.server.name}")
        activity.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ex.load() }
            withContext(Dispatchers.Main) {
                fetchingServers.remove(key)
                Logger.log("SubtitleRail: ${ex.server.name} returned ${ex.subtitles.size} subs")
                rebuild()
            }
        }
    }

    private fun searchOnline(media: Media, episode: Episode, episodeId: String) {
        if (searchingOnline) return
        searchingOnline = true
        rebuild()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val imdbId = media.idIMDB ?: IdMappers.getImdbId(media.id)
                if (imdbId == null) {
                    withContext(Dispatchers.Main) {
                        searchingOnline = false
                        toast("No IMDb id for online subtitles")
                        rebuild()
                    }
                    return@launch
                }
                if (media.idIMDB == null) media.idIMDB = imdbId
                val selectedEpisode = media.anime?.selectedEpisode ?: "1"
                val episodeNum = EpisodeMapper.episodeNumberOf(selectedEpisode, episode)
                val seasonEpisode = EpisodeMapper.mapEpisode(media, episodeNum, episode)
                Logger.log(
                    "SubtitleRail: streaming search imdbId=$imdbId " +
                        "season=${seasonEpisode.season} episode=${seasonEpisode.episode}"
                )
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
                        val merged = existing.distinctBy { subtitleKey(it) }
                        existing.clear()
                        existing.addAll(merged)
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

    private fun subtitleKey(item: Any): String = when (item) {
        is StremioSub -> "${item.source}:${item.id}"
        is WyzieSub -> "wyzie:${item.url}"
        else -> item.toString()
    }

    private fun providerLabel(sub: StremioSub): String {
        sub.provider?.takeIf { it.isNotBlank() }?.let { return it }
        return when (sub.source.lowercase()) {
            "opensubtitles" -> "OpenSubtitles (inbuilt)"
            "wyzie", "wy" -> "Wyzie (inbuilt)"
            "subdl", "dl" -> "SubDL (inbuilt)"
            "subsource", "ss" -> "SubSource (inbuilt)"
            "stremio", "st", "online" -> {
                if (sub.id.contains(":")) "Stremio add-on" else "Stremio (inbuilt)"
            }
            else -> {
                val source = sub.source.takeIf { it.isNotBlank() } ?: "Online"
                "$source (inbuilt)"
            }
        }
    }

    private fun displayLanguage(language: String?): String {
        val clean = language?.trim().orEmpty()
        if (clean.isEmpty()) return "Unknown"
        return languageLabel(clean).ifBlank { "Unknown" }
    }

    private fun subtitleName(label: String?, language: String?, fallback: String): String {
        val cleanLabel = label?.trim().orEmpty()
        if (cleanLabel.isNotEmpty()) {
            val languageText = languageLabel(language.orEmpty()).trim()
            return if (languageText.isNotEmpty() && cleanLabel.equals(languageText, ignoreCase = true)) {
                fallback
            } else {
                cleanLabel
            }
        }
        return fallback
    }

    private fun fileName(url: String): String? {
        val clean = url.substringBefore('?').substringBefore('#')
        val name = clean.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        return runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
    }

    private fun headerLabel(language: String, count: Int): CharSequence {
        val label = languageLabel(language).ifBlank { "Unknown" }
        val countText = count.toString()
        val text = "$label  ·  $countText"
        val countStart = text.length - countText.length
        return SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(ColorUtils.setAlphaComponent(Color.WHITE, 0x66)),
                countStart,
                text.length,
                0,
            )
        }
    }

    private fun languageLabel(lang: String): String {
        return when (lang.lowercase()) {
            "eng", "en", "en-us", "en-gb" -> "English"
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

    private fun dp(value: Int): Int =
        (activity.resources.displayMetrics.density * value).roundToInt()

    private fun setRowMargins(view: View, start: Int, top: Int, end: Int, bottom: Int) {
        val params = view.layoutParams
        if (params is ViewGroup.MarginLayoutParams) {
            params.setMargins(dp(start), dp(top), dp(end), dp(bottom))
            view.layoutParams = params
        }
    }

    private fun sameLanguage(a: RailItem?, b: RailItem): Boolean =
        a?.isChoice == true && a.language.equals(b.language, ignoreCase = true)

    private inner class RailAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class RailViewHolder(val binding: ItemSubtitleTextBinding) :
            RecyclerView.ViewHolder(binding.root)

        inner class ActionViewHolder(val binding: ItemSubtitleActionsBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun getItemViewType(position: Int): Int =
            if (rows[position].isActionsRow) VIEW_ACTIONS else VIEW_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == VIEW_ACTIONS) {
                ActionViewHolder(
                    ItemSubtitleActionsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                )
            } else {
                RailViewHolder(
                    ItemSubtitleTextBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                )
            }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is RailViewHolder -> bindRow(holder.binding, rows[position], position)
                is ActionViewHolder -> bindActions(holder.binding, rows[position])
            }
        }

        private fun bindRow(binding: ItemSubtitleTextBinding, item: RailItem, position: Int) {
            val subtitlesEnabled = PrefManager.getVal<Boolean>(PrefName.Subtitles)
            val enabled = item.isToggle || subtitlesEnabled
            val themePrimary = MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorPrimary
            )
            val grey = 0xFF808080.toInt()

            binding.root.cardElevation = 0f
            binding.root.radius = 0f
            binding.root.setCardBackgroundColor(TRANSPARENT)
            binding.root.background = null
            binding.root.alpha = 1f
            binding.root.minimumHeight = dp(48)
            binding.subtitleProvider.visibility = View.GONE
            binding.subtitleProvider.alpha = 0.6f
            binding.subtitleCheck.visibility = View.GONE
            binding.subtitleSpinner.visibility = View.GONE
            binding.subtitleToggle.visibility = View.GONE
            binding.subtitleToggle.setOnCheckedChangeListener(null)
            binding.subtitleToggle.isFocusable = false
            binding.subtitleTitle.alpha = 1f
            binding.root.setOnClickListener(null)
            binding.root.isClickable = false
            binding.root.isFocusable = false

            when {
                item.isHeader -> {
                    setRowMargins(binding.root, 22, 12, 8, 4)
                    binding.root.minimumHeight = 0
                    FocusEffectUtil.applyFocusListener(binding.root)
                    binding.subtitleTitle.text = item.label
                    binding.subtitleTitle.setTextColor(themePrimary)
                    binding.subtitleTitle.textSize = 12f
                }

                item.isToggle -> {
                    setRowMargins(binding.root, 8, 4, 8, 4)
                    FocusEffectUtil.applyFocusListener(binding.root)
                    binding.subtitleTitle.text = item.label
                    binding.subtitleTitle.setTextColor(Color.WHITE)
                    binding.subtitleTitle.textSize = 16f
                    binding.subtitleToggle.visibility = View.VISIBLE
                    binding.subtitleToggle.isFocusable = true
                    binding.subtitleToggle.isChecked = subtitlesEnabled
                    binding.subtitleToggle.setOnCheckedChangeListener { _, checked ->
                        if (checked != PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
                            activity.setSubtitlesEnabled(checked)
                            rebuild()
                            focusFirst()
                        }
                    }
                    binding.root.isClickable = true
                    binding.root.setOnClickListener { binding.subtitleToggle.toggle() }
                }

                item.isStatus -> {
                    setRowMargins(binding.root, 8, 4, 8, 8)
                    binding.root.minimumHeight = dp(40)
                    FocusEffectUtil.applyFocusListener(binding.root)
                    binding.subtitleTitle.text = item.label
                    binding.subtitleTitle.setTextColor(
                        ColorUtils.setAlphaComponent(Color.WHITE, 0x80)
                    )
                    binding.subtitleTitle.textSize = 12f
                    if (item.label.isBlank()) {
                        binding.subtitleSpinner.visibility = View.VISIBLE
                    }
                }

                item.isChoice -> {
                    if (!enabled) binding.root.alpha = 0.55f
                    val prev = rows.getOrNull(position - 1)
                    val next = rows.getOrNull(position + 1)
                    val groupStart = !sameLanguage(prev, item)
                    val groupEnd = !sameLanguage(next, item)
                    setRowMargins(
                        binding.root,
                        8,
                        if (groupStart) 2 else 0,
                        8,
                        if (groupEnd) 8 else 0,
                    )
                    binding.root.minimumHeight = dp(56)
                    GlassPill.applyServerRowCard(
                        view = binding.root,
                        cornerRadiusDp = 14f,
                        topRounded = groupStart,
                        bottomRounded = groupEnd,
                        showTopRim = groupStart,
                        selected = item.selected,
                    )
                    FocusEffectUtil.applyFocusListener(
                        binding.root,
                        cornerRadiusPx = dp(14).toFloat(),
                    )
                    binding.subtitleTitle.text = item.label
                    binding.subtitleTitle.setTextColor(
                        when {
                            !enabled -> grey
                            item.selected -> themePrimary
                            else -> Color.WHITE
                        }
                    )
                    binding.subtitleTitle.textSize = 15f
                    binding.subtitleProvider.visibility = View.VISIBLE
                    binding.subtitleProvider.text = item.provider
                    binding.subtitleProvider.setTextColor(
                        if (enabled) {
                            ColorUtils.setAlphaComponent(Color.WHITE, 0x99)
                        } else {
                            ColorUtils.setAlphaComponent(grey, 0x99)
                        }
                    )
                    binding.subtitleProvider.alpha = 1f
                    binding.subtitleCheck.visibility = if (item.selected) View.VISIBLE else View.GONE
                    binding.subtitleCheck.imageTintList = ColorStateList.valueOf(
                        if (enabled) themePrimary else grey
                    )
                    val clickable = enabled && item.onClick != null
                    binding.root.isClickable = clickable
                    binding.root.isFocusable = clickable
                    if (clickable) {
                        binding.root.setOnClickListener { item.onClick?.invoke() }
                    }
                }
            }
        }

        private fun bindActions(binding: ItemSubtitleActionsBinding, item: RailItem) {
            binding.subtitleActionRow.removeAllViews()
            item.actions.forEach { action ->
                binding.subtitleActionRow.addView(buildActionButton(binding.root.context, action))
            }
        }
    }

    private fun buildActionButton(context: Context, action: RailAction): MaterialButton {
        return MaterialButton(context).apply {
            text = action.label
            isAllCaps = false
            textSize = 12f
            typeface = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(TRANSPARENT)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(Color.WHITE, 0x33))
            cornerRadius = dp(20)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(36)
            minWidth = 0
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36),
            ).apply { marginEnd = dp(6) }
            isEnabled = action.enabled
            alpha = if (action.enabled) 1f else 0.4f
            isFocusable = action.enabled
            isClickable = action.enabled
            setOnClickListener { action.onClick() }
            FocusEffectUtil.applyFocusListener(
                this,
                borderDp = 2f,
                cornerRadiusPx = dp(20).toFloat(),
            )
        }
    }

    private companion object {
        const val VIEW_ROW = 0
        const val VIEW_ACTIONS = 1
    }
}
