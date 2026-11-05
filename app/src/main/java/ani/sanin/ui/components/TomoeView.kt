package ani.sanin.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Animatable
import android.util.AttributeSet
import android.view.View
import ani.sanin.R
import ani.sanin.getThemeColor
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath

class TomoeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LottieAnimationView(context, attrs) {

    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val monochrome = PrefManager.getVal<String>(PrefName.Theme).contains("MONOCHROME", ignoreCase = true)
    private val railColor = if (monochrome) Color.BLACK
    else context.getThemeColor(com.google.android.material.R.attr.colorPrimary)

    
    
    private val useLottie = PrefManager.getVal<Boolean>(PrefName.SaninCustomSpinner)

    init {
        if (!useLottie) {
            setImageResource(R.drawable.spinner_indeterminate)
            imageTintList = ColorStateList.valueOf(railColor)
        }
        if (composition != null) applyFilters()
        addLottieOnCompositionLoadedListener { if (useLottie) applyFilters() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE) resumeAnimation()
    }

    override fun onDetachedFromWindow() {
        pauseAnimation()
        super.onDetachedFromWindow()
    }

    
    
    
    
    override fun onVisibilityAggregated(isAggregatedVisible: Boolean) {
        super.onVisibilityAggregated(isAggregatedVisible)
        if (isAggregatedVisible) resumeAnimation() else pauseAnimation()
    }

    
    
    
    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = drawable
        if (!useLottie || d == null) {
            if (d != null) {
                val iw = d.intrinsicWidth
                val ih = d.intrinsicHeight
                if (iw <= 0 || ih <= 0 || width <= 0 || height <= 0) {
                    d.draw(canvas)
                } else {
                    
                    
                    val s = minOf(width / iw.toFloat(), height / ih.toFloat(), 2.4f)
                    canvas.save()
                    canvas.translate((width - iw * s) / 2f, (height - ih * s) / 2f)
                    canvas.scale(s, s)
                    d.draw(canvas)
                    canvas.restore()
                }
            }
            return
        }
        super.onDraw(canvas)
    }

    
    
    override fun playAnimation() {
        if (!useLottie) { startAnimatableDrawable(); return }
        super.playAnimation()
    }

    override fun resumeAnimation() {
        if (!useLottie) { startAnimatableDrawable(); return }
        super.resumeAnimation()
    }

    override fun pauseAnimation() {
        if (!useLottie) { stopAnimatableDrawable(); return }
        super.pauseAnimation()
    }

    private fun startAnimatableDrawable() {
        (drawable as? Animatable)?.takeIf { !it.isRunning }?.start()
    }

    private fun stopAnimatableDrawable() {
        (drawable as? Animatable)?.takeIf { it.isRunning }?.stop()
    }

    private fun applyFilters() {
        val dots = PorterDuffColorFilter(if (dark) Color.WHITE else Color.BLACK, PorterDuff.Mode.SRC_IN)
        val rail = PorterDuffColorFilter(railColor, PorterDuff.Mode.SRC_IN)
        val outline = PorterDuffColorFilter(if (monochrome) Color.BLACK else railColor, PorterDuff.Mode.SRC_IN)
        val railColorOnMonochrome = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        for (nm in arrayOf("Ellipse 281", "Ellipse 280", "Ellipse 279", "Logo")) {
            addValueCallback(KeyPath(nm, "**", "**"), LottieProperty.COLOR_FILTER) { dots }
            addValueCallback(KeyPath(nm, nm, "Contour 1"), LottieProperty.COLOR_FILTER) { dots }
        }
        addValueCallback(KeyPath("Cercle complet", "**", "**"), LottieProperty.COLOR_FILTER) { if (monochrome) railColorOnMonochrome else rail }
        addValueCallback(KeyPath("Cercle complet", "Ellipse 279", "Contour en dégradé 1"), LottieProperty.COLOR_FILTER) { if (monochrome) railColorOnMonochrome else rail }
        addValueCallback(KeyPath("Rail outline", "**", "**"), LottieProperty.COLOR_FILTER) { outline }
    }
}
