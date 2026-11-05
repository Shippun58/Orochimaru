package com.lagradost.cloudstream3.utils

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun jsValueToString(v: Any?): String {
    return v?.toString() ?: ""
}

suspend fun evalJs(
    js: String,
    variable: String? = null,
    maxExecutionTime: Long = 5000,
    maxInstructions: Int = 100000,
    scope: Map<String, Any>? = null
): Any? {
    return withContext(Dispatchers.Main) {
        try {
            val latch = CountDownLatch(1)
            var result: Any? = null

            val app = (com.lagradost.api.getContext() as? android.content.Context) ?: return@withContext null
            val webView = WebView(app)

            webView.settings.javaScriptEnabled = true
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun returnResult(value: String) {
                    result = value
                    latch.countDown()
                }
            }, "android")

            val script = buildString {
                append(js)
                if (variable != null) {
                    append("\ntry { android.returnResult($variable.toString()) } catch(e) { android.returnResult('') }")
                }
            }

            webView.loadDataWithBaseURL("https://blank.org", "<html></html>", "text/html", "UTF-8", null)
            webView.post {
                webView.evaluateJavascript(script, null)
            }

            latch.await(maxExecutionTime, TimeUnit.MILLISECONDS)
            webView.destroy()
            result
        } catch (e: Exception) {
            null
        }
    }
}
