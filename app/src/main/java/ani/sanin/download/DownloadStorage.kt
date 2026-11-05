package ani.sanin.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import java.io.File
import java.io.InputStream

object DownloadStorageHelper {

    const val ROOT_NAME = "Orochimaru"
    private const val TEMP_DIR = "tmp"

    fun mode(): DownloadStorage =
        if (PrefManager.getVal<Int>(PrefName.DownloadStorage) == 1) DownloadStorage.SHARED
        else DownloadStorage.APP

    fun appRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, ROOT_NAME).apply { mkdirs() }
    }

    fun sharedTreeUri(): Uri? =
        PrefManager.getVal<String>(PrefName.DownloadsDir).takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun sharedRoot(context: Context): DocumentFile? {
        val uri = sharedTreeUri() ?: return null
        return try {
            DocumentFile.fromTreeUri(context, uri)
        } catch (_: Exception) {
            null
        }
    }

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trim('.')
        return if (cleaned.isBlank()) "untitled" else cleaned.take(120)
    }

    fun extensionFor(item: DownloadItem): String = when {
        item.videoType == ani.sanin.parsers.VideoType.M3U8 -> "mkv"
        item.url.contains(".mkv", true) -> "mkv"
        item.url.contains(".webm", ignoreCase = true) -> "webm"
        else -> "mp4"
    }

    fun finalName(item: DownloadItem): String =
        sanitize(item.fileName) + "." + extensionFor(item)

    fun tempFile(context: Context, item: DownloadItem): File {
        val dir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
        val ext = extensionFor(item)

        return File(dir, sanitize(item.id) + ".$ext.part")
    }

    fun appMediaDir(context: Context, mediaName: String): File =
        File(appRoot(context), sanitize(mediaName)).apply { mkdirs() }

    fun commit(context: Context, item: DownloadItem, tempFile: File): String? {
        if (!tempFile.exists()) return null
        return when (mode()) {
            DownloadStorage.APP -> {
                val dir = File(appRoot(context), sanitize(item.mediaName)).apply { mkdirs() }
                val out = File(dir, finalName(item))
                if (out.exists()) out.delete()
                if (tempFile.renameTo(out)) out.absolutePath
                else {

                    tempFile.copyTo(out, overwrite = true)
                    tempFile.delete()
                    out.absolutePath
                }
            }

            DownloadStorage.SHARED -> {
                val root = sharedRoot(context)
                if (root == null) {

                    val dir = File(appRoot(context), sanitize(item.mediaName)).apply { mkdirs() }
                    val out = File(dir, finalName(item))
                    tempFile.copyTo(out, overwrite = true)
                    tempFile.delete()
                    return out.absolutePath
                }
                val folder = root.findFile(sanitize(item.mediaName))
                    ?: root.createDirectory(sanitize(item.mediaName))
                if (folder == null) return null
                val name = finalName(item)
                val doc = folder.findFile(name)
                    ?: folder.createFile(mimeFor(item), name)
                if (doc == null) return null
                context.contentResolver.openOutputStream(doc.uri, "wt")?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                } ?: return null
                tempFile.delete()
                doc.uri.toString()
            }
        }
    }

    private fun mimeFor(item: DownloadItem): String {
        return when (extensionFor(item)) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }

    fun openInput(context: Context, path: String): InputStream? = try {
        if (path.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(path))
        else File(path).inputStream()
    } catch (_: Exception) {
        null
    }
}