package ani.sanin.media.anime

import ani.sanin.toast
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.BottomSheetDialogFragment
import ani.sanin.util.Logger
import ani.sanin.R
import ani.sanin.connections.subtitles.StremioSub
import ani.sanin.connections.subtitles.StremioSubtitles
import ani.sanin.connections.subtitles.WyzieSub
import ani.sanin.connections.subtitles.WyzieSubtitles
import ani.sanin.media.EpisodeMapper
import ani.sanin.databinding.BottomSheetSubtitlesBinding
import ani.sanin.databinding.ItemSubtitleTextBinding
import ani.sanin.media.MediaDetailsViewModel
import ani.sanin.parsers.Subtitle
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import androidx.core.graphics.ColorUtils
import androidx.core.content.ContextCompat
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import ani.sanin.getThemeColor
import kotlinx.coroutines.launch
import ani.sanin.others.IdMappers
import ani.sanin.util.FocusEffectUtil

class SubtitleDialogFragment : BottomSheetDialogFragment() {
    data class AddLocalSubtitle(val text: String)
    data class SearchOnlineSubtitles(var text: String)
    data class OpenSubtitleSync(val text: String)
    private var _binding: BottomSheetSubtitlesBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()
    private lateinit var episode: Episode
    private var currentSeasonEpisode: EpisodeMapper.SeasonEpisode? = null

    private fun mapLanguageCode(isoCode: String): String = when (isoCode.lowercase()) {
        "eng" -> "English"
        "spa" -> "Spanish"
        "fra" -> "French"
        "deu" -> "German"
        "ita" -> "Italian"
        "por" -> "Portuguese"
        "rus" -> "Russian"
        "jpn" -> "Japanese"
        "zho", "chi" -> "Chinese"
        "ara" -> "Arabic"
        "hin" -> "Hindi"
        "kor" -> "Korean"
        "pol" -> "Polish"
        "tur" -> "Turkish"
        "hun" -> "Hungarian"
        "ron" -> "Romanian"
        "ell" -> "Greek"
        "cze" -> "Czech"
        "swe" -> "Swedish"
        "dan" -> "Danish"
        "fin" -> "Finnish"
        "nor" -> "Norwegian"
        "nld" -> "Dutch"
        "tha" -> "Thai"
        "vie" -> "Vietnamese"
        "ind" -> "Indonesian"
        "ukr" -> "Ukrainian"
        "heb" -> "Hebrew"
        "bul" -> "Bulgarian"
        "hrv" -> "Croatian"
        "slk" -> "Slovak"
        "slv" -> "Slovenian"
        "mon" -> "Mongolian"
        "srp" -> "Serbian"
        else -> isoCode
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSubtitlesBinding.inflate(inflater, container, false)
        FocusEffectUtil.applyFocusListener(binding.root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Logger.log("SubtitleDialogFragment: onViewCreated called")

        binding.subtitlesRecycler.layoutManager = LinearLayoutManager(requireContext())

        model.getMedia().observe(viewLifecycleOwner) { media ->
            episode = media?.anime?.episodes?.get(media.anime.selectedEpisode) ?: return@observe
            Logger.log("SubtitleDialogFragment: Media observed, ep=${episode.number}")
            val currentExtractor =
                episode.extractors?.find { it.server.name == episode.selectedExtractor }
                    ?: return@observe

            Logger.log("SubtitleDialogFragment: extractor=${currentExtractor.server.name}, localSubtitles=${currentExtractor.subtitles.size}")

            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val episodeId = "${media.id}-${episode.number}"
                val selectedEpisode = media.anime?.selectedEpisode ?: "1"
                val episodeNum = EpisodeMapper.episodeNumberOf(selectedEpisode, episode)

                val allSubtitles = mutableListOf<Any>()
                allSubtitles.add(AddLocalSubtitle("+ Add Local Subtitle"))

                val localSubs = model.getLocalSubtitles(episodeId)
                allSubtitles.addAll(localSubs)

                val cached = model.getFetchedSubtitles(episodeId)
                if (cached != null) {
                    if (cached.isNotEmpty()) {
                        allSubtitles.addAll(cached)
                    }
                } else {
                    val onlineSubsEnabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
                    if (onlineSubsEnabled) {
                        allSubtitles.add(SearchOnlineSubtitles("+ Online Subtitle"))
                    }
                }

                allSubtitles.add(0, OpenSubtitleSync("Subtitle Sync"))

                allSubtitles.addAll(currentExtractor.subtitles)

                if (_binding != null) {
                     binding.subtitlesRecycler.adapter = SubtitleAdapter(allSubtitles)
                }
                Logger.log("SubtitleDialogFragment: rows=${allSubtitles.size} (local=${currentExtractor.subtitles.size}, cachedOnline=${cached?.size ?: 0})")

                launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (media.idIMDB == null) {
                        try {
                            media.idIMDB = IdMappers.getImdbId(media.id)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    if (currentSeasonEpisode == null) {
                        try {
                            currentSeasonEpisode = EpisodeMapper.mapEpisode(media, episodeNum, episode)

                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (_binding != null) binding.subtitlesRecycler.adapter?.notifyDataSetChanged()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    private fun fetchOnlineSubtitles(adapter: SubtitleAdapter, item: SearchOnlineSubtitles, position: Int) {
        Logger.log("SubtitleDialogFragment: fetchOnlineSubtitles start")
        item.text = "Searching..."
        adapter.notifyItemChanged(position)

        val media = model.getMedia().value ?: return

        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val imdbId = media.idIMDB ?: IdMappers.getImdbId(media.id) ?: return@launch
                val isMovie = media.format == "MOVIE"
                val selectedEpisode = media.anime?.selectedEpisode ?: "1"

                val currentEpisode = media.anime?.episodes?.get(selectedEpisode)
                val episodeNum = EpisodeMapper.episodeNumberOf(selectedEpisode, currentEpisode)
                val seasonEpisode = EpisodeMapper.mapEpisode(media, episodeNum, currentEpisode)
                currentSeasonEpisode = seasonEpisode
                Logger.log("SubtitleDialogFragment: fetch imdbId=$imdbId ep=$episodeNum season=${seasonEpisode.season} epInSeason=${seasonEpisode.episode}")

                val onlineSubs = mutableListOf<Any>()

                val fetchedSubs = StremioSubtitles.getSubtitles(media, seasonEpisode.season, seasonEpisode.episode)
                onlineSubs.addAll(fetchedSubs)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        if (_binding == null) return@withContext
                        val validAdapter = binding.subtitlesRecycler.adapter as? SubtitleAdapter ?: return@withContext

                        if (position < validAdapter.subtitles.size && validAdapter.subtitles[position] == item) {
                            val list = validAdapter.subtitles as MutableList<Any>
                            list.removeAt(position)

                            Logger.log("SubtitleDialogFragment: fetched ${onlineSubs.size} online subtitles")
                                if (onlineSubs.isNotEmpty()) {

                                    model.saveFetchedSubtitles("${media.id}-$selectedEpisode", onlineSubs)
                                    list.addAll(onlineSubs)
                            } else {
                                Logger.log("SubtitleDialogFragment: no online subtitles found")
                                     toast("No subtitles found")
                                }
                            validAdapter.notifyDataSetChanged()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                         toast("Error fetching subtitles")
                         if (_binding != null) adapter.notifyDataSetChanged()
                }
            }
        }
    }

    inner class SubtitleAdapter(val subtitles: List<Any>) :
        RecyclerView.Adapter<SubtitleAdapter.StreamViewHolder>() {
        inner class StreamViewHolder(val binding: ItemSubtitleTextBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder =
            StreamViewHolder(
                ItemSubtitleTextBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        @OptIn(UnstableApi::class)
        override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
            val binding = holder.binding
            FocusEffectUtil.applyFocusListener(binding.root)

            val highlightColor = ColorUtils.setAlphaComponent(
                PrefManager.getVal<Int>(PrefName.PrimaryColor),
                60
            )

            if (position == 0) {
                binding.subtitleTitle.setText(R.string.none)
                model.getMedia().observe(viewLifecycleOwner) { media ->
                    val mediaID: Int = media.id
                    val selSubs = PrefManager.getNullableCustomVal(
                        "subLang_${mediaID}",
                        null,
                        String::class.java
                    )
                    if (episode.selectedSubtitle != null && selSubs != "None") {
                        binding.root.setCardBackgroundColor(TRANSPARENT)
                    } else {
                        binding.root.setCardBackgroundColor(highlightColor)
                    }
                }
                binding.root.setOnClickListener {
                    Logger.log("SubtitleDialogFragment: None selected")
                    episode.selectedSubtitle = null
                    model.setEpisode(episode, "Subtitle")
                    model.getMedia().observe(viewLifecycleOwner) { media ->
                        val mediaID: Int = media.id
                        PrefManager.setCustomVal("subLang_${mediaID}", "None")
                    }
                    dismiss()
                }
                return
            }

            val adjustedPosition = position - 1
            val item = subtitles[adjustedPosition]

            if (item is String) {
                binding.subtitleTitle.text = item
                binding.root.isClickable = false
                binding.root.setCardBackgroundColor(TRANSPARENT)
                return
            }

            if (item is AddLocalSubtitle) {
                binding.subtitleTitle.text = item.text
                binding.root.setCardBackgroundColor(TRANSPARENT)
                binding.root.setOnClickListener {
                    Logger.log("SubtitleDialogFragment: Add Local Subtitle clicked")
                    (requireActivity() as? ExoplayerView)?.requestLocalSubtitle()
                    dismiss()
                }
                return
            }

            if (item is SearchOnlineSubtitles) {
                binding.subtitleTitle.text = item.text
                binding.root.setCardBackgroundColor(TRANSPARENT)
                binding.root.setOnClickListener {

                    if (item.text == "Searching...") return@setOnClickListener
                    Logger.log("SubtitleDialogFragment: Online Subtitle search clicked")
                    fetchOnlineSubtitles(this@SubtitleAdapter, item, adjustedPosition)
                }
                return
            }

            if (item is OpenSubtitleSync) {
                val ctx = binding.root.context
                val primary = ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary)
                val gray = ContextCompat.getColor(ctx, android.R.color.darker_gray)
                val full = "Subtitle Sync (online subtitles only)"
                val ss = SpannableString(full)
                val openIdx = full.indexOf('(')
                val closeIdx = full.indexOf(')')
                ss.setSpan(ForegroundColorSpan(primary), openIdx, openIdx + 1, 0)
                ss.setSpan(ForegroundColorSpan(gray), openIdx + 1, closeIdx, 0)
                ss.setSpan(ForegroundColorSpan(primary), closeIdx, closeIdx + 1, 0)
                binding.subtitleTitle.text = ss
                binding.root.setCardBackgroundColor(TRANSPARENT)
                binding.root.setOnClickListener {
                    dismiss()
                    val syncDialog = SubtitleSyncDialogFragment()
                    syncDialog.show(parentFragmentManager, "subtitle_sync")
                }
                return
            }

            if (item is Subtitle) {
                binding.subtitleTitle.text = when (item.language) {
                    "ja-JP" -> "[ja-JP] Japanese"
                    "en-US" -> "[en-US] English"
                    "de-DE" -> "[de-DE] German"
                    "es-ES" -> "[es-ES] Spanish"
                    "es-419" -> "[es-419] Spanish"
                    "fr-FR" -> "[fr-FR] French"
                    "it-IT" -> "[it-IT] Italian"
                    "pt-BR" -> "[pt-BR] Portuguese (Brazil)"
                    "pt-PT" -> "[pt-PT] Portuguese (Portugal)"
                    "ru-RU" -> "[ru-RU] Russian"
                    "zh-CN" -> "[zh-CN] Chinese (Simplified)"
                    "tr-TR" -> "[tr-TR] Turkish"
                    "ar-ME" -> "[ar-ME] Arabic"
                    "ar-SA" -> "[ar-SA] Arabic (Saudi Arabia)"
                    "uk-UK" -> "[uk-UK] Ukrainian"
                    "he-IL" -> "[he-IL] Hebrew"
                    "pl-PL" -> "[pl-PL] Polish"
                    "ro-RO" -> "[ro-RO] Romanian"
                    "sv-SE" -> "[sv-SE] Swedish"
                    else -> if (item.language matches Regex("([a-z]{2})-([A-Z]{2}|\\d{3})")) "[${item.language}]" else item.language
                }

                model.getMedia().observe(viewLifecycleOwner) { media ->
                    val mediaID: Int = media.id
                    val selSubs: String? =
                        PrefManager.getNullableCustomVal(
                            "subLang_${mediaID}",
                            null,
                            String::class.java
                        )

                    if (selSubs != item.language) {
                        binding.root.setCardBackgroundColor(TRANSPARENT)
                    } else {
                         binding.root.setCardBackgroundColor(highlightColor)
                    }
                }

                binding.root.setOnClickListener {
                    Logger.log("SubtitleDialogFragment: local subtitle clicked language=${item.language}")

                    if (item.language.startsWith("[Local]")) {

                        model.getMedia().value?.let { media ->
                            PrefManager.setCustomVal("subLang_${media.id}", item.language)
                        }

                        val activity = requireActivity()
                        if (activity is ExoplayerView) {
                            try {
                                activity.reApplyLocalSubtitle(item.file.url)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } else {

                        Logger.log("SubtitleDialogFragment: built-in subtitle selected index=$adjustedPosition language=${item.language}")
                        episode.selectedSubtitle = adjustedPosition
                        model.setEpisode(episode, "Subtitle")
                        model.getMedia().observe(viewLifecycleOwner) { media ->
                            val mediaID: Int = media.id
                            PrefManager.setCustomVal(
                                "subLang_${mediaID}",
                                item.language
                            )
                        }
                    }
                    dismiss()
                }
                return
            }

            val seInfo = currentSeasonEpisode?.let { "S${it.season}.E${it.episode}" } ?: ""

            if (item is WyzieSub) {

                val format = item.format.uppercase()
                val label = "[$format] ${if(seInfo.isNotEmpty()) "$seInfo." else ""}${item.displayLabel}"
                binding.subtitleTitle.text = label

                model.getMedia().observe(viewLifecycleOwner) { media ->
                    val selSubs: String? = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)

                    if (selSubs != "Online:${item.url}") {
                        binding.root.setCardBackgroundColor(TRANSPARENT)
                    } else {
                        binding.root.setCardBackgroundColor(highlightColor)
                    }
                }

                binding.root.setOnClickListener {
                    Logger.log("SubtitleDialogFragment: Wyzie subtitle clicked lang=${item.language} url=${item.url} format=${item.format}")
                    try {
                        val activity = requireActivity()
                        if (activity is ExoplayerView) {
                            episode.selectedSubtitle = -1
                            model.setEpisode(episode, "Subtitle")

                            model.getMedia().observe(viewLifecycleOwner) { media ->
                                PrefManager.setCustomVal("subLang_${media.id}", "Online:${item.url}")
                            }

                            val stremioSub = StremioSub(
                                id = item.url,
                                url = item.url,
                                lang = item.language,
                                source = "wyzie"
                            )
                            activity.applyOnlineSubtitle(stremioSub)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    dismiss()
                }
                return
            }

            if (item is StremioSub) {
                val langName = mapLanguageCode(item.lang)
                val label = "[ONLINE] ${if(seInfo.isNotEmpty()) "$seInfo." else ""}$langName"
                binding.subtitleTitle.text = label

                model.getMedia().observe(viewLifecycleOwner) { media ->
                    val selSubs: String? = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)

                    if (selSubs != "Online:${item.id}") {
                        binding.root.setCardBackgroundColor(TRANSPARENT)
                    } else {
                        binding.root.setCardBackgroundColor(highlightColor)
                    }
                }

                binding.root.setOnClickListener {
                    Logger.log("SubtitleDialogFragment: Stremio subtitle clicked id=${item.id} lang=${item.lang}")
                    try {
                        val activity = requireActivity()
                        Logger.log("SubtitleDialogFragment: Activity = ${activity::class.simpleName}")

                        if (activity is ExoplayerView) {
                            Logger.log("SubtitleDialogFragment: Activity IS ExoplayerView, calling applyOnlineSubtitle")

                            episode.selectedSubtitle = -1
                            model.setEpisode(episode, "Subtitle")

                            model.getMedia().observe(viewLifecycleOwner) { media ->
                                PrefManager.setCustomVal("subLang_${media.id}", "Online:${item.id}")
                            }

                            activity.applyOnlineSubtitle(item)
                            Logger.log("SubtitleDialogFragment: applyOnlineSubtitle called successfully")
                        } else {
                            Logger.log("SubtitleDialogFragment: Activity is NOT ExoplayerView! Type = ${activity::class.qualifiedName}")
                        }
                    } catch (e: Exception) {
                        Logger.log("SubtitleDialogFragment: Exception in online subtitle click: ${e.message}")
                        e.printStackTrace()
                    }
                    dismiss()
                }
            }
        }

        override fun getItemCount(): Int = subtitles.size + 1
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}