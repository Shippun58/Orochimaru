package ani.sanin.bridge

import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

object AnimeBridgeStore {
    const val CLOUDSTREAM = "cloudstream"
    const val NUVIO = "nuvio"
    const val STREMIO = "stremio"

    fun isBridged(kind: String, id: String): Boolean {
        val key = key(kind, id)
        return key in PrefManager.getVal<Set<String>>(PrefName.AnimeBridgeSources)
    }

    fun setBridged(kind: String, id: String, bridged: Boolean) {
        val key = key(kind, id)
        val current = PrefManager.getVal<Set<String>>(PrefName.AnimeBridgeSources).toMutableSet()
        if (bridged) current.add(key) else current.remove(key)
        PrefManager.setVal(PrefName.AnimeBridgeSources, current)
    }

    fun toggle(kind: String, id: String): Boolean {
        val next = !isBridged(kind, id)
        setBridged(kind, id, next)
        return next
    }

    private fun key(kind: String, id: String): String = "$kind::$id"
}
