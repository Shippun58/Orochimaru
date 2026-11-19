package ani.sanin.parsers

import ani.sanin.FileUrl
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.xdrop.fuzzywuzzy.FuzzySearch













class AniZoneProvider : NativeAnimeParser() {

    override val name = "AniZone"
    override val saveName = "AniZone"
    override val defaultBaseUrl = "https://anizone.to"
    override val knownServers = listOf("AniZone")

    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = false

    override suspend fun search(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        try {
            val html = get(
                "$baseUrl/anime?search=${encode(query)}",
                "$baseUrl/",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            val shows = parseSearchItems(html).mapNotNull(::buildShow)
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
        setUserText("Searching AniZone: ${mediaObj.mainName()}")
        return withContext(Dispatchers.IO) {
            try {
                val mediaNames = buildList {
                    add(mediaObj.mainName())
                    if (mediaObj.nameRomaji.isNotBlank()) add(mediaObj.nameRomaji)
                    mediaObj.synonyms.forEach { add(it) }
                }.filter { it.isNotBlank() }.map(::clean).distinct()
                val mediaSeason = seasonFromText("${mediaObj.mainName()} ${mediaObj.nameRomaji}")

                val queries = buildList {
                    add(mediaObj.mainName())
                    add(searchableQuery(mediaObj.mainName()))
                    if (mediaObj.nameRomaji.isNotBlank()) {
                        add(mediaObj.nameRomaji)
                        add(searchableQuery(mediaObj.nameRomaji))
                    }
                }.filter { it.isNotBlank() }.distinct()

                val candidates = linkedMapOf<String, ShowResponse>()
                for (q in queries) {
                    runCatching { search(q) }.getOrDefault(emptyList())
                        .forEach { candidates.putIfAbsent(it.link, it) }
                }
                if (candidates.isEmpty()) return@withContext null

                var best: ShowResponse? = null
                var bestScore = Double.NEGATIVE_INFINITY
                for (r in candidates.values) {
                    val names = (listOf(r.name) + r.otherNames).map(::clean)
                    var score = mediaNames.maxOfOrNull { mn ->
                        names.maxOfOrNull { FuzzySearch.ratio(it, mn).toDouble() } ?: 0.0
                    } ?: 0.0
                    if (mediaNames.any { it in names }) score += 100.0
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
        val slug = o.string("slug")?.takeIf { it.isNotBlank() } ?: return null
        val name = o.string("main_title")?.takeIf { it.isNotBlank() } ?: slug
        val cover = (o.string("cover") ?: defaultImage).replace("http://", "https://")
        val total = o.int("episode_count")?.takeIf { it > 0 }
        val titles = (o["title_list"] as? JsonObject)?.values
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        val otherNames = titles.filterNot { it.equals(name, ignoreCase = true) }.take(20)

        return ShowResponse(
            name = name,
            link = slug,
            coverUrl = cover,
            otherNames = otherNames,
            total = total,
            extra = mutableMapOf(
                "slug" to slug,
                "total" to (total?.toString() ?: "")
            )
        )
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?, sAnime: SAnime): List<Episode> {
        val slug = extra?.get("slug")?.takeIf { it.isNotBlank() } ?: animeLink
        val knownTotal = extra?.get("total")?.toIntOrNull()?.takeIf { it > 0 }
        return withContext(Dispatchers.IO) {
            try {
                var titles: Map<Int, String> = emptyMap()
                runCatching {
                    val html = get(
                        "$baseUrl/anime/$slug",
                        "$baseUrl/",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                    titles = parseEpisodeTitles(html)
                }.onFailure { Logger.log("$name loadEpisodes title fetch failed: ${it.message}") }

                val total = knownTotal ?: titles.keys.maxOrNull() ?: 0
                if (total <= 0) {
                    Logger.log("$name loadEpisodes: no episode count for $slug")
                    return@withContext emptyList()
                }
                val episodes = (1..total).map { n ->
                    Episode(
                        number = n.toString(),
                        link = n.toString(),
                        title = titles[n] ?: "Episode $n",
                        extra = mutableMapOf("slug" to slug, "num" to n.toString())
                    )
                }
                Logger.log("$name loadEpisodes: $slug -> ${episodes.size} episodes")
                episodes
            } catch (e: Exception) {
                Logger.log("$name loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Map<String, String>?, sEpisode: SEpisode): List<VideoServer> {
        val slug = extra?.get("slug")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val ep = extra?.get("num")?.takeIf { it.isNotBlank() } ?: episodeLink
        return withContext(Dispatchers.IO) {
            try {
                val html = get(
                    "$baseUrl/anime/$slug/$ep",
                    "$baseUrl/anime/$slug",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                val headers = mapOf(
                    "Referer" to "$baseUrl/",
                    "Origin" to baseUrl,
                    "User-Agent" to USER_AGENT
                )
                val extraData = mutableMapOf("referer" to "$baseUrl/")

                val player = Regex("""vidstackPlayer\s*\(\s*JSON\.parse\('(.*?)'\)\s*\)""", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.let { parseObject(it.groupValues[1]) }

                val servers = mutableListOf<VideoServer>()
                val src = player?.string("src")?.takeIf { it.isNotBlank() }
                if (src != null) {
                    player?.let { subtitleJson(it) }?.let { extraData["subtitles"] = it }
                    val full = absoluteUrl("$baseUrl/", src)
                    if (isDirectMedia(full)) {
                        servers.add(VideoServer("AniZone", FileUrl(full, headers), extraData))
                    } else {
                        EmbedRouter.resolve(full, "$baseUrl/").urls.forEachIndexed { index, url ->
                            servers.add(VideoServer(if (index == 0) "AniZone" else "AniZone ${index + 1}", FileUrl(url, headers), extraData))
                        }
                    }
                }

                if (servers.isEmpty()) {
                    val fallback = hlsUrls(html).firstOrNull()
                    if (fallback != null) {
                        servers.add(VideoServer("AniZone", FileUrl(absoluteUrl("$baseUrl/", fallback), headers), extraData))
                    }
                }
                Logger.log("$name: $slug ep $ep -> ${servers.size} server(s)")
                servers
            } catch (e: Exception) {
                Logger.log("$name loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun isDirectMedia(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) || url.contains(".mp4", ignoreCase = true)

    private fun subtitleJson(player: JsonObject): String? {
        val arr = player["subtitles"] as? JsonArray ?: return null
        val headerObj = buildJsonObject {
            put("Referer", "$baseUrl/")
            put("Origin", baseUrl)
            put("User-Agent", USER_AGENT)
        }.toString()
        val subs = arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val raw = o.string("file") ?: o.string("src") ?: o.string("url") ?: return@mapNotNull null
            val full = absoluteUrl("$baseUrl/", raw)
            val lang = o.string("title") ?: o.string("language") ?: "English"
            val type = o.string("format")?.lowercase()?.ifBlank { null }
                ?: if (full.contains(".ass")) "ass" else if (full.contains(".srt")) "srt" else "vtt"
            "{\"url\":\"${escape(full)}\",\"language\":\"${escape(lang)}\",\"type\":\"$type\",\"headers\":$headerObj}"
        }
        return if (subs.isEmpty()) null else "[${subs.joinToString(",")}]"
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun parseSearchItems(html: String): List<JsonObject> {
        val match = Regex("""items\s*:\s*JSON\.parse\('(.*?)'\)""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return emptyList()
        return parseArray(match.groupValues[1]).orEmpty().mapNotNull { it as? JsonObject }
    }

    private fun parseEpisodeTitles(html: String): Map<Int, String> {
        val result = linkedMapOf<Int, String>()
        Regex("""JSON\.parse\('(.*?)'\)""", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { match ->
            val arr = parseArray(match.groupValues[1]) ?: return@forEach
            for (element in arr) {
                val o = element as? JsonObject ?: continue
                val num = o.string("slug")?.toIntOrNull() ?: continue
                if (!o.containsKey("title_list") || !o.containsKey("url")) continue
                val titleList = o["title_list"] as? JsonObject
                val title = (titleList?.get("1") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: titleList?.values?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                if (title != null) result.putIfAbsent(num, title)
            }
        }
        return result
    }

    private fun parseArray(jsLiteral: String): JsonArray? = runCatching {
        Mapper.json.parseToJsonElement(jsUnescape(jsLiteral)) as? JsonArray
    }.getOrNull()

    private fun parseObject(jsLiteral: String): JsonObject? = runCatching {
        Mapper.json.parseToJsonElement(jsUnescape(jsLiteral)) as? JsonObject
    }.getOrNull()





    private fun jsUnescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                out.append(c)
                i++
                continue
            }
            when (val n = value[i + 1]) {
                'u' -> {
                    val hex = value.substring(i + 2, minOf(i + 6, value.length))
                    val cp = if (hex.length == 4) hex.toIntOrNull(16) else null
                    if (cp != null) {
                        out.append(cp.toChar())
                        i += 6
                    } else {
                        out.append(n)
                        i += 2
                    }
                }
                'x' -> {
                    val hex = value.substring(i + 2, minOf(i + 4, value.length))
                    val cp = if (hex.length == 2) hex.toIntOrNull(16) else null
                    if (cp != null) {
                        out.append(cp.toChar())
                        i += 4
                    } else {
                        out.append(n)
                        i += 2
                    }
                }
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                '\\', '/', '\'', '"' -> { out.append(n); i += 2 }
                else -> { out.append(n); i += 2 }
            }
        }
        return out.toString()
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
