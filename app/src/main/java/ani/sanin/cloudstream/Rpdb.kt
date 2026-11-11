package ani.sanin.cloudstream

import ani.sanin.settings.StremioAddonManager
import ani.sanin.util.Logger

object Rpdb {

    private var cachedKey: String? = null
    private var checked = false

    private fun decodedVariants(raw: String): List<String> = buildList {
        add(raw)
        raw.split('/').forEach { segment ->
            val clean = segment.substringBefore('?')
            if (clean.length < 8 || clean.endsWith(".json", true)) return@forEach
            val padded = clean.trimEnd('=') + "=".repeat((4 - clean.length % 4) % 4)
            runCatching { add(String(java.util.Base64.getUrlDecoder().decode(padded))) }
            runCatching { add(String(java.util.Base64.getDecoder().decode(padded))) }
        }
    }

    fun key(): String? {
        if (checked) return cachedKey
        checked = true
        val rx = Regex("api\\.ratingposterdb\\.com/([A-Za-z0-9_-]+)")
        val hay = StremioAddonManager.installed().joinToString("\n") { addon ->
            val parts = mutableListOf(
                addon.transportUrl,
                addon.manifest.logo ?: "",
                addon.manifest.id,
                addon.manifest.description,
            )
            decodedVariants(addon.transportUrl).forEach { parts.add(it) }
            parts.joinToString("\n")
        }
        cachedKey = rx.find(hay)?.groupValues?.get(1)
        Logger.log("RPDB: key detected=${cachedKey != null}")
        return cachedKey
    }

    fun posterUrl(type: String, tmdbId: Int): String? {
        if (tmdbId <= 0) return null
        val k = key() ?: return null
        val idPart = if (type == "tv") "series-$tmdbId" else "movie-$tmdbId"
        return "https://api.ratingposterdb.com/$k/tmdb/poster-default/$idPart.jpg?fallback=true"
    }
}
