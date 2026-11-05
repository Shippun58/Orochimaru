package ani.sanin.ui.components
import ani.sanin.settings.AnimUtils

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

class CurseMarkSpinnerDrawable : Drawable() {

    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeJoin = Paint.Join.ROUND
    }

    private var rotation = 0f
    private var animator: ValueAnimator? = null

    var accentColor: Int = Color.MAGENTA

    private val alphas = intArrayOf(255, 100, 40)

    init { startAnimation() }

    private fun startAnimation() {
        if (!AnimUtils.enabled(ani.sanin.settings.saving.PrefName.InstallSpinnerAnimations)) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotation = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val size = minOf(b.width(), b.height()).toFloat()
        val radius = size * 0.38f
        val headR = size * 0.09f

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            alpha = 20
            style = Paint.Style.STROKE
            strokeWidth = size * 0.016f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(cx, cy, radius + headR * 0.3f, track)

        canvas.save()
        canvas.rotate(rotation, cx, cy)

        for (i in 0..2) {
            canvas.save()
            canvas.rotate((i * 120).toFloat(), cx, cy)

            headPaint.color = accentColor
            headPaint.alpha = alphas[i]
            tailPaint.color = accentColor
            tailPaint.alpha = alphas[i]

            val headX = cx
            val headY = cy - radius
            canvas.drawCircle(headX, headY, headR, headPaint)

            val tail = buildTail(cx, cy, radius, headR)
            canvas.drawPath(tail, tailPaint)

            canvas.restore()
        }

        canvas.restore()
    }

    private fun buildTail(cx: Float, cy: Float, radius: Float, headR: Float): Path {
        val path = Path()
        val headY = cy - radius
        val h = headR

        val sx = cx + h * 0.85f
        val sy = headY + h * 0.5f

        path.moveTo(sx, sy)

        path.cubicTo(
            cx + h * 2.0f, headY + h * 0.3f,
            cx + h * 1.5f, headY + radius * 0.9f,
            cx + h * 0.4f, headY + radius * 1.3f
        )

        path.cubicTo(
            cx, headY + radius * 1.55f,
            cx - h * 0.1f, headY + radius * 1.6f,
            cx - h * 0.1f, headY + radius * 1.6f
        )

        path.cubicTo(
            cx - h * 0.6f, headY + radius * 1.1f,
            cx - h * 0.7f, headY + h * 1.2f,
            cx - h * 0.5f, headY + h * 0.7f
        )
        path.close()
        return path
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
