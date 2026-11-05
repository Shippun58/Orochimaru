package ani.sanin.notifications.push

import android.content.Context
import ani.sanin.currContext
import ani.sanin.notifications.subscription.SubscriptionHelper
import ani.sanin.notifications.subscription.TmdbSubscriptionHelper
import ani.sanin.util.Logger
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PushSync {

    const val ENDPOINT = "https://sanin-push.shemaus58.workers.dev"

    fun sync(context: Context? = currContext()) {
        val ctx = context ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = firebaseToken()
                val anilist = SubscriptionHelper.getSubscriptions()
                    .values.filter { it.isAnime }.map { it.id }
                val tmdb = TmdbSubscriptionHelper.getSubscriptions()
                    .values.filter { it.type == "tv" }.map { it.id }

                val payload = JSONObject().apply {
                    put("token", token)
                    put("anilist", JSONArray(anilist))
                    put("tmdb", JSONArray(tmdb))
                }

                val conn =
                    (URL("$ENDPOINT/subscribe").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 15000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) Logger.log("PushSync subscribe failed: $code")
                conn.disconnect()
            } catch (e: Exception) {
                Logger.log("PushSync failed: ${e.message}")
            }
        }
    }

    private suspend fun firebaseToken(): String = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result)
            } else {
                cont.resumeWithException(task.exception ?: RuntimeException("FCM token request failed"))
            }
        }
    }
}