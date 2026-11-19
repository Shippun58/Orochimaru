package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.media.Media
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.xdrop.fuzzywuzzy.FuzzySearch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody











class ANVProvider : NativeAnimeParser() {

    override val name = "ANV"
    override val saveName = "ANV"
    override val defaultBaseUrl = "https://anv.to"
    override val knownServers = listOf("ANV - Orange", "ANV - Pear", "ANV - Banana", "ANV - Default", "ANV")

    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    @Volatile private var shiroCookie: String? = null
    @Volatile private var shiroUntil = 0L

    override suspend fun search(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        try {
            val data = catalog("SearchAnime", buildJsonObject { put("search", query) })
            val media = data?.get("Page")?.jsonObject?.get("media")?.jsonArray
                ?: return@withContext emptyList()
            val shows = media.mapNotNull { (it as? JsonObject)?.let(::buildShow) }
            Logger.log("$name search '$query' -> ${shows.size} results")
            shows
        } catch (e: Exception) {
            Logger.log("$name search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) {
            saveShowResponse(mediaObj.id, saved, true)
            return saved
        }
        setUserText("Searching ANV: ${mediaObj.mainName()}")
        return withContext(Dispatchers.IO) {
            try {
                val queries = buildList {
                    add(mediaObj.mainName())
                    if (mediaObj.nameRomaji.isNotBlank()) add(mediaObj.nameRomaji)
                    mediaObj.synonyms.forEach { add(it) }
                }.filter { it.isNotBlank() }.distinct().take(6)

                val candidates = linkedMapOf<String, ShowResponse>()
                for (q in queries) {
                    runCatching { search(q) }.getOrDefault(emptyList())
                        .forEach { candidates.putIfAbsent(it.link, it) }
                }
                if (candidates.isEmpty()) return@withContext null

                val mediaNames = buildList {
                    add(mediaObj.mainName())
                    if (mediaObj.nameRomaji.isNotBlank()) add(mediaObj.nameRomaji)
                    mediaObj.synonyms.forEach { add(it) }
                }.filter { it.isNotBlank() }.map(::clean)

                val mediaSeason = seasonFromText("${mediaObj.mainName()} ${mediaObj.nameRomaji}")
                var best: ShowResponse? = null
                var bestScore = Double.NEGATIVE_INFINITY
                for (r in candidates.values) {
                    var score = mediaNames.maxOfOrNull {
                        FuzzySearch.ratio(r.name.lowercase(), it).toDouble()
                    } ?: 0.0
                    r.otherNames.forEach { n ->
                        val cleaned = clean(n)
                        if (cleaned.isNotEmpty() && cleaned in mediaNames) score = maxOf(score, 100.0)
                    }
                    if (mediaObj.idMAL != null && r.extra?.get("idMal")?.toIntOrNull() == mediaObj.idMAL) {
                        score += 1000.0
                    }
                    val rSeason = seasonFromText(r.name)
                    if (mediaSeason != null && rSeason == mediaSeason) score += 40.0
                    if (score > bestScore) {
                        bestScore = score
                        best = r
                    }
                }
                Logger.log("$name autoSearch for '${mediaObj.mainName()}': picked '${best?.name ?: "none"}' (score $bestScore)")
                best?.let { saveShowResponse(mediaObj.id, it) }
                best
            } catch (e: Exception) {
                Logger.log("$name autoSearch error: ${e.message}")
                null
            }
        }
    }

    private fun clean(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun buildShow(o: JsonObject): ShowResponse? {
        val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return null
        val idMal = (o["idMal"] as? JsonPrimitive)?.intOrNull
        val titleObj = o["title"] as? JsonObject
        val title = (titleObj?.get("english") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (titleObj?.get("romaji") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (titleObj?.get("native") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "ANV $id"
        val cover = (o["coverImage"] as? JsonObject)?.get("large")
            ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: defaultImage
        val total = (o["episodes"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            ?: (o["nextAiringEpisode"] as? JsonObject)?.get("episode")
                ?.let { (it as? JsonPrimitive)?.intOrNull?.minus(1)?.takeIf { n -> n > 0 } }
        val otherNames = buildList {
            (o["synonyms"] as? JsonArray)?.forEach { s ->
                (s as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            (titleObj?.get("romaji") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() && it != title }?.let { add(it) }
        }.distinct()

        return ShowResponse(
            name = title,
            link = id.toString(),
            coverUrl = cover,
            otherNames = otherNames,
            total = total,
            extra = mutableMapOf(
                "id" to id.toString(),
                "idMal" to (idMal?.toString() ?: ""),
                "total" to (total?.toString() ?: "")
            )
        )
    }

    private fun catalog(operation: String, variables: JsonObject): JsonObject? {
        val payload = buildJsonObject {
            put("operation", operation)
            put("variables", variables)
        }.toString()
        val request = Request.Builder().url("$baseUrl/api/catalog")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Logger.log("$name catalog HTTP ${response.code}: ${body.take(200)}")
                return null
            }
            val root = parseJson(body) ?: return null
            val errors = root["errors"] as? JsonArray
            if (errors != null && errors.isNotEmpty()) {
                Logger.log("$name catalog error: ${errors.firstOrNull()?.toString()?.take(200)}")
                return null
            }
            return root["data"] as? JsonObject
        }
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?, sAnime: SAnime): List<Episode> {
        val id = extra?.get("id")?.toIntOrNull() ?: animeLink.toIntOrNull() ?: return emptyList()
        val idMal = extra?.get("idMal")?.toIntOrNull()
        val fallbackTotal = extra?.get("total")?.toIntOrNull() ?: 0
        val audio = currentAudio()
        return withContext(Dispatchers.IO) {
            try {
                val media = catalog("AnimeDetail", buildJsonObject { put("id", id) })
                    ?.get("Media") as? JsonObject

                val streaming = media?.get("streamingEpisodes") as? JsonArray
                val episodes = mutableListOf<Episode>()
                if (streaming != null && streaming.isNotEmpty()) {
                    var index = 0
                    for (element in streaming) {
                        index++
                        val obj = element as? JsonObject ?: continue
                        val rawTitle = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val number = Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE)
                            .find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: index
                        val title = Regex("""^Episode\s+\d+\s*(?:—|-|:)\s*""", RegexOption.IGNORE_CASE)
                            .replace(rawTitle, "")
                            .trim()
                            .ifBlank { "Episode $number" }
                        val thumb = (obj["thumbnail"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: defaultImage
                        val filler = (obj["filler"] as? JsonPrimitive)?.booleanOrNull ?: false
                        episodes.add(
                            Episode(
                                number = number.toString(),
                                link = number.toString(),
                                title = title,
                                thumbnail = thumb,
                                isFiller = filler,
                                extra = episodeExtra(id, idMal, number, audio)
                            )
                        )
                    }
                    episodes.sortBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
                } else {
                    val total = (media?.get("episodes") as? JsonPrimitive)?.intOrNull
                        ?.takeIf { it > 0 } ?: fallbackTotal
                    Logger.log("$name loadEpisodes: no streaming list, falling back to $total episodes")
                    for (n in 1..total) {
                        episodes.add(
                            Episode(
                                number = n.toString(),
                                link = n.toString(),
                                title = "Episode $n",
                                extra = episodeExtra(id, idMal, n, audio)
                            )
                        )
                    }
                }
                Logger.log("$name loadEpisodes: id $id -> ${episodes.size} episodes ($audio)")
                episodes
            } catch (e: Exception) {
                Logger.log("$name loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun episodeExtra(id: Int, idMal: Int?, number: Int, audio: String) = mutableMapOf(
        "id" to id.toString(),
        "idMal" to (idMal?.toString() ?: ""),
        "num" to number.toString(),
        "audio" to audio
    )

    private fun currentAudio(): String = if (selectDub) "dub" else "sub"

    override suspend fun loadVideoServers(episodeLink: String, extra: Map<String, String>?, sEpisode: SEpisode): List<VideoServer> {
        val id = extra?.get("id")?.toIntOrNull() ?: return emptyList()
        val idMal = extra?.get("idMal")?.toIntOrNull()
        val ep = extra?.get("num")?.toIntOrNull() ?: episodeLink.toIntOrNull() ?: return emptyList()
        val audio = extra?.get("audio")?.takeIf { it.isNotBlank() } ?: currentAudio()
        val wantedDub = audio == "dub"
        return withContext(Dispatchers.IO) {
            try {
                val cookie = anvCookie()
                if (cookie.isEmpty()) {
                    Logger.log("$name: could not obtain shiro_watch cookie, playback will fail")
                }
                val data = postEpisode(id, idMal, ep, cookie) ?: return@withContext emptyList()
                val status = (data["status"] as? JsonPrimitive)?.contentOrNull
                if (status != "ready") {
                    Logger.log("$name: ep $ep unavailable for id $id (status=$status)")
                    return@withContext emptyList()
                }
                val variants = data["variants"] as? JsonArray ?: return@withContext emptyList()
                if (variants.isEmpty()) return@withContext emptyList()

                val matching = variants.filter { isDubVariant(it) == wantedDub }
                val chosen = matching.ifEmpty { variants.toList() }

                val playbackHeaders = buildMap {
                    put("Referer", "$baseUrl/")
                    put("Origin", baseUrl)
                    put("User-Agent", USER_AGENT)
                    if (cookie.isNotEmpty()) put("Cookie", cookie)
                }

                val servers = mutableListOf<VideoServer>()
                val seenUrls = mutableSetOf<String>()
                for (variant in chosen) {
                    val vo = variant as? JsonObject ?: continue
                    val sources = vo["sources"] as? JsonArray ?: continue
                    for (element in sources) {
                        val so = element as? JsonObject ?: continue
                        val raw = (so["url"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: continue
                        val full = absoluteUrl("$baseUrl/", raw)
                        if (!seenUrls.add(full)) continue
                        val label = (so["label"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: "Default"
                        val serverName = "ANV - $label"

                        val extraData = mutableMapOf("referer" to "$baseUrl/")
                        subtitleJson(so, cookie)?.let { extraData["subtitles"] = it }

                        if (isDirectMedia(full)) {
                            servers.add(VideoServer(serverName, FileUrl(full, playbackHeaders), extraData))
                        } else {
                            val resolved = EmbedRouter.resolve(full, "$baseUrl/")
                            resolved.urls.forEachIndexed { index, url ->
                                val name = if (index == 0) serverName else "$serverName ${index + 1}"
                                servers.add(VideoServer(name, FileUrl(url, playbackHeaders), extraData))
                            }
                        }
                    }
                }
                Logger.log("$name: ep $ep ($audio) -> ${servers.size} server(s)")
                servers
            } catch (e: Exception) {
                Logger.log("$name loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun isDirectMedia(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) || url.contains(".mp4", ignoreCase = true)

    private fun isDubVariant(v: JsonElement): Boolean {
        val o = v as? JsonObject ?: return false
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
        val label = (o["label"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
        return id == "dub" || id.contains("dub") || label.contains("dub")
    }

    private fun subtitleJson(source: JsonObject, cookie: String): String? {
        val arr = (source["tracks"] ?: source["subtitles"]) as? JsonArray ?: return null
        val headerObj = buildJsonObject {
            put("User-Agent", USER_AGENT)
            put("Referer", "$baseUrl/")
            put("Origin", baseUrl)
            if (cookie.isNotEmpty()) put("Cookie", cookie)
        }
        val headersJson = headerObj.toString()
        val subs = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val raw = (o["src"] as? JsonPrimitive)?.contentOrNull
                ?: (o["url"] as? JsonPrimitive)?.contentOrNull
                ?: (o["file"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            val full = absoluteUrl("$baseUrl/", raw)
            val lang = (o["label"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (o["language"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "English"
            val type = (o["type"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty().ifBlank {
                if (full.contains(".ass")) "ass" else if (full.contains(".srt")) "srt" else "vtt"
            }
            "{\"url\":\"${escape(full)}\",\"language\":\"${escape(lang)}\",\"type\":\"$type\",\"headers\":$headersJson}"
        }
        return if (subs.isEmpty()) null else "[${subs.joinToString(",")}]"
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private suspend fun anvCookie(): String {
        val now = System.currentTimeMillis()
        shiroCookie?.let { if (now < shiroUntil) return it }
        return withContext(Dispatchers.IO) {
            var cookie = ""
            runCatching {
                var target = "$baseUrl/anime/aoki-denshou-welsh-shedar-626fd-episode-5"
                runCatching {
                    val xml = get("$baseUrl/recents.xml", "$baseUrl/", "application/rss+xml,application/xml,text/xml,*/*")
                    Regex("<link>(https://anv\\.to/anime/[^<]+)</link>")
                        .find(xml)?.groupValues?.get(1)?.let { target = it }
                }
                val request = Request.Builder().url(target)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Referer", "$baseUrl/")
                    .get().build()
                okHttpClient.newCall(request).execute().use { response ->
                    val setCookie = response.headers("Set-Cookie")
                        .firstOrNull { it.contains("shiro_watch=") }
                    cookie = if (setCookie != null) {
                        val value = Regex("shiro_watch=([^;]+)").find(setCookie)?.groupValues?.get(1).orEmpty()
                        if (value.isNotEmpty()) "shiro_watch=$value" else ""
                    } else {
                        jarCookie()
                    }
                }
            }
            if (cookie.isEmpty()) cookie = jarCookie()
            if (cookie.isNotEmpty()) {
                shiroCookie = cookie
                shiroUntil = now + 20L * 60 * 60 * 1000
            }
            cookie
        }
    }

    private fun jarCookie(): String = runCatching {
        okHttpClient.cookieJar.loadForRequest("$baseUrl/".toHttpUrl())
            .joinToString("; ") { "${it.name}=${it.value}" }
    }.getOrDefault("")

    private fun postEpisode(anilistId: Int, malId: Int?, episode: Int, cookie: String): JsonObject? {
        val body = buildJsonObject {
            put("anilistId", anilistId)
            if (malId != null) put("malId", malId) else put("malId", JsonNull)
            put("episode", episode)
        }.toString()
        val request = Request.Builder().url("$baseUrl/api/episode")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "*/*")
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Logger.log("$name episode API HTTP ${response.code}: ${text.take(200)}")
                return null
            }
            return parseJson(text)
        }
    }

    private fun parseJson(text: String): JsonObject? = runCatching {
        Mapper.json.parseToJsonElement(text) as? JsonObject
    }.getOrNull()
}
