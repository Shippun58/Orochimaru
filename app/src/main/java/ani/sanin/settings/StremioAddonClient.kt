package ani.sanin.settings

import android.net.Uri
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.TimeUnit

data class StremioCatalogCard(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
)

data class StremioCatalogShelf(
    val name: String,
    val cards: List<StremioCatalogCard>,
)

data class StremioVideo(
    val id: String,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
    val released: String? = null,
    val overview: String? = null,
)

data class StremioMetaDetails(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val videos: List<StremioVideo> = emptyList(),
)

data class StremioStream(
    val name: String? = null,
    val description: String? = null,
    val url: String? = null,
    val externalUrl: String? = null,
    val infoHash: String? = null,
    val ytId: String? = null,
)

object StremioAddonClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client get() = Injekt.get<NetworkHelper>().client

    fun stremioType(tmdbType: String): String = if (tmdbType == "tv") "series" else "movie"

    fun supports(addon: StremioAddon, resource: String, tmdbType: String): Boolean {
        val type = stremioType(tmdbType)
        val matchingResources = addon.manifest.resources.mapNotNull { it.resourceDescription() }
            .filter { it.name.equals(resource, ignoreCase = true) }
        if (matchingResources.isEmpty()) return false
        return matchingResources.any { definition ->
            val supportedTypes = definition.types ?: addon.manifest.types
            supportedTypes.any { it.equals(type, ignoreCase = true) }
        }
    }

    fun providerSupportsMedia(addon: StremioAddon, tmdbType: String): Boolean =
        listOf("stream", "meta", "catalog").any { supports(addon, it, tmdbType) }

    fun capabilities(addon: StremioAddon): List<String> {
        val names = addon.manifest.resources
            .mapNotNull { it.resourceDescription()?.name?.lowercase() }
            .toSet()
        return listOf("catalog", "meta", "stream").filter { it in names }
    }

    fun supportedTypes(addon: StremioAddon): List<String> = addon.manifest.types.distinct()

    fun isConfigurable(addon: StremioAddon): Boolean {
        val config = addon.manifest.config
        if (config.isNotEmpty()) return true
        val hint = (addon.manifest.behaviorHints?.get("configurable") as? JsonPrimitive)?.contentOrNull
        return hint?.toBooleanStrictOrNull() == true
    }

    fun configureUrl(addon: StremioAddon): String? {
        if (!isConfigurable(addon)) return null
        return runCatching {
            val uri = Uri.parse(addon.transportUrl)
            val path = uri.path.orEmpty()
                .removeSuffix("/manifest.json")
                .trimEnd('/')
            val configurePath = if (path.isBlank()) "/configure" else "$path/configure"
            uri.buildUpon().path(configurePath).build().toString()
        }.getOrNull()
    }

    suspend fun fetchManifest(transportUrl: String): StremioAddon? = withContext(Dispatchers.IO) {
        if (transportUrl.isBlank()) return@withContext null
        val manifestUrl = runCatching {
            val uri = Uri.parse(transportUrl)
            val path = uri.path.orEmpty().trimEnd('/')
            val manifestPath = if (path.endsWith("/manifest.json", ignoreCase = true)) path
            else "$path/manifest.json"
            uri.buildUpon().path(manifestPath).build().toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext null
        Logger.log("STREMIO: fetching manifest url=$manifestUrl")
        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            val request = Request.Builder()
                .url(manifestUrl)
                .header("Accept", "application/json")
                .build()
            val body = client.newBuilder()
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) throw IOException("Add-on returned HTTP ${response.code}")
                    response.body?.string() ?: throw IOException("Add-on returned an empty response")
                }
            StremioAddon(
                transportUrl = transportUrl,
                transportName = "http",
                manifest = json.decodeFromString<StremioAddonManifest>(body),
            )
        }
        val elapsed = System.currentTimeMillis() - startedAt
        result.onSuccess { addon ->
            Logger.log(
                "STREMIO: manifest ok name='${addon.manifest.name}' id=${addon.manifest.id} " +
                    "types=${addon.manifest.types} capabilities=${capabilities(addon)} " +
                    "catalogs=${addon.manifest.catalogs.size} elapsedMs=$elapsed"
            )
        }.onFailure { ex ->
            Logger.log(
                "STREMIO: manifest FAILED url=$manifestUrl elapsedMs=$elapsed " +
                    "error=${ex.message ?: ex.javaClass.simpleName}"
            )
        }
        result.getOrNull()
    }

    suspend fun loadMeta(
        addon: StremioAddon,
        tmdbType: String,
        tmdbId: Int,
        imdbId: String?,
    ): StremioMetaDetails? = withContext(Dispatchers.IO) {
        if (!supports(addon, "meta", tmdbType)) {
            Logger.log("STREMIO: meta skipped addon='${addon.manifest.name}' type=$tmdbType (unsupported)")
            return@withContext null
        }
        val candidates = metaIdCandidates(addon, tmdbType, tmdbId, imdbId)
        Logger.log("STREMIO: meta request addon='${addon.manifest.name}' type=$tmdbType candidates=$candidates")
        for (id in candidates) {
            if (!idMatches(addon, "meta", tmdbType, id)) continue
            val root = request(addon, listOf("meta", stremioType(tmdbType), id)) ?: continue
            val meta = root["meta"] as? JsonObject ?: continue
            val parsed = parseMeta(meta)
            if (parsed != null) {
                Logger.log("STREMIO: meta ok addon='${addon.manifest.name}' id=$id videos=${parsed.videos.size}")
                return@withContext parsed
            }
        }
        Logger.log("STREMIO: meta none addon='${addon.manifest.name}' type=$tmdbType id=$tmdbId")
        null
    }

    suspend fun loadCatalogShelves(addon: StremioAddon, tmdbType: String): List<StremioCatalogShelf> =
        withContext(Dispatchers.IO) {
            if (!supports(addon, "catalog", tmdbType)) {
                Logger.log("STREMIO: catalog skipped addon='${addon.manifest.name}' type=$tmdbType (unsupported)")
                return@withContext emptyList()
            }
            val type = stremioType(tmdbType)
            val definitions = catalogDefinitions(addon)
                .filter { it.type == type || it.type == "all" }
                .filterNot { it.hasRequiredExtras }
                .take(4)
            Logger.log(
                "STREMIO: catalog request addon='${addon.manifest.name}' type=$type " +
                    "catalogs=${definitions.map { it.id }}"
            )
            definitions.mapNotNull { catalog ->
                val root = request(addon, listOf("catalog", catalog.type, catalog.id)) ?: return@mapNotNull null
                val cards = (root["metas"] as? JsonArray).orEmpty().mapNotNull { it.asObject()?.let(::parseCard) }
                Logger.log(
                    "STREMIO: catalog '${catalog.name}' (${catalog.type}/${catalog.id}) cards=${cards.size}"
                )
                cards.takeIf { it.isNotEmpty() }?.let { StremioCatalogShelf(catalog.name, it) }
            }
        }

    suspend fun loadStreams(
        addon: StremioAddon,
        tmdbType: String,
        tmdbId: Int,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        metadataVideoId: String? = null,
    ): List<StremioStream> = withContext(Dispatchers.IO) {
        if (!supports(addon, "stream", tmdbType)) {
            Logger.log("STREMIO: stream skipped addon='${addon.manifest.name}' type=$tmdbType (unsupported)")
            return@withContext emptyList()
        }
        val videoId = metadataVideoId?.takeIf { idMatches(addon, "stream", tmdbType, it) }
            ?: streamVideoId(addon, tmdbType, tmdbId, imdbId, season, episode)
            ?: run {
                Logger.log("STREMIO: stream no id addon='${addon.manifest.name}' tmdb=$tmdbId imdb=$imdbId S$season E$episode")
                return@withContext emptyList()
            }
        if (!idMatches(addon, "stream", tmdbType, videoId)) return@withContext emptyList()
        Logger.log("STREMIO: stream request addon='${addon.manifest.name}' type=$tmdbType id=$videoId")
        val root = request(addon, listOf("stream", stremioType(tmdbType), videoId))
            ?: return@withContext emptyList()
        val streams = (root["streams"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element.asObject() ?: return@mapNotNull null
            StremioStream(
                name = item.string("name"),
                description = item.string("description"),
                url = item.string("url"),
                externalUrl = item.string("externalUrl"),
                infoHash = item.string("infoHash"),
                ytId = item.string("ytId"),
            )
        }
        Logger.log("STREMIO: stream ok addon='${addon.manifest.name}' id=$videoId streams=${streams.size}")
        streams
    }

    private fun metaIdCandidates(
        addon: StremioAddon,
        tmdbType: String,
        tmdbId: Int,
        imdbId: String?,
    ): List<String> {
        val prefixes = idPrefixes(addon, "meta", tmdbType)
        val candidates = buildList {
            if (!imdbId.isNullOrBlank()) add(imdbId)
            add("tmdb:${stremioType(tmdbType)}:$tmdbId")
            add("tmdb:$tmdbId")
            add(tmdbId.toString())
        }
        return candidates.distinct().filter { id -> prefixes.isEmpty() || prefixes.any(id::startsWith) }
    }

    private fun streamVideoId(
        addon: StremioAddon,
        tmdbType: String,
        tmdbId: Int,
        imdbId: String?,
        season: Int?,
        episode: Int?,
    ): String? {
        val prefixes = idPrefixes(addon, "stream", tmdbType)
        val baseIds = buildList {
            if (!imdbId.isNullOrBlank()) add(imdbId)
            add("tmdb:${stremioType(tmdbType)}:$tmdbId")
            add("tmdb:$tmdbId")
            add(tmdbId.toString())
        }.distinct()
        val candidates = if (tmdbType == "tv" && season != null && episode != null) {
            baseIds.flatMap { listOf("$it:$season:$episode", "$it:$season:$episode") }
        } else baseIds
        return candidates.firstOrNull { id -> prefixes.isEmpty() || prefixes.any(id::startsWith) }
    }

    private fun idMatches(addon: StremioAddon, resource: String, tmdbType: String, id: String): Boolean {
        val prefixes = idPrefixes(addon, resource, tmdbType)
        return prefixes.isEmpty() || prefixes.any(id::startsWith)
    }

    private fun idPrefixes(addon: StremioAddon, resource: String, tmdbType: String): List<String> {
        val type = stremioType(tmdbType)
        val definitions = addon.manifest.resources.mapNotNull { it.resourceDescription() }
            .filter { it.name.equals(resource, ignoreCase = true) }
            .filter { it.types.isNullOrEmpty() || it.types.any { declared -> declared.equals(type, true) } }
        return definitions.firstNotNullOfOrNull { it.idPrefixes }
            ?: addon.manifest.idPrefixes
    }

    private fun catalogDefinitions(addon: StremioAddon): List<CatalogDefinition> =
        addon.manifest.catalogs.mapNotNull { element ->
            val obj = element.asObject() ?: return@mapNotNull null
            val type = obj.string("type") ?: return@mapNotNull null
            val id = obj.string("id") ?: return@mapNotNull null
            val name = obj.string("name") ?: id
            val hasRequired = (obj["extra"] as? JsonArray).orEmpty().any { extra ->
                extra.asObject()?.get("isRequired").asBoolean() == true
            }
            CatalogDefinition(type, id, name, hasRequired)
        }

    private data class CatalogDefinition(val type: String, val id: String, val name: String, val hasRequiredExtras: Boolean)
    private data class ResourceDescription(
        val name: String,
        val types: List<String>?,
        val idPrefixes: List<String>?,
    )

    private fun JsonElement.resourceDescription(): ResourceDescription? = when (this) {
        is JsonPrimitive -> if (isString) ResourceDescription(content, null, null) else null
        is JsonObject -> {
            val name = string("name")
            if (name == null) null
            else ResourceDescription(name, stringList("types"), stringList("idPrefixes"))
        }
        else -> null
    }

    private suspend fun request(addon: StremioAddon, path: List<String>): JsonObject? {
        val manifestUrl = addon.transportUrl.substringBefore('?')
        val rootUrl = if (manifestUrl.endsWith("/manifest.json", ignoreCase = true)) {
            manifestUrl.dropLast("/manifest.json".length)
        } else manifestUrl.trimEnd('/')
        val pathSuffix = path.joinToString("/") { Uri.encode(it) }
        val url = "$rootUrl/$pathSuffix.json"
        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            val body = client.newBuilder()
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) throw IOException("Add-on returned HTTP ${response.code}")
                    response.body?.string() ?: throw IOException("Add-on returned an empty response")
                }
            json.parseToJsonElement(body).asObject()
        }
        val elapsed = System.currentTimeMillis() - startedAt
        result.onFailure { ex ->
            Logger.log(
                "STREMIO: request FAILED addon='${addon.manifest.name}' url=$url " +
                    "elapsedMs=$elapsed error=${ex.message ?: ex.javaClass.simpleName}"
            )
        }
        return result.getOrNull()
    }

    private fun parseMeta(obj: JsonObject): StremioMetaDetails? {
        val id = obj.string("id") ?: return null
        val type = obj.string("type") ?: return null
        val name = obj.string("name") ?: return null
        return StremioMetaDetails(
            id = id,
            type = type,
            name = name,
            poster = obj.string("poster"),
            background = obj.string("background"),
            description = obj.string("description"),
            releaseInfo = obj.string("releaseInfo") ?: obj.string("released"),
            runtime = obj.string("runtime"),
            imdbRating = obj.string("imdbRating"),
            genres = obj.stringList("genres"),
            videos = (obj["videos"] as? JsonArray).orEmpty().mapNotNull { it.asObject()?.let(::parseVideo) },
        )
    }

    private fun parseVideo(obj: JsonObject): StremioVideo? {
        val id = obj.string("id") ?: return null
        return StremioVideo(
            id = id,
            title = obj.string("title"),
            season = obj.string("season")?.toIntOrNull(),
            episode = obj.string("episode")?.toIntOrNull(),
            thumbnail = obj.string("thumbnail"),
            released = obj.string("released"),
            overview = obj.string("overview"),
        )
    }

    private fun parseCard(obj: JsonObject): StremioCatalogCard? {
        val id = obj.string("id") ?: return null
        val type = obj.string("type") ?: return null
        val name = obj.string("name") ?: return null
        return StremioCatalogCard(
            id = id,
            type = type,
            name = name,
            poster = obj.string("poster"),
            background = obj.string("background"),
            description = obj.string("description"),
            releaseInfo = obj.string("releaseInfo") ?: obj.string("released"),
            imdbRating = obj.string("imdbRating"),
        )
    }

    private fun JsonElement?.asBoolean(): Boolean = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.stringList(key: String): List<String> = (this[key] as? JsonArray)
        .orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
