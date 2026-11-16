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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
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
        animation.animateTo(1f, animationSpec = tween(durationMillis = 3_900))
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
            ) * 0.78f
            val imageWidth = artwork.first.width * fit
            val imageHeight = artwork.first.height * fit
            val left = (size.width - imageWidth) / 2f
            val top = (size.height - imageHeight) / 2f
            val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
            val dstSize = IntSize(imageWidth.roundToInt(), imageHeight.roundToInt())
            val p = animation.value

            fun smooth(t: Float): Float {
                val value = t.coerceIn(0f, 1f)
                return value * value * (3f - 2f * value)
            }

            fun drawMovingBloom(
                start: Offset,
                end: Offset,
                angle: Float,
                alpha: Float,
                lengthFactor: Float,
                widthFactor: Float
            ) {
                // All four light paths share one timing window and finish together.
                val from = 0.08f
                val until = 0.91f
                val progress = smooth((p - from) / (until - from))
                val center = Offset(
                    left + (start.x + (end.x - start.x) * progress) * imageWidth,
                    top + (start.y + (end.y - start.y) * progress) * imageHeight
                )
                val shortSide = min(imageWidth, imageHeight)
                val length = shortSide * lengthFactor
                val width = shortSide * widthFactor
                val fadeIn = smooth((p - from) / 0.10f)
                val fadeOut = 1f - smooth((p - (until - 0.10f)) / 0.10f)
                val strength = alpha * fadeIn * fadeOut
                if (strength <= 0f) return

                rotate(degrees = angle, pivot = center) {
                    drawOval(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.78f * strength),
                                Color.White.copy(alpha = 0.38f * strength),
                                Color.White.copy(alpha = 0.10f * strength),
                                Color.Transparent
                            ),
                            center = center,
                            radius = length * 0.72f
                        ),
                        topLeft = Offset(center.x - length / 2f, center.y - width / 2f),
                        size = Size(length, width)
                    )
                }
            }

            // Ribbon: its two soft blooms travel along the diagonal.
            drawMovingBloom(
                start = Offset(0.24f, 0.39f),
                end = Offset(0.76f, 0.61f),
                angle = -26f,
                alpha = 1f,
                lengthFactor = 0.25f,
                widthFactor = 0.13f
            )
            drawMovingBloom(
                start = Offset(0.34f, 0.43f),
                end = Offset(0.84f, 0.65f),
                angle = -26f,
                alpha = 0.82f,
                lengthFactor = 0.21f,
                widthFactor = 0.11f
            )

            // Upper triangle: light descends from its tip toward the ribbon.
            drawMovingBloom(
                start = Offset(0.78f, 0.27f),
                end = Offset(0.67f, 0.43f),
                angle = 64f,
                alpha = 0.92f,
                lengthFactor = 0.19f,
                widthFactor = 0.10f
            )

            // Lower triangle: light rises from its outer tip toward the ribbon.
            drawMovingBloom(
                start = Offset(0.22f, 0.74f),
                end = Offset(0.36f, 0.59f),
                angle = -48f,
                alpha = 0.92f,
                lengthFactor = 0.19f,
                widthFactor = 0.10f
            )

            // The emblem stays black while the moving light passes underneath.
            if (p < 0.91f) {
                drawImage(blackMask, dstOffset = dstOffset, dstSize = dstSize)
            }

            // The passing energy is absorbed: the silhouette steadily gains
            // white intensity before resolving into the final solid-white mark.
            val absorbedPower = smooth((p - 0.54f) / 0.39f) * 0.88f
            if (absorbedPower > 0f) {
                drawImage(
                    image = blackMask,
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn),
                    alpha = absorbedPower
                )
            }

            val reveal = smooth((p - 0.82f) / 0.18f)
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
