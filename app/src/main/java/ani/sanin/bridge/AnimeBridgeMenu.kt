package ani.sanin.bridge

import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import ani.sanin.R

object AnimeBridgeMenu {
    private const val BRIDGE = 1
    private const val SETTINGS = 2
    private const val DELETE = 3

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
        val popup = PopupMenu(anchor.context, anchor)
        popup.setForceShowIcon(true)
        val bridged = AnimeBridgeStore.isBridged(kind, id)
        popup.menu.add(
            Menu.NONE,
            BRIDGE,
            0,
            if (bridged) "Remove from anime mode" else "Bridge to anime mode",
        ).setIcon(R.drawable.ic_places_bridge)
        if (onSettings != null && settingsLabel != null) {
            popup.menu.add(Menu.NONE, SETTINGS, 1, settingsLabel)
                .setIcon(R.drawable.ic_round_settings_24)
        }
        if (onDelete != null) {
            popup.menu.add(Menu.NONE, DELETE, 2, deleteLabel)
                .setIcon(R.drawable.ic_round_delete_24)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                BRIDGE -> {
                    AnimeBridgeUi.confirm(anchor.context, kind, id, name)
                    true
                }

                SETTINGS -> {
                    onSettings?.invoke()
                    true
                }

                DELETE -> {
                    onDelete?.invoke()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }
}
