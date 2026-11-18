package ani.sanin.notifications.push

import android.content.Context
import ani.sanin.connections.simkl.Simkl
import ani.sanin.currContext
import ani.sanin.notifications.subscription.SubscriptionHelper
import ani.sanin.notifications.subscription.TmdbSubscriptionHelper
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
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
                PrefManager.init(ctx)
                val enabled = PrefManager.getVal<Boolean>(PrefName.NotificationsEnabled) &&
                    PrefManager.getVal<Boolean>(PrefName.PushNotifications)
                val token = firebaseToken()

                if (!enabled) {
                    val payload = JSONObject().apply { put("token", token) }
                    if (post("$ENDPOINT/unsubscribe", payload)) {
                        PrefManager.setVal(PrefName.PushRegistered, false)
                    }
                    return@launch
                }

                val anilist = LinkedHashSet<Int>()
                val tmdb = LinkedHashSet<Int>()

                if (PrefManager.getVal<Boolean>(PrefName.AnilistNotifications)) {
                    SubscriptionHelper.getSubscriptions().values
                        .filter { it.isAnime }
                        .mapTo(anilist) { it.id }
                }
                if (PrefManager.getVal<Boolean>(PrefName.EpisodeNotifications)) {
                    TmdbSubscriptionHelper.getSubscriptions().values
                        .filter { it.type == "tv" }
                        .mapTo(tmdb) { it.id }
                }

                if (PrefManager.getVal<Boolean>(PrefName.NotificationSimkl)) {
                    val ids = simklIds()
                    anilist.addAll(ids.first)
                    tmdb.addAll(ids.second)
                }

                val payload = JSONObject().apply {
                    put("token", token)
                    put("anilist", JSONArray(anilist.toList()))
                    put("tmdb", JSONArray(tmdb.toList()))
                }

                if (post("$ENDPOINT/subscribe", payload)) {
                    PrefManager.setVal(PrefName.PushRegistered, true)
                }
            } catch (e: Exception) {
                Logger.log("PushSync failed: ${e.message}")
            }
        }
    }

    private const val SIMKL_CACHE_TTL = 10 * 60 * 1000L
    private var simklCache: Triple<Long, Set<Int>, Set<Int>>? = null

    private suspend fun simklIds(): Pair<Set<Int>, Set<Int>> {
        val cached = simklCache
        if (cached != null && System.currentTimeMillis() - cached.first < SIMKL_CACHE_TTL) {
            return cached.second to cached.third
        }
        val anilist = mutableSetOf<Int>()
        val tmdb = mutableSetOf<Int>()
        if (Simkl.token == null) Simkl.getSavedToken()
        if (Simkl.token != null) {
            Simkl.getLibrary()?.shows?.forEach { item ->
                val status = item.status?.lowercase()
                if (status != "watching" && status != "current") return@forEach
                val anilistId = item.ids?.anilist
                val tmdbId = item.ids?.tmdb
                when {
                    anilistId != null && anilistId > 0 -> anilist.add(anilistId)
                    tmdbId != null && tmdbId > 0 -> tmdb.add(tmdbId)
                }
            }
        }
        simklCache = Triple(System.currentTimeMillis(), anilist, tmdb)
        return anilist to tmdb
    }

    private fun post(url: String, payload: JSONObject): Boolean {
        return try {
            val conn =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) Logger.log("PushSync $url failed: $code")
            code in 200..299
        } catch (e: Exception) {
            Logger.log("PushSync $url failed: ${e.message}")
            false
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
