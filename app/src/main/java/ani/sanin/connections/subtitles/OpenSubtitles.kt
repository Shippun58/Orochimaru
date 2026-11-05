package ani.sanin.connections.subtitles

import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object OpenSubtitles {

    private const val BASE_URL = "https://api.opensubtitles.com/api/v1"
    private const val USER_AGENT = "Cloudstream3 v0.2"
    private const val API_KEY = "uyBLgFD17MgrYmA0gSXoKllMJBelOYj2"
    private const val MAX_RESULTS = 12

    const val URL_PREFIX = "opensubtitles://"

    suspend fun search(imdbId: String, season: Int, episode: Int, isTv: Boolean, queryText: String? = null): List<StremioSub> {
        return withContext(Dispatchers.IO) {
            try {
                val cleanImdb = imdbId.removePrefix("tt")

                val urls = if (isTv) {
                    listOf("$BASE_URL/subtitles?imdb_id=$cleanImdb&season_number=$season&episode_number=$episode")
                } else {
                    listOf("$BASE_URL/subtitles?imdb_id=$cleanImdb")
                }

                val results = mutableListOf<StremioSub>()
                for (url in urls) {
                    try {
                        Logger.log("OpenSubtitles: Searching $url")

                        val request = Request.Builder()
                            .url(url)
                            .addHeader("Api-Key", API_KEY)
                            .addHeader("User-Agent", USER_AGENT)
                            .addHeader("Accept", "application/json")
                            .build()

                        okHttpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                Logger.log("OpenSubtitles: Search failed HTTP ${response.code}")
                                return@use
                            }

                            val body = response.body?.string() ?: return@use
                            val searchResult =
                                Mapper.json.decodeFromString<OpenSubtitlesSearchResponse>(body)

                            for (item in searchResult.data.take(MAX_RESULTS)) {
                                try {
                                    val file = item.attributes.files.firstOrNull() ?: continue
                                    val fileId = file.fileId
                                    val fileName =
                                        file.fileName ?: item.attributes.release ?: "OpenSubtitles"
                                    val lang = item.attributes.language
                                    val isHi = item.attributes.hearingImpaired == true

                                    Logger.log("OpenSubtitles: Found file $fileId ($fileName)")

                                    results.add(
                                        StremioSub(
                                            id = "${URL_PREFIX}${fileId}",
                                            url = "${URL_PREFIX}${fileId}",
                                            lang = lang,
                                            label = if (isHi) "$fileName (HI)" else fileName,
                                            source = "opensubtitles"
                                        )
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                        if (results.isNotEmpty()) break
                    } catch (e: Exception) {
                        Logger.log("OpenSubtitles: url failed $url -> ${e.message}")
                    }
                }
                results
            } catch (e: Exception) {
                Logger.log("OpenSubtitles: Error - ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun getDownloadUrl(fileId: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                val bodyStr = """{"file_id":$fileId}"""
                val req = Request.Builder()
                    .url("$BASE_URL/download")
                    .addHeader("Api-Key", API_KEY)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("Accept", "application/json")
                    .post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful && resp.body != null) {
                        val parsed = Mapper.json.decodeFromString<DownloadResponse>(resp.body!!.string())
                        parsed.link
                    } else null
                }
            } catch (e: Exception) {
                Logger.log("OpenSubtitles: Download error - ${e.message}")
                null
            }
        }
    }
}

@Serializable
data class OpenSubtitlesSearchResponse(
    val data: List<OpenSubtitlesItem> = emptyList()
)

@Serializable
data class OpenSubtitlesItem(
    val id: String,
    val attributes: OpenSubtitlesAttributes
)

@Serializable
data class OpenSubtitlesAttributes(
    val language: String,
    val release: String? = null,
    val files: List<OpenSubtitlesFile> = emptyList(),
    @SerialName("hearing_impaired") val hearingImpaired: Boolean? = null
)

@Serializable
data class OpenSubtitlesFile(
    @SerialName("file_id") val fileId: Int,
    @SerialName("file_name") val fileName: String? = null
)

@Serializable
data class DownloadRequest(
    @SerialName("file_id") val fileId: Int
)

@Serializable
data class DownloadResponse(
    val link: String? = null,
    @SerialName("file_name") val fileName: String? = null
)
