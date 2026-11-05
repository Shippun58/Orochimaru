package eu.kanade.tachiyomi.network.interceptor

import ani.sanin.util.Logger
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RetryInterceptor(
    private val maxRetries: Int = 2,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var retryCount = 0
        val request = chain.request()

        if (request.method != "GET") {
            return chain.proceed(request)
        }

        while (true) {
            try {
                val response = chain.proceed(request)
                if (response.code in 500..599 && retryCount < maxRetries) {
                    response.close()
                    retryCount++
                    Logger.log("RetryInterceptor: retrying ${request.url} after ${response.code} (attempt $retryCount/$maxRetries)")
                } else {
                    return response
                }
            } catch (e: Exception) {
                if (retryCount >= maxRetries || !shouldRetry(e)) {
                    throw e
                }
                retryCount++
                Logger.log("RetryInterceptor: retrying ${request.url} after ${e::class.simpleName} (attempt $retryCount/$maxRetries)")
            }
        }
    }

    private fun shouldRetry(e: Exception): Boolean {
        return e is SocketException ||
            e is SocketTimeoutException ||
            e is UnknownHostException ||
            e is IOException
    }
}
