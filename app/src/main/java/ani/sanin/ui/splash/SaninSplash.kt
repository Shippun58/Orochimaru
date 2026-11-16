package ani.sanin.ui.splash

import ani.sanin.R
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.StrokeCap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt

private data class SplashArtwork(
    val bitmap: Bitmap,
    val outline: List<Offset>
)

private fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable) bitmap?.let { return it }
    val width = intrinsicWidth.coerceAtLeast(1)
    val height = intrinsicHeight.coerceAtLeast(1)
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        val canvas = android.graphics.Canvas(it)
        setBounds(0, 0, width, height)
        draw(canvas)
    }
}

private fun createArtwork(bitmap: Bitmap): SplashArtwork {
    val points = ArrayList<Offset>()
    var centerX = 0f
    var centerY = 0f

    fun isLogo(x: Int, y: Int): Boolean {
        if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return false
        val pixel = bitmap.getPixel(x, y)
        return android.graphics.Color.red(pixel) > 150 &&
            android.graphics.Color.green(pixel) > 150 &&
            android.graphics.Color.blue(pixel) > 150
    }

    for (y in 1 until bitmap.height - 1) {
        for (x in 1 until bitmap.width - 1) {
            if (!isLogo(x, y)) continue
            if (!isLogo(x - 1, y) || !isLogo(x + 1, y) ||
                !isLogo(x, y - 1) || !isLogo(x, y + 1)
            ) {
                val px = (x + 0.5f) / bitmap.width
                val py = (y + 0.5f) / bitmap.height
                points.add(Offset(px, py))
                centerX += px
                centerY += py
            }
        }
    }

    if (points.isNotEmpty()) {
        centerX /= points.size
        centerY /= points.size
        points.sortBy { atan2(it.y - centerY, it.x - centerX) }
    }
    return SplashArtwork(bitmap, points)
}

@Composable
fun SaninLandscapeSplash(onFinished: () -> Unit) {
    SaninSplash(emblemRes = R.drawable.sanin_emblem, onFinished = onFinished)
}

@Composable
fun SaninPortraitSplash(onFinished: () -> Unit) {
    SaninSplash(emblemRes = R.drawable.sanin_emblem_portrait, onFinished = onFinished)
}

@Composable
internal fun SaninSplash(
    emblemRes: Int,
    onFinished: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val artwork = remember(emblemRes) {
        val bitmap = context.resources.getDrawable(emblemRes, context.theme).toBitmap()
        createArtwork(bitmap)
    }
    val animation = remember { Animatable(0f) }

    LaunchedEffect(onFinished) {
        animation.animateTo(1f, animationSpec = tween(durationMillis = 2_050))
        delay(300)
        onFinished()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val bitmap = artwork.bitmap.asImageBitmap()
            val fit = min(1f, min(size.width / artwork.bitmap.width, size.height / artwork.bitmap.height))
            val imageWidth = artwork.bitmap.width * fit
            val imageHeight = artwork.bitmap.height * fit
            val left = (size.width - imageWidth) / 2f
            val top = (size.height - imageHeight) / 2f
            val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
            val dstSize = IntSize(imageWidth.roundToInt(), imageHeight.roundToInt())

            val progress = animation.value
            val traceProgress = (progress / 0.68f).coerceIn(0f, 1f)
            val visibleCount = (artwork.outline.size * traceProgress).toInt()
            val visible = artwork.outline.take(visibleCount).map {
                Offset(left + it.x * imageWidth, top + it.y * imageHeight)
            }

            if (visible.isNotEmpty() && progress < 0.94f) {
                val swell = ((progress - 0.48f) / 0.36f).coerceIn(0f, 1f)
                drawPoints(
                    visible,
                    PointMode.Points,
                    Color.White.copy(alpha = 0.10f + 0.20f * swell),
                    strokeWidth = 18f + 20f * swell,
                    cap = StrokeCap.Round
                )
                drawPoints(
                    visible,
                    PointMode.Points,
                    Color.White.copy(alpha = 0.45f + 0.45f * swell),
                    strokeWidth = 7f + 8f * swell,
                    cap = StrokeCap.Round
                )
                drawPoints(
                    visible,
                    PointMode.Points,
                    Color.White,
                    strokeWidth = 2.8f,
                    cap = StrokeCap.Round
                )
            }

            val fillAlpha = ((progress - 0.76f) / 0.20f).coerceIn(0f, 1f)
            if (fillAlpha > 0f) {
                drawImage(
                    image = bitmap,
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    alpha = fillAlpha
                )
            }
        }
    }
}
