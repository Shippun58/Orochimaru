package ani.sanin.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import ani.sanin.BANNER_TYPE_CLASSIC
import ani.sanin.BANNER_TYPE_COMPACT
import ani.sanin.BANNER_TYPE_MODERN
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.request.RequestOptions
import jp.wasabeef.glide.transformations.internal.FastBlur
import java.security.MessageDigest

class StackBlurTransformation(
    private val radius: Int,
    private val sampling: Int = 2,
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        val divisor = sampling.coerceAtLeast(1)
        val scaled = if (divisor == 1) {
            toTransform
        } else {
            Bitmap.createScaledBitmap(
                toTransform,
                (toTransform.width / divisor).coerceAtLeast(1),
                (toTransform.height / divisor).coerceAtLeast(1),
                true,
            )
        }
        return FastBlur.blur(scaled, radius.coerceIn(1, 25), true)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {

        messageDigest.update("$ID$radius$sampling".toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val ID = "ani.sanin.util.StackBlurTransformation."
    }
}

const val BANNER_BLUR_SAMPLING = 3

const val BANNER_MAX_WIDTH = 720

const val CLASSIC_BLUR_BAND = 0.45f

class BottomBlurTransformation(
    private val radius: Int,
    private val fraction: Float = CLASSIC_BLUR_BAND,
    private val sampling: Int = BANNER_BLUR_SAMPLING,
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        val width = toTransform.width
        val height = toTransform.height
        val bandHeight = (height * fraction.coerceIn(0.05f, 1f)).toInt().coerceIn(1, height)
        val bandTop = height - bandHeight

        val band = Bitmap.createBitmap(toTransform, 0, bandTop, width, bandHeight)
        val divisor = sampling.coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(
            band,
            (width / divisor).coerceAtLeast(1),
            (bandHeight / divisor).coerceAtLeast(1),
            true,
        )
        val blurred = FastBlur.blur(small, radius.coerceIn(1, 25), true)
        val bandBlurred = Bitmap.createScaledBitmap(blurred, width, bandHeight, true)

        val canvas = Canvas(toTransform)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val feather = (bandHeight * FEATHER_FRACTION).toInt().coerceAtLeast(1)
        val strips = FEATHER_STRIPS
        val stripHeight = (feather / strips).coerceAtLeast(1)
        for (i in 0 until strips) {
            val from = i * stripHeight
            val to = if (i == strips - 1) feather else (i + 1) * stripHeight
            paint.alpha = (255f * (to.toFloat() / feather)).toInt().coerceIn(0, 255)
            canvas.drawBitmap(
                bandBlurred,
                Rect(0, from, width, to),
                RectF(0f, (bandTop + from).toFloat(), width.toFloat(), (bandTop + to).toFloat()),
                paint,
            )
        }
        paint.alpha = 255
        val solidFrom = feather.coerceAtMost(bandHeight)
        canvas.drawBitmap(
            bandBlurred,
            Rect(0, solidFrom, width, bandHeight),
            RectF(0f, (bandTop + solidFrom).toFloat(), width.toFloat(), height.toFloat()),
            paint,
        )
        return toTransform
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID$radius$fraction$sampling".toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val ID = "ani.sanin.util.BottomBlurTransformation."
        const val FEATHER_STRIPS = 8
        const val FEATHER_FRACTION = 0.45f
    }
}

fun bannerLoadOptions(bannerType: Int): RequestOptions {
    val options = RequestOptions().override(BANNER_MAX_WIDTH)
    if (!PrefManager.getVal<Boolean>(PrefName.BlurBanners)) return options
    val radius = PrefManager.getVal<Float>(PrefName.BlurStrength).toInt()
    if (radius <= 0) return options
    val r = radius.coerceIn(1, 16)
    return when (bannerType) {
        BANNER_TYPE_CLASSIC -> options.transform(BottomBlurTransformation(r))
        BANNER_TYPE_MODERN -> options.transform(StackBlurTransformation(r, BANNER_BLUR_SAMPLING))
        else -> options
    }
}

fun currentBannerType(modern: Boolean, large: Boolean): Int = when {
    modern -> BANNER_TYPE_MODERN
    large -> BANNER_TYPE_CLASSIC
    else -> BANNER_TYPE_COMPACT
}

const val EPISODE_BLUR_RADIUS = 5

const val EPISODE_BLUR_SAMPLING = 3
