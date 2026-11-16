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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

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

@Composable
fun SaninLandscapeSplash(onFinished: () -> Unit) {
    SaninSplash(
        emblemRes = R.drawable.sanin_emblem,
        maskRes = R.drawable.sanin_emblem_black,
        onFinished = onFinished
    )
}

@Composable
fun SaninPortraitSplash(onFinished: () -> Unit) {
    SaninSplash(
        emblemRes = R.drawable.sanin_emblem_portrait,
        maskRes = R.drawable.sanin_emblem_portrait_black,
        onFinished = onFinished
    )
}

@Composable
internal fun SaninSplash(
    emblemRes: Int,
    maskRes: Int,
    onFinished: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val artwork = remember(emblemRes, maskRes) {
        val finalLogo = context.resources.getDrawable(emblemRes, context.theme).toBitmap()
        val blackMask = context.resources.getDrawable(maskRes, context.theme).toBitmap()
        finalLogo to blackMask
    }
    val animation = remember { Animatable(0f) }

    LaunchedEffect(onFinished) {
        delay(120)
        animation.animateTo(1f, animationSpec = tween(durationMillis = 2_150))
        delay(180)
        onFinished()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val finalLogo = artwork.first.asImageBitmap()
            val blackMask = artwork.second.asImageBitmap()
            val fit = min(
                size.width / artwork.first.width,
                size.height / artwork.first.height
            )
            val imageWidth = artwork.first.width * fit
            val imageHeight = artwork.first.height * fit
            val left = (size.width - imageWidth) / 2f
            val top = (size.height - imageHeight) / 2f
            val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
            val dstSize = IntSize(imageWidth.roundToInt(), imageHeight.roundToInt())
            val p = animation.value

            // Two separate light sources travel along the emblem on staggered timings.
            fun travel(start: Offset, end: Offset, from: Float, to: Float): Offset {
                val t = ((p - from) / (to - from)).coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)
                return Offset(
                    left + (start.x + (end.x - start.x) * eased) * imageWidth,
                    top + (start.y + (end.y - start.y) * eased) * imageHeight
                )
            }

            val first = travel(Offset(0.30f, 0.30f), Offset(0.70f, 0.70f), 0.10f, 0.67f)
            val second = travel(Offset(0.70f, 0.30f), Offset(0.30f, 0.70f), 0.24f, 0.79f)
            val firstAlpha = (p / 0.17f).coerceIn(0f, 1f) * (1f - ((p - 0.79f) / 0.15f).coerceIn(0f, 1f))
            val secondAlpha = ((p - 0.12f) / 0.16f).coerceIn(0f, 1f) * (1f - ((p - 0.82f) / 0.13f).coerceIn(0f, 1f))
            val radius = min(imageWidth, imageHeight) * 0.17f

            if (firstAlpha > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.92f * firstAlpha),
                            Color.White.copy(alpha = 0.38f * firstAlpha),
                            Color.White.copy(alpha = 0f)
                        ),
                        center = first,
                        radius = radius
                    ),
                    radius = radius,
                    center = first
                )
            }
            if (secondAlpha > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.92f * secondAlpha),
                            Color.White.copy(alpha = 0.38f * secondAlpha),
                            Color.White.copy(alpha = 0f)
                        ),
                        center = second,
                        radius = radius
                    ),
                    radius = radius,
                    center = second
                )
            }

            // The transparent black silhouette masks the glow, keeping the emblem itself dark.
            if (p < 0.88f) {
                drawImage(blackMask, dstOffset = dstOffset, dstSize = dstSize)
            }

            // The accumulated light is absorbed into the exact original white artwork.
            val reveal = ((p - 0.70f) / 0.25f).coerceIn(0f, 1f)
            if (reveal > 0f) {
                drawImage(
                    image = finalLogo,
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    alpha = reveal
                )
            }
        }
    }
}
