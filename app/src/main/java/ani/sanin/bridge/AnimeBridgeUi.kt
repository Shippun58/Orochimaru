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
            setTitle(if (bridged) "Remove from anime mode?" else "Bridge to anime mode?")
            setMessage(
                if (bridged) {
                    "Stop using $name for anime? You can bridge it again later."
                } else {
                    "Bridge $name to anime mode? It will appear in anime watch as a source. " +
                        "Use Wrong Title? if the TMDB match is wrong."
                }
            )
            setPosButton(if (bridged) "Remove" else "Bridge") {
                val next = AnimeBridgeStore.toggle(kind, id)
                AnimeSources.refreshPluginSources()
                onChanged(next)
                snackString(
                    if (next) "Bridged $name to anime mode"
                    else "Removed $name from anime mode"
                )
            }
            setNegButton("Cancel")
            show()
        }
    }
}
