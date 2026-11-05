package ani.sanin.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.util.GlassComponent
import ani.sanin.util.GlassEffectDrawable
import ani.sanin.util.GlassEffectManager
import ani.sanin.util.NavPillCustomizer

class SnakeNavRailView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var glassDrawable: GlassEffectDrawable? = null

    init {
        setWillNotDraw(true)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> suggestedMinimumHeight
            else -> suggestedMinimumHeight
        }
        setMeasuredDimension(width, height)
    }

    var live = true
        set(value) {
            field = value
            invalidate()
        }

    private var _glassEnabled: Boolean = false
    fun setGlassEnabled(enabled: Boolean) {
        _glassEnabled = enabled && GlassEffectManager.isComponentEnabled(GlassComponent.NavPills)
        if (_glassEnabled) {
            if (glassDrawable == null) {
                glassDrawable = GlassEffectDrawable.applyToView(
                    view = this,
                    cornerRadiusDp = NavPillCustomizer.getCornerRadiusDp().toFloat(),
                    blurRadius = GlassEffectManager.getBlurRadius(),
                    tintColor = GlassEffectManager.getTintColor()
                ).also { d ->
                    GlassEffectManager.applyParams(d)
                    post { wireScrollInvalidator(d) }
                }
            }
        } else {
            glassDrawable?.destroy()
            glassDrawable = null
            background = null
        }
        invalidate()
    }

    private fun wireScrollInvalidator(drawable: GlassEffectDrawable) {
        val rv = rootView
        if (rv is ViewGroup) {
            fun find(vg: ViewGroup): View? {
                for (i in 0 until vg.childCount) {
                    val c = vg.getChildAt(i)
                    if (c is RecyclerView || c is NestedScrollView ||
                        c is ScrollView || c is android.widget.ListView
                    ) return c
                    if (c is ViewGroup) find(c)?.let { return it }
                }
                return null
            }
            find(rv)?.let { drawable.invalidateOnScroll(it) }
        }
    }

    fun updateGlassParams() {
        if (!_glassEnabled) return
        glassDrawable?.let { d ->
            d.setTintColor(GlassEffectManager.getTintColor())
            d.setCornerRadius(NavPillCustomizer.getCornerRadiusDp() * resources.displayMetrics.density)
            GlassEffectManager.applyParams(d)
            d.invalidateCache()
            d.invalidateSelf()
        }
    }

    fun getColorAtFraction(fraction: Float): Int = NavPillCustomizer.getPillFillColor()

    fun invalidateGlass() {
        glassDrawable?.invalidateCache()
        invalidate()
    }

}
