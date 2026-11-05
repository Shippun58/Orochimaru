package ani.sanin.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

object LensButtonBackground {

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun make(
        context: Context,
        fillAlpha: Float? = null,
        tint: Int? = null,
        cornerRadiusPx: Float? = null,
        flat: Boolean = false,
    ): Drawable {
        val dark = isNight(context)
        val alpha = fillAlpha ?: if (dark) 0.70f else 0.68f

        val baseColor = if (dark) 0x161922 else 0xFFFFFF
        val fillColor = tint ?: ((Math.round(alpha * 255f) shl 24) or baseColor)

        
        
        
        val filler = tint != null && (((tint ushr 24) and 0xFF) / 255f) <= 0.20f
        val rippleValue = if (flat || filler) Color.TRANSPARENT
        else if (dark) 0x33FFFFFF else 0x1F000000
        val rippleColor = ColorStateList.valueOf(rippleValue)

        fun lighten(color: Int, p: Float): Int {
            val a = (color ushr 24) and 0xFF
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            val mix = { c: Int -> c + Math.round((255 - c) * p) }
            return (a shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
        }

        if (cornerRadiusPx != null) {
            val outline = if (dark) 0x40FFFFFF else 0x5CFFFFFF
            val density = context.resources.displayMetrics.density
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(cornerRadiusPx)
                if (flat || filler) {
                    colors = intArrayOf(fillColor, fillColor)
                } else {
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    colors = intArrayOf(lighten(fillColor, 0.12f), fillColor)
                }
                setStroke(
                    Math.max(1, (1f * density).toInt()),
                    outline
                )
            }
            return RippleDrawable(rippleColor, drawable, null)
        }

        val rim = intArrayOf(
            if (dark) 0x47FFFFFF else 0xE6FFFFFF.toInt(),
            if (dark) 0x1AFFFFFF else 0x66FFFFFF,
            if (dark) 0x0AFFFFFF else 0x2EFFFFFF,
        )

        val rimDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = rim
        }
        val fillDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
        }

        val inset = (context.resources.displayMetrics.density * 1f).toInt().coerceAtLeast(1)
        val layer = LayerDrawable(arrayOf(rimDrawable, fillDrawable))
        layer.setLayerInset(1, inset, inset, inset, inset)

        return RippleDrawable(rippleColor, layer, null)
    }

    fun apply(view: View, fillAlpha: Float? = null, tint: Int? = null, cornerRadiusPx: Float? = null) {
        val flat = tint != null &&
            (((tint ushr 24) and 0xFF) / 255f) <= 0.20f
        view.background = make(view.context, fillAlpha, tint, cornerRadiusPx, flat)

        if (!isNight(view.context)) {
            view.elevation = 3f * view.resources.displayMetrics.density
        }
    }

    fun fillColor(context: Context, alpha: Float): Int {
        val dark = isNight(context)
        return (Math.round(alpha * 255f) shl 24) or if (dark) 0x161922 else 0xFFFFFF
    }

    fun rimColor(context: Context, top: Boolean = true): Int {
        val dark = isNight(context)
        return if (dark) {
            if (top) 0x47FFFFFF else 0x0AFFFFFF
        } else {
            if (top) 0xE6FFFFFF.toInt() else 0x2EFFFFFF
        }
    }

    val RIPPLE_NEUTRAL = Color.TRANSPARENT
}