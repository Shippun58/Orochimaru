package ani.sanin.util

import android.content.res.ColorStateList
import android.content.res.Resources
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import ani.sanin.isDarkTheme
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

object NavPillCustomizer {

    fun getHeightDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillHeight).coerceIn(32, 72)
    fun getIconSizeDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillIconSize).coerceIn(8, 28)

    fun getIconColor(): Int = PrefManager.getVal<Int>(PrefName.NavPillIconColor)

    fun isDarkTheme(): Boolean = ani.sanin.isDarkTheme()
    fun getCornerRadiusDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillCornerRadius).coerceIn(0, 48)

    fun getShowLabel(): Boolean = PrefManager.getVal<Boolean>(PrefName.NavPillShowLabel)

    fun getShrinkOnScroll(): Boolean = PrefManager.getVal<Boolean>(PrefName.NavPillShrinkOnScroll)

    fun getPillFillColor(): Int = if (isDarkTheme()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    fun applyPillBackground(view: View) {
        val density = view.resources.displayMetrics.density
        view.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = getCornerRadiusDp() * density
            setColor(getPillFillColor())
        }
    }

    private fun computeIconPadding(pillSizeDp: Int, iconSizeDp: Int): Int {
        return ((pillSizeDp - iconSizeDp) / 2).coerceIn(2, pillSizeDp / 2 - 2)
    }

    fun getIconPaddingPx(pillSizePx: Int): Int {
        val density = Resources.getSystem().displayMetrics.density
        return (computeIconPadding(getHeightDp(), getIconSizeDp()) * density).toInt()
            .coerceAtMost(pillSizePx / 2)
    }

    fun getIconDrawnSizePx(pillSizePx: Int): Int {
        val density = Resources.getSystem().displayMetrics.density
        val content = pillSizePx - 2 * getIconPaddingPx(pillSizePx)
        return minOf((getIconSizeDp() * density).toInt(), content).coerceAtLeast(0)
    }

    private const val fixedSpacingDp = 6

    fun applyIconSettings(btn: ImageButton, iconPaddingPx: Int) {
        btn.setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
        btn.imageTintList = ColorStateList.valueOf(getIconColor())
    }

    fun applyToPillList(pillList: LinearLayout) {
        val density = pillList.resources.displayMetrics.density
        val height = getHeightDp()
        val iconSize = getIconSizeDp()

        val pillSizePx = (height * density).toInt()
        val spacingPx = (fixedSpacingDp * density).toInt()
        val isHorizontal = pillList.orientation == LinearLayout.HORIZONTAL

        if (isHorizontal) {
            pillList.setPadding(spacingPx, pillList.paddingTop, spacingPx, pillList.paddingBottom)
        } else {
            pillList.setPadding(pillList.paddingLeft, spacingPx, pillList.paddingRight, spacingPx)

            (pillList.parent as? View)?.let { rail ->
                val railLp = rail.layoutParams
                if (railLp != null && railLp.width != pillSizePx) {
                    railLp.width = pillSizePx
                    rail.layoutParams = railLp
                }
            }
        }

        for (i in 0 until pillList.childCount) {
            val child = pillList.getChildAt(i)
            if (child is ImageButton) {
                val lp = child.layoutParams
                if (isHorizontal) {
                    lp.width = pillSizePx
                    lp.height = pillSizePx
                } else {
                    lp.width = pillSizePx

                }
                child.layoutParams = lp
                val contentSizeDp = if (isHorizontal) height else minOf(height, 44)
                val iconPaddingPx = (computeIconPadding(contentSizeDp, iconSize) * density).toInt()
                applyIconSettings(child, iconPaddingPx)
            }
        }
    }
}
