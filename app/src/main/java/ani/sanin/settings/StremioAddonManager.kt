package ani.sanin.settings

import android.content.Context
import ani.sanin.R
import ani.sanin.util.Logger
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
    // null = not probed yet, true = only info/recommendation cards, false = playable streams
    val extrasOnly: Boolean? = null,
    // true = turned off by the user; hidden from home, watch tab, server sheet, search, etc.
    val disabled: Boolean = false,
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
    val config: List<JsonElement> = emptyList(),
    @SerialName("behaviorHints") val behaviorHints: JsonObject? = null,
)

object StremioAddonManager {
    const val SOURCE_PREFIX = "stremio:"
    const val COMMUNITY_CATALOG_URL =
        "https://v3-cinemeta.strem.io/addon_catalog/all/community.json"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client get() = Injekt.get<NetworkHelper>().client

    private fun rawInstalled(): List<StremioAddon> =
        PrefManager.getVal<List<String>>(PrefName.StremioInstalledAddons).mapNotNull { encoded ->
            runCatching { json.decodeFromString<StremioAddon>(encoded) }.getOrNull()
        }

    /** Every installed add-on, including disabled ones (settings UI + install checks). */
    fun allInstalled(): List<StremioAddon> = rawInstalled()

    /** Active add-ons only; disabled ones are hidden from the app everywhere. */
    fun installed(): List<StremioAddon> = rawInstalled().filterNot { it.disabled }

    fun isInstalled(transportUrl: String): Boolean =
        allInstalled().any { it.transportUrl.equals(transportUrl, ignoreCase = true) }

    fun metadataSources(): List<StremioAddon> = installed().filter { addon ->
        addon.manifest.resources.mapNotNull { it.resourceName() }
            .any { it.equals("catalog", ignoreCase = true) || it.equals("meta", ignoreCase = true) }
    }

    fun homeSources(): List<StremioAddon> = installed().filter { addon ->
        StremioAddonClient.hasHomeCatalogs(addon)
    }

    fun sourceId(addon: StremioAddon): String = SOURCE_PREFIX + addon.transportUrl

    fun addonForSourceId(sourceId: String): StremioAddon? {
        if (!sourceId.startsWith(SOURCE_PREFIX, ignoreCase = true)) return null
        val transportUrl = sourceId.substring(SOURCE_PREFIX.length)
        return installed().firstOrNull { it.transportUrl.equals(transportUrl, ignoreCase = true) }
    }

    fun install(addon: StremioAddon): Boolean {
        val current = PrefManager.getVal<List<String>>(PrefName.StremioInstalledAddons)
        if (allInstalled().any { it.transportUrl.equals(addon.transportUrl, ignoreCase = true) }) {
            Logger.log("STREMIO: install skipped (already installed) url=${addon.transportUrl}")
            return false
        }
        PrefManager.setVal(
            PrefName.StremioInstalledAddons,
            current + json.encodeToString(addon),
        )
        Logger.log("STREMIO: installed name='${addon.manifest.name}' url=${addon.transportUrl}")
        return true
    }

    suspend fun installWithManifest(addon: StremioAddon): StremioAddon? {
        if (isInstalled(addon.transportUrl)) {
            Logger.log("STREMIO: installWithManifest skipped (already installed) url=${addon.transportUrl}")
            return null
        }
        val resolved = StremioAddonClient.fetchManifest(addon.transportUrl)
        if (resolved == null) {
            Logger.log("STREMIO: installWithManifest fetch failed, using provided manifest url=${addon.transportUrl}")
        }
        val target = resolved ?: addon
        return persistInstall(target)
    }

    suspend fun installFromUrl(url: String): StremioAddon? {
        val resolved = StremioAddonClient.fetchManifest(url)
        if (resolved == null) {
            Logger.log("STREMIO: installFromUrl failed to fetch manifest url=$url")
            return null
        }
        return persistInstall(resolved)
    }

    private suspend fun persistInstall(addon: StremioAddon): StremioAddon? {
        if (isInstalled(addon.transportUrl)) {
            Logger.log("STREMIO: persistInstall skipped (already installed) url=${addon.transportUrl}")
            return null
        }
        val classified = if (addon.extrasOnly == null) {
            val extras = StremioAddonClient.detectExtras(addon)
            Logger.log(
                "STREMIO: classified name='${addon.manifest.name}' extrasOnly=$extras"
            )
            addon.copy(extrasOnly = extras)
        } else addon
        val current = PrefManager.getVal<List<String>>(PrefName.StremioInstalledAddons)
        PrefManager.setVal(
            PrefName.StremioInstalledAddons,
            current + json.encodeToString(classified),
        )
        Logger.log(
            "STREMIO: installed name='${classified.manifest.name}' url=${classified.transportUrl} " +
                "types=${classified.manifest.types} capabilities=${StremioAddonClient.capabilities(classified)} " +
                "extrasOnly=${classified.extrasOnly}"
        )
        return classified
    }

    fun setExtrasOnly(transportUrl: String, extrasOnly: Boolean) {
        val current = PrefManager.getVal<List<String>>(PrefName.StremioInstalledAddons)
        var changed = false
        val updated = current.map { encoded ->
            val addon = runCatching { json.decodeFromString<StremioAddon>(encoded) }.getOrNull()
                ?: return@map encoded
            if (!addon.transportUrl.equals(transportUrl, ignoreCase = true) ||
                addon.extrasOnly == extrasOnly
            ) {
                encoded
            } else {
                changed = true
                json.encodeToString(addon.copy(extrasOnly = extrasOnly))
            }
        }
        if (changed) {
            PrefManager.setVal(PrefName.StremioInstalledAddons, updated)
            Logger.log("STREMIO: set extrasOnly=$extrasOnly url=$transportUrl")
        }
    }

    fun extrasSources(): List<StremioAddon> = installed().filter { it.extrasOnly == true }

    fun setDisabled(transportUrl: String, disabled: Boolean) {
        var changed = false
        val updated = rawInstalled().map { addon ->
            if (!addon.transportUrl.equals(transportUrl, ignoreCase = true) ||
                addon.disabled == disabled
            ) {
                addon
            } else {
                changed = true
                addon.copy(disabled = disabled)
            }
        }
        if (changed) {
            PrefManager.setVal(PrefName.StremioInstalledAddons, updated.map { json.encodeToString(it) })
            Logger.log("STREMIO: set disabled=$disabled url=$transportUrl")
        }
    }

    fun setOrder(orderedUrls: List<String>) {
        val rank = HashMap<String, Int>()
        orderedUrls.forEachIndexed { index, url -> rank[url.lowercase()] = index }
        val sorted = rawInstalled().sortedWith(
            compareBy { addon -> rank[addon.transportUrl.lowercase()] ?: Int.MAX_VALUE }
        )
        PrefManager.setVal(PrefName.StremioInstalledAddons, sorted.map { json.encodeToString(it) })
        Logger.log("STREMIO: set order urls=${orderedUrls.size} installed=${sorted.size}")
    }

    fun uninstall(transportUrl: String) {
        val current = PrefManager.getVal<List<String>>(PrefName.StremioInstalledAddons)
        val remaining = current.filterNot { encoded ->
            runCatching { json.decodeFromString<StremioAddon>(encoded).transportUrl }
                .getOrNull()?.equals(transportUrl, ignoreCase = true) == true
        }
        PrefManager.setVal(PrefName.StremioInstalledAddons, remaining)
        Logger.log("STREMIO: uninstalled url=$transportUrl remaining=${remaining.size}")
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
        Logger.log("STREMIO: fetching community catalog url=$COMMUNITY_CATALOG_URL")
        val startedAt = System.currentTimeMillis()
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
        val distinct = catalog.addons.distinctBy { it.transportUrl.lowercase() }
        Logger.log(
            "STREMIO: community catalog ok count=${catalog.addons.size} distinct=${distinct.size} " +
                "elapsedMs=${System.currentTimeMillis() - startedAt}"
        )
        distinct
    }

    fun toRepoUi(addon: StremioAddon, installedTab: Boolean = false): RepoUi {
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
            watermarkRes = R.drawable.ic_stremio_watermark,
            watermarkLeft = false,
            hideCount = true,
            showInfoButton = true,
            enabled = !addon.disabled,
            toggleVisible = installedTab,
            dragVisible = installedTab,
            needsConfiguration = StremioAddonClient.configurationRequired(addon),
            configUrl = StremioAddonClient.configureUrl(addon),
        )
    }

    private fun JsonElement.resourceName(): String? = when (this) {
        is JsonPrimitive -> if (isString) content else null
        is JsonObject -> (this["name"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    private fun cacheFile(context: Context) = File(context.cacheDir, "stremio_community_addons.json")
}
