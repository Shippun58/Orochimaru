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
import kotlinx.serialization.json.contentOrNull
import me.xdrop.fuzzywuzzy.FuzzySearch

/**
 * 123Anime (123animehub.cc).
 *
 * Search is an AJAX endpoint returning {html} with <div class="item"> cards.
 * Episodes are not listed on the show page, so the latest episode number ("Ep 220") from the
 * search card is used as the total (it matches the finished-show length and the current count
 * for airing shows).
 *
 * Episode lookup is `ajax/episode/info?epr=<slug>/<episode>`. Only the number directly after
 * the slug is the episode; VyseTV's `slug/<season>/<ep>` format is wrong (it silently played the
 * wrong episode). The returned target is a Vidplay-style embed, handled by EmbedRouter.
 */
class Anime123Provider : NativeAnimeParser() {

    override val name = "123Anime"
    override val saveName = "123Anime"
    override val defaultBaseUrl = "https://123animehub.cc"
    override val knownServers = listOf("123Anime")

    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    override suspend fun search(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/ajax/film/search?keyword=${encode(query)}&_=${System.currentTimeMillis()}"
            val body = get(
                url,
                mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$baseUrl/",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                )
            )
            val html = extractHtml(body)
            val shows = parseItems(html)
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
        setUserText("Searching 123Anime: ${mediaObj.mainName()}")
        return withContext(Dispatchers.IO) {
            try {
                val wantDub = selectDub
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

                    val isDub = r.extra?.get("dub") == "1"
                    score += if (isDub == wantDub) 60.0 else -20.0

                    val rSeason = seasonFromText(r.name)
                    if (mediaSeason != null && rSeason == mediaSeason) score += 40.0

                    if (score > bestScore) {
                        bestScore = score
                        best = r
                    }
                }
                Logger.log(
                    "$name autoSearch for '${mediaObj.mainName()}' (dub=$wantDub): " +
                        "picked '${best?.name ?: "none"}' (score $bestScore)"
                )
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

    private fun extractHtml(body: String): String {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) return body
        val obj = runCatching {
            Mapper.json.parseToJsonElement(trimmed) as? JsonObject
        }.getOrNull() ?: return body
        return (obj["html"] as? JsonPrimitive)?.contentOrNull ?: body
    }

    private fun parseItems(html: String): List<ShowResponse> {
        val results = linkedMapOf<String, ShowResponse>()
        val parts = html.split(Regex("""<div\s+class=["']item["']""", RegexOption.IGNORE_CASE))
        for (part in parts.drop(1)) {
            val slug = Regex("""href=["']/anime/([^"'?#]+)["']""", RegexOption.IGNORE_CASE)
                .find(part)?.groupValues?.get(1)?.trim('/')?.takeIf { it.isNotBlank() }
                ?: continue

            val nameMatch = Regex(
                """<a\b[^>]*\bclass=["'][^"']*\bname\b[^"']*["'][^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(part)
            val jTitle = Regex("""data-jtitle=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(part)?.groupValues?.get(1)?.let(::decodeEntities)?.trim().orEmpty()
            val title = nameMatch?.let { stripTags(it.groupValues[1]) }?.takeIf { it.isNotBlank() }
                ?: jTitle.takeIf { it.isNotBlank() }
                ?: slug.replace('-', ' ')

            val rawPoster = Regex("""data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(part)?.groupValues?.get(1)
                ?: Regex("""<img\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(part)?.groupValues?.get(1)
            val cover = when {
                rawPoster.isNullOrBlank() -> defaultImage
                rawPoster.contains("no_poster", ignoreCase = true) -> defaultImage
                else -> absoluteUrl("$baseUrl/", rawPoster)
            }

            val total = Regex("""Ep\s*(?:sp)?\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(part)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
            val isDub = slug.endsWith("-dub", ignoreCase = true) ||
                title.contains(Regex("""\bdub\b""", RegexOption.IGNORE_CASE)) ||
                part.contains("class=\"dub\"", ignoreCase = true)

            val otherNames = buildList {
                if (jTitle.isNotBlank() && !jTitle.equals(title, ignoreCase = true)) add(jTitle)
                Regex("""<img\b[^>]*\balt=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(part)?.groupValues?.get(1)?.let(::decodeEntities)
                    ?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }?.let { add(it) }
            }.distinct()

            val show = ShowResponse(
                name = title,
                link = slug,
                coverUrl = cover,
                otherNames = otherNames,
                total = total,
                extra = mutableMapOf(
                    "slug" to slug,
                    "title" to title,
                    "total" to (total?.toString() ?: ""),
                    "dub" to if (isDub) "1" else "0"
                )
            )
            results.putIfAbsent(slug, show)
        }
        return results.values.toList()
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?, sAnime: SAnime): List<Episode> {
        val slug = extra?.get("slug")?.takeIf { it.isNotBlank() } ?: animeLink
        if (slug.isBlank()) return emptyList()
        val title = extra?.get("title")?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
        val dubFlag = extra?.get("dub") ?: "0"
        return withContext(Dispatchers.IO) {
            try {
                val total = extra?.get("total")?.toIntOrNull()?.takeIf { it > 0 }
                    ?: lookupTotal(slug, title)
                if (total == null || total <= 0) {
                    Logger.log("$name loadEpisodes: no episode count for $slug")
                    return@withContext emptyList()
                }
                val episodes = (1..total).map { n ->
                    Episode(
                        number = n.toString(),
                        link = n.toString(),
                        title = "Episode $n",
                        extra = mutableMapOf(
                            "slug" to slug,
                            "num" to n.toString(),
                            "title" to title,
                            "dub" to dubFlag
                        )
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

    private suspend fun lookupTotal(slug: String, title: String): Int? {
        runCatching {
            search(title).firstOrNull { it.link == slug }?.total
        }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        runCatching {
            search(slug.replace('-', ' ')).firstOrNull { it.link == slug }?.total
        }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        return null
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Map<String, String>?, sEpisode: SEpisode): List<VideoServer> {
        val slug = extra?.get("slug")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val ep = extra?.get("num")?.toIntOrNull() ?: episodeLink.toIntOrNull() ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val infoUrl = "$baseUrl/ajax/episode/info?epr=${encode("$slug/$ep")}&ts=1&_=${System.currentTimeMillis()}"
                val body = get(
                    infoUrl,
                    mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$baseUrl/anime/$slug",
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    )
                )
                val info = runCatching {
                    Mapper.json.parseToJsonElement(body) as? JsonObject
                }.getOrNull()
                val target = (info?.get("target") as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.startsWith("http") }
                    ?: return@withContext emptyList()

                val resolved = EmbedRouter.resolve(target, "$baseUrl/")
                val embedOrigin = origin(target)
                val playbackHeaders = buildMap {
                    putAll(resolved.headers)
                    put("User-Agent", USER_AGENT)
                    put("Referer", "$embedOrigin/")
                    put("Origin", embedOrigin)
                }
                val extraData = mutableMapOf("referer" to target)

                val urls = resolved.urls.ifEmpty { fetchSourcesManually(target) }
                if (urls.isEmpty()) {
                    Logger.log("$name: no sources extracted from $target")
                    return@withContext emptyList()
                }
                val servers = urls.distinct().mapIndexed { index, url ->
                    val serverName = if (index == 0) "123Anime" else "123Anime ${index + 1}"
                    VideoServer(serverName, FileUrl(url, playbackHeaders), extraData)
                }
                Logger.log("$name: $slug ep $ep -> ${servers.size} server(s)")
                servers
            } catch (e: Exception) {
                Logger.log("$name loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun fetchSourcesManually(target: String): List<String> {
        val id = Regex("""/(?:embed-[^/]+)/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(target)?.groupValues?.get(1) ?: return emptyList()
        val type = Regex("""/(embed-[^/]+)/""", RegexOption.IGNORE_CASE)
            .find(target)?.groupValues?.get(1)
        val base = origin(target)
        val candidates = buildList {
            if (type != null) add("$base/$type/getSources?id=$id")
            add("$base/hs/getSources?id=$id")
        }.distinct()

        for (url in candidates) {
            val body = runCatching {
                get(
                    url,
                    mapOf(
                        "Referer" to target,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                )
            }.getOrNull() ?: continue

            val obj = runCatching {
                Mapper.json.parseToJsonElement(body) as? JsonObject
            }.getOrNull() ?: continue

            val urls = sourceUrls(obj)
            if (urls.isNotEmpty()) return urls
        }
        return emptyList()
    }

    private fun sourceUrls(obj: JsonObject): List<String> {
        val sources = obj["sources"] ?: return emptyList()
        return when (sources) {
            is JsonPrimitive -> listOfNotNull(sources.contentOrNull)
            is JsonArray -> sources.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull
                    is JsonObject -> (element["file"] as? JsonPrimitive)?.contentOrNull
                        ?: (element["src"] as? JsonPrimitive)?.contentOrNull
                        ?: (element["url"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }
            }
            else -> emptyList()
        }.filter { it.isNotBlank() }
    }
}
