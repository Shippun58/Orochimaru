package ani.sanin.others

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.PictureDrawable
import ani.sanin.okHttpClient
import ani.sanin.others.svg.SvgDecoder
import ani.sanin.others.svg.SvgDrawableTranscoder
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG
import java.io.InputStream

@GlideModule
class SaninGlideApp : AppGlideModule() {
    @SuppressLint("CheckResult")
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        super.applyOptions(context, builder)
        val diskCacheSizeBytes = 1024 * 1024 * 100

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(mi)
        val totalMemMb = (mi.totalMem) / (1024 * 1024)
        val memoryCacheSizeBytes = when {
            totalMemMb >= 4096 -> 1024L * 1024 * 50
            totalMemMb >= 3072 -> 1024L * 1024 * 35
            else               -> 1024L * 1024 * 20
        }
        builder.apply {
            setDiskCache(InternalCacheDiskCacheFactory(context, "img", diskCacheSizeBytes.toLong()))
            setMemoryCache(LruResourceCache(memoryCacheSizeBytes))
        }
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(okHttpClient)
        )
        registry.append(
            InputStream::class.java,
            SVG::class.java,
            SvgDecoder()
        )
        registry.register(
            SVG::class.java,
            PictureDrawable::class.java,
            SvgDrawableTranscoder()
        )
        super.registerComponents(context, glide, registry)
    }
}
