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
        val scrapers = NuvioStorage.installedMetadata().filter { it.repositoryUrl == url }
        NuvioRepositoryItem(
            manifestUrl = url,
            name = scrapers.firstOrNull()?.id?.let { url.substringAfterLast('/') } ?: url.cleanRepoLabel(),
            scraperCount = scrapers.size,
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

    suspend fun addRepository(context: Context, input: String): NuvioRepositoryItem = withContext(Dispatchers.IO) {
        val manifestUrl = resolveManifestUrl(input)
            ?: throw IllegalArgumentException("Enter a valid Nuvio repository URL")
        val manifest = fetchManifest(manifestUrl)
        val previous = NuvioStorage.installedMetadata().associateBy { it.id }
        var installedCount = 0
        manifest.scrapers.filter { supportsPlatform(it) }.forEach { scraper ->
            val code = runCatching { fetchText(sourceUrl(manifestUrl, scraper.filename)) }.getOrNull()
            if (!code.isNullOrBlank()) {
                val enabled = previous[scraper.id]?.enabled ?: scraper.enabled
                NuvioStorage.install(
                    context,
                    nuvioManifestScraperToStored(scraper, manifestUrl, enabled),
                    code,
                )
                installedCount += 1
            }
        }
        NuvioStorage.addRepo(manifestUrl)
        val now = System.currentTimeMillis()
        PrefManager.setCustomVal("nuvio_repo_updated_$manifestUrl", now)
        NuvioRepositoryItem(
            manifestUrl = manifestUrl,
            name = manifest.name,
            description = manifest.description,
            version = manifest.version,
            scraperCount = installedCount,
            lastUpdated = now,
        )
    }

    suspend fun refreshRepository(context: Context, manifestUrl: String): NuvioRepositoryItem = withContext(Dispatchers.IO) {
        val manifest = fetchManifest(manifestUrl)
        val previous = NuvioStorage.installedMetadata().filter { it.repositoryUrl == manifestUrl }.associateBy { it.id }
        manifest.scrapers.filter { supportsPlatform(it) }.forEach { scraper ->
            val code = runCatching { fetchText(sourceUrl(manifestUrl, scraper.filename)) }.getOrNull()
            if (!code.isNullOrBlank()) {
                NuvioStorage.install(
                    context,
                    nuvioManifestScraperToStored(scraper, manifestUrl, previous[scraper.id]?.enabled ?: scraper.enabled),
                    code,
                )
            }
        }
        val now = System.currentTimeMillis()
        PrefManager.setCustomVal("nuvio_repo_updated_$manifestUrl", now)
        NuvioRepositoryItem(
            manifestUrl = manifestUrl,
            name = manifest.name,
            description = manifest.description,
            version = manifest.version,
            scraperCount = NuvioStorage.installedMetadata().count { it.repositoryUrl == manifestUrl },
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

    private fun supportsPlatform(scraper: NuvioManifestScraper): Boolean {
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
