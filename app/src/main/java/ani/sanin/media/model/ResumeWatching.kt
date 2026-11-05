package ani.sanin.media.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.safefile.SafeFile
import kotlinx.serialization.SerialName
import java.io.IOException
import java.io.OutputStream

data class ResumeWatching(
    @JsonProperty("parentId") @SerialName("parentId") val parentId: Int,
    @JsonProperty("episodeId") @SerialName("episodeId") val episodeId: Int?,
    @JsonProperty("episode") @SerialName("episode") val episode: Int?,
    @JsonProperty("season") @SerialName("season") val season: Int?,
    @JsonProperty("updateTime") @SerialName("updateTime") val updateTime: Long,
    @JsonProperty("isFromDownload") @SerialName("isFromDownload") val isFromDownload: Boolean,
)

data class StreamData(
    private val fileLength: Long,
    val file: SafeFile,
) {
    val resume: Boolean get() = fileLength > 0

    @Throws(IOException::class)
    fun open(): OutputStream = file.openOutputStreamOrThrow(resume)

    @Throws(IOException::class)
    fun openNew(): OutputStream = file.openOutputStreamOrThrow(false)
}

@Throws(IOException::class)
fun setupStream(
    baseFile: SafeFile,
    name: String,
    folder: String?,
    extension: String,
    tryResume: Boolean,
): StreamData {
    val displayName = "$name.$extension"
    val subDir = baseFile.gotoDirectory(folder, createMissingDirectories = true)
        ?: throw IOException("Cant create directory")
    val foundFile = subDir.findFile(displayName)

    val (file, fileLength) = if (foundFile == null || foundFile.exists() != true) {
        subDir.createFileOrThrow(displayName) to 0L
    } else {
        if (tryResume) {
            foundFile to foundFile.lengthOrThrow()
        } else {
            foundFile.deleteOrThrow()
            subDir.createFileOrThrow(displayName) to 0L
        }
    }

    return StreamData(fileLength, file)
}
