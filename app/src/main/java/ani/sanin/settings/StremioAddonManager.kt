package ani.sanin.settings

import android.content.Context
import ani.sanin.R
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class StremioAddonCatalogResponse(
    val addons: List<StremioAddon> = emptyList(),
)

@Serializable
data class StremioAddon(
    val transportUrl: String,
    val transportName: String = "http",
    val manifest: StremioAddonManifest,
)

@Serializable
data class StremioAddonManifest(
    val id: String = "",
    val name: String = "Unnamed add-on",
    val version: String = "",
    val description: String = "",
    val logo: String? = null,
    val types: List<String> = emptyList(),
    val resources: List<JsonElement> = emptyList(),
    val catalogs: List<JsonElement> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    @SerialName("addonCatalogs") val addonCatalogs: List<JsonElement> = emptyList(),
)

object StremioAddonManager {
    const val SOURCE_PREFIX = "stremio:"
    const val COMMUNITY_CATALOG_URL =
        "https://v3-cinemeta.strem.io/addon_catalog/all/community.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val client get() = Injekt.get<NetworkHelper>().client

    fun installed(): List<StremioAddon> =
        PrefManager.getVal<List<String>>(PrefName.StremioInstalledAddons).mapNotNull { encoded ->
            runCatching { json.decodeFromString<StremioAddon>(encoded) }.getOrNull()
        }

    fun isInstalled(transportUrl: String): Boolean =
        installed().any { it.transportUrl.equals(transportUrl, ignoreCase = true) }

    fun metadataSources(): List<StremioAddon> = installed().filter { addon ->
        addon.manifest.resources.mapNotNull { it.resourceName() }
            .any { it.equals("catalog", ignoreCase = true) || it.equals("meta", ignoreCase = true) }
    }

    fun sourceId(addon: StremioAddon): String = SOURCE_PREFIX + addon.transportUrl

    fun addonForSourceId(sourceId: String): StremioAddon? {
        if (!sourceId.startsWith(SOURCE_PREFIX, ignoreCase = true)) return null
        val transportUrl = sourceId.substring(SOURCE_PREFIX.length)
        return installed().firstOrNull { it.transportUrl.equals(transportUrl, ignoreCase = true) }
    }

    fun install(addon: StremioAddon): Boolean {
        val current = PrefManager.getVal<List<String>>(PrefName.StremioInstalledAddons)
        if (installed().any { it.transportUrl.equals(addon.transportUrl, ignoreCase = true) }) {
            return false
        }
        PrefManager.setVal(
            PrefName.StremioInstalledAddons,
            current + json.encodeToString(addon),
        )
        return true
    }

    suspend fun installWithManifest(addon: StremioAddon): StremioAddon? {
        if (isInstalled(addon.transportUrl)) return null
        val resolved = StremioAddonClient.fetchManifest(addon.transportUrl) ?: addon
        if (isInstalled(resolved.transportUrl)) return null
        val current = PrefManager.getVal<List<String>>(PrefName.StremioInstalledAddons)
        PrefManager.setVal(
            PrefName.StremioInstalledAddons,
            current + json.encodeToString(resolved),
        )
        return resolved
    }

    fun uninstall(transportUrl: String) {
        val current = PrefManager.getVal<List<String>>(PrefName.StremioInstalledAddons)
        val remaining = current.filterNot { encoded ->
            runCatching { json.decodeFromString<StremioAddon>(encoded).transportUrl }
                .getOrNull()?.equals(transportUrl, ignoreCase = true) == true
        }
        PrefManager.setVal(PrefName.StremioInstalledAddons, remaining)
    }

    suspend fun cachedCommunity(context: Context): List<StremioAddon> = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile(context)
            if (file.exists()) {
                json.decodeFromString<StremioAddonCatalogResponse>(file.readText()).addons
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchCommunity(context: Context): List<StremioAddon> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(COMMUNITY_CATALOG_URL)
            .header("Accept", "application/json")
            .build()
        val responseText = client.newBuilder()
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Community add-on catalog returned ${response.code}")
                }
                response.body?.string() ?: throw IOException("Community add-on catalog was empty")
            }
        val catalog = json.decodeFromString<StremioAddonCatalogResponse>(responseText)
        runCatching {
            cacheFile(context).apply { parentFile?.mkdirs() }.writeText(responseText)
        }
        catalog.addons.distinctBy { it.transportUrl.lowercase() }
    }

    fun toRepoUi(addon: StremioAddon): RepoUi {
        val manifest = addon.manifest
        val resources = manifest.resources.mapNotNull { it.resourceName() }.distinct()
        val catalogCount = manifest.catalogs.size + manifest.addonCatalogs.size
        return RepoUi(
            name = manifest.name,
            url = addon.transportUrl,
            count = catalogCount,
            iconUrl = manifest.logo,
            contentTypes = resources,
            languages = manifest.types.distinct(),
            countText = "${resources.size} resources · $catalogCount catalogs",
            scopeLabel = "types",
            description = manifest.description,
            watermarkRes = R.drawable.ic_extension,
        )
    }

    private fun JsonElement.resourceName(): String? = when (this) {
        is JsonPrimitive -> if (isString) content else null
        is JsonObject -> (this["name"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    private fun cacheFile(context: Context) = File(context.cacheDir, "stremio_community_addons.json")
}
