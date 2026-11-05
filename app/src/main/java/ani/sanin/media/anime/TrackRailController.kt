package ani.sanin.media.anime

import android.graphics.Color
import android.graphics.Color.TRANSPARENT
import android.view.Gravity
import androidx.core.view.isVisible
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.graphics.ColorUtils
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.ItemSubtitleTextBinding
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.color.MaterialColors
import java.util.Locale

@UnstableApi
class TrackRailController(
    private val activity: ExoplayerView,
    private val content: View,
    private val closeButton: ImageButton,
    private val recycler: RecyclerView,
) {

    data class TrackEntry(
        val group: Tracks.Group,
        val index: Int,
        val type: @C.TrackType Int,
        val label: CharSequence,
        val active: Boolean,

        val sort: Int = 0,
    )

    data class RailItem(
        val label: CharSequence,
        val isHeader: Boolean = false,
        val entry: TrackEntry? = null,
    )

    private val rows = mutableListOf<RailItem>()
    private val adapter = RailAdapter()

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        closeButton.nextFocusDownId = R.id.tracksDrawerList
        recycler.nextFocusUpId = R.id.tracksDrawerClose
        FocusEffectUtil.applyFocusListener(closeButton)
    }

    private var dialog: android.app.Dialog? = null

    fun open() {
        if (dialog?.isShowing == true) { close(); return }
        rebuild()
        val dlg = android.app.Dialog(activity, R.style.DialogTracksTopSheet)
        dialog = dlg
        val root = LayoutInflater.from(activity).inflate(R.layout.player_tracks_sheet, null)
        dlg.setContentView(root)
        dlg.setCancelable(true)
        dlg.setOnDismissListener { dialog = null }

        val videoHeader = root.findViewById<View>(R.id.tracksSheetVideoHeader)
        val audioHeader = root.findViewById<View>(R.id.tracksSheetAudioHeader)
        val videoList = root.findViewById<RecyclerView>(R.id.tracksSheetVideoList)
        val audioList = root.findViewById<RecyclerView>(R.id.tracksSheetAudioList)
        val videoChevron = root.findViewById<View>(R.id.tracksSheetVideoChevron)
        val audioChevron = root.findViewById<View>(R.id.tracksSheetAudioChevron)

        val videoRows = rows.filter { it.entry?.type == C.TRACK_TYPE_VIDEO }
        val audioRows = rows.filter { it.entry?.type == C.TRACK_TYPE_AUDIO }

        videoHeader.setOnClickListener {
            val show = videoList.visibility != View.VISIBLE
            videoList.isVisible = show
            videoChevron.animate().rotation(if (show) 180f else 0f).setDuration(180).start()
        }
        audioHeader.setOnClickListener {
            val show = audioList.visibility != View.VISIBLE
            audioList.isVisible = show
            audioChevron.animate().rotation(if (show) 180f else 0f).setDuration(180).start()
        }

        FocusEffectUtil.applyFocusListener(videoHeader)
        FocusEffectUtil.applyFocusListener(audioHeader)

        if (videoRows.isNotEmpty()) {
            videoHeader.isVisible = true
            videoList.layoutManager = LinearLayoutManager(activity)
            videoList.adapter = DialogAdapter(videoRows.toMutableList())
            videoList.isNestedScrollingEnabled = false
        } else {
            videoHeader.isVisible = false
        }
        if (audioRows.isNotEmpty()) {
            audioHeader.isVisible = true
            audioList.layoutManager = LinearLayoutManager(activity)
            audioList.adapter = DialogAdapter(audioRows.toMutableList())
            audioList.isNestedScrollingEnabled = false
        } else {
            audioHeader.isVisible = false
        }

        val window = dlg.window ?: return
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val params = window.attributes
        params.gravity = Gravity.TOP or Gravity.END
        params.y = (32 * activity.resources.displayMetrics.density).toInt()
        params.x = (16 * activity.resources.displayMetrics.density).toInt()
        window.attributes = params
        dlg.show()
        if (videoRows.isNotEmpty()) videoHeader.requestFocus()
        else if (audioRows.isNotEmpty()) audioHeader.requestFocus()
    }

    fun close() {
        dialog?.dismiss()
        dialog = null
    }

    fun isOpen(): Boolean = dialog?.isShowing == true

    fun rebuild() {
        rows.clear()
        val tracks = activity.playerCurrentTracks()

        val videos = videoEntries(tracks).sortedByDescending { it.sort }
        if (videos.isNotEmpty()) {
            rows.add(RailItem("Tracks", isHeader = true))
            videos.forEach { rows.add(RailItem(it.label, entry = it)) }
        }

        val audios = audioEntries(tracks)
        if (audios.isNotEmpty()) {
            rows.add(RailItem("Audio", isHeader = true))
            audios.forEach { rows.add(RailItem(it.label, entry = it)) }
        }

        if (rows.isEmpty()) {
            rows.add(RailItem("No selectable tracks", isHeader = true))
        }
        adapter.notifyDataSetChanged()
    }

    fun focusFirst() {
        val first = rows.indexOfFirst { it.entry != null }
        if (first >= 0) {
            recycler.post {
                recycler.scrollToPosition(first)
                recycler.post {
                    val holder = recycler.findViewHolderForAdapterPosition(first)
                    if (holder != null) holder.itemView.requestFocus()
                    else closeButton.requestFocus()
                }
            }
        } else {
            closeButton.requestFocus()
        }
    }

    private fun videoEntries(tracks: Tracks): List<TrackEntry> {
        val result = mutableListOf<TrackEntry>()
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i, true)) continue
                val format = group.getTrackFormat(i)
                val height = format.height
                if (height == Format.NO_VALUE || height <= 0) continue
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: "${format.width}x$height"
                    ?: "Track ${i + 1}"
                result.add(
                    TrackEntry(
                        group = group,
                        index = i,
                        type = C.TRACK_TYPE_VIDEO,
                        label = qualityLabel(height, label),
                        active = group.isTrackSelected(i),
                        sort = height,
                    )
                )
            }
        }
        return result
    }

    private fun audioEntries(tracks: Tracks): List<TrackEntry> {
        val result = mutableListOf<TrackEntry>()
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i, true)) continue
                val format = group.getTrackFormat(i)
                val language = languageLabel(format.language)
                val channels = when (format.channelCount) {
                    Format.NO_VALUE, -1, 0 -> ""
                    else -> "${format.channelCount}ch"
                }
                val label = listOfNotNull(language, channels.takeIf { it.isNotEmpty() })
                    .joinToString(" • ")
                    .ifBlank { format.label?.takeIf { it.isNotBlank() } ?: "Track ${i + 1}" }
                result.add(
                    TrackEntry(
                        group = group,
                        index = i,
                        type = C.TRACK_TYPE_AUDIO,
                        label = label,
                        active = group.isTrackSelected(i),
                    )
                )
            }
        }
        return result
    }

    private fun qualityLabel(height: Int, fallback: String): String {
        if (height in intArrayOf(2160, 1440, 1080, 720, 576, 480, 360)) {
            return "${height}p"
        }
        return fallback
    }

    private fun languageLabel(lang: String?): String {
        val code = lang ?: return ""
        if (code.isBlank()) return ""
        val lower = code.lowercase(Locale.ROOT)
        val mapped = when (lower) {
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
            else -> code
        }

        return if (mapped == code) {
            runCatching { ani.sanin.others.LanguageMapper.getLanguageName(code) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: code
        } else {
            mapped
        }
    }

    private fun select(entry: TrackEntry, position: Int) {
        activity.onSetTrackGroupOverride(entry.group, entry.type, entry.index)
        rebuild()
        recycler.post {
            recycler.scrollToPosition(position)
            recycler.post {
                val holder = recycler.findViewHolderForAdapterPosition(position)
                holder?.itemView?.requestFocus()
            }
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

            val primary = PrefManager.getVal<Int>(PrefName.PrimaryColor)
            val themePrimary = MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorPrimary
            )

            if (item.isHeader) {
                binding.root.setCardBackgroundColor(Color.TRANSPARENT)
                binding.root.isClickable = false
                binding.root.isFocusable = false
                binding.root.setOnClickListener(null)
                binding.subtitleTitle.text = item.label
                binding.subtitleTitle.setTextColor(themePrimary)
                binding.subtitleTitle.textSize = 12f
                binding.subtitleGlobe.visibility = View.GONE
                binding.subtitleBadge.visibility = View.GONE
                binding.subtitleToggle.visibility = View.GONE
                return
            }

            val entry = item.entry
            binding.subtitleTitle.text = item.label
            binding.subtitleTitle.setTextColor(Color.WHITE)
            binding.subtitleGlobe.visibility = View.GONE
            binding.subtitleBadge.visibility = View.GONE
            binding.subtitleToggle.visibility = View.GONE

            if (entry != null) {
                binding.root.setCardBackgroundColor(
                    if (entry.active) {
                        ColorUtils.setAlphaComponent(primary, 60)
                    } else {
                        TRANSPARENT
                    }
                )
                binding.root.isClickable = true
                binding.root.isFocusable = true
                binding.root.setOnClickListener {
                    select(entry, position)
                }
                if (entry.active) {
                    binding.subtitleBadge.visibility = View.VISIBLE
                    binding.subtitleBadge.text = "✔"
                    binding.subtitleBadge.setTextColor(primary)
                    binding.subtitleBadge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(
                            ColorUtils.setAlphaComponent(primary, 40)
                        )
                }
            } else {
                binding.root.isClickable = false
                binding.root.isFocusable = false
                binding.root.setOnClickListener(null)
            }
        }
    }

    private inner class DialogAdapter(private val items: List<RailItem>) :
        RecyclerView.Adapter<DialogAdapter.VH>() {
        inner class VH(val binding: ItemSubtitleTextBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemSubtitleTextBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val binding = holder.binding
            val item = items[position]
            val entry = item.entry ?: return
            FocusEffectUtil.applyFocusListener(binding.root)
            val primary = PrefManager.getVal<Int>(PrefName.PrimaryColor)
            binding.subtitleTitle.text = item.label
            binding.subtitleTitle.setTextColor(Color.WHITE)
            binding.subtitleGlobe.visibility = View.GONE
            binding.subtitleBadge.visibility = View.GONE
            binding.subtitleToggle.visibility = View.GONE
            binding.root.setCardBackgroundColor(
                if (entry.active) ColorUtils.setAlphaComponent(primary, 60)
                else TRANSPARENT
            )
            binding.root.isClickable = true
            binding.root.isFocusable = true
            binding.root.setOnClickListener {
                activity.onSetTrackGroupOverride(entry.group, entry.type, entry.index)
                rebuild()
                close()
                open()
            }
            if (entry.active) {
                binding.subtitleBadge.visibility = View.VISIBLE
                binding.subtitleBadge.text = "✔"
                binding.subtitleBadge.setTextColor(primary)
                binding.subtitleBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(primary, 40)
                    )
            }
        }
    }
}
