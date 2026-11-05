package ani.sanin.ui.components
import ani.sanin.settings.AnimUtils

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import ani.sanin.getThemeColor
import ani.sanin.ui.GlassPill
import ani.sanin.ui.GlassStripLayout
import com.google.android.material.R
import kotlin.math.pow
import kotlin.math.roundToInt

data class LibraryStatusTab(val label: String, val count: Int)

class LibraryStatusPill @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val scroll = HorizontalScrollView(context)
    private val strip = GlassStripLayout(context)
    private val row = LinearLayout(context)

    private var tabs: List<LibraryStatusTab> = emptyList()
    private var selectedIndex = 0
    private var onTabSelected: ((Int) -> Unit)? = null
    private var compact = true

    private var indicatorPlaced = false
    private var indLeft = 0f
    private var indRight = 0f
    private var indTop = 0f
    private var indBottom = 0f
    private var indicatorAnimator: ValueAnimator? = null

    private val density = resources.displayMetrics.density

    init {
        orientation = HORIZONTAL
        GlassPill.applyContainer(this)
        val inset = dp(6f)
        setPadding(inset, inset, inset, inset)

        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        strip.setIndicator(GlassPill.indicatorDrawable(context))
        strip.addView(row)

        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(strip)
        addView(scroll)

        isFocusable = true
        isFocusableInTouchMode = false
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focusSelectedTab()
        }
    }

    fun bind(
        tabs: List<LibraryStatusTab>,
        selectedIndex: Int,
        onTabSelected: (Int) -> Unit,
        fillWidth: Boolean = true,
        compact: Boolean = true,
    ) {
        this.onTabSelected = onTabSelected
        this.compact = compact
        scroll.layoutParams = (scroll.layoutParams ?: LayoutParams(
            if (fillWidth) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        )).apply {
            width = if (fillWidth) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
            height = LayoutParams.WRAP_CONTENT
        }
        val structureChanged = this.tabs != tabs
        this.tabs = tabs
        this.selectedIndex = selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        if (structureChanged) {
            rebuildTabs()
            indicatorPlaced = false
        }
        paintAll()
        post { moveIndicator(this.selectedIndex, animate = indicatorPlaced) }
    }

    fun setSelected(index: Int) {
        if (index !in tabs.indices) return
        if (index == selectedIndex && indicatorPlaced) return
        selectedIndex = index
        paintAll()
        moveIndicator(index, animate = indicatorPlaced)
    }

    fun setExitFocusUp(id: Int) {
        if (nextFocusUpId == id) return
        nextFocusUpId = id
        propagateNextFocus()
    }

    fun setExitFocusDown(id: Int) {
        if (nextFocusDownId == id) return
        nextFocusDownId = id
        propagateNextFocus()
    }

    private fun rebuildTabs() {
        indicatorAnimator?.cancel()
        row.removeAllViews()

        val hPad = dp(if (compact) 14f else 16f)
        val vPad = dp(if (compact) 7f else 10f)

        tabs.forEachIndexed { index, tab ->
            val cell = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(hPad, vPad, hPad, vPad)
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                id = View.generateViewId()
            }

            cell.addView(
                TextView(context).apply {
                    text = tab.label
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
                },
            )

            if (tab.count > 0) {
                val badge = TextView(context).apply {
                    text = if (tab.count > 99) "99+" else tab.count.toString()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    minWidth = dp(18f)
                    minHeight = dp(18f)
                    setPadding(dp(5f), 0, dp(5f), 0)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(100f).toFloat()
                        setColor(accentColor())
                    }
                }
                cell.addView(
                    badge,
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(6f)
                    },
                )
            }

            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            if (index < tabs.lastIndex) lp.marginEnd = dp(8f)
            row.addView(cell, lp)

            cell.setOnClickListener { select(index) }
            cell.setOnFocusChangeListener { _, focused ->
                paintCell(index)
                if (focused) reveal(cell)
            }
        }

        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            c.nextFocusLeftId = if (i > 0) row.getChildAt(i - 1).id else nextFocusLeftId
            c.nextFocusRightId =
                if (i < row.childCount - 1) row.getChildAt(i + 1).id else nextFocusRightId
        }
    }

    private fun select(index: Int) {
        if (index !in tabs.indices) return
        if (index == selectedIndex) return
        onTabSelected?.invoke(index)
        setSelected(index)
    }

    private fun focusSelectedTab() {
        val idx = selectedIndex.coerceIn(0, (row.childCount - 1).coerceAtLeast(0))
        row.getChildAt(idx)?.requestFocus()
    }

    private fun reveal(cell: View) {
        val viewport = scroll.width
        if (viewport <= 0) return
        val target = when {
            cell.left < scroll.scrollX -> cell.left
            cell.right > scroll.scrollX + viewport -> cell.right - viewport
            else -> return
        }
        scroll.smoothScrollTo(target, 0)
    }

    private fun paintAll() {
        for (i in tabs.indices) paintCell(i)
    }

    private fun paintCell(index: Int) {
        val cell = row.getChildAt(index) as? ViewGroup ?: return

        GlassPill.applyCell(cell, selected = false, focused = cell.isFocused)

        val selected = index == selectedIndex
        val label = cell.getChildAt(0) as? TextView ?: return
        val dark = GlassPill.isNight(context)
        label.setTextColor(
            when {
                selected -> onSurfaceColor()
                dark -> withAlpha(onSurfaceColor(), 0.65f)
                else -> onSurfaceVariantColor()
            },
        )
        label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        if (cell.childCount > 1) {
            (cell.getChildAt(1) as? TextView)?.setTextColor(onAccentColor())
        }
    }

    private fun moveIndicator(index: Int, animate: Boolean) {
        if (index < 0 || index >= row.childCount) return
        val cell = row.getChildAt(index)
        if (cell.width == 0 || cell.height == 0) {

            if (isShown) post { moveIndicator(index, animate) }
            return
        }

        val gap = dp(2f).toFloat()
        val tl = cell.left + gap
        val tr = cell.right - gap
        val tt = cell.top + gap
        val tb = cell.bottom - gap

        if (!animate || !AnimUtils.enabled(ani.sanin.settings.saving.PrefName.NavRailAnimations) || !indicatorPlaced) {
            indicatorAnimator?.cancel()
            indLeft = tl
            indRight = tr
            indTop = tt
            indBottom = tb
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

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        propagateNextFocus()
    }

    private fun propagateNextFocus() {
        if (row.childCount == 0) return
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (nextFocusUpId != NO_ID) c.nextFocusUpId = nextFocusUpId
            if (nextFocusDownId != NO_ID) c.nextFocusDownId = nextFocusDownId
        }
        row.getChildAt(0).nextFocusLeftId = nextFocusLeftId
        row.getChildAt(row.childCount - 1).nextFocusRightId = nextFocusRightId
    }

    private fun themed() = GlassPill.themedContext(context)

    private fun accentColor() = themed().getThemeColor(R.attr.colorPrimary)

    private fun onAccentColor() = themed().getThemeColor(R.attr.colorOnPrimary)

    private fun onSurfaceColor() = themed().getThemeColor(R.attr.colorOnSurface)

    private fun onSurfaceVariantColor() = themed().getThemeColor(R.attr.colorOnSurfaceVariant)

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha * 255f).roundToInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun dp(value: Float): Int = (density * value).roundToInt().coerceAtLeast(0)
}
