package ani.sanin.parsers

import ani.sanin.Mapper
import ani.sanin.media.Media
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl

class AniPmProvider : NativeAnimeParser() {

    override val name = "AniPM"
    override val saveName = "AniPM"
    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    override val defaultBaseUrl = "https://ani.pm"
    override val backupBaseUrls = listOf("https://ani.nz", "https://ani.vg")

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) return saved
        val response = ShowResponse(
            name = mediaObj.mainName(),
            link = mediaObj.id.toString(),
            coverUrl = mediaObj.cover ?: defaultImage,
            extra = mutableMapOf(
                "anilistId" to mediaObj.id.toString(),
                "idMal" to (mediaObj.idMAL?.toString() ?: "")
            )
        )
        saveShowResponse(mediaObj.id, response)
        return response
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> {
        val anilistId = extra?.get("anilistId")?.toIntOrNull() ?: animeLink.toIntOrNull() ?: return emptyList()
        val audio = currentAudio()
        return withContext(Dispatchers.IO) {
            try {
                val root = get("$baseUrl/api/anime/anipm-server/_packages?anilistId=$anilistId")
                val packages = Mapper.json.parseToJsonElement(root) as? JsonObject ?: return@withContext emptyList()
                val episodes = packages["episodes"] as? JsonObject ?: return@withContext emptyList()
                val flag = if (audio == "dub") "dub" else "sub"
                val idMal = extra?.get("idMal").orEmpty()
                episodes.keys.mapNotNull { it.toIntOrNull() }
                    .filter { number -> (episodes[number.toString()] as? JsonObject)?.flag(flag) == true }
                    .sorted()
                    .map { number ->
                        Episode(
                            number = number.toString(),
                            link = number.toString(),
                            title = "Episode $number",
                            extra = mutableMapOf(
                                "anilistId" to anilistId.toString(),
                                "idMal" to idMal,
                                "audio" to audio,
                                "num" to number.toString()
                            )
                        )
                    }
            } catch (e: Exception) {
                Logger.log("$name loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> {
        val anilistId = extra?.get("anilistId")?.toIntOrNull() ?: return emptyList()
        val ep = extra?.get("num")?.toIntOrNull() ?: episodeLink.toIntOrNull() ?: return emptyList()
        val audio = extra?.get("audio")?.takeIf { it.isNotBlank() } ?: currentAudio()
        return withContext(Dispatchers.IO) {
            try {
                val bootstrap = getJson(
                    "$baseUrl/api/anime/playback-bootstrap/anilist/$anilistId?ep=$ep&lang=$audio&core=0"
                ) ?: return@withContext emptyList()
                if (bootstrap.string("effectiveLanguage") != audio) {
                    Logger.log("$name: no $audio for AniList $anilistId ep $ep")
                    return@withContext emptyList()
                }
                val selection = bootstrap.string("settlarSelection") ?: return@withContext emptyList()
                val sessionUrl = "$baseUrl/api/anime/settlar/session".toHttpUrl().newBuilder()
                    .addQueryParameter("selection", selection)
                    .addQueryParameter("provider", "anipm")
                    .addQueryParameter("ep", ep.toString())
                    .addQueryParameter("channel", audio)
                    .addQueryParameter("telemetry", "0")
                    .build().toString()
                val session = getJson(sessionUrl) ?: return@withContext emptyList()
                val embedUrl = session.string("embedUrl") ?: return@withContext emptyList()
                val token = embedUrl.toHttpUrl().queryParameter("t") ?: return@withContext emptyList()
                val embedSession = getJson("https://embed.settlar.io/api/embed/session?t=${encode(token)}")
                    ?: return@withContext emptyList()
                val source = embedSession.string("source")?.takeIf { it.startsWith("https://") }
                    ?: return@withContext emptyList()

                val extraData = mutableMapOf(
                    "audio" to audio,
                    "videoType" to "m3u8"
                )
                val subtitles = buildJsonArray {
                    (embedSession["subtitles"] as? JsonArray).orEmpty().forEach { element ->
                        val track = element as? JsonObject ?: return@forEach
                        val url = track.string("url")?.takeIf { it.startsWith("https://") } ?: return@forEach
                        val useAss = track.string("pick") == "ass"
                        val file = if (useAss) track.string("assUrl")?.takeIf { it.startsWith("https://") } ?: url else url
                        val code = track.string("srclang").orEmpty().lowercase()
                        add(buildJsonObject {
                            put("url", file)
                            put("language", LANGUAGES[code] ?: code.ifEmpty { "und" })
                            put("type", if (useAss) "ass" else "vtt")
                        })
                    }
                }
                if (subtitles.isNotEmpty()) extraData["subtitles"] = subtitles.toString()

                val skip = bootstrap["skip"] as? JsonObject
                skip?.times("op")?.let { extraData["intro"] = it }
                skip?.times("ed")?.let { extraData["outro"] = it }

                Logger.log("$name: AniList $anilistId ep $ep ($audio) -> $source")
                listOf(VideoServer("AniPM", source, extraData))
            } catch (e: Exception) {
                Logger.log("$name loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun currentAudio(): String = if (selectDub) "dub" else "sub"

    private fun getJson(url: String): JsonObject? {
        val body = get(url)
        return Mapper.json.parseToJsonElement(body) as? JsonObject
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.times(name: String): String? {
        val block = this[name] as? JsonObject ?: return null
        val start = block.number("start") ?: return null
        val end = block.number("end") ?: return null
        return """{"start":$start,"end":$end}"""
    }

    private companion object {
        val LANGUAGES = mapOf(
            "ind" to "id", "tha" to "th", "chi" to "zh", "zho" to "zh", "kor" to "ko", "pol" to "pl",
            "tur" to "tr", "jpn" to "ja", "eng" to "en", "spa" to "es", "por" to "pt", "fre" to "fr",
            "fra" to "fr", "ger" to "de", "deu" to "de", "ita" to "it", "rus" to "ru", "ara" to "ar",
            "vie" to "vi", "may" to "ms", "msa" to "ms"
        )
    }
}
