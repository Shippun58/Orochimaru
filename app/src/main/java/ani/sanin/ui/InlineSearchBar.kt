package ani.sanin.ui
import ani.sanin.settings.AnimUtils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.text.TextWatcher
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import androidx.core.widget.addTextChangedListener
import ani.sanin.R
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.TvKeyboardUtil

class InlineSearchBar(
    val button: ImageButton,
    private val bar: View,
    private val field: EditText,
    private val onQueryChanged: (String) -> Unit,
    private val onQuerySettled: (String) -> Unit = {},
    private val onOpened: ((Int) -> Unit)? = null,
) {

    var isExpanded: Boolean = false
        private set

    private var latestQuery: String = ""

    private val handler = Handler(Looper.getMainLooper())

    private val settle = Runnable { onQuerySettled(latestQuery) }

    private var watcher: TextWatcher? = null

    init {
        LensButtonBackground.apply(button)
        FocusEffectUtil.applyFocusListener(button)
        button.setOnClickListener { toggle() }
        watcher = field.addTextChangedListener { text ->
            latestQuery = text?.toString()?.trim().orEmpty()
            onQueryChanged(latestQuery)
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, SETTLE_MS)
        }
        TvKeyboardUtil.setupTvInput(field)
    }

    fun toggle() {
        if (isExpanded) collapse() else expand()
    }

    fun clearQuery() {
        field.setText("")
    }

    fun release() {
        handler.removeCallbacks(settle)
        watcher?.let { field.removeTextChangedListener(it) }
        watcher = null
    }

    fun expand() {
        if (isExpanded) return
        isExpanded = true
        button.setImageResource(R.drawable.ic_round_close_24)
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        ValueAnimator.ofInt(0, barHeightPx()).apply {
            duration = if (AnimUtils.enabled(ani.sanin.settings.saving.PrefName.SearchHeaderAnimations)) 220 else 0
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bar.layoutParams.height = it.animatedValue as Int
                bar.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bar.alpha = 1f
                    field.requestFocus()
                    TvKeyboardUtil.showKeyboardDelayed(field)
                    onOpened?.invoke(barHeightPx())
                }
            })
        }.start()
    }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        button.setImageResource(R.drawable.ic_round_search_24)
        field.clearFocus()
        field.setText("")
        val startHeight = bar.layoutParams.height
        ValueAnimator.ofInt(startHeight, 0).apply {
            duration = if (AnimUtils.enabled(ani.sanin.settings.saving.PrefName.SearchHeaderAnimations)) 200 else 0
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bar.layoutParams.height = it.animatedValue as Int
                bar.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bar.visibility = View.GONE
                    bar.layoutParams.height = 0
                    onQueryChanged("")
                }
            })
        }.start()
    }

    private fun barHeightPx(): Int =
        (56 * bar.resources.displayMetrics.density).toInt()

    companion object {

        private const val SETTLE_MS = 400L
    }
}
