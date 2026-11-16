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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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

        val ribbonPath = remember(emblemRes) {
            PathParser().parsePathString(
                "M 30,59 L 12,108 L 9,158 L 14,181 L 27,209 L 52,240 L 96,273 L 130,291 L 404,412 L 440,441 L 466,479 L 484,430 L 487,381 L 482,358 L 471,333 L 440,295 L 412,273 L 365,247 L 97,129 L 55,96 Z"
            ).toPath()
        }
        val lowerTrianglePath = remember(emblemRes) {
            PathParser().parsePathString(
                "M 8,354 L 8,530 L 11,530 L 25,521 L 29,520 L 34,516 L 41,513 L 48,508 L 50,508 L 74,493 L 78,492 L 83,488 L 90,485 L 97,480 L 99,480 L 102,477 L 111,473 L 114,470 L 116,470 L 128,462 L 130,462 L 140,455 L 144,454 L 149,450 L 151,450 L 164,442 L 102,406 L 100,406 L 81,394 L 79,394 L 72,389 L 65,386 L 60,382 L 56,381 L 51,378 L 50,376 L 44,374 L 41,371 L 39,371 L 27,363 L 25,363 L 15,356 Z"
            ).toPath()
        }
        val upperTrianglePath = remember(emblemRes) {
            PathParser().parsePathString(
                "M 332,96 L 352,107 L 357,111 L 366,115 L 371,119 L 373,119 L 385,127 L 387,127 L 402,137 L 406,138 L 423,149 L 432,153 L 437,157 L 439,157 L 446,162 L 453,165 L 458,169 L 462,170 L 468,175 L 476,178 L 487,185 L 488,184 L 488,8 L 485,8 L 461,23 L 459,23 L 452,28 L 441,33 L 426,43 L 415,48 L 412,51 L 401,56 L 391,63 L 389,63 Z"
            ).toPath()
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

            val portrait = emblemRes == R.drawable.sanin_emblem_portrait
            val viewportWidth = if (portrait) 149f else 230f
            val viewportHeight = if (portrait) 226f else 310f
            val groupScale = if (portrait) 0.171264f else 0.290805f
            val groupTranslateX = if (portrait) 32.0264f else 42.8805f
            val groupTranslateY = if (portrait) 66.9299f else 76.7736f

            fun drawShapeGlow(
                path: Path,
                progress: Float,
                strength: Float,
                horizontal: Boolean,
                axisStart: Float,
                axisEnd: Float,
                crossAxis: Float,
                patchHalfLength: Float,
                haloWidth: Float,
                coreWidth: Float
            ) {
                if (strength <= 0f) return
                val center = axisStart + (axisEnd - axisStart) * progress.coerceIn(0f, 1f)
                val from = if (horizontal) {
                    Offset(center - patchHalfLength, crossAxis)
                } else {
                    Offset(crossAxis, center - patchHalfLength)
                }
                val to = if (horizontal) {
                    Offset(center + patchHalfLength, crossAxis)
                } else {
                    Offset(crossAxis, center + patchHalfLength)
                }
                val glow = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.32f to Color.White.copy(alpha = 0.22f * strength),
                        0.50f to Color.White.copy(alpha = 0.78f * strength),
                        0.68f to Color.White.copy(alpha = 0.22f * strength),
                        1f to Color.Transparent
                    ),
                    start = from,
                    end = to
                )
                val core = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.38f to Color.White.copy(alpha = 0.88f * strength),
                        0.50f to Color.White.copy(alpha = strength),
                        0.62f to Color.White.copy(alpha = 0.88f * strength),
                        1f to Color.Transparent
                    ),
                    start = from,
                    end = to
                )

                // Transform the original vector path into the exact rendered
                // logo position. The glow is stroked along that path itself,
                // never approximated with a free-standing diagonal line.
                withTransform({
                    translate(left, top)
                    scale(imageWidth / viewportWidth, imageHeight / viewportHeight)
                    translate(groupTranslateX, groupTranslateY)
                    scale(groupScale, groupScale)
                }) {
                    drawPath(path, glow, style = Stroke(width = haloWidth))
                    drawPath(path, core, style = Stroke(width = coreWidth))
                }
            }


            val travel = smooth((p - 0.08f) / 0.83f)
            val fadeIn = smooth((p - 0.08f) / 0.10f)
            val fadeOut = 1f - smooth((p - 0.81f) / 0.10f)
            val streakStrength = fadeIn * fadeOut

            // Each highlight follows one of the three exact vector paths.
            // The ribbon travels left-to-right; the upper triangle travels
            // top-to-bottom; the lower triangle travels bottom-to-top.
            if (streakStrength > 0f) {
                drawShapeGlow(
                    path = ribbonPath,
                    progress = travel,
                    strength = streakStrength,
                    horizontal = true,
                    axisStart = 8f,
                    axisEnd = 488f,
                    crossAxis = 245f,
                    patchHalfLength = 104f,
                    haloWidth = 46f,
                    coreWidth = 23f
                )
                drawShapeGlow(
                    path = upperTrianglePath,
                    progress = travel,
                    strength = streakStrength * 0.96f,
                    horizontal = false,
                    axisStart = 8f,
                    axisEnd = 185f,
                    crossAxis = 410f,
                    patchHalfLength = 43f,
                    haloWidth = 38f,
                    coreWidth = 19f
                )
                drawShapeGlow(
                    path = lowerTrianglePath,
                    progress = 1f - travel,
                    strength = streakStrength * 1.02f,
                    horizontal = false,
                    axisStart = 354f,
                    axisEnd = 530f,
                    crossAxis = 86f,
                    patchHalfLength = 43f,
                    haloWidth = 38f,
                    coreWidth = 19f
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
