package ani.sanin.profile.notification

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils

object EpisodeCardGradient {

    fun build(context: Context): GradientDrawable {
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val base = if (isDark) Color.BLACK else Color.WHITE
        val colors = intArrayOf(
            ColorUtils.setAlphaComponent(base, 0),
            ColorUtils.setAlphaComponent(base, 150),
            base
        )
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            colors
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            drawable.setColors(colors, floatArrayOf(0f, 0.42f, 1f))
        }
        return drawable
    }
}
