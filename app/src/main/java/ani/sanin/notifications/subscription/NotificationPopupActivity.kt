package ani.sanin.notifications.subscription
import ani.sanin.settings.AnimUtils

import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import ani.sanin.databinding.PopupNotificationBinding
import ani.sanin.themes.ThemeManager
import kotlin.math.exp
import kotlin.math.cos

class NotificationPopupActivity : AppCompatActivity() {

    private lateinit var binding: PopupNotificationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        binding = PopupNotificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val maxCardWidth = (420 * resources.displayMetrics.density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val margin = ((screenWidth - maxCardWidth) / 2).coerceAtLeast((24 * resources.displayMetrics.density).toInt())
        val params = binding.popupCard.layoutParams as ViewGroup.MarginLayoutParams
        params.leftMargin = margin
        params.rightMargin = margin
        binding.popupCard.layoutParams = params

        val title = intent.getStringExtra("title") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val coverUrl = intent.getStringExtra("coverUrl")

        binding.popupTitle.text = title
        binding.popupText.text = text

        if (coverUrl != null) {
            Glide.with(this)
                .load(coverUrl)
                .apply(RequestOptions().transform(RoundedCorners(16)))
                .into(binding.popupCover)
        }

        startEntryAnimation()

        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 2000)
    }

    private fun startEntryAnimation() {
        if (!AnimUtils.enabled(ani.sanin.settings.saving.PrefName.NotificationPopupAnimations)) {
            binding.popupCard.alpha = 1f
            binding.popupCard.translationY = 0f
            binding.popupCard.scaleY = 1f
            binding.popupCard.visibility = View.VISIBLE
            return
        }
        binding.popupCard.apply {
            pivotY = 0f
            translationY = -resources.displayMetrics.heightPixels.toFloat()
            scaleY = 0.3f
            alpha = 0f
            visibility = View.VISIBLE
        }
        binding.popupCard.post {
            ObjectAnimator.ofFloat(binding.popupCard, View.SCALE_Y, 1f).apply {
                interpolator = SpringInterpolator(damping = 6f, stiffness = 10f)
                duration = 700
            }.start()
            binding.popupCard.animate()
                .translationY(0f)
                .alpha(1f)
                .setInterpolator(DecelerateInterpolator())
                .setDuration(500)
                .start()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private class SpringInterpolator(
        private val damping: Float = 6f,
        private val stiffness: Float = 10f
    ) : android.animation.TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            val decay = exp(-t * damping)
            val oscillation = cos(t * stiffness)
            return 1f - decay * oscillation
        }
    }
}
