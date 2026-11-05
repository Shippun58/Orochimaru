package com.lagradost.cloudstream3

import android.content.Context
import com.lagradost.cloudstream3.utils.Event
import java.io.File

object PluginRuntime {
    val afterPluginsLoadedEvent = Event<Boolean>()

    var lastError: String? = null

    private const val FILE_DELETE_KEY = "FILES_TO_DELETE_KEY"

    private var filesToDelete: MutableSet<String> = mutableSetOf()

    fun deleteFileOnExit(file: File) {
        filesToDelete.add(file.absolutePath)
    }

    fun cleanDeletedFiles(context: Context) {
        val prefs = context.getSharedPreferences("cloudstream_exit", 0)
        val files = prefs.getStringSet(FILE_DELETE_KEY, emptySet()) ?: emptySet()
        files.forEach { path ->
            try { File(path).delete() } catch (_: Exception) {}
        }
        prefs.edit().remove(FILE_DELETE_KEY).apply()
    }

    fun deleteFilesOnExit(context: Context) {
        val prefs = context.getSharedPreferences("cloudstream_exit", 0)
        val existing = prefs.getStringSet(FILE_DELETE_KEY, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(FILE_DELETE_KEY, existing + filesToDelete).apply()
        filesToDelete.clear()
    }
}
