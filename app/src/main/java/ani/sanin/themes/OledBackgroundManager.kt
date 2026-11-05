package ani.sanin.themes

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.ViewGroup

object OledBackgroundManager {

    private val applied = java.util.WeakHashMap<Activity, Pair<android.graphics.drawable.Drawable?, android.graphics.drawable.Drawable?>>()

    fun apply(activity: Activity, oledMode: Int, primaryColor: Int, gradientDir: Int = 0, intensity: Float = 1f) {
        val drawable = when (oledMode) {
            1 -> SolidBlackDrawable()
            2 -> GlowSpotsDrawable(primaryColor, intensity)
            3 -> GradientBgDrawable(primaryColor, gradientDir, intensity)
            4 -> VignetteBgDrawable(primaryColor, intensity)
            else -> return
        }

        if (applied.containsKey(activity) && activity.window.decorView.background === drawable) return
        applied.remove(activity)

        applied[activity] = Pair(activity.window.decorView.background, null)
        activity.window.setBackgroundDrawable(drawable)

        if (oledMode >= 2) {
            clearContentBackground(activity)
        }
    }

    fun remove(activity: Activity) {
        val stored = applied.remove(activity) ?: return
        activity.window.setBackgroundDrawable(stored.first)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content?.getChildAt(0)
        if (root != null && stored.second != null) root.background = stored.second
    }

    private fun clearContentBackground(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        if (root.background != null) {
            val existing = applied[activity]
            if (existing != null) applied[activity] = Pair(existing.first, root.background)
            root.background = null
        }
    }

    private class SolidBlackDrawable : Drawable() {
        override fun draw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    private class GlowSpotsDrawable(
        private val primaryColor: Int,
        private val intensity: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cachedBounds: android.graphics.Rect? = null
        private val spots = listOf(
            floatArrayOf(0.50f, 0.10f, 0.45f),
            floatArrayOf(0.90f, 0.18f, 0.36f),
            floatArrayOf(0.10f, 0.82f, 0.38f),
        )
        private val cachedShaders = arrayOfNulls<Shader>(3)

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            canvas.drawColor(Color.BLACK)
            if (cachedBounds != b) {
                cachedBounds = android.graphics.Rect(b)
                val r = Color.red(primaryColor)
                val g = Color.green(primaryColor)
                val bl = Color.blue(primaryColor)
                for (i in spots.indices) {
                    val spot = spots[i]
                    cachedShaders[i] = RadialGradient(
                        spot[0] * w, spot[1] * h, spot[2] * minOf(w, h),
                        intArrayOf(
                            scaleAlpha(Color.argb(90, r, g, bl)),
                            scaleAlpha(Color.argb(40, r, g, bl)),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.40f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
            }
            for (i in spots.indices) {
                paint.shader = cachedShaders[i]
                canvas.drawCircle(spots[i][0] * w, spots[i][1] * h, spots[i][2] * minOf(w, h), paint)
            }
        }

        private fun scaleAlpha(color: Int): Int {
            val a = (Color.alpha(color) * intensity).toInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class GradientBgDrawable(
        private val primaryColor: Int,
        private val direction: Int,
        private val intensity: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cachedBounds: android.graphics.Rect? = null
        private var cachedShader: Shader? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            canvas.drawColor(Color.BLACK)
            if (cachedBounds != b) {
                cachedBounds = android.graphics.Rect(b)
                val r = Color.red(primaryColor)
                val g = Color.green(primaryColor)
                val bl = Color.blue(primaryColor)
                val x0: Float; val y0: Float; val x1: Float; val y1: Float
                when (direction) {
                    1 -> { x0 = w / 2f; y0 = h; x1 = w / 2f; y1 = h * 0.30f }
                    2 -> { x0 = 0f; y0 = h / 2f; x1 = w * 0.70f; y1 = h / 2f }
                    3 -> { x0 = w; y0 = h / 2f; x1 = w * 0.30f; y1 = h / 2f }
                    else -> { x0 = w / 2f; y0 = 0f; x1 = w / 2f; y1 = h * 0.70f }
                }
                cachedShader = LinearGradient(
                    x0, y0, x1, y1,
                    intArrayOf(
                        scaleAlpha(Color.argb(80, r, g, bl)),
                        scaleAlpha(Color.argb(35, r, g, bl)),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.40f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            paint.shader = cachedShader
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        private fun scaleAlpha(color: Int): Int {
            val a = (Color.alpha(color) * intensity).toInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class VignetteBgDrawable(
        private val primaryColor: Int,
        private val intensity: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cachedBounds: android.graphics.Rect? = null
        private var cachedShader: Shader? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            canvas.drawColor(Color.BLACK)
            if (cachedBounds != b) {
                cachedBounds = android.graphics.Rect(b)
                val r = Color.red(primaryColor)
                val g = Color.green(primaryColor)
                val bl = Color.blue(primaryColor)
                val cx = w / 2f
                val cy = h / 2f
                val radius = maxOf(w, h) * 0.80f
                cachedShader = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(
                        Color.TRANSPARENT,
                        scaleAlpha(Color.argb(45, r, g, bl)),
                        scaleAlpha(Color.argb(110, 0, 0, 0))
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            paint.shader = cachedShader
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        private fun scaleAlpha(color: Int): Int {
            val a = (Color.alpha(color) * intensity).toInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
