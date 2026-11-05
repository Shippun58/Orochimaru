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
import me.xdrop.fuzzywuzzy.FuzzySearch

class AniWavesProvider : NativeAnimeParser() {

    override val name = "AniWaves"
    override val saveName = "AniWaves"
    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    override val defaultBaseUrl = "https://aniwaves.ru"
    override val knownServers = listOf("AniWaves", "VidPlay", "MegaPlay", "Byse", "Nova", "DataSv", "BabaStream", "AnimeSalt", "Vidmoly")

    override suspend fun search(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        try {
            val html = get("$baseUrl/filter?keyword=${encode(query)}", "$baseUrl/", "text/html,*/*")
            parseSearch(html)
        } catch (e: Exception) {
            Logger.log("AniWaves search error: ${e.message}")
            emptyList()
        }
    }

    private fun parseSearch(html: String): List<ShowResponse> {
        val found = linkedMapOf<String, ShowResponse>()
        val re = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in re.findAll(html)) {
            val attrs = m.groupValues[1]
            if (!Regex("""class=["'][^"']*\bname\b[^"']*\bd-title\b""", RegexOption.IGNORE_CASE).containsMatchIn(attrs)) continue
            val href = attr(attrs, "href")
            val slugMatch = Regex("""/watch/([a-z0-9-]+)$""", RegexOption.IGNORE_CASE).find(href) ?: continue
            val slug = slugMatch.groupValues[1]
            if (found.containsKey(slug)) continue
            val title = stripTags(m.groupValues[2])
            if (title.isBlank()) continue
            val siteId = Regex("""-(\d+)$""").find(slug)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            found[slug] = ShowResponse(
                name = title,
                link = slug,
                coverUrl = defaultImage,
                extra = mutableMapOf("slug" to slug, "siteId" to siteId.toString())
            )
        }
        return found.values.toList()
    }

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) {
            saveShowResponse(mediaObj.id, saved, true)
            return saved
        }
        setUserText("Searching AniWaves: ${mediaObj.mainName()}")
        return withContext(Dispatchers.IO) {
            try {
                val queries = buildList {
                    add(mediaObj.mainName())
                    if (mediaObj.nameRomaji.isNotBlank()) add(mediaObj.nameRomaji)
                    mediaObj.synonyms?.forEach { add(it) }
                }.filter { it.isNotBlank() }.distinct().take(5)

                val candidates = linkedMapOf<String, ShowResponse>()
                for (q in queries) {
                    runCatching { parseSearch(get("$baseUrl/filter?keyword=${encode(q)}", "$baseUrl/", "text/html,*/*")) }
                        .getOrDefault(emptyList()).forEach { candidates.putIfAbsent(it.link, it) }
                }
                val best = candidates.values.map { it to FuzzySearch.ratio(it.name.lowercase(), mediaObj.mainName().lowercase()) }
                    .maxByOrNull { it.second }?.first ?: return@withContext null
                saveShowResponse(mediaObj.id, best)
                best
            } catch (e: Exception) {
                Logger.log("AniWaves autoSearch error: ${e.message}")
                null
            }
        }
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?, sAnime: SAnime): List<Episode> {
        val slug = extra?.get("slug") ?: animeLink
        return withContext(Dispatchers.IO) {
            try {
                val html = get("$baseUrl/watch/$slug", "$baseUrl/", "text/html,*/*")

                val count = Regex("""<div>\s*Episodes?:\s*<span[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE).find(html)
                    ?.groupValues?.get(1)?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                    ?: Regex("""Episodes?[:\s]*(\d+)""", RegexOption.IGNORE_CASE).find(html)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    ?: run {
                        val maxEp = Regex("""data-(?:number|num|ep)="(\d+)"""", RegexOption.IGNORE_CASE).findAll(html)
                            .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                        maxEp ?: 0
                    }
                if (count <= 0) return@withContext emptyList()
                val siteId = extra?.get("siteId") ?: ""
                val episodes = (1..count).map { num ->
                    Episode(
                        number = num.toString(),
                        link = num.toString(),
                        title = "Episode $num",
                        extra = mutableMapOf("slug" to slug, "siteId" to siteId, "num" to num.toString())
                    )
                }
                episodes
            } catch (e: Exception) {
                Logger.log("AniWaves loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Map<String, String>?, sEpisode: SEpisode): List<VideoServer> {
        val slug = extra?.get("slug") ?: return emptyList()
        val siteId = extra?.get("siteId") ?: ""
        val epNum = extra?.get("num") ?: episodeLink
        return withContext(Dispatchers.IO) {
            try {
                val referer = "$baseUrl/watch/$slug/ep-$epNum"
                val headers = mapOf(
                    "Referer" to referer,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                )
                val serverJson = get("$baseUrl/ajax/server/list?servers=${encode(siteId)}&eps=$epNum", headers)
                val serverResult = (parseJson(serverJson)?.get("result") as? JsonPrimitive)?.contentOrNull.orEmpty()
                val servers = parseServerGroups(serverResult)
                Logger.log("AniWaves: ${servers.size} servers for ep $epNum")
                if (servers.isEmpty()) return@withContext emptyList()

                val results = servers.map { server ->
                    async {
                        try {
                            val sourceJson = get("$baseUrl/ajax/sources?id=${encode(server.linkId)}&asi=0&autoPlay=0", headers)
                            val sourceObj = parseJson(sourceJson)?.get("result") as? JsonObject
                            val embedUrl = (sourceObj?.get("url") as? JsonPrimitive)?.contentOrNull ?: return@async null
                            val skipData = sourceObj?.get("skip_data") as? JsonObject
                            val res = EmbedRouter.resolve(embedUrl, "$baseUrl/")
                            val resolvedUrl = res.urls.firstOrNull()
                            if (resolvedUrl == null) {
                                Logger.log("AniWaves: no playable url for ${server.serverName} embed=$embedUrl")
                                return@async null
                            }
                            val extraData = mutableMapOf("referer" to "$baseUrl/")
                            val subs = res.subtitles
                            if (subs.isNotEmpty()) {
                                val subJson = subs.joinToString(",") {
                                    "{\"url\":\"${it.url.replace("\"", "\\\"")}\",\"language\":\"${it.language}\",\"type\":\"${it.type}\"}"
                                }
                                extraData["subtitles"] = "[$subJson]"
                            }

                            skipData?.get("intro")?.let { arr ->
                                val a = arr as? JsonArray
                                val s = (a?.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@let
                                val e = (a?.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@let
                                if (e > s) extraData["intro"] = """{"start":$s,"end":$e}"""
                            }
                            skipData?.get("outro")?.let { arr ->
                                val a = arr as? JsonArray
                                val s = (a?.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@let
                                val e = (a?.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@let
                                if (e > s) extraData["outro"] = """{"start":$s,"end":$e}"""
                            }
                            VideoServer(
                                name = server.serverName.ifEmpty { server.audio },
                                embed = FileUrl(resolvedUrl),
                                extraData = extraData
                            )
                        } catch (e: Exception) {
                            Logger.log("AniWaves source failed: ${e.message}")
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
                results
            } catch (e: Exception) {
                Logger.log("AniWaves loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun parseServerGroups(html: String): List<AniWavesServer> {
        val servers = mutableListOf<AniWavesServer>()
        val re = Regex("""<div class="type" data-type="(\w+)">([\s\S]*?)</ul>\s*</div>""")
        val groups = re.findAll(html)
        if (!groups.iterator().hasNext()) {

            for (m in Regex("""<li\b([^>]*)>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE).findAll(html)) {
                val lid = Regex("""data-link-id="([^"]+)"""").find(m.groupValues[1])?.groupValues?.get(1) ?: continue
                val name = stripTags(m.groupValues[2])
                servers.add(AniWavesServer("", lid, name))
            }
            return servers
        }
        for (g in groups) {
            val audio = g.groupValues[1]
            for (li in Regex("""<li\b([^>]*data-link-id[^>]*)>([\s\S]*?)</li>""").findAll(g.groupValues[2])) {
                val lid = Regex("""data-link-id="([^"]+)"""").find(li.groupValues[1])?.groupValues?.get(1) ?: continue
                val name = stripTags(li.groupValues[2])
                servers.add(AniWavesServer(audio, lid, name))
            }
        }
        return servers
    }

    private fun parseJson(s: String): JsonObject? = runCatching {
        Mapper.json.parseToJsonElement(s) as? JsonObject
    }.getOrNull()

    private data class AniWavesServer(val audio: String, val linkId: String, val serverName: String)
}
