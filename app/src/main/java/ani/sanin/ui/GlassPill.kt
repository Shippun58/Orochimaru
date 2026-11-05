package ani.sanin.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import ani.sanin.getThemeColor

object GlassPill {

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(context: Context, value: Float): Int =
        (context.resources.displayMetrics.density * value).toInt().coerceAtLeast(1)

    fun themedContext(context: Context): Context {
        var ctx: Context = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return context
    }

    fun radiusPx(context: Context): Float =
        context.resources.displayMetrics.density * 100f

    fun applyContainer(view: View) {
        val context = view.context
        val dark = isNight(context)
        val accent = themedContext(context)
            .getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val radius = radiusPx(context)

        val fill: IntArray
        val rimPeak: Int
        if (dark) {
            fill = intArrayOf(0x14FFFFFF, 0x0AFFFFFF, 0x05FFFFFF)

            rimPeak = ColorUtils.setAlphaComponent(Color.WHITE, 0x61)
        } else {
            fill = intArrayOf(
                ColorUtils.blendARGB(Color.WHITE, accent, 0.10f),
                ColorUtils.blendARGB(Color.WHITE, accent, 0.05f),
                ColorUtils.blendARGB(Color.WHITE, accent, 0.02f),
            )

            rimPeak = ColorUtils.setAlphaComponent(Color.BLACK, 0x61)
        }

        view.background = topLit(fill, rimPeak, dp(context, 3f), radius)
        view.clipToOutline = true
        view.elevation = if (dark) 0f else 6f * view.resources.displayMetrics.density
    }

    fun topLit(context: Context, fill: IntArray, cornerRadiusPx: Float): Drawable {
        val rimPeak = ColorUtils.setAlphaComponent(
            if (isNight(context)) Color.WHITE else Color.BLACK, 0x61
        )
        return topLit(fill, rimPeak, dp(context, 3f), cornerRadiusPx)
    }

    fun topLit(
        fill: IntArray,
        rimPeak: Int,
        thickness: Int,
        cornerRadiusPx: Float,
        rimFloor: Float = 0f,
    ): Drawable {
        val fillLayer = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setColors(fill)
            cornerRadius = cornerRadiusPx
        }
        val rimLayer = TopLitRimDrawable(rimPeak, thickness.toFloat(), cornerRadiusPx, rimFloor)
        return LayerDrawable(arrayOf<Drawable>(fillLayer, rimLayer))
    }

    fun applyServerCard(view: View, cornerRadiusDp: Float = 16f) {
        val context = view.context
        val dark = isNight(context)
        val accent = themedContext(context)
            .getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val radius = cornerRadiusDp * context.resources.displayMetrics.density
        val fill = if (dark) {
            intArrayOf(
                ColorUtils.setAlphaComponent(accent, 0x1F),
                ColorUtils.setAlphaComponent(accent, 0x0A),
                ColorUtils.setAlphaComponent(accent, 0x00),
            )
        } else {
            intArrayOf(
                ColorUtils.setAlphaComponent(accent, 0x1A),
                ColorUtils.setAlphaComponent(accent, 0x09),
                ColorUtils.setAlphaComponent(accent, 0x00),
            )
        }
        val rimPeak = ColorUtils.setAlphaComponent(accent, 0xCC)
        view.background = topLit(fill, rimPeak, dp(context, 1f), radius, rimFloor = 0.5f)
        view.clipToOutline = true
    }

    fun applyCell(view: View, selected: Boolean, focused: Boolean) {
        val context = view.context
        val dark = isNight(context)
        val accent = themedContext(context)
            .getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val radius = radiusPx(context)
        val gap = dp(context, 2f)

        view.background = when {
            selected -> {
                val fill = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    if (dark) {
                        intArrayOf(
                            ColorUtils.setAlphaComponent(
                                ColorUtils.blendARGB(accent, Color.WHITE, 0.18f), 0x52
                            ),
                            ColorUtils.setAlphaComponent(accent, 0x2E),
                        )
                    } else {
                        intArrayOf(
                            ColorUtils.setAlphaComponent(accent, 0x33),
                            ColorUtils.setAlphaComponent(Color.WHITE, 0x66),
                        )
                    },
                ).apply { cornerRadius = radius }
                val border = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = radius
                    setStroke(
                        dp(context, 1f),
                        if (dark) {
                            ColorUtils.setAlphaComponent(accent, 0x40)
                        } else {
                            ColorUtils.setAlphaComponent(accent, 0x47)
                        },
                    )
                }
                inset(arrayOf(fill, border), gap)
            }

            focused -> {
                val rim = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = radius
                    setStroke(dp(context, 2f), accent)
                }
                inset(arrayOf(rim), gap)
            }

            else -> null
        }
        view.elevation = 0f
    }

    fun indicatorDrawable(context: Context): Drawable {
        val dark = isNight(context)
        val accent = themedContext(context)
            .getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val radius = radiusPx(context)

        val fillLayer = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (dark) {
                intArrayOf(
                    ColorUtils.setAlphaComponent(
                        ColorUtils.blendARGB(accent, Color.WHITE, 0.18f), 0x52
                    ),
                    ColorUtils.setAlphaComponent(accent, 0x2E),
                )
            } else {
                intArrayOf(
                    ColorUtils.setAlphaComponent(accent, 0x33),
                    ColorUtils.setAlphaComponent(Color.WHITE, 0x66),
                )
            },
        ).apply { cornerRadius = radius }

        val rimLayer = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(
                dp(context, 1f),
                if (dark) {
                    ColorUtils.setAlphaComponent(accent, 0x40)
                } else {
                    ColorUtils.setAlphaComponent(accent, 0x47)
                },
            )
        }

        return LayerDrawable(arrayOf<Drawable>(fillLayer, rimLayer))
    }

    private fun inset(layers: Array<GradientDrawable>, inset: Int): Drawable {
        val layer = LayerDrawable(layers as Array<Drawable>)
        for (i in layers.indices) layer.setLayerInset(i, inset, inset, inset, inset)
        return layer
    }
}

private class TopLitRimDrawable(
    private val peak: Int,
    private val thicknessPx: Float,
    private val radius: Float,
    rimFloor: Float = 0f,
) : Drawable() {
    private val floor = ColorUtils.setAlphaComponent(
        peak, (Color.alpha(peak) * rimFloor).toInt().coerceIn(0, 255)
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = thicknessPx
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = thicknessPx / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        paint.shader = LinearGradient(
            0f,
            b.top.toFloat(),
            0f,
            b.bottom.toFloat(),
            peak,
            floor,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) {
            outline.setEmpty()
        } else {
            outline.setRoundRect(bounds, radius)
        }
    }
}