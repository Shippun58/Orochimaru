package ani.sanin.connections.subtitles

import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request

object SubDLSubtitles {

    private const val API_KEY = "Ds7m-fFUW0YzTEH3exnJD8jOBX6viXizGVY68YqvDHE"
    private const val BASE_URL = "https://api.subdl.com/api/v1/subtitles"

    suspend fun getSubtitles(imdbId: String, season: Int, episode: Int): List<StremioSub> {
        return withContext(Dispatchers.IO) {
            try {
                val cleanImdb = imdbId.removePrefix("tt")
                val sb = StringBuilder("$BASE_URL?api_key=$API_KEY")
                sb.append("&imdb_id=$cleanImdb")
                if (season > 0) sb.append("&season_number=$season")
                if (episode > 0) sb.append("&episode_number=$episode")
                sb.append("&languages=en")

                val url = sb.toString()
                Logger.log("SubDL: Searching $url")

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .build()

                val subs = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logger.log("SubDL: HTTP ${response.code}")
                        return@withContext emptyList()
                    }

                    val body = response.body?.string() ?: return@withContext emptyList()
                    val result = Mapper.json.decodeFromString<SubDLResponse>(body)

                    result.subtitles?.filter { sub ->
                        val badSeason = season > 0 && sub.season != null && sub.season != season
                        val badEpisode = episode > 0 && sub.episode != null && sub.episode != episode
                        if (badSeason || badEpisode) {
                            Logger.log(
                                "SubDL: dropped mismatching sub " +
                                    "S${sub.season}E${sub.episode} (want S$season E$episode) " +
                                    "${(sub.releaseName ?: sub.name ?: "")}"
                            )
                        }
                        !badSeason && !badEpisode
                    }?.map { sub ->
                        StremioSub(
                            id = sub.url ?: "",
                            url = if (sub.url?.startsWith("http") == true) sub.url!!
                                  else "https://dl.subdl.com${sub.url ?: ""}",
                            lang = sub.lang ?: "English",
                            label = sub.releaseName ?: sub.name ?: "SubDL",
                            source = "subdl"
                        )
                    } ?: emptyList()
                }

                Logger.log("SubDL: Found ${subs.size} subs")
                subs
            } catch (e: Exception) {
                Logger.log("SubDL: Error - ${e.message}")
                emptyList()
            }
        }
    }
}

@Serializable
data class SubDLResponse(
    @SerialName("status") val status: Boolean? = null,
    @SerialName("subtitles") val subtitles: List<SubDLSubtitle>? = null
)

@Serializable
data class SubDLSubtitle(
    @SerialName("release_name") val releaseName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("lang") val lang: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("hi") val hearingImpaired: Boolean? = null
)
