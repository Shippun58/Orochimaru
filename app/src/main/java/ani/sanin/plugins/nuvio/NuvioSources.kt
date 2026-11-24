package ani.sanin.plugins.nuvio

import android.content.Context
import ani.sanin.cloudstream.CsInstalledSource

internal object NuvioSources {
    const val SOURCE_PREFIX = "nuvio:"

    fun sourceId(scraper: NuvioScraper): String =
        "$SOURCE_PREFIX${scraper.repositoryUrl}:${scraper.id}"

    fun isNuvioSource(id: String?): Boolean =
        id?.startsWith(SOURCE_PREFIX, ignoreCase = true) == true

    fun scraperForSourceId(context: Context, id: String?): NuvioScraper? {
        if (!isNuvioSource(id)) return null
        val raw = id!!.substring(SOURCE_PREFIX.length)
        val scraperId = raw.substringAfterLast(':')
        return NuvioRepository.installed(context).firstOrNull {
            it.id.equals(scraperId, ignoreCase = true) && sourceId(it).equals(id, ignoreCase = true)
        }
    }

    fun installed(context: Context, mediaType: String? = null): List<NuvioScraper> =
        NuvioRepository.installed(context).filter { scraper ->
            scraper.enabled && (mediaType == null || scraper.supportsType(mediaType))
        }

    fun synthetic(context: Context, scraper: NuvioScraper): CsInstalledSource =
        CsInstalledSource(
            id = sourceId(scraper),
            name = scraper.name,
            version = 1,
            type = if (scraper.supportsType("tv")) "tv" else "movie",
            lang = scraper.contentLanguage.firstOrNull()?.takeIf { it.isNotBlank() } ?: "en",
            url = scraper.repositoryUrl,
            repoUrl = scraper.repositoryUrl,
            iconUrl = scraper.logo,
        )

    fun synthetics(context: Context, mediaType: String? = null): List<CsInstalledSource> =
        installed(context, mediaType).map { synthetic(context, it) }
}
