package ani.sanin.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.FrameLayout

class GlassStripLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var indicator: Drawable? = null
    private val bounds = RectF()
    private var hasBounds = false

    fun setIndicator(drawable: Drawable?) {
        indicator = drawable
        invalidate()
    }

    fun setIndicatorBounds(left: Float, top: Float, right: Float, bottom: Float) {
        bounds.set(left, top, right, bottom)
        hasBounds = true
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val d = indicator
        if (d != null && hasBounds && bounds.width() > 0f && bounds.height() > 0f) {
            d.setBounds(
                bounds.left.toInt(),
                bounds.top.toInt(),
                bounds.right.toInt(),
                bounds.bottom.toInt(),
            )
            d.draw(canvas)
        }
        super.dispatchDraw(canvas)
    }
}
