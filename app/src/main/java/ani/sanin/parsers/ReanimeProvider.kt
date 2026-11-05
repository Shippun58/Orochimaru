package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.media.Media
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ReanimeProvider : NativeAnimeParser() {

    override val name = "Reanime"
    override val saveName = "Reanime"
    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    override val defaultBaseUrl = "https://reanime.to"
    override val knownServers = listOf("Reanime")

    private val FLIX = "https://flixcloud.cc"

    override suspend fun search(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        try {
            val json = get("$baseUrl/api/v1/search?q=${encode(query)}&limit=10", baseUrl)
            val obj = Mapper.json.parseToJsonElement(json) as? JsonObject ?: return@withContext emptyList()
            val results = obj["results"] as? JsonArray ?: return@withContext emptyList()
            results.mapNotNull { el ->
                val r = el as? JsonObject ?: return@mapNotNull null
                val animeId = (r["anime_id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val title = (r["title"] as? JsonObject)?.let { t ->
                    (t["english"] as? JsonPrimitive)?.contentOrNull
                        ?: (t["romaji"] as? JsonPrimitive)?.contentOrNull
                } ?: animeId
                val cover = (r["cover_image"] as? JsonObject)?.let { c ->
                    (c["large"] as? JsonPrimitive)?.contentOrNull ?: (c["medium"] as? JsonPrimitive)?.contentOrNull
                } ?: defaultImage
                val subbed = (r["subbed"] as? JsonPrimitive)?.intOrNull
                val dubbed = (r["dubbed"] as? JsonPrimitive)?.intOrNull
                ShowResponse(
                    name = title,
                    link = animeId,
                    coverUrl = cover,
                    total = (r["episodes"] as? JsonPrimitive)?.intOrNull
                        ?: subbed ?: dubbed,
                    extra = mutableMapOf(
                        "animeId" to animeId,
                        "subbed" to (subbed?.toString() ?: ""),
                        "dubbed" to (dubbed?.toString() ?: "")
                    )
                )
            }
        } catch (e: Exception) {
            Logger.log("Reanime search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) {
            saveShowResponse(mediaObj.id, saved, true)
            return saved
        }
        setUserText("Searching Reanime: ${mediaObj.mainName()}")
        return withContext(Dispatchers.IO) {
            try {
                val anilistId = mediaObj.id
                val queries = buildTitleQueries(mediaObj)

                val candidates = linkedMapOf<String, JsonObject>()
                for (q in queries.take(5)) {
                    val json = runCatching { get("$baseUrl/api/v1/search?q=${encode(q)}&limit=10", baseUrl) }.getOrNull() ?: continue
                    val obj = runCatching { Mapper.json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: continue
                    (obj["results"] as? JsonArray)?.forEach { el ->
                        val r = el as? JsonObject ?: return@forEach
                        val id = (r["anime_id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        if (!candidates.containsKey(id)) candidates[id] = r
                    }
                }

                for ((id, r) in candidates) {
                    val coverId = anilistIdFromCover(r)
                    if (coverId == anilistId) return@withContext buildResponse(anilistId, id, r, null)
                }

                val needsDetail = candidates.filter { (_, r) -> anilistIdFromCover(r) == null }
                for ((id, r) in needsDetail) {
                    val detailJson = runCatching { get("$baseUrl/api/v1/anime/$id", baseUrl) }.getOrNull() ?: continue
                    val detail = runCatching { Mapper.json.parseToJsonElement(detailJson) as? JsonObject }.getOrNull()
                    val detailAnilist = detail?.get("anilist_id")?.let { (it as? JsonPrimitive)?.intOrNull }
                    if (detailAnilist == anilistId) {
                        return@withContext buildResponse(anilistId, id, r, detail)
                    }
                }
                Logger.log("Reanime autoSearch: no match for AniList $anilistId")
                null
            } catch (e: Exception) {
                Logger.log("Reanime autoSearch error: ${e.message}")
                null
            }
        }
    }

    private fun buildTitleQueries(media: Media): List<String> = buildList {
        add(media.mainName())
        add(media.nameRomaji)
        media.synonyms?.forEach { add(it) }
    }.filter { it.isNotBlank() }.distinct()

    private fun anilistIdFromCover(r: JsonObject): Int? {
        val cover = r["cover_image"] as? JsonObject ?: return null
        val urls = listOf(
            (cover["extra_large"] as? JsonPrimitive)?.contentOrNull,
            (cover["large"] as? JsonPrimitive)?.contentOrNull,
            (cover["medium"] as? JsonPrimitive)?.contentOrNull
        ).filterNotNull()
        for (u in urls) {
            Regex("""anilist\.co/.*/bx(\d+)-""").find(u)?.let { return it.groupValues[1].toIntOrNull() }
        }
        return null
    }

    private fun buildResponse(anilistId: Int, animeId: String, r: JsonObject, detail: JsonObject?): ShowResponse {
        val titleObj = (r["title"] as? JsonObject)
        val title = (titleObj?.get("english") as? JsonPrimitive)?.contentOrNull
            ?: (titleObj?.get("romaji") as? JsonPrimitive)?.contentOrNull
            ?: (detail?.get("title") as? JsonObject)?.let { t ->
                (t["english"] as? JsonPrimitive)?.contentOrNull ?: (t["romaji"] as? JsonPrimitive)?.contentOrNull
            } ?: animeId
        val subbed = (r["subbed"] as? JsonPrimitive)?.intOrNull
            ?: (detail?.get("subbed") as? JsonPrimitive)?.intOrNull
        val dubbed = (r["dubbed"] as? JsonPrimitive)?.intOrNull
            ?: (detail?.get("dubbed") as? JsonPrimitive)?.intOrNull
        val cover = (r["cover_image"] as? JsonObject)?.let { c ->
            (c["large"] as? JsonPrimitive)?.contentOrNull ?: (c["medium"] as? JsonPrimitive)?.contentOrNull
        } ?: defaultImage
        val response = ShowResponse(
            name = title,
            link = animeId,
            coverUrl = cover,
            total = subbed ?: dubbed,
            extra = mutableMapOf(
                "anilistId" to anilistId.toString(),
                "animeId" to animeId,
                "subbed" to (subbed?.toString() ?: ""),
                "dubbed" to (dubbed?.toString() ?: "")
            )
        )
        saveShowResponse(anilistId, response)
        return response
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?, sAnime: SAnime): List<Episode> {
        if (animeLink.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val json = get("$baseUrl/api/v1/anime/$animeLink/episodes?limit=2000", baseUrl)
                val obj = Mapper.json.parseToJsonElement(json) as? JsonObject ?: return@withContext emptyList()
                val arr = obj["data"] as? JsonArray ?: return@withContext emptyList()
                val subbed = extra?.get("subbed")?.toIntOrNull()
                val dubbed = extra?.get("dubbed")?.toIntOrNull()
                arr.mapNotNull { el ->
                    val ep = el as? JsonObject ?: return@mapNotNull null
                    val num = (ep["episode_number"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                    val title = (ep["title"] as? JsonPrimitive)?.contentOrNull ?: "Episode $num"
                    val epExtra = mutableMapOf<String, String>()
                    extra?.forEach { (k, v) -> epExtra[k] = v }
                    epExtra["audio"] = if (selectDub && (dubbed ?: 0) >= num) "dub" else "sub"
                    if (selectDub && (dubbed ?: 0) < num && (subbed ?: 0) > 0) epExtra["audio"] = "sub"
                    Episode(
                        number = num.toString(),
                        link = num.toString(),
                        title = title,
                        extra = epExtra
                    )
                }.sortedBy { it.number.toIntOrNull() ?: 0 }
            } catch (e: Exception) {
                Logger.log("Reanime loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Map<String, String>?, sEpisode: SEpisode): List<VideoServer> {
        val animeId = extra?.get("animeId") ?: return emptyList()
        val anilistId = extra?.get("anilistId") ?: return emptyList()
        val epNum = episodeLink.toIntOrNull() ?: return emptyList()
        val audio = extra?.get("audio") ?: "sub"
        return withContext(Dispatchers.IO) {
            try {
                val watchJson = runCatching { get("$baseUrl/api/watch/$animeId/$epNum", baseUrl) }.getOrNull()
                val flixJson = runCatching { get("$baseUrl/api/flix/$anilistId/$epNum", baseUrl) }.getOrNull()

                val links = linkedMapOf<String, JsonObject>()
                (runCatching { Mapper.json.parseToJsonElement(watchJson ?: "") as? JsonObject }.getOrNull()
                    ?.get("episode_links") as? JsonArray)?.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val id = (o["\$id"] as? JsonPrimitive)?.contentOrNull ?: (o["dataLink"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    links[id] = o
                }
                val flixObj = runCatching { Mapper.json.parseToJsonElement(flixJson ?: "") as? JsonObject }.getOrNull()
                (flixObj?.get("servers") as? JsonArray)?.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val id = (o["\$id"] as? JsonPrimitive)?.contentOrNull ?: (o["dataLink"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    if (!links.containsKey(id)) links[id] = o
                }

                val audioTypes = if (audio == "dub") setOf("dub", "s-dub") else setOf("sub", "s-sub")
                val servers = links.values
                    .filter { (it["dataType"] as? JsonPrimitive)?.contentOrNull in audioTypes }
                    .sortedBy { (it["serverName"] as? JsonPrimitive)?.contentOrNull.let { n ->
                        when (n) { "HD-2" -> 0; "HD-1" -> 1; else -> 9 }
                    } }
                    .distinctBy { (it["dataLink"] as? JsonPrimitive)?.contentOrNull }

                Logger.log(
                    "Reanime: ep $epNum ($audio) links=${links.size} servers=${servers.size} " +
                        "flixOk=${flixObj?.get("success")}"
                )
                val results = servers.map { server ->
                    async {
                        val embedUrl = (server["dataLink"] as? JsonPrimitive)?.contentOrNull ?: return@async null
                        try {
                            val res = FlixcloudExtractor.extract(embedUrl, "$baseUrl/")
                            val resolvedUrl = res.urls.firstOrNull()
                            if (resolvedUrl == null) {
                                Logger.log("Reanime: no playable url from flixcloud $embedUrl")
                                return@async null
                            }
                            val name = (server["serverName"] as? JsonPrimitive)?.contentOrNull ?: "Reanime"
                            val extraData = mutableMapOf("referer" to "$FLIX/", "origin" to FLIX)
                            val subs = res.subtitles
                            if (subs.isNotEmpty()) {
                                val subJson = subs.joinToString(",") {
                                    "{\"url\":\"${it.url.replace("\"", "\\\"")}\",\"language\":\"${it.language}\",\"type\":\"${it.type}\"}"
                                }
                                extraData["subtitles"] = "[$subJson]"
                            }
                            res.intro?.let { (s, e) -> extraData["intro"] = """{"start":$s,"end":$e}""" }
                            res.outro?.let { (s, e) -> extraData["outro"] = """{"start":$s,"end":$e}""" }
                            VideoServer(name, resolvedUrl, extraData)
                        } catch (e: Exception) {
                            Logger.log("Reanime embed resolve failed: ${e.message}")
                            null
                        }
                    }
                }.awaitAll().filterNotNull()

                if (results.isEmpty()) {
                    Logger.log("Reanime: no resolvable servers for ep $epNum ($audio)")
                    emptyList()
                } else results
            } catch (e: Exception) {
                Logger.log("Reanime loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }
}
