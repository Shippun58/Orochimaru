package ani.sanin.download

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ani.sanin.settings.saving.PrefManager

object DownloadStore {

    private const val KEY_QUEUE = "download_queue"
    private const val KEY_COMPLETED = "download_completed"

    private val gson = Gson()

    private fun prefs() = PrefManager.getAnimeDownloadPreferences()

    fun loadQueue(): List<DownloadItem> = try {
        val json = prefs().getString(KEY_QUEUE, null) ?: return emptyList()
        val type = object : TypeToken<List<DownloadItem>>() {}.type
        gson.fromJson<List<DownloadItem>>(json, type) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun saveQueue(items: List<DownloadItem>) {
        try {
            prefs().edit().putString(KEY_QUEUE, gson.toJson(items)).apply()
        } catch (_: Exception) {
        }
    }

    fun loadCompleted(): List<DownloadedItem> = try {
        val json = prefs().getString(KEY_COMPLETED, null) ?: return emptyList()
        val type = object : TypeToken<List<DownloadedItem>>() {}.type
        gson.fromJson<List<DownloadedItem>>(json, type) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun saveCompleted(items: List<DownloadedItem>) {
        try {
            prefs().edit().putString(KEY_COMPLETED, gson.toJson(items)).apply()
        } catch (_: Exception) {
        }
    }

    fun clearAll() {
        prefs().edit().remove(KEY_QUEUE).remove(KEY_COMPLETED).apply()
    }
}