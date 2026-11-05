package ani.sanin.media
import ani.sanin.settings.AnimUtils

import android.content.Intent
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.VelocityTracker
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.databinding.ActivityCalendarBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.navBarHeight
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.ui.GlassPill
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.pow

class CalendarActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalendarBinding
    private val model: OtherDetailsViewModel by viewModels()

    private var rangeStart = Calendar.getInstance()
    private var selectedDate = Calendar.getInstance()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fullDayFmt = SimpleDateFormat("EEEE, MMMM d", Locale.US)
    private var allCalendarData: Map<String, MutableList<Media>> = emptyMap()
    private val dayShortNames = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    private var indicatorPlaced = false
    private var indLeft = 0f
    private var indRight = 0f
    private var indTop = 0f
    private var indBottom = 0f
    private var indicatorAnimator: ValueAnimator? = null

    private val pillSnap = Runnable { snapPill() }

    private companion object {

        const val TOTAL_DAYS = 21

        const val VISIBLE_DAYS = 7
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.calendarRoot.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        binding.calendarBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        FocusEffectUtil.applyFocusListener(binding.calendarBack)

        binding.calendarListToggle.isSaveEnabled = false
        binding.calendarListToggle.isChecked = PrefManager.getVal<Boolean>(PrefName.CalendarListOnly)
        binding.calendarListToggle.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.CalendarListOnly, isChecked)
            refreshDisplay(allCalendarData)
        }
        FocusEffectUtil.applyFocusListener(binding.calendarListToggle)

        binding.calendarTodayBtn.setOnClickListener { goToToday() }
        FocusEffectUtil.applyFocusListener(binding.calendarTodayBtn)

        binding.calendarListToggle.nextFocusUpId = R.id.calendarBack
        binding.calendarListToggle.nextFocusDownId = R.id.calendarWeekStrip

        binding.calendarListToggle.post { binding.calendarListToggle.requestFocus() }

        rangeStart = mondayOfWeek(Calendar.getInstance()).apply {
            add(Calendar.DAY_OF_YEAR, -7)
        }
        selectedDate = Calendar.getInstance()

        GlassPill.applyContainer(binding.calendarPill)

        binding.calendarWeekScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            binding.calendarWeekScroll.removeCallbacks(pillSnap)
            binding.calendarWeekScroll.postDelayed(pillSnap, 150)
        }

        buildWeekStrip()
        updateDayLabel()
        updateSubtitle()

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                binding.calendarSpinner.visibility = View.VISIBLE
                binding.calendarDayEpisodes.visibility = android.view.View.GONE
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { model.loadCalendar() }
                    live.postValue(false)
                }
            }
        }

        model.getCalendar().observe(this) { data ->
            binding.calendarSpinner.visibility = View.GONE
            if (data != null) {
                allCalendarData = data
                refreshDisplay(data)
            }
        }
        setupDaySwipe()
    }

    private fun setupDaySwipe() {
        val container = binding.calendarDayEpisodes
        var velocityTracker: VelocityTracker? = null
        var downX = 0f
        var downY = 0f
        var isDragging = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        container.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    downX = event.x
                    downY = event.y
                    isDragging = false
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!isDragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        isDragging = true
                    }
                    false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val dx = event.x - downX
                    val threshold = 80 * resources.displayMetrics.density
                    if (isDragging && (Math.abs(dx) > threshold || Math.abs(vx) > 500f)) {

                        moveSelection(if (dx < 0 || vx < -500f) 1 else -1)
                        velocityTracker?.recycle()
                        velocityTracker = null
                        true
                    } else {
                        velocityTracker?.recycle()
                        velocityTracker = null
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun goToToday() {
        selectedDate = Calendar.getInstance()
        afterSelectionChanged()
    }

    private fun mondayOfWeek(cal: Calendar): Calendar {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val daysSinceMonday = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        c.add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
        return c
    }

    private fun selectedIndex(): Int {
        val start = rangeStart.timeInMillis
        val sel = (selectedDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ((sel - start) / 86_400_000L).toInt().coerceIn(0, TOTAL_DAYS - 1)
    }

    private fun moveSelection(delta: Int) {
        val current = selectedIndex()
        val target = (current + delta).coerceIn(0, TOTAL_DAYS - 1)
        if (target == current) return
        selectedDate = (rangeStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, target) }
        afterSelectionChanged()
    }

    private fun selectDayIndex(index: Int) {
        val clamped = index.coerceIn(0, TOTAL_DAYS - 1)
        selectedDate = (rangeStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, clamped) }
        afterSelectionChanged()
    }

    private fun afterSelectionChanged() {
        repaintCells()
        updateDayLabel()
        updateSubtitle()
        moveIndicatorTo(selectedIndex(), animate = indicatorPlaced)
        scrollPillTo(selectedIndex())
        refreshDisplay(allCalendarData)
    }

    private fun buildWeekStrip() {
        val strip = binding.calendarWeekStrip
        val row = binding.calendarWeekRow
        row.removeAllViews()
        val pillWidth = resources.displayMetrics.widthPixels - dpToPx(24) - dpToPx(12)
        val dayW = (pillWidth / VISIBLE_DAYS).coerceAtLeast(dpToPx(34))

        for (i in 0 until TOTAL_DAYS) {
            val day = (rangeStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dayW, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dpToPx(2), dpToPx(6), dpToPx(2), dpToPx(6))
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = false
                id = View.generateViewId()
            }
            val nameTv = TextView(this).apply {
                text = dayShortNames[(day.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7]
                textSize = 11f
                gravity = Gravity.CENTER
            }
            val numTv = TextView(this).apply {
                text = day.get(Calendar.DAY_OF_MONTH).toString()
                textSize = 15f
                gravity = Gravity.CENTER
            }
            cell.addView(nameTv)
            cell.addView(numTv)
            cell.setOnClickListener { selectDayIndex(i) }
            cell.setOnFocusChangeListener { _, focused ->
                paintDayCell(cell, nameTv, numTv, i)
                if (focused) scrollPillTo(i)
            }
            row.addView(cell)
        }

        for (i in 0 until row.childCount) {
            val cell = row.getChildAt(i)
            cell.nextFocusLeftId = if (i > 0) row.getChildAt(i - 1).id else View.NO_ID
            cell.nextFocusRightId = if (i < row.childCount - 1) row.getChildAt(i + 1).id else View.NO_ID
            cell.nextFocusUpId = R.id.calendarListToggle
            cell.nextFocusDownId = R.id.calendarDayEpisodes
        }

        strip.setIndicator(GlassPill.indicatorDrawable(this))
        repaintCells()
        strip.post {
            moveIndicatorTo(selectedIndex(), animate = false)
            scrollPillTo(selectedIndex(), smooth = false)
        }
    }

    private fun moveIndicatorTo(index: Int, animate: Boolean) {
        val strip = binding.calendarWeekStrip
        val row = binding.calendarWeekRow
        if (index < 0 || index >= row.childCount) return
        val cell = row.getChildAt(index)
        if (cell.width == 0 || cell.height == 0) return

        val gap = dpToPx(2)
        val tl = (cell.left + gap).toFloat()
        val tr = (cell.right - gap).toFloat()
        val tt = (cell.top + gap).toFloat()
        val tb = (cell.bottom - gap).toFloat()

        if (!animate || !AnimUtils.enabled(ani.sanin.settings.saving.PrefName.NavRailAnimations) || !indicatorPlaced) {
            indicatorAnimator?.cancel()
            indLeft = tl; indRight = tr; indTop = tt; indBottom = tb
            strip.setIndicatorBounds(tl, tt, tr, tb)
            indicatorPlaced = true
            return
        }

        val startLeft = indLeft
        val startRight = indRight
        val startTop = indTop
        val startBottom = indBottom
        val movingRight = (tl + tr) / 2f >= (startLeft + startRight) / 2f

        indicatorAnimator?.cancel()
        indicatorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                val lead = 1f - (1f - t).pow(3.2f)
                val trail = 1f - (1f - t).pow(1.7f)
                if (movingRight) {
                    indRight = startRight + (tr - startRight) * lead
                    indLeft = startLeft + (tl - startLeft) * trail
                } else {
                    indLeft = startLeft + (tl - startLeft) * lead
                    indRight = startRight + (tr - startRight) * trail
                }
                indTop = startTop + (tt - startTop) * t
                indBottom = startBottom + (tb - startBottom) * t
                strip.setIndicatorBounds(indLeft, indTop, indRight, indBottom)
            }
            start()
        }
    }

    private fun repaintCells() {
        val row = binding.calendarWeekRow
        for (i in 0 until row.childCount) {
            val cell = row.getChildAt(i) as? LinearLayout ?: continue
            val nameTv = cell.getChildAt(0) as? TextView ?: continue
            val numTv = cell.getChildAt(1) as? TextView ?: continue
            paintDayCell(cell, nameTv, numTv, i)
        }
    }

    private fun paintDayCell(cell: LinearLayout, nameTv: TextView, numTv: TextView, index: Int) {
        val day = (rangeStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, index) }
        val iso = dateFmt.format(day.time)
        val selected = iso == dateFmt.format(selectedDate.time)
        val today = iso == dateFmt.format(Date())
        val onSurface = getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val accent = getThemeColor(com.google.android.material.R.attr.colorPrimary)

        GlassPill.applyCell(cell, selected = false, focused = cell.isFocused)
        nameTv.setTextColor(if (selected) onSurface else onSurfaceVariant)
        numTv.setTextColor(
            when {
                selected -> onSurface
                today -> accent
                else -> onSurface
            }
        )
        nameTv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        numTv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun scrollPillTo(index: Int, smooth: Boolean = true) {
        val scroll = binding.calendarWeekScroll
        val row = binding.calendarWeekRow
        if (row.childCount == 0) return
        val cellW = row.getChildAt(0).width
        if (cellW <= 0) return
        val maxFirst = (TOTAL_DAYS - VISIBLE_DAYS).coerceAtLeast(0)
        val first = (index - VISIBLE_DAYS / 2).coerceIn(0, maxFirst)
        val target = first * cellW
        if (smooth) scroll.smoothScrollTo(target, 0) else scroll.scrollTo(target, 0)
    }

    private fun snapPill() {
        val scroll = binding.calendarWeekScroll
        val row = binding.calendarWeekRow
        if (row.childCount == 0) return
        val cellW = row.getChildAt(0).width
        if (cellW <= 0) return
        val maxFirst = (TOTAL_DAYS - VISIBLE_DAYS).coerceAtLeast(0)
        val first = Math.round(scroll.scrollX.toFloat() / cellW).coerceIn(0, maxFirst)
        val target = first * cellW
        if (target != scroll.scrollX) scroll.smoothScrollTo(target, 0)
    }

    private fun updateSubtitle() {
        val todayMonday = mondayOfWeek(Calendar.getInstance())
        val selectedMonday = mondayOfWeek(selectedDate)
        binding.calendarSubtitle.text = when {
            selectedMonday.before(todayMonday) -> getString(R.string.calendar_episodes_past_week)
            selectedMonday.after(todayMonday) -> getString(R.string.calendar_episodes_next_week)
            else -> getString(R.string.calendar_episodes_this_week)
        }
    }

    private fun updateDayLabel() {
        binding.calendarDayLabel.text = fullDayFmt.format(selectedDate.time)
        binding.calendarDayLabel.visibility = View.VISIBLE
    }

    private fun refreshDisplay(data: Map<String, MutableList<Media>>) {
        val showOnlyList = PrefManager.getVal<Boolean>(PrefName.CalendarListOnly)
        val selectedIso = dateFmt.format(selectedDate.time)
        val allEpisodes = mutableListOf<Media>()

        for ((key, list) in data) {
            val keyDate = try { dateFmt.parse(key) } catch (_: Exception) { null }
            val isoMatch = key == selectedIso || (keyDate != null && dateFmt.format(keyDate) == selectedIso)
            if (isoMatch) allEpisodes.addAll(list)
        }

        if (allEpisodes.isEmpty()) {
            val selectedDoy = selectedDate.get(Calendar.DAY_OF_YEAR)
            val selectedYear = selectedDate.get(Calendar.YEAR)
            for ((key, list) in data) {
                val keyDate = try {
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL, Locale.US).parse(key)
                } catch (_: Exception) { null }
                if (keyDate != null) {
                    val keyCal = Calendar.getInstance().apply { time = keyDate }
                    if (keyCal.get(Calendar.DAY_OF_YEAR) == selectedDoy && keyCal.get(Calendar.YEAR) == selectedYear) {
                        allEpisodes.addAll(list)
                    }
                }
            }
        }

        val filtered = if (showOnlyList) {
            allEpisodes.filter { it.userProgress != null || it.userStatus != null }
        } else allEpisodes

        val epContainer = binding.calendarDayEpisodes
        epContainer.removeAllViews()
        if (filtered.isEmpty()) {
            binding.calendarEmpty.visibility = View.VISIBLE
            epContainer.visibility = View.GONE
        } else {
            binding.calendarEmpty.visibility = View.GONE
            epContainer.visibility = View.VISIBLE
            for (media in filtered) {
                val v = LayoutInflater.from(this).inflate(R.layout.item_calendar_poster, epContainer, false)
                v.findViewById<android.widget.ImageView>(R.id.calendarPoster).loadImage(media.cover)
                v.findViewById<TextView>(R.id.calendarTitle).text = media.userPreferredName.ifBlank { media.name ?: media.nameRomaji }

                val badge = v.findViewById<TextView>(R.id.calendarBadge)
                val rel = media.relation ?: ""
                val epNum = Regex("""Episode\s+(\d+)""").find(rel)?.groupValues?.get(1)
                val sxeNum = Regex("""S(\d+)E(\d+)""").find(rel)?.groupValues?.let { "S${it[1]}E${it[2]}" }
                val timeStr = if (rel.contains("\n")) rel.lines().getOrNull(1)?.trim() else null
                val isMovie = media.tmdbType == "movie" || media.format == "MOVIE"
                val primaryColor = getThemeColor(com.google.android.material.R.attr.colorPrimary)
                val badgeText = when {
                    isMovie -> {
                        val dateLine = rel.lines().firstOrNull { it != "Movie" && it.isNotBlank() }
                        if (dateLine != null && dateLine.length >= 10) {
                            val parts = dateLine.split("-")
                            if (parts.size >= 3) "Movie \u00b7 ${parts[1]}/${parts[2]} \u00b7 12:00AM" else "Movie"
                        } else "Movie"
                    }
                    sxeNum != null && !timeStr.isNullOrBlank() -> "Ssn${sxeNum.substringAfter("S").substringBefore("E")} Ep ${sxeNum.substringAfter("E")} \u00b7 $timeStr"
                    sxeNum != null -> "Ssn${sxeNum.substringAfter("S").substringBefore("E")} Ep ${sxeNum.substringAfter("E")}"
                    epNum != null && !timeStr.isNullOrBlank() -> "Ep $epNum \u00b7 $timeStr"
                    epNum != null -> "Ep $epNum"
                    else -> "New"
                }
                val spannable = SpannableString(badgeText)
                Regex("\\d+").findAll(badgeText).forEach { match ->
                    spannable.setSpan(ForegroundColorSpan(primaryColor), match.range.first, match.range.last + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                badge.text = spannable

                v.setOnClickListener {
                    val isAnime = media.anime != null
                    val intent = if (isAnime) {
                        Intent(this, MediaDetailsActivity::class.java).apply {
                            putExtra("mediaId", media.id)
                        }
                    } else {
                        Intent(this, ani.sanin.cloudstream.TmdbDetailsActivity::class.java).apply {
                            putExtra("mediaId", media.id)
                            putExtra("mediaType", media.tmdbType ?: "tv")
                        }
                    }
                    startActivity(intent)
                }

                FocusEffectUtil.applyFocusListener(v)
                epContainer.addView(v)
            }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
