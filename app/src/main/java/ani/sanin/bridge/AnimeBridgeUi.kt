package ani.sanin.bridge

import android.content.Context
import ani.sanin.parsers.AnimeSources
import ani.sanin.snackString
import ani.sanin.util.customAlertDialog

object AnimeBridgeUi {
    fun confirm(
        context: Context,
        kind: String,
        id: String,
        name: String,
        onChanged: (Boolean) -> Unit = {},
    ) {
        val bridged = AnimeBridgeStore.isBridged(kind, id)
        context.customAlertDialog().apply {
            setTitle(if (bridged) "Destroy Aniyomi bridge?" else "Bridge to Aniyomi?")
            setMessage(
                if (bridged) {
                    "Stop using $name for Aniyomi? You can bridge it again later."
                } else {
                    "Bridge $name to Aniyomi? It will appear in Aniyomi watch as a source. " +
                        "Use Wrong Title? if the TMDB match is wrong."
                }
            )
            setPosButton(if (bridged) "Destroy bridge" else "Bridge") {
                val next = AnimeBridgeStore.toggle(kind, id)
                AnimeSources.refreshPluginSources()
                onChanged(next)
                snackString(
                    if (next) "Bridged $name to Aniyomi"
                    else "Destroyed Aniyomi bridge for $name"
                )
            }
            setNegButton("Cancel")
            show()
        }
    }
}
