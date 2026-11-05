package eu.kanade.tachiyomi.util.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import ani.sanin.util.Logger

object WebViewUtil {
    const val SPOOF_PACKAGE_NAME = "org.chromium.chrome"

    const val MINIMUM_WEBVIEW_VERSION = 108

    fun supportsWebView(context: Context): Boolean {
        try {

            CookieManager.getInstance()
        } catch (e: Throwable) {
            Logger.log(e)
            return false
        }

        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)
    }
}

fun WebView.isOutdated(): Boolean {
    return getWebViewMajorVersion() < WebViewUtil.MINIMUM_WEBVIEW_VERSION
}

@SuppressLint("SetJavaScriptEnabled")
fun WebView.setDefaultSettings() {
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        cacheMode = WebSettings.LOAD_DEFAULT

        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }
}

private fun WebView.getWebViewMajorVersion(): Int {
    val uaRegexMatch = """.*Chrome/(\d+)\..*""".toRegex().matchEntire(getDefaultUserAgentString())
    return if (uaRegexMatch != null && uaRegexMatch.groupValues.size > 1) {
        uaRegexMatch.groupValues[1].toInt()
    } else {
        0
    }
}

private fun WebView.getDefaultUserAgentString(): String {
    val originalUA: String = settings.userAgentString

    settings.userAgentString = null
    val defaultUserAgentString = settings.userAgentString

    settings.userAgentString = originalUA

    return defaultUserAgentString
}
