package ani.sanin.ui.components
import ani.sanin.settings.AnimUtils

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import com.google.android.material.color.MaterialColors
import ani.sanin.R

class CurseMarkSpinnerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val commaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeJoin = Paint.Join.ROUND
    }
    private val commaStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var rotation = 0f
    private var animator: ValueAnimator? = null

    private var accentColor: Int = Color.MAGENTA
    private var alphasResolved = false

    private val alphas = intArrayOf(255, 120, 55)

    val tailPath = Path()

    private fun resolveAccentColor() {
        accentColor = try {
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        } catch (_: Exception) {
            Color.MAGENTA
        }
    }

    init {
        resolveAccentColor()
        startAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveAccentColor()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    private fun startAnimation() {
        if (!AnimUtils.enabled(ani.sanin.settings.saving.PrefName.InstallSpinnerAnimations)) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotation = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val size = minOf(width, height).toFloat()
        val radius = size * 0.36f
        val headR = size * 0.095f

        trackPaint.color = accentColor
        trackPaint.alpha = 25
        trackPaint.strokeWidth = size * 0.018f
        canvas.drawCircle(cx, cy, radius + headR * 0.5f, trackPaint)

        canvas.save()
        canvas.rotate(rotation, cx, cy)

        for (i in 0..2) {
            canvas.save()
            canvas.rotate(i * 120f, cx, cy)

            commaPaint.color = accentColor
            commaPaint.alpha = alphas[i]
            commaStrokePaint.color = accentColor
            commaStrokePaint.alpha = (alphas[i] * 0.3f).toInt()
            commaStrokePaint.strokeWidth = size * 0.012f

            val headX = cx
            val headY = cy - radius

            canvas.drawCircle(headX, headY, headR, commaPaint)

            buildTailPath(tailPath, cx, cy, radius, headR, size)
            canvas.drawPath(tailPath, commaPaint)
            canvas.drawPath(tailPath, commaStrokePaint)

            canvas.restore()
        }

        canvas.restore()
    }

    private fun buildTailPath(path: Path, cx: Float, cy: Float, radius: Float, headR: Float, size: Float) {
        path.reset()
        val headY = cy - radius
        val hw = headR * 0.7f
        val tw = size * 0.09f

        path.moveTo(cx + headR * 0.85f, headY + headR * 0.5f)
        path.cubicTo(
            cx + headR * 0.85f + tw, headY + headR * 0.7f,
            cx + tw * 0.6f, headY + radius * 0.8f,
            cx + tw * 0.2f, headY + radius * 1.3f
        )
        path.cubicTo(
            cx - tw * 0.05f, headY + radius * 1.6f,
            cx - tw * 0.15f, headY + radius * 1.7f,
            cx - tw * 0.15f, headY + radius * 1.7f
        )
        path.cubicTo(
            cx - tw * 0.25f, headY + radius * 1.4f,
            cx - hw * 0.3f, headY + headR * 1.2f,
            cx - hw * 0.3f, headY + headR * 0.8f
        )
        path.close()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resolveSize(48, widthMeasureSpec)
        setMeasuredDimension(size, size)
    }
}
