package ani.sanin.ui.splash

import ani.sanin.R
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay

internal data class SaninSplashGeometry(
    val wordmarkOffsetY: Dp,
    val emblemOffsetY: Dp,
    val emblemWidth: Dp,
    val emblemHeight: Dp
)

internal sealed interface SaninSplashConfig {
    val backgroundRes: Int

    val wordmarkRes: Int?
    val emblemRes: Int
    val particleCount: Int
}

internal data class FixedSaninSplashConfig(
    override val backgroundRes: Int,
    override val wordmarkRes: Int?,
    override val emblemRes: Int,
    val geometry: SaninSplashGeometry,
    override val particleCount: Int = 220
) : SaninSplashConfig

internal data class CanvasSaninSplashConfig(
    override val backgroundRes: Int,
    override val wordmarkRes: Int?,
    override val emblemRes: Int,
    val wordmarkCenterY: Float,
    val emblemCenterY: Float,
    override val particleCount: Int = 220
) : SaninSplashConfig

private val LandscapeConfig = CanvasSaninSplashConfig(
    backgroundRes = R.drawable.sanin_splash_background,
    wordmarkRes = null,
    emblemRes = R.drawable.sanin_emblem,

    wordmarkCenterY = 0.36f,
    emblemCenterY = 0.5f
)

private val PortraitConfig = CanvasSaninSplashConfig(
    backgroundRes = R.drawable.sanin_splash_background_portrait,
    wordmarkRes = null,
    emblemRes = R.drawable.sanin_emblem_portrait,
    wordmarkCenterY = 0.42f,
    emblemCenterY = 0.5f
)

@Composable
fun SaninLandscapeSplash(
    onFinished: () -> Unit
) {
    SaninSplash(
        config = LandscapeConfig,
        onFinished = onFinished
    )
}

@Composable
fun SaninPortraitSplash(
    onFinished: () -> Unit
) {
    SaninSplash(
        config = PortraitConfig,
        onFinished = onFinished
    )
}

@Composable
internal fun SaninSplash(
    config: SaninSplashConfig,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val emblemBitmap = remember {
        context.resources.getDrawable(config.emblemRes, context.theme)?.toBitmap()
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    LaunchedEffect(Unit) {
        delay(500)
        onFinished()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        val geometry = when (config) {
            is FixedSaninSplashConfig -> config.geometry
            is CanvasSaninSplashConfig -> with(density) {
                SaninSplashGeometry(
                    wordmarkOffsetY = maxHeight * (config.wordmarkCenterY - 0.5f),
                    emblemOffsetY = maxHeight * (config.emblemCenterY - 0.5f),
                    emblemWidth = Dp(emblemBitmap.width / density.density),
                    emblemHeight = Dp(emblemBitmap.height / density.density)
                )
            }
        }

        Image(
            painter = painterResource(
                config.backgroundRes
            ),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        Image(
            painter = painterResource(
                config.emblemRes
            ),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = geometry.emblemOffsetY),
            contentScale = ContentScale.None
        )
    }
}

private fun Drawable.toBitmap(): Bitmap {
    val w = intrinsicWidth.takeIf { it > 0 } ?: 1
    val h = intrinsicHeight.takeIf { it > 0 } ?: 1
    if (this is BitmapDrawable) {
        bitmap?.let { return it }
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bmp
}
