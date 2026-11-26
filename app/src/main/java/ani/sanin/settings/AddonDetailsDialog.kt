package ani.sanin.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ani.sanin.R
import ani.sanin.bridge.AnimeBridgeStore
import ani.sanin.bridge.AnimeBridgeUi
import ani.sanin.getThemeColor
import ani.sanin.util.customAlertDialog
import com.google.android.material.button.MaterialButton

fun showAddonDetailsDialog(context: Context, card: RepoUi, availableTab: Boolean = false) {
    val density = context.resources.displayMetrics.density
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            (20 * density).toInt(),
            (8 * density).toInt(),
            (20 * density).toInt(),
            0,
        )
    }
    container.addView(
        TextView(context).apply {
            text = buildString {
                if (!card.description.isNullOrBlank()) append(card.description)
                append("\n\n")
                append(card.countText.orEmpty())
                if (card.contentTypes.isNotEmpty()) {
                    append("\nResources: ${card.contentTypes.joinToString()}")
                }
                if (card.languages.isNotEmpty()) {
                    append("\nTypes: ${card.languages.joinToString()}")
                }
            }.trim()
            textSize = 14f
        }
    )

    if (!availableTab || !card.needsConfiguration) {
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (14 * density).toInt() }
        }
        actions.addView(
            MaterialButton(context).apply {
                text = "Copy manifest URL"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
                setOnClickListener {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("Manifest URL", card.url)
                    )
                    Toast.makeText(context, "Manifest URL copied", Toast.LENGTH_SHORT).show()
                }
            }
        )
        if (!availableTab) {
            val bridgeButton = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt(),
                ).apply { marginStart = (8 * density).toInt() }
                setImageResource(R.drawable.ic_places_bridge)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(
                    (12 * density).toInt(),
                    (12 * density).toInt(),
                    (12 * density).toInt(),
                    (12 * density).toInt(),
                )
                val ripple = TypedValue()
                context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless,
                    ripple,
                    true,
                )
                setBackgroundResource(ripple.resourceId)

                fun tintBridge(bridged: Boolean) {
                    imageTintList = ColorStateList.valueOf(
                        context.getThemeColor(
                            if (bridged) android.R.attr.colorPrimary
                            else com.google.android.material.R.attr.colorOnSurface
                        )
                    )
                    contentDescription =
                        if (bridged) "Remove from anime mode" else "Bridge to anime mode"
                }

                tintBridge(AnimeBridgeStore.isBridged(AnimeBridgeStore.STREMIO, card.url))
                setOnClickListener {
                    AnimeBridgeUi.confirm(
                        context,
                        AnimeBridgeStore.STREMIO,
                        card.url,
                        card.name,
                    ) { tintBridge(it) }
                }
            }
            actions.addView(bridgeButton)
        }
        container.addView(actions)
    }

    if (availableTab && card.needsConfiguration && card.configUrl != null) {
        container.addView(
            MaterialButton(context).apply {
                text = "Configure"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin =
                        if (container.childCount > 1) (10 * density).toInt() else (14 * density).toInt()
                }
                setOnClickListener {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(card.configUrl)))
                    }.onFailure {
                        Toast.makeText(context, "Could not open configuration", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    context.customAlertDialog().apply {
        setTitle(card.name)
        setCustomView(container)
        setPosButton("OK")
        show()
    }
}
