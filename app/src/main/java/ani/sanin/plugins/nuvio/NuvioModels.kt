package ani.sanin.plugins.nuvio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val NUVIO_REPOSITORY_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1_000L

internal fun nuvioRefreshDue(lastUpdatedEpochMs: Long, nowEpochMs: Long): Boolean =
    lastUpdatedEpochMs <= 0L || nowEpochMs - lastUpdatedEpochMs >= NUVIO_REPOSITORY_REFRESH_INTERVAL_MS

@Serializable
data class NuvioManifest(
    val name: String,
    val version: String,
    val description: String? = null,
    val author: String? = null,
    val scrapers: List<NuvioManifestScraper> = emptyList(),
)

@Serializable
data class NuvioManifestScraper(
    val id: String,
    val name: String,
    val description: String? = null,
    val author: String? = null,
    val version: String,
    val filename: String,
    @SerialName("supportedTypes") val supportedTypes: List<String> = listOf("movie", "tv"),
    val enabled: Boolean = true,
    val hasSettings: Boolean = false,
    val logo: String? = null,
    @SerialName("contentLanguage") val contentLanguage: List<String>? = null,
    @SerialName("supportedPlatforms") val supportedPlatforms: List<String>? = null,
    @SerialName("disabledPlatforms") val disabledPlatforms: List<String>? = null,
    val formats: List<String>? = null,
    @SerialName("supportedFormats") val supportedFormats: List<String>? = null,
    @SerialName("supportsExternalPlayer") val supportsExternalPlayer: Boolean? = null,
    val limited: Boolean? = null,
)

@Serializable
data class NuvioSubtitleResult(
    val url: String,
    val language: String,
    val name: String? = null,
    val headers: Map<String, String>? = null,
)

data class NuvioRuntimeResult(
    val title: String,
    val name: String? = null,
    val url: String,
    val quality: String? = null,
    val size: String? = null,
    val language: String? = null,
    val provider: String? = null,
    val type: String? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val infoHash: String? = null,
    val headers: Map<String, String>? = null,
    val subtitles: List<NuvioSubtitleResult>? = null,
)

data class NuvioRepositoryItem(
    val manifestUrl: String,
    val name: String,
    val description: String? = null,
    val version: String? = null,
    val scraperCount: Int = 0,
    val lastUpdated: Long = 0L,
)

@Serializable
data class NuvioStoredScraper(
    val id: String,
    val repositoryUrl: String,
    val name: String,
    val description: String,
    val author: String? = null,
    val version: String,
    val filename: String,
    val supportedTypes: List<String>,
    val enabled: Boolean,
    val manifestEnabled: Boolean,
    val hasSettings: Boolean = false,
    val logo: String? = null,
    val contentLanguage: List<String> = emptyList(),
    val formats: List<String>? = null,
)

data class NuvioScraper(
    val id: String,
    val repositoryUrl: String,
    val name: String,
    val description: String,
    val author: String? = null,
    val version: String,
    val filename: String,
    val supportedTypes: List<String>,
    val enabled: Boolean,
    val manifestEnabled: Boolean,
    val hasSettings: Boolean = false,
    val logo: String? = null,
    val contentLanguage: List<String> = emptyList(),
    val formats: List<String>? = null,
    val code: String,
) {
    fun supportsType(type: String): Boolean {
        val normalized = normalizeNuvioType(type)
        return supportedTypes.any { normalizeNuvioType(it) == normalized }
    }
}

internal fun nuvioManifestScraperToStored(
    scraper: NuvioManifestScraper,
    repositoryUrl: String,
    enabled: Boolean,
): NuvioStoredScraper = NuvioStoredScraper(
    id = scraper.id,
    repositoryUrl = repositoryUrl,
    name = scraper.name,
    description = scraper.description.orEmpty(),
    author = scraper.author,
    version = scraper.version,
    filename = scraper.filename,
    supportedTypes = scraper.supportedTypes,
    enabled = enabled,
    manifestEnabled = scraper.enabled,
    hasSettings = scraper.hasSettings,
    logo = scraper.logo,
    contentLanguage = scraper.contentLanguage.orEmpty(),
    formats = scraper.formats ?: scraper.supportedFormats,
)

internal fun NuvioStoredScraper.withCode(code: String): NuvioScraper = NuvioScraper(
    id = id,
    repositoryUrl = repositoryUrl,
    name = name,
    description = description,
    author = author,
    version = version,
    filename = filename,
    supportedTypes = supportedTypes,
    enabled = enabled,
    manifestEnabled = manifestEnabled,
    hasSettings = hasSettings,
    logo = logo,
    contentLanguage = contentLanguage,
    formats = formats,
    code = code,
)

internal fun normalizeNuvioType(value: String): String = when (value.lowercase()) {
    "series", "show", "other" -> "tv"
    else -> value.lowercase()
}
