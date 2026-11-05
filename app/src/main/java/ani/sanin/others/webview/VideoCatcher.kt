package ani.sanin.others.webview

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import ani.sanin.FileUrl

class VideoCatcher(
    override val location: FileUrl,
    override val title: String = "Loading video…"
) : WebViewBottomDialog() {

    private val candidates = LinkedHashSet<String>()

    override val webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString().orEmpty()
            if (!isMediaUrl(url)) return super.shouldInterceptRequest(view, request)
            if (isAdUrl(url)) {
                Log.d("AnimeJL", "VideoCatcher: skipping ad request ${url.take(140)}")
                return super.shouldInterceptRequest(view, request)
            }
            if (candidates.add(url)) {
                Log.d("AnimeJL", "VideoCatcher: captured media ${url.take(140)}")
                view?.post {
                    privateCallback.invoke(mapOf("videos" to candidates.joinToString("\n")))
                }
            }
            return super.shouldInterceptRequest(view, request)
        }
    }

    companion object {
        fun newInstance(url: FileUrl) = VideoCatcher(url)
        fun newInstance(url: String) = VideoCatcher(FileUrl(url))
    }
}

private fun isMediaUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    val lower = url.lowercase()
    if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
        lower.contains(".webp") || lower.contains(".svg") || lower.contains(".gif") ||
        lower.contains(".css") || lower.contains(".js") || lower.contains(".ico") ||
        lower.contains("favicon") || lower.endsWith("/")
    ) return false
    return lower.contains(".m3u8") || lower.contains(".mpd") ||
        lower.contains(".mp4") || lower.contains(".webm")
}

private fun isAdUrl(url: String): Boolean {
    val lower = url.lowercase()
    val adMarkers = listOf(
        "doubleclick", "googlesyndication", "googletagmanager", "googlead", "adservice",
        "amazon-adsystem", "outbrain", "taboola", "revcontent", "adform", "adsystem",
        "adsdk", "vast", "vpaid", "adserver", "/ad/", "/ads/", "adshorte", "exoclick",
        "/banner/", "adpush", "/ad?", "=ad", "ads?", "double-click"
    )
    return adMarkers.any { lower.contains(it) }
}
