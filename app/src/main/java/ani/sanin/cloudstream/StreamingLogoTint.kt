package ani.sanin.cloudstream

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

data class StreamingTint(
    val start: Int,
    val end: Int,
    val vertical: Boolean,
    val contentScale: Float
) {
    val isFlat: Boolean get() = start == end
}

object StreamingLogoTint {
    private val cache = HashMap<String, StreamingTint?>()
    private val inFlight = HashSet<String>()

    fun cached(url: String): StreamingTint? = synchronized(cache) { cache[url] }

    fun isKnown(url: String): Boolean = synchronized(cache) { cache.containsKey(url) }

    fun beginLoad(url: String): Boolean = synchronized(cache) {
        if (inFlight.contains(url) || cache.containsKey(url)) return false
        inFlight.add(url)
        true
    }

    fun put(url: String, tint: StreamingTint?) = synchronized(cache) {
        cache[url] = tint
        inFlight.remove(url)
    }

    fun failLoad(url: String) = synchronized(cache) { inFlight.remove(url) }

    private const val INSET = 6

    private const val TARGET_FILL = 0.80f

    private const val MAX_SCALE = 1.35f

    fun sample(bmp: Bitmap): StreamingTint? {
        val w = bmp.width
        val h = bmp.height
        if (w <= INSET * 2 || h <= INSET * 2) return null
        fun at(x: Int, y: Int): Int? {
            if (x < 0 || y < 0 || x >= w || y >= h) return null
            val c = bmp.getPixel(x, y)

            if (Color.alpha(c) < 250) return null
            return c
        }
        val left = at(INSET, h / 2) ?: return null
        val right = at(w - 1 - INSET, h / 2) ?: return null
        val top = at(w / 2, INSET) ?: return null
        val bottom = at(w / 2, h - 1 - INSET) ?: return null

        val vertical = delta(top, bottom) > delta(left, right)
        val scale = contentScale(bmp, left)
        return if (vertical) {
            StreamingTint(top, bottom, vertical = true, contentScale = scale)
        } else {
            StreamingTint(left, right, vertical = false, contentScale = scale)
        }
    }

    private fun contentScale(bmp: Bitmap, background: Int): Float {
        val w = bmp.width
        val h = bmp.height
        val br = Color.red(background)
        val bg = Color.green(background)
        val bb = Color.blue(background)
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                if (Color.alpha(c) >= 250) {
                    val d = abs(Color.red(c) - br) + abs(Color.green(c) - bg) + abs(Color.blue(c) - bb)
                    if (d > 60) {
                        if (x < x0) x0 = x
                        if (x > x1) x1 = x
                        if (y < y0) y0 = y
                        if (y > y1) y1 = y
                    }
                }
                x += 2
            }
            y += 2
        }
        if (x1 <= x0 || y1 <= y0) return 1f
        val side = if (w > h) w else h
        val mark = if ((x1 - x0) > (y1 - y0)) (x1 - x0) else (y1 - y0)
        val fill = mark.toFloat() / side
        if (fill <= 0f) return 1f
        return (TARGET_FILL / fill).coerceIn(1f, MAX_SCALE)
    }

    private fun delta(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))
}
