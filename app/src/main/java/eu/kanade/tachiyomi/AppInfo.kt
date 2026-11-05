package eu.kanade.tachiyomi

import tachiyomi.core.util.system.ImageUtil

@Suppress("UNUSED")
object AppInfo {

    fun getVersionCode(): Int = ani.sanin.BuildConfig.VERSION_CODE

    fun getVersionName(): String = ani.sanin.BuildConfig.VERSION_NAME

    fun getSupportedImageMimeTypes(): List<String> = ImageUtil.ImageType.values().map { it.mime }
}
