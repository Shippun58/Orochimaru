package ani.sanin.plugins.nuvio

import android.content.Context
import ani.sanin.settings.saving.PrefManager
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal object NuvioRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val client get() = Injekt.get<NetworkHelper>().client.newBuilder().cache(null).build()

    fun repositories(): List<NuvioRepositoryItem> = NuvioStorage.repos().map { url ->
        NuvioStorage.repoMeta(url) ?: NuvioRepositoryItem(
            manifestUrl = url,
            name = url.cleanRepoLabel(),
            scraperCount = NuvioStorage.installedMetadata().count { it.repositoryUrl == url },
            lastUpdated = PrefManager.getNullableCustomVal(
                "nuvio_repo_updated_$url",
                0L,
                Long::class.java,
            ) ?: 0L,
        )
    }

    suspend fun resolveManifestUrl(input: String): String? = withContext(Dispatchers.IO) {
        val trimmed = input.trim().removeSuffix("/")
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return@withContext null
        }
        if (trimmed.endsWith(".json", ignoreCase = true)) trimmed else "$trimmed/manifest.json"
    }

    suspend fun fetchManifest(manifestUrl: String): NuvioManifest = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(manifestUrl)
            .header("User-Agent", "Orochimaru Nuvio Plugins")
            .header("Cache-Control", "no-store")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        json.decodeFromString<NuvioManifest>(body)
    }

    suspend fun addRepository(input: String): NuvioRepositoryItem = withContext(Dispatchers.IO) {
        val manifestUrl = resolveManifestUrl(input)
            ?: throw IllegalArgumentException("Enter a valid Nuvio repository URL")
        val manifest = fetchManifest(manifestUrl)
        val item = repositoryItem(manifestUrl, manifest)
        NuvioStorage.addRepo(manifestUrl)
        NuvioStorage.saveRepoMeta(item)
        item
    }

    suspend fun refreshRepository(manifestUrl: String): NuvioRepositoryItem = withContext(Dispatchers.IO) {
        val manifest = fetchManifest(manifestUrl)
        val item = repositoryItem(manifestUrl, manifest)
        NuvioStorage.addRepo(manifestUrl)
        NuvioStorage.saveRepoMeta(item)
        item
    }

    suspend fun installScraper(
        context: Context,
        manifestUrl: String,
        scraper: NuvioManifestScraper,
    ): NuvioScraper = withContext(Dispatchers.IO) {
        val code = fetchText(sourceUrl(manifestUrl, scraper.filename))
        if (code.isBlank()) throw IllegalStateException("Empty plugin code")
        val enabled = NuvioStorage.installedMetadata()
            .firstOrNull { it.id == scraper.id }?.enabled ?: scraper.enabled
        val stored = nuvioManifestScraperToStored(scraper, manifestUrl, enabled)
        NuvioStorage.install(context, stored, code)
        stored.withCode(code)
    }

    private fun repositoryItem(manifestUrl: String, manifest: NuvioManifest): NuvioRepositoryItem {
        val now = System.currentTimeMillis()
        PrefManager.setCustomVal("nuvio_repo_updated_$manifestUrl", now)
        return NuvioRepositoryItem(
            manifestUrl = manifestUrl,
            name = manifest.name,
            description = manifest.description,
            version = manifest.version,
            scraperCount = manifest.scrapers.count { supportsPlatform(it) },
            lastUpdated = now,
        )
    }

    fun installed(context: Context): List<NuvioScraper> = NuvioStorage.installed(context)

    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Orochimaru Nuvio Plugins")
            .header("Cache-Control", "no-store")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} for $url")
            response.body?.string().orEmpty()
        }
    }

    fun sourceUrl(manifestUrl: String, filename: String): String {
        if (filename.startsWith("http://") || filename.startsWith("https://")) return filename
        val idx = manifestUrl.lastIndexOf('/')
        val base = if (idx > 0) manifestUrl.substring(0, idx + 1) else manifestUrl
        return base + filename.removePrefix("/")
    }

    internal fun supportsPlatform(scraper: NuvioManifestScraper): Boolean {
        val disabled = scraper.disabledPlatforms.orEmpty().map { it.lowercase() }
        if (disabled.any { it == "android" || it == "mobile" || it == "all" }) return false
        val supported = scraper.supportedPlatforms.orEmpty().map { it.lowercase() }
        if (supported.isEmpty()) return true
        return supported.any { it == "android" || it == "mobile" || it == "tv" || it == "all" }
    }

    private fun String.cleanRepoLabel(): String =
        removePrefix("https://raw.githubusercontent.com/")
            .removeSuffix("/manifest.json")
            .removeSuffix("/")
            .ifBlank { this }
}
