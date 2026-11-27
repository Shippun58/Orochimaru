package ani.sanin.bridge

import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

object AnimeBridgeStore {
    const val CLOUDSTREAM = "cloudstream"
    const val NUVIO = "nuvio"
    const val STREMIO = "stremio"

    fun isBridged(kind: String, id: String): Boolean {
        val key = key(kind, normalizeId(kind, id))
        if (key !in PrefManager.getVal<Set<String>>(PrefName.AnimeBridgeSources)) return false
        return kind != CLOUDSTREAM ||
            key !in PrefManager.getVal<Set<String>>(PrefName.AnimeCloudStreamUnbridged)
    }

    fun setBridged(kind: String, id: String, bridged: Boolean) {
        val key = key(kind, normalizeId(kind, id))
        val current = PrefManager.getVal<Set<String>>(PrefName.AnimeBridgeSources).toMutableSet()
        if (bridged) {
            current.add(key)
        } else {
            current.removeAll { it == key || it.startsWith("$key#") }
        }
        PrefManager.setVal(PrefName.AnimeBridgeSources, current)

        if (kind == CLOUDSTREAM) {
            val legacy = PrefManager.getVal<Set<String>>(PrefName.AnimeCloudStreamUnbridged).toMutableSet()
            legacy.removeAll { it == key || it.startsWith("$key#") }
            if (!bridged) legacy.add(key)
            PrefManager.setVal(PrefName.AnimeCloudStreamUnbridged, legacy)
        }
    }

    fun toggle(kind: String, id: String): Boolean {
        val next = !isBridged(kind, id)
        setBridged(kind, id, next)
        return next
    }

    fun snapshot(): Set<String> =
        PrefManager.getVal<Set<String>>(PrefName.AnimeBridgeSources) +
            PrefManager.getVal<Set<String>>(PrefName.AnimeCloudStreamUnbridged)

    private fun normalizeId(kind: String, id: String): String =
        if (kind == CLOUDSTREAM) id.substringBefore('#') else id

    private fun key(kind: String, id: String): String = "$kind::$id"
}
