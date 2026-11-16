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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
        animation.animateTo(1f, animationSpec = tween(durationMillis = 3_650))
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
            ) * if (emblemRes == R.drawable.sanin_emblem_portrait) 0.78f else 1f
            val imageWidth = artwork.first.width * fit
            val imageHeight = artwork.first.height * fit
            val left = (size.width - imageWidth) / 2f
            val top = (size.height - imageHeight) / 2f
            val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
            val dstSize = IntSize(imageWidth.roundToInt(), imageHeight.roundToInt())
            val p = animation.value

            // Soft, localized light blooms travel beneath the emblem. They are
            // not drawn as strokes and never trace the logo's perimeter.
            fun smooth(t: Float): Float {
                val value = t.coerceIn(0f, 1f)
                return value * value * (3f - 2f * value)
            }

            fun drawMovingBloom(
                start: Offset,
                end: Offset,
                from: Float,
                until: Float,
                alpha: Float,
                lengthFactor: Float,
                widthFactor: Float
            ) {
                val progress = smooth((p - from) / (until - from))
                val center = Offset(
                    left + (start.x + (end.x - start.x) * progress) * imageWidth,
                    top + (start.y + (end.y - start.y) * progress) * imageHeight
                )
                val length = min(imageWidth, imageHeight) * lengthFactor
                val width = min(imageWidth, imageHeight) * widthFactor
                val fadeIn = smooth((p - from) / 0.12f)
                val fadeOut = 1f - smooth((p - (until - 0.13f)) / 0.13f)
                val strength = alpha * fadeIn * fadeOut
                if (strength <= 0f) return

                rotate(
                    degrees = -26f,
                    pivot = center
                ) {
                    drawOval(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.72f * strength),
                                Color.White.copy(alpha = 0.30f * strength),
                                Color.White.copy(alpha = 0.08f * strength),
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

            // Two separate travelling blooms, staggered along the emblem's
            // main diagonal. Their broad falloff creates a moving backlight,
            // rather than a bright outline or a drawing/reveal effect.
            drawMovingBloom(
                start = Offset(0.24f, 0.39f),
                end = Offset(0.76f, 0.61f),
                from = 0.06f,
                until = 0.76f,
                alpha = 1f,
                lengthFactor = 0.25f,
                widthFactor = 0.13f
            )
            drawMovingBloom(
                start = Offset(0.34f, 0.43f),
                end = Offset(0.84f, 0.65f),
                from = 0.24f,
                until = 0.88f,
                alpha = 0.82f,
                lengthFactor = 0.21f,
                widthFactor = 0.11f
            )

            // Keep the complete emblem solid black while the light passes
            // behind it; only the soft bloom around its edges is visible.
            if (p < 0.86f) {
                drawImage(blackMask, dstOffset = dstOffset, dstSize = dstSize)
            }

            // The backlight is absorbed and the exact original white emblem
            // resolves in one clean transition.
            val reveal = smooth((p - 0.78f) / 0.20f)
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
