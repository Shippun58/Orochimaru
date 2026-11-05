package ani.sanin.connections.subtitles

import ani.sanin.Mapper
import ani.sanin.client
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object WyzieSubtitles {

    private const val BASE_URL = "https://sub.wyzie.io/search"

    suspend fun getWyzieSubtitles(imdbId: String, season: Int, episode: Int): List<WyzieSub> {
        return withContext(Dispatchers.IO) {
            try {

                val languages = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleLanguages)
                    .map { langToIso(it) }
                    .joinToString(",")

                suspend fun fetchWyzie(s: Int, e: Int): List<WyzieSub> {
                    val url = "$BASE_URL?id=$imdbId&season=$s&episode=$e&language=$languages&key=wyzie-o7k6nk8qlykqb5hniy1wq4xdeh26hzpa"
                    Logger.log("WyzieSubtitles: Fetching from $url")
                    val response = client.get(url)
                    val text = response.text
                    if (text.trim().startsWith("<") || !text.trim().startsWith("[")) {
                        return emptyList()
                    }
                    return try {
                        Mapper.json.decodeFromString<List<WyzieSub>>(text)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                var data = fetchWyzie(season, episode)
                if (data.isEmpty()) Logger.log("WyzieSubtitles: no subs for S$season:E$episode")

                Logger.log("WyzieSubtitles: Decoded ${data.size} subs")

                val userLang = java.util.Locale.getDefault().language

                data.sortedWith(compareByDescending<WyzieSub> {

                    it.format.lowercase() == "ass"
                }.thenBy {

                    it.displayLabel
                })

            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    private fun langToIso(lang: String): String = when (lang.lowercase().trim()) {
        "english" -> "en"
        "spanish" -> "es"
        "portuguese" -> "pt"
        "french" -> "fr"
        "german" -> "de"
        "italian" -> "it"
        "japanese" -> "ja"
        "korean" -> "ko"
        "chinese" -> "zh"
        "arabic" -> "ar"
        "russian" -> "ru"
        "turkish" -> "tr"
        "dutch" -> "nl"
        "polish" -> "pl"
        "hindi" -> "hi"
        "thai" -> "th"
        "indonesian" -> "id"
        "vietnamese" -> "vi"
        "swedish" -> "sv"
        "danish" -> "da"
        "finnish" -> "fi"
        "norwegian" -> "no"
        "czech" -> "cs"
        "romanian" -> "ro"
        "hungarian" -> "hu"
        "greek" -> "el"
        "ukrainian" -> "uk"
        "hebrew" -> "he"
        "bulgarian" -> "bg"
        "croatian" -> "hr"
        "slovak" -> "sk"
        "serbian" -> "sr"
        "farsi" -> "fa"
        "persian" -> "fa"
        "bengali" -> "bn"
        "malay" -> "ms"
        "swahili" -> "sw"

        else -> lang.lowercase().trim().take(2)
    }
}

@Serializable
data class WyzieSub(
    @SerialName("id") val id: String,
    @SerialName("url") val url: String,
    @SerialName("display") val display: String?,
    @SerialName("language") val language: String,
    @SerialName("format") val format: String
) {
    val displayLabel: String
        get() = display ?: language
}
