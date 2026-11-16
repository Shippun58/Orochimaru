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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
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

            fun drawMovingStreak(
                start: Offset,
                end: Offset,
                progress: Float,
                strength: Float,
                lengthFactor: Float,
                widthFactor: Float
            ) {
                val center = Offset(
                    left + (start.x + (end.x - start.x) * progress) * imageWidth,
                    top + (start.y + (end.y - start.y) * progress) * imageHeight
                )
                // Compute the tangent in rendered pixels so portrait and
                // landscape layouts keep the streak aligned with the same path.
                val direction = Offset(
                    (end.x - start.x) * imageWidth,
                    (end.y - start.y) * imageHeight
                )
                val magnitude = kotlin.math.sqrt(
                    direction.x * direction.x + direction.y * direction.y
                ).coerceAtLeast(0.0001f)
                val tangent = Offset(direction.x / magnitude, direction.y / magnitude)
                val halfLength = min(imageWidth, imageHeight) * lengthFactor
                val from = Offset(
                    center.x - tangent.x * halfLength,
                    center.y - tangent.y * halfLength
                )
                val to = Offset(
                    center.x + tangent.x * halfLength,
                    center.y + tangent.y * halfLength
                )
                val haloBrush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.40f to Color.White.copy(alpha = 0.24f * strength),
                        0.60f to Color.White.copy(alpha = 0.18f * strength),
                        1f to Color.Transparent
                    ),
                    start = from,
                    end = to
                )
                val coreBrush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.42f to Color.White.copy(alpha = 0.92f * strength),
                        0.58f to Color.White.copy(alpha = 0.58f * strength),
                        1f to Color.Transparent
                    ),
                    start = from,
                    end = to
                )
                val shortSide = min(imageWidth, imageHeight)

                // Broad, low-intensity light sits behind the black silhouette;
                // the tighter streak supplies the travelling highlight.
                drawLine(
                    brush = haloBrush,
                    start = from,
                    end = to,
                    strokeWidth = shortSide * widthFactor * 2.1f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    brush = coreBrush,
                    start = from,
                    end = to,
                    strokeWidth = shortSide * widthFactor * 0.72f,
                    cap = StrokeCap.Round
                )
            }

            val travel = smooth((p - 0.08f) / 0.83f)
            val fadeIn = smooth((p - 0.08f) / 0.10f)
            val fadeOut = 1f - smooth((p - 0.81f) / 0.10f)
            val streakStrength = fadeIn * fadeOut

            // Draw light first, then place the complete black emblem over it.
            // This restores the Infinix-style backlight: the soft spill is visible
            // around the silhouette while the icon itself stays completely black.
            if (streakStrength > 0f) {
                // Main ribbon: two staggered passes along its long diagonal.
                drawMovingStreak(
                    Offset(0.17f, 0.29f),
                    Offset(0.83f, 0.56f),
                    travel,
                    streakStrength,
                    lengthFactor = 0.115f,
                    widthFactor = 0.075f
                )
                drawMovingStreak(
                    Offset(0.25f, 0.37f),
                    Offset(0.90f, 0.63f),
                    (travel - 0.13f).coerceIn(0f, 1f),
                    streakStrength * 0.82f,
                    lengthFactor = 0.095f,
                    widthFactor = 0.060f
                )
                // Upper triangle: from the outer top-right tip down-left.
                drawMovingStreak(
                    Offset(0.80f, 0.26f),
                    Offset(0.61f, 0.42f),
                    travel,
                    streakStrength * 0.95f,
                    lengthFactor = 0.075f,
                    widthFactor = 0.075f
                )
                // Lower triangle: corrected to stay on its actual lower-left arm,
                // travelling from the bottom tip up-right toward the ribbon.
                drawMovingStreak(
                    Offset(0.20f, 0.74f),
                    Offset(0.39f, 0.58f),
                    travel,
                    streakStrength * 1.05f,
                    lengthFactor = 0.080f,
                    widthFactor = 0.085f
                )
            }

            // The opaque black silhouette sits above the light, preserving the
            // dark icon while letting the moving glow show from behind its edges.
            drawImage(blackMask, dstOffset = dstOffset, dstSize = dstSize)

            // As the streaks reach the ends of their paths, their energy is
            // absorbed into the silhouette before the final white reveal.
            val absorbedPower = smooth((p - 0.58f) / 0.34f) * 0.88f
            if (absorbedPower > 0f) {
                drawImage(
                    image = blackMask,
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        Color.White,
                        BlendMode.SrcIn
                    ),
                    alpha = absorbedPower
                )
            }

            val reveal = smooth((p - 0.84f) / 0.16f)
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
