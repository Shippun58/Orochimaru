package ani.sanin.parsers

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class DdosGuardInterceptor(private val client: OkHttpClient) : Interceptor {

    private val cookieManager by lazy { CookieManager.getInstance() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        if (response.code !in ERROR_CODES || response.header("Server") !in SERVER_CHECK) {
            return response
        }

        response.close()
        val url = originalRequest.url
        val cookies = cookieManager.getCookie(url.toString())
        val oldCookie = if (!cookies.isNullOrEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(url, it) }
        } else {
            emptyList()
        }

        val ddg2Cookie = oldCookie.firstOrNull { it.name == "__ddg2_" }
        if (!ddg2Cookie?.value.isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }

        val newCookie = getNewCookie(url) ?: return chain.proceed(originalRequest)
        val newCookieHeader = buildString {
            (oldCookie + newCookie).forEachIndexed { index, cookie ->
                if (index > 0) append("; ")
                append(cookie.name).append('=').append(cookie.value)
            }
        }

        return chain.proceed(originalRequest.newBuilder().addHeader("cookie", newCookieHeader).build())
    }

    private fun getNewCookie(url: HttpUrl): Cookie? {
        val cookies = cookieManager.getCookie(url.toString())
        val oldCookie = if (!cookies.isNullOrEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(url, it) }
        } else {
            emptyList()
        }
        val ddg2Cookie = oldCookie.firstOrNull { it.name == "__ddg2_" }
        if (!ddg2Cookie?.value.isNullOrEmpty()) {
            return ddg2Cookie
        }
        val wellKnown = try {
            client.newCall(Request.Builder().url(WELL_KNOWN_URL).build()).execute()
                .body?.string().orEmpty()
        } catch (_: Exception) {
            return null
        }
        val path = wellKnown.substringAfter("'", "").substringBefore("'", "")
        if (path.isEmpty()) return null
        val checkUrl = "${url.scheme}://${url.host}$path"
        return try {
            client.newCall(Request.Builder().url(checkUrl).build()).execute()
                .header("set-cookie")?.let { Cookie.parse(url, it) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val WELL_KNOWN_URL = "https://check.ddos-guard.net/check.js"
        private val ERROR_CODES = listOf(403)
        private val SERVER_CHECK = listOf("ddos-guard")
    }
}
