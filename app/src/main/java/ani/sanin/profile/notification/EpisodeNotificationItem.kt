package ani.sanin.profile.notification

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import ani.sanin.R
import ani.sanin.databinding.ItemNotificationEpisodeBinding
import ani.sanin.connections.anilist.api.Notification
import android.util.Log
import ani.sanin.util.Logger
import ani.sanin.loadImage
import ani.sanin.notifications.subscription.SubscriptionStore
import ani.sanin.profile.activity.ActivityItemBuilder
import ani.sanin.setAnimation
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val enrichmentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class EpisodeNotificationItem(
    private val notification: Notification,
    val type: TabType,
    val parentAdapter: GroupieAdapter,
    val clickCallback: (Int, Int?, NotificationClickType) -> Unit,
) : BindableItem<ItemNotificationEpisodeBinding>() {

    private lateinit var binding: ItemNotificationEpisodeBinding

    override fun getLayout(): Int = R.layout.item_notification_episode

    override fun initializeViewBinding(view: View): ItemNotificationEpisodeBinding =
        ItemNotificationEpisodeBinding.bind(view)

    override fun bind(viewBinding: ItemNotificationEpisodeBinding, position: Int) {
        binding = viewBinding

        val generation = ((viewBinding.root.getTag(R.id.episode_enrichment_gen) as? Int) ?: 0) + 1
        viewBinding.root.setTag(R.id.episode_enrichment_gen, generation)
        setAnimation(binding.root.context, binding.root)

        bindPill()
        bindCard(generation)
        shrinkMetaIcons(viewBinding)

        binding.episodeWatch.setOnClickListener { open() }
        FocusEffectUtil.applyFocusListener(binding.episodeWatch)
        binding.episodeCard.setOnLongClickListener { dialog(); true }
    }

    private fun bindPill() {

        val dark = isDarkMode()
        binding.episodePill.setBackgroundResource(
            if (dark) R.drawable.bg_episode_pill_dark else R.drawable.bg_episode_pill_light
        )
        val pillTextColor = if (dark) Color.BLACK else Color.WHITE
        binding.episodePillText.setTextColor(pillTextColor)
        binding.episodePillTime.setTextColor(pillTextColor)

        val poster = notification.image ?: notification.media?.coverImage?.large
        if (!poster.isNullOrBlank()) {
            binding.episodePillPoster.loadImage(poster)
        }

        binding.episodePillText.text = pillText()

        val time = ActivityItemBuilder.getDateTime(notification.createdAt)
        binding.episodePillTime.text = time
        binding.episodePillTime.isVisible = time.isNotBlank()
    }

    private fun pillText(): CharSequence {
        val contextApp = binding.root.context
        val accent = if (isDarkMode()) Color.BLACK else Color.WHITE
        val episode = notification.episode
        val title = notification.media?.title?.english
            ?: notification.media?.title?.userPreferred
            ?: notification.context?.substringBefore(":").orEmpty().trim()

        val episodeLabel = if (episode != null) "Episode $episode" else "New episode"
        val builder = SpannableStringBuilder()
        builder.append("$episodeLabel of ")
        val titleStart = builder.length
        builder.append(if (title.isBlank()) contextApp.getString(R.string.episode_airing_unknown_title) else title)
        builder.setSpan(
            ForegroundColorSpan(accent),
            titleStart,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            titleStart,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.append(" has aired")
        return builder
    }

    private fun bindCard(generation: Int) {

        val viewBinding = binding

        val banner = notification.banner ?: notification.image
            ?: notification.media?.bannerImage ?: notification.media?.coverImage?.large
        if (!banner.isNullOrBlank()) viewBinding.episodeThumb.loadImage(banner)

        val dark = isDarkMode()

        viewBinding.episodeRim.setBackgroundResource(
            if (dark) R.drawable.bg_episode_card_rim_light else R.drawable.bg_repo_card_rim
        )
        val onRight = if (dark) Color.WHITE else Color.BLACK
        viewBinding.episodeTitle.setTextColor(onRight)
        bindMetaColors(onRight)

        val episode = notification.episode
            ?: notification.context
                ?.let { Regex("Episode\\s+(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val knownTitle = notification.episodeTitle?.takeIf { it.isNotBlank() }
        val knownDuration = notification.durationMinutes
        val knownAirDate = notification.airDate
        val fallbackTitle = episode?.let { "Episode $it" }
        viewBinding.episodeTitle.text = knownTitle ?: fallbackTitle
        viewBinding.episodeTitle.isVisible = knownTitle != null || fallbackTitle != null
        applyMeta(viewBinding,
            knownDuration,
            knownAirDate,
            notification.airTimeMillis ?: notification.createdAt.toLong() * 1000L
        )

        Logger.log(Log.INFO, "EpNotifItem [${episode}] knownTitle=$knownTitle mediaId=${notification.mediaId} context=${notification.context?.take(40)}")

        viewBinding.episodeGradient.background = EpisodeCardGradient.build(viewBinding.root.context)

        enrichmentScope.launch {
            val isAnime = notification.tmdbType.isNullOrBlank()
            var title = knownTitle
            var duration = knownDuration
            var airDate = knownAirDate
            var thumb = notification.thumbnail
            var anizipBackdrop: String? = null

            if (isAnime && notification.mediaId != null && episode != null) {
                val extra = EpisodeNotificationResolver.resolve(
                    notification.mediaId,
                    episode,
                    fallbackTitle = knownTitle
                )
                if (!extra.isEmpty) {
                    title = extra.title ?: title
                    duration = extra.durationMinutes ?: duration
                    airDate = extra.airDate ?: airDate
                    thumb = extra.thumbnailUrl ?: thumb
                    anizipBackdrop = extra.backdropUrl ?: anizipBackdrop
                }
            }

            val resolvedTitle = title
            val resolvedDuration = duration
            val resolvedAirDate = airDate
            val resolvedThumb = thumb
            val resolvedBackdrop = anizipBackdrop

            withContext(Dispatchers.Main) {

                if (generation != viewBinding.root.getTag(R.id.episode_enrichment_gen)) {
                    Logger.log(Log.INFO, "EpNotifItem skipped: view rebound (gen=$generation)")
                    return@withContext
                }
                if (!resolvedTitle.isNullOrBlank() && knownTitle.isNullOrBlank()) {
                    viewBinding.episodeTitle.text = resolvedTitle
                    viewBinding.episodeTitle.isVisible = true
                }
                applyMeta(viewBinding, resolvedDuration, resolvedAirDate,
                    notification.airTimeMillis ?: notification.createdAt.toLong() * 1000L)

                val displayUrl = resolvedThumb?.takeIf { it.isNotBlank() }
                    ?: resolvedBackdrop?.takeIf { it.isNotBlank() }
                if (displayUrl != null) {
                    viewBinding.episodeThumb.loadImage(displayUrl)
                }
                Logger.log(Log.INFO, "EpNotifItem resolved: title=$resolvedTitle thumb=${displayUrl != null}")
            }
        }
    }

    private fun shrinkMetaIcons(viewBinding: ItemNotificationEpisodeBinding) {
        val sizePx = (8 * viewBinding.root.resources.displayMetrics.density).toInt()
        listOf(
            viewBinding.episodeMetaDuration to R.drawable.ic_baseline_clock_24,
            viewBinding.episodeMetaDate to R.drawable.ic_round_calendar_today_24
        ).forEach { (tv, res) ->

            val prev = tv.compoundDrawables.getOrNull(0)
            val drawable = prev
                ?: ContextCompat.getDrawable(viewBinding.root.context, res)
                ?: return@forEach
            if (prev == null) {
                drawable.setTintList(android.content.res.ColorStateList.valueOf(tv.currentTextColor))
            }
            drawable.setBounds(0, 0, sizePx, sizePx)
            tv.setCompoundDrawablesRelative(drawable, null, null, null)
        }
    }

    private fun bindMetaColors(onRight: Int) {
        val tint = android.content.res.ColorStateList.valueOf(onRight)
        binding.episodeMetaDuration.setTextColor(onRight)
        binding.episodeMetaDuration.setCompoundDrawableTintList(tint)
        binding.episodeMetaDate.setTextColor(onRight)
        binding.episodeMetaDate.setCompoundDrawableTintList(tint)
    }

    private fun applyMeta(viewBinding: ItemNotificationEpisodeBinding, durationMinutes: Int?, airDate: String?, airTimeMillis: Long) {
        val date = airDate ?: if (airTimeMillis > 0)
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(airTimeMillis)) else null
        val time = if (airTimeMillis > 0)
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(airTimeMillis)) else null

        viewBinding.episodeMetaDuration.text = durationMinutes?.let { "${it}m" }
        viewBinding.episodeMetaDuration.isVisible = durationMinutes != null

        val dateParts = listOfNotNull(date, time)
        viewBinding.episodeMetaDate.text = dateParts.joinToString("  ")
        viewBinding.episodeMetaDate.isVisible = dateParts.isNotEmpty()
    }

    private fun isDarkMode(): Boolean =
        (binding.root.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun open() {
        val isTmdb = type == TabType.SUBSCRIPTION && !notification.tmdbType.isNullOrBlank()
        if (isTmdb) {
            clickCallback(
                notification.mediaId ?: 0,
                if (notification.tmdbType == "tv") 1 else 0,
                NotificationClickType.TMDB_MEDIA
            )
        } else {
            clickCallback(notification.mediaId ?: 0, null, NotificationClickType.MEDIA)
        }
    }

    private fun dialog() {
        if (type != TabType.SUBSCRIPTION) return
        binding.root.context.customAlertDialog().apply {
            setTitle(R.string.delete)
            setMessage(notification.context ?: "")
            setPosButton(R.string.yes) {
                val list = PrefManager.getNullableVal<List<SubscriptionStore>>(
                    PrefName.SubscriptionNotificationStore, null
                ) ?: listOf()
                val newList = list.filter { (it.time / 1000L).toInt() != notification.createdAt }
                PrefManager.setVal(PrefName.SubscriptionNotificationStore, newList)
                parentAdapter.remove(this@EpisodeNotificationItem)
            }
            setNegButton(R.string.no)
            show()
        }
    }
}
