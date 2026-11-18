package ani.sanin.home

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import kotlin.math.abs

class BannerCrossfadeRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val contentRise = 6f * density
    private val chipsRise = 12f * density

    init {
        itemAnimator = null
        setChildrenDrawingOrderEnabled(true)
    }

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        applyCrossfade()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyCrossfade()
    }

    private fun progressOf(child: View): Float {
        val span = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val center = (paddingLeft + width - paddingRight) * 0.5f
        val childCenter = (child.left + child.right) * 0.5f
        return (1f - abs(childCenter - center) / span).coerceIn(0f, 1f)
    }

    private fun applyCrossfade() {
        val count = childCount
        if (count == 0 || width == 0) return

        var dominant = 0
        var maxProgress = -1f
        for (i in 0 until count) {
            val p = progressOf(getChildAt(i))
            if (p > maxProgress) {
                maxProgress = p
                dominant = i
            }
        }

        for (i in 0 until count) {
            val child = getChildAt(i)
            val p = progressOf(child)
            if (p > 0f) {
                val center = (paddingLeft + width - paddingRight) * 0.5f
                child.translationX = center - (child.left + child.right) * 0.5f
                child.alpha = if (i == dominant) 1f else p
                val scale = 1f + 0.02f * (1f - p)
                child.scaleX = scale
                child.scaleY = scale
            } else {
                child.translationX = 0f
                child.alpha = 1f
                child.scaleX = 1f
                child.scaleY = 1f
            }
            contentOf(child)?.translationY = (1f - p) * contentRise
            chipsOf(child)?.translationY = (1f - p) * chipsRise
        }
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        if (childCount <= 1) return drawingPosition
        for (i in 0 until childCount) {
            val pi = progressOf(getChildAt(i))
            var rank = 0
            for (j in 0 until childCount) {
                if (j == i) continue
                val pj = progressOf(getChildAt(j))
                if (pj > pi || (pj == pi && j < i)) rank++
            }
            if (rank == drawingPosition) return i
        }
        return drawingPosition
    }

    private fun contentOf(child: View): View? {
        (child.getTag(R.id.banner_morph_content) as? View)?.let { return it }
        val view = child.findViewById<View>(R.id.bannerContent)
            ?: child.findViewById(R.id.bannerModernContent)
        child.setTag(R.id.banner_morph_content, view)
        return view
    }

    private fun chipsOf(child: View): View? {
        (child.getTag(R.id.banner_morph_chips) as? View)?.let { return it }
        val view = child.findViewById<View>(R.id.bannerGenresRow)
            ?: child.findViewById(R.id.bannerMetaRow)
        child.setTag(R.id.banner_morph_chips, view)
        return view
    }
}
