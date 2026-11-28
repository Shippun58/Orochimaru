package ani.sanin.bridge

import android.view.View
import ani.sanin.R
import ani.sanin.util.OverflowMenuItem
import ani.sanin.util.showOverflowMenu

object AnimeBridgeMenu {
    fun show(
        anchor: View,
        kind: String,
        id: String,
        name: String,
        settingsLabel: String? = null,
        onSettings: (() -> Unit)? = null,
        deleteLabel: String = "Delete",
        onDelete: (() -> Unit)? = null,
    ) {
        val bridged = AnimeBridgeStore.isBridged(kind, id)
        val items = mutableListOf<OverflowMenuItem>()
        items.add(
            OverflowMenuItem(
                if (bridged) "Destroy Aniyomi bridge" else "Bridge to Aniyomi",
                R.drawable.ic_places_bridge,
            ) {
                AnimeBridgeUi.confirm(anchor.context, kind, id, name)
            }
        )
        if (onSettings != null && settingsLabel != null) {
            items.add(
                OverflowMenuItem(settingsLabel, R.drawable.ic_round_settings_24) {
                    onSettings.invoke()
                }
            )
        }
        if (onDelete != null) {
            items.add(
                OverflowMenuItem(deleteLabel, R.drawable.ic_round_delete_24) {
                    onDelete.invoke()
                }
            )
        }
        showOverflowMenu(anchor, items)
    }
}
