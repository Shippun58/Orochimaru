package ani.sanin.others.svg

import android.graphics.drawable.PictureDrawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions

object SvgImageLoader {

    fun load(imageView: ImageView, url: String?, placeholder: Int, error: Int) {
        if (url.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            imageView.setImageResource(error)
            return
        }
        val context = imageView.context
        if (url.contains(".svg", ignoreCase = true)) {
            val requestOptions = RequestOptions()
            context.getDrawable(placeholder)?.let { requestOptions.placeholder(it) }
            context.getDrawable(error)?.let { requestOptions.error(it) }
            val request = Glide.with(imageView).`as`(PictureDrawable::class.java).load(url)
            request.apply(requestOptions)
            request.into(imageView)
        } else {
            Glide.with(imageView).load(url).placeholder(placeholder).error(error).into(imageView)
        }
    }
}
