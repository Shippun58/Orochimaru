package ani.sanin.util

import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.flexbox.FlexboxLayout
import ani.sanin.R
import ani.sanin.getThemeColor

private const val CLASSIC_CHIPS_TOP_SHARE = 5f
private const val CLASSIC_CHIPS_SPLIT_TOTAL = 8f

const val CLASSIC_CHIPS_MAX = 10

fun bindClassicChips(
    container: ViewGroup?,
    top: ViewGroup?,
    bottom: ViewGroup?,
    labels: List<String>,
) {
    if (top == null || bottom == null) return
    top.removeAllViews()
    bottom.removeAllViews()

    val capped = labels.filter { it.isNotBlank() }.take(CLASSIC_CHIPS_MAX)
    if (capped.isEmpty()) {

        container?.isVisible = false
        top.isVisible = false
        bottom.isVisible = false
        return
    }
    container?.isVisible = true

    val split = Math.round(capped.size * CLASSIC_CHIPS_TOP_SHARE / CLASSIC_CHIPS_SPLIT_TOTAL)
        .coerceIn(0, capped.size)
    addChips(top, capped.take(split))
    addChips(bottom, capped.drop(split))
    top.isVisible = true
    bottom.isVisible = capped.size > split
}

private fun addChips(row: ViewGroup, labels: List<String>) {
    if (labels.isEmpty()) {
        row.isVisible = false
        return
    }
    val ctx = row.context
    val res = ctx.resources
    val density = res.displayMetrics.density
    val gap = res.getDimension(R.dimen.banner_classic_chip_gap).toInt()
    val padH = res.getDimension(R.dimen.banner_classic_chip_pad).toInt()
    val height = res.getDimensionPixelSize(R.dimen.banner_classic_chip_height)
    val textSize = res.getDimension(R.dimen.banner_classic_chip_text) / density
    val onBackground = ctx.getThemeColor(com.google.android.material.R.attr.colorOnBackground)

    row.isVisible = true
    for (label in labels) {
        val chip = TextView(ctx).apply {
            text = label
            setTextColor(onBackground)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, textSize)
            setBackgroundResource(R.drawable.tag_chip_bg)
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            setPadding(padH, 0, padH, 0)
            layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                height,
            ).apply { marginEnd = gap }
        }
        row.addView(chip)
    }
}
