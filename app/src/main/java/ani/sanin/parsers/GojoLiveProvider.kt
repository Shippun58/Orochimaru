package ani.sanin.parsers

import android.util.Base64
import ani.sanin.Mapper
import ani.sanin.media.Media
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class GojoLiveProvider : NativeAnimeParser() {

    override val name = "GojoLive"
    override val saveName = "GojoLive"
    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    override val defaultBaseUrl = "https://gojolive.site"
    override val knownServers = listOf("MegaPlay", "Vidnest", "Anitaku")

    private val aniListUrl = "https://graphql.anilist.co"

    private val searchQuery =
        "query (\$search: String) { Page(perPage: 25) { media(search: \$search, type: ANIME, isAdult: false) " +
            "{ id idMal episodes format status title { english romaji native } coverImage { large } } } }"

    private val idQuery =
        "query (\$id: Int) { Media(id: \$id, type: ANIME) { id idMal episodes format status " +
            "title { english romaji native } coverImage { large } nextAiringEpisode { episode } } }"

    override suspend fun search(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        try {
            val root = anilist(searchQuery, mapOf("search" to JsonPrimitive(query))) ?: return@withContext emptyList()
            val media = root["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media")?.jsonArray
                ?: return@withContext emptyList()
            val shows = media.mapNotNull { buildShow(it as? JsonObject ?: return@mapNotNull null) }
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
        setUserText("Searching GojoLive: ${mediaObj.mainName()}")
        val direct = withContext(Dispatchers.IO) {
            try {
                val root = anilist(idQuery, mapOf("id" to JsonPrimitive(mediaObj.id)))
                val m = root?.get("data")?.jsonObject?.get("Media")?.jsonObject
                m?.let { buildShow(it) }
            } catch (e: Exception) {
                Logger.log("$name autoSearch (AniList ${mediaObj.id}) error: ${e.message}")
                null
            }
        }
        if (direct != null) {
            Logger.log("$name autoSearch matched AniList ${mediaObj.id} -> '${direct.name}'")
            saveShowResponse(mediaObj.id, direct)
            return direct
        }
        Logger.log("$name autoSearch: no AniList entry for ${mediaObj.id}, falling back to text search")
        return super.autoSearch(mediaObj)
    }

    private fun buildShow(o: JsonObject): ShowResponse? {
        val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return null
        val idMal = (o["idMal"] as? JsonPrimitive)?.intOrNull
        val titleObj = o["title"] as? JsonObject
        val title = (titleObj?.get("english") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (titleObj?.get("romaji") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (titleObj?.get("native") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "AniList $id"
        val cover = (o["coverImage"] as? JsonObject)?.get("large")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: defaultImage
        val episodes = (o["episodes"] as? JsonPrimitive)?.intOrNull
            ?: (o["nextAiringEpisode"] as? JsonObject)?.get("episode")?.let { (it as? JsonPrimitive)?.intOrNull?.minus(1) }
        return ShowResponse(
            name = title,
            link = id.toString(),
            coverUrl = cover,
            total = episodes?.takeIf { it > 0 },
            extra = mutableMapOf(
                "anilistId" to id.toString(),
                "idMal" to (idMal?.toString() ?: ""),
                "episodes" to (episodes?.takeIf { it > 0 }?.toString() ?: "")
            )
        )
    }

    private fun anilist(query: String, variables: Map<String, JsonElement>): JsonObject? {
        val payload = buildJsonObject {
            put("query", query)
            putJsonObject("variables") { variables.forEach { (k, v) -> put(k, v) } }
        }.toString()
        val request = Request.Builder().url(aniListUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Logger.log("$name AniList HTTP ${response.code}: ${body.take(200)}")
                return null
            }
            return Mapper.json.parseToJsonElement(body) as? JsonObject
        }
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?, sAnime: SAnime): List<Episode> {
        val anilistId = extra?.get("anilistId")?.toIntOrNull() ?: animeLink.toIntOrNull() ?: return emptyList()
        val idMal = extra?.get("idMal")?.toIntOrNull()
        return withContext(Dispatchers.IO) {
            val total = extra?.get("episodes")?.toIntOrNull()?.takeIf { it > 0 }
                ?: fetchEpisodeCount(anilistId)
                ?: 1
            val audio = currentAudio()
            Logger.log("$name loadEpisodes: AniList $anilistId -> $total episodes ($audio)")
            (1..total).map { n ->
                Episode(
                    number = n.toString(),
                    link = n.toString(),
                    title = "Episode $n",
                    extra = mutableMapOf(
                        "anilistId" to anilistId.toString(),
                        "idMal" to (idMal?.toString() ?: ""),
                        "audio" to audio,
                        "num" to n.toString()
                    )
                )
            }
        }
    }

    private fun fetchEpisodeCount(anilistId: Int): Int? {
        runCatching {
            val root = anilist(idQuery, mapOf("id" to JsonPrimitive(anilistId)))
            val m = root?.get("data")?.jsonObject?.get("Media")?.jsonObject ?: return@runCatching
            (m["episodes"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let { return it }
            ((m["nextAiringEpisode"] as? JsonObject)?.get("episode") as? JsonPrimitive)?.intOrNull
                ?.let { return (it - 1).coerceAtLeast(1) }
        }

        runCatching {
            val json = get("https://api.ani.zip/mappings?anilist_id=$anilistId", "$defaultBaseUrl/")
            val eps = (Mapper.json.parseToJsonElement(json) as? JsonObject)?.get("episodes")?.jsonObject
                ?: return@runCatching
            val max = eps.keys.mapNotNull { it.toIntOrNull() }.maxOrNull()
            if (max != null && max > 0) return max
        }
        return null
    }

    private fun currentAudio(): String = if (selectDub) "dub" else "sub"

    override suspend fun loadVideoServers(episodeLink: String, extra: Map<String, String>?, sEpisode: SEpisode): List<VideoServer> {
        val anilistId = extra?.get("anilistId")?.toIntOrNull() ?: return emptyList()
        val idMal = extra?.get("idMal")?.toIntOrNull()
        val ep = extra?.get("num")?.toIntOrNull() ?: episodeLink.toIntOrNull() ?: return emptyList()
        val audio = extra?.get("audio")?.takeIf { it.isNotBlank() } ?: currentAudio()
        Logger.log("$name loadVideoServers: AniList $anilistId ep $ep ($audio) idMal=$idMal -> listing lazily")
        val servers = listOf(
            megaPlayLazy(anilistId, idMal, ep, audio),
            vidnestLazy("Vidnest", "/aniwave_hls/$anilistId/$ep/$audio", VIDNEST_REFERER, anilistId, idMal, ep, audio),
            vidnestLazy("Anitaku", "/hianime/anime/$anilistId/$ep/$audio/hd-2", MEGAPLAY_REFERER, anilistId, idMal, ep, audio)
        )
        Logger.log("$name: ep $ep ($audio) -> ${servers.size} lazily-listed server(s): ${servers.joinToString { it.name }}")
        return servers
    }

    private fun megaPlayLazy(anilistId: Int, idMal: Int?, ep: Int, audio: String): VideoServer {
        val embed = "$MEGAPLAY_ORIGIN/stream/ani/$anilistId/$ep/$audio"
        val extraData = mutableMapOf(
            "kind" to "megaplay",
            "anilistId" to anilistId.toString(),
            "idMal" to (idMal?.toString() ?: ""),
            "num" to ep.toString(),
            "audio" to audio
        )
        return VideoServer("MegaPlay", embed, extraData)
    }

    private fun vidnestLazy(
        name: String,
        path: String,
        defaultReferer: String,
        anilistId: Int,
        idMal: Int?,
        ep: Int,
        audio: String
    ): VideoServer {
        val extraData = mutableMapOf(
            "kind" to "vidnest",
            "path" to path,
            "referer" to defaultReferer,
            "anilistId" to anilistId.toString(),
            "idMal" to (idMal?.toString() ?: ""),
            "num" to ep.toString(),
            "audio" to audio
        )
        return VideoServer(name, "$VIDNEST_BASE$path", extraData)
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor =
        when (server.extraData?.get("kind")) {
            "megaplay" -> GojoLiveMegaPlayExtractor(server)
            "vidnest" -> GojoLiveVidnestExtractor(server)
            else -> NativeVideoExtractor(server)
        }
}

private const val VIDNEST_BASE = "https://new.vidnest.fun"
private const val VIDNEST_REFERER = "https://vidnest.fun/"
private const val MEGAPLAY_ORIGIN = "https://megaplay.buzz"
private const val MEGAPLAY_REFERER = "https://megaplay.buzz/"
private const val VIDNEST_SECRET = "F9k2mR8vL4qP7wY1zN0fG3bS6tH5jC8d_x4R7vN2mP9qL1"
private val GOJO_RNG = SecureRandom()

private fun gojoGet(url: String, referer: String?, accept: String = "application/json, */*"): String {
    val request = Request.Builder().url(url)
        .header("User-Agent", NativeAnimeParser.USER_AGENT)
        .apply { referer?.let { header("Referer", it) } }
        .header("Accept", accept)
        .get().build()
    return okHttpClient.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
        body
    }
}

private fun vidnestNonce(): String {
    val bytes = ByteArray(16).also { GOJO_RNG.nextBytes(it) }
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private fun vidnestDecrypt(d: String, nonce: String): JsonObject? {
    val raw = try {
        Base64.decode(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    } catch (_: Exception) {
        return null
    }
    if (raw.size <= 28) return null
    val key = MessageDigest.getInstance("SHA-256")
        .digest("$nonce|$VIDNEST_SECRET".toByteArray(Charsets.UTF_8))
    return try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        val plain = cipher.doFinal(raw, 12, raw.size - 12)
        Mapper.json.parseToJsonElement(String(plain, Charsets.UTF_8)) as? JsonObject
    } catch (e: Exception) {
        Logger.log("GojoLive vidnest decrypt failed: ${e.message}")
        null
    }
}

private fun addCommonExtras(
    extraData: MutableMap<String, String>,
    subtitles: List<SubData>,
    intro: Pair<Double, Double>?,
    outro: Pair<Double, Double>?
) {
    if (subtitles.isNotEmpty()) {
        val json = subtitles.joinToString(",") {
            "{\"url\":\"${it.url.replace("\"", "\\\"")}\",\"language\":\"${it.language}\",\"type\":\"${it.type}\"}"
        }
        extraData["subtitles"] = "[$json]"
    }
    intro?.let { (s, e) -> extraData["intro"] = "{\"start\":$s,\"end\":$e}" }
    outro?.let { (s, e) -> extraData["outro"] = "{\"start\":$s,\"end\":$e}" }
}

private fun parseSkip(v: JsonElement?): Pair<Double, Double>? {
    val a = v as? JsonArray ?: return null
    if (a.size < 2) return null
    fun num(el: JsonElement?): Double? = when (el) {
        is JsonPrimitive -> el.doubleOrNull ?: el.contentOrNull?.toDoubleOrNull()
        else -> null
    }
    val s = num(a.getOrNull(0)) ?: return null
    val e = num(a.getOrNull(1)) ?: return null
    return if (e > s) s to e else null
}

class GojoLiveMegaPlayExtractor(override val server: VideoServer) : VideoExtractor() {

    override suspend fun extract(): VideoContainer {
        val extra = server.extraData
        val ep = extra?.get("num").orEmpty()
        val audio = extra?.get("audio")?.takeIf { it.isNotBlank() } ?: "sub"
        val idMal = extra?.get("idMal")?.takeIf { it.isNotBlank() }
        val embeds = buildList {
            add(server.embed.url)
            if (!idMal.isNullOrBlank() && ep.isNotBlank()) {
                add("$MEGAPLAY_ORIGIN/stream/mal/$idMal/$ep/$audio")
            }
        }
        val started = System.currentTimeMillis()
        for (embed in embeds) {
            Logger.log("GojoLive MegaPlay: resolving ep $ep ($audio) -> $embed")
            val res = runCatching { EmbedRouter.resolve(embed, "$MEGAPLAY_ORIGIN/") }.getOrNull()
            val url = res?.urls?.firstOrNull()
            if (res == null || url.isNullOrBlank()) {
                Logger.log(
                    "GojoLive MegaPlay: no url for $embed after " +
                        "${System.currentTimeMillis() - started}ms"
                )
                continue
            }
            val out = mutableMapOf("referer" to (res.headers["Referer"] ?: MEGAPLAY_REFERER))
            res.headers["Origin"]?.takeIf { it.isNotBlank() }?.let { out["origin"] = it }
            addCommonExtras(out, res.subtitles, res.intro, res.outro)
            Logger.log(
                "GojoLive MegaPlay: OK ep $ep ($audio) -> ${url.take(80)} " +
                    "subs=${res.subtitles.size} in ${System.currentTimeMillis() - started}ms"
            )
            return NativeVideoExtractor(VideoServer(server.name, url, out)).extract()
        }
        Logger.log("GojoLive MegaPlay: FAILED ep $ep ($audio) after ${System.currentTimeMillis() - started}ms")
        return VideoContainer(emptyList())
    }
}

class GojoLiveVidnestExtractor(override val server: VideoServer) : VideoExtractor() {

    override suspend fun extract(): VideoContainer {
        val extra = server.extraData ?: return VideoContainer(emptyList())
        val path = extra["path"] ?: return VideoContainer(emptyList())
        val defaultReferer = extra["referer"] ?: VIDNEST_REFERER
        val ep = extra["num"].orEmpty()
        val audio = extra["audio"] ?: "sub"
        val url = "$VIDNEST_BASE$path?_n=${vidnestNonce()}"
        val started = System.currentTimeMillis()
        Logger.log("GojoLive ${server.name}: resolving ep $ep ($audio) -> ${url.take(100)}")
        return try {
            val body = gojoGet(url, VIDNEST_REFERER)
            val root = Mapper.json.parseToJsonElement(body) as? JsonObject
                ?: throw IllegalStateException("not json (${body.take(80)})")

            val encV = (root["v"] as? JsonPrimitive)?.intOrNull
            val payload = if (encV == 2 && root["d"] != null) {
                val d = (root["d"] as? JsonPrimitive)?.contentOrNull ?: throw IllegalStateException("no d")
                val n = (root["n"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                vidnestDecrypt(d, n) ?: throw IllegalStateException("decrypt failed")
            } else {
                root
            }

            val sources = payload["sources"]?.jsonArray ?: payload["multiSrc"]?.jsonArray
            val first = sources?.firstOrNull() as? JsonObject ?: throw IllegalStateException("no sources")
            val streamUrl = (first["file"] as? JsonPrimitive)?.contentOrNull
                ?: (first["url"] as? JsonPrimitive)?.contentOrNull
                ?: throw IllegalStateException("no stream url")

            val srcReferer = (first["referer"] as? JsonPrimitive)?.contentOrNull
            val isMegaCdn = streamUrl.contains("megap.", true) || streamUrl.contains("shiora", true)
            val referer = when {
                isMegaCdn -> MEGAPLAY_REFERER
                !srcReferer.isNullOrBlank() -> srcReferer
                else -> defaultReferer
            }
            val out = mutableMapOf("referer" to referer)
            if (isMegaCdn) out["origin"] = MEGAPLAY_ORIGIN

            val subs = payload["tracks"]?.jsonArray?.mapNotNull { t ->
                val o = t as? JsonObject ?: return@mapNotNull null
                val f = (o["file"] as? JsonPrimitive)?.contentOrNull
                    ?: (o["url"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                val lang = (o["label"] as? JsonPrimitive)?.contentOrNull
                    ?: (o["kind"] as? JsonPrimitive)?.contentOrNull ?: "en"
                SubData(f, lang)
            } ?: emptyList()
            addCommonExtras(out, subs, parseSkip(payload["intro"]), parseSkip(payload["outro"]))

            val sourceName = (first["server"] as? JsonPrimitive)?.contentOrNull
            val host = runCatching { java.net.URI(streamUrl).host }.getOrNull()
            Logger.log(
                "GojoLive ${server.name}: OK ep $ep ($audio) source=$sourceName host=$host " +
                    "referer=$referer subs=${subs.size} in ${System.currentTimeMillis() - started}ms"
            )
            NativeVideoExtractor(VideoServer(server.name, streamUrl, out)).extract()
        } catch (e: Exception) {
            Logger.log(
                "GojoLive ${server.name}: FAILED ep $ep ($audio) after " +
                    "${System.currentTimeMillis() - started}ms - ${e.message}"
            )
            VideoContainer(emptyList())
        }
    }
}
