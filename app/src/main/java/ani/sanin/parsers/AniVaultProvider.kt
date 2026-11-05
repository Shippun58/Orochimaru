package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.media.Media
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class AniVaultProvider : NativeAnimeParser() {

    override val name = "AniVault"
    override val saveName = "AniVault"
    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = false

    override val defaultBaseUrl = "https://www.anivault.co"

    override val knownServers: List<String> = listOf("AniVault")

    override suspend fun search(query: String): List<ShowResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query.trim(), "utf-8")
                val jsonStr = get("$baseUrl/api/mobile/browse?q=$encoded")
                val obj = Mapper.json.parseToJsonElement(jsonStr) as? JsonObject ?: return@withContext emptyList()
                val data = obj["data"] as? JsonArray ?: return@withContext emptyList()
                data.mapNotNull { el ->
                    val item = el as? JsonObject ?: return@mapNotNull null
                    val id = (item["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                    val title = (item["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val image = (item["image"] as? JsonPrimitive)?.contentOrNull
                    val total = (item["episodes"] as? JsonPrimitive)?.intOrNull
                    ShowResponse(
                        name = title,
                        link = id.toString(),
                        coverUrl = FileUrl(image ?: defaultImage),
                        total = total,
                        extra = mutableMapOf("mal_id" to id.toString())
                    )
                }
            } catch (e: Exception) {
                Logger.log("AniVault search error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) return saved
        val malId = mediaObj.idMAL
        if (malId != null && malId > 0) {
            val response = ShowResponse(
                name = mediaObj.mainName(),
                link = malId.toString(),
                coverUrl = FileUrl(mediaObj.cover ?: defaultImage),
                extra = mutableMapOf("mal_id" to malId.toString())
            )
            saveShowResponse(mediaObj.id, response)
            return response
        }
        setUserText("Searching AniVault: ${mediaObj.mainName()}")
        return searchWithFallback(mediaObj.mainName()).firstOrNull()
            ?: searchWithFallback(mediaObj.nameRomaji).firstOrNull()
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> {
        val malId = animeLink.toIntOrNull() ?: extra?.get("mal_id")?.toIntOrNull() ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val jsonStr = get("$baseUrl/api/mobile/anime/$malId/episodes")
                val obj = Mapper.json.parseToJsonElement(jsonStr) as? JsonObject ?: return@withContext emptyList()
                val data = obj["data"] as? JsonArray ?: return@withContext emptyList()
                data.mapNotNull { el ->
                    val ep = el as? JsonObject ?: return@mapNotNull null
                    val num = (ep["mal_id"] as? JsonPrimitive)?.intOrNull
                        ?: (ep["episode"] as? JsonPrimitive)?.intOrNull
                        ?: return@mapNotNull null
                    val title = (ep["title"] as? JsonPrimitive)?.contentOrNull
                    Episode(
                        number = num.toString(),
                        link = num.toString(),
                        title = title?.takeIf { it.isNotBlank() },
                        extra = (extra ?: emptyMap()) + mapOf("mal_id" to malId.toString())
                    )
                }.sortedBy { it.number.toIntOrNull() ?: 0 }
            } catch (e: Exception) {
                Logger.log("AniVault loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> {
        val malId = extra?.get("mal_id")?.toIntOrNull() ?: episodeLink.toIntOrNull() ?: return emptyList()
        val epNum = sEpisode.episode_number.toInt()
            ?: episodeLink.toIntOrNull()
            ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val body = get("$baseUrl/api/videos.php?anime_id=$malId&ep=$epNum")
                val obj = Mapper.json.parseToJsonElement(body) as? JsonObject ?: return@withContext emptyList()
                if (obj["success"]?.jsonPrimitive?.booleanOrNull == false) return@withContext emptyList()

                val servers = mutableListOf<VideoServer>()

                val video = obj["video"] as? JsonObject
                if (video != null) {
                    servers.addAll(parseVideoRow(video, malId))
                }

                val videos = obj["videos"] as? JsonArray
                videos?.forEach { el ->
                    val row = el as? JsonObject ?: return@forEach
                    servers.addAll(parseVideoRow(row, malId))
                }

                servers.distinctBy { it.embed.url }
            } catch (e: Exception) {
                Logger.log("AniVault loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun parseVideoRow(row: JsonObject, malId: Int): List<VideoServer> {
        val servers = mutableListOf<VideoServer>()
        val embedCode = (row["embed_code"] as? JsonPrimitive)?.contentOrNull
        val qualitiesJson = (row["qualities"] as? JsonPrimitive)?.contentOrNull

        if (!embedCode.isNullOrBlank()) {
            val iframeUrl = extractIframeSrc(embedCode)
            if (!iframeUrl.isNullOrBlank()) {
                val extraData = mutableMapOf<String, String>()
                extraData["referer"] = "$defaultBaseUrl/"
                servers.add(VideoServer("AniVault", iframeUrl, extraData))
            }
        }

        if (!qualitiesJson.isNullOrBlank() && qualitiesJson != "null") {
            try {
                val qualities = Mapper.json.parseToJsonElement(qualitiesJson) as? JsonObject ?: return servers
                for ((track, entries) in qualities) {
                    val arr = entries as? JsonArray ?: continue
                    arr.forEach { el ->
                        val q = el as? JsonObject ?: return@forEach
                        val label = (q["label"] as? JsonPrimitive)?.contentOrNull ?: "Default"
                        val embed = (q["embed"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        val iframeUrl = extractIframeSrc(embed) ?: return@forEach
                        val extraData = mutableMapOf<String, String>()
                        extraData["referer"] = "$defaultBaseUrl/"
                        extraData["audio"] = track
                        servers.add(VideoServer("AniVault $label", iframeUrl, extraData))
                    }
                }
            } catch (_: Exception) { }
        }

        return servers
    }

    private fun extractIframeSrc(html: String): String? {
        val match = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: return null
        val url = match.groupValues[1]
        return if (url.startsWith("http")) url else null
    }
}
