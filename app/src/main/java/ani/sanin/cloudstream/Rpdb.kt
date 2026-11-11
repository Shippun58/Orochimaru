package ani.sanin.cloudstream

import ani.sanin.settings.StremioAddonManager
import ani.sanin.util.Logger

object Rpdb {

    private var cachedKey: String? = null
    private var checked = false

    fun key(): String? {
        if (checked) return cachedKey
        checked = true
        val rx = Regex("api\\.ratingposterdb\\.com/([A-Za-z0-9_-]+)")
        val hay = StremioAddonManager.installed().joinToString("\n") { addon ->
            addon.transportUrl + "\n" + (addon.manifest.logo ?: "") + "\n" + addon.manifest.id
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
