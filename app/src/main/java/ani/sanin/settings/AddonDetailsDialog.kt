package ani.sanin.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
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
        val primary = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val onPrimary = context.getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        val borderWidth = (3f * density).toInt()
        fun styleAction(view: View, focused: Boolean) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (focused) primary else Color.WHITE)
                cornerRadius = 24f * density
            }
            view.foreground = if (focused) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 24f * density
                    setStroke(borderWidth, onPrimary)
                }
            } else null
        }

        if (!availableTab) {
            val bridgeButton = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt(),
                ).apply { marginEnd = (8 * density).toInt() }
                isFocusable = true
                setImageResource(R.drawable.ic_places_bridge)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(
                    (12 * density).toInt(),
                    (12 * density).toInt(),
                    (12 * density).toInt(),
                    (12 * density).toInt(),
                )
                var focused = false
                fun refresh() {
                    styleAction(this@apply, focused)
                    val bridged = AnimeBridgeStore.isBridged(AnimeBridgeStore.STREMIO, card.url)
                    imageTintList = ColorStateList.valueOf(
                        if (focused) onPrimary else if (bridged) primary else Color.BLACK
                    )
                    contentDescription =
                        if (bridged) "Destroy Aniyomi bridge" else "Bridge to Aniyomi"
                }
                refresh()
                setOnFocusChangeListener { _, hasFocus ->
                    focused = hasFocus
                    refresh()
                }
                setOnClickListener {
                    AnimeBridgeUi.confirm(
                        context,
                        AnimeBridgeStore.STREMIO,
                        card.url,
                        card.name,
                    ) { refresh() }
                }
            }
            actions.addView(bridgeButton)
        }

        actions.addView(
            MaterialButton(context).apply {
                text = "Copy manifest URL"
                isAllCaps = false
                isFocusable = true
                backgroundTintList = null
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
                fun styleCopy(focused: Boolean) {
                    styleAction(this@apply, focused)
                    setTextColor(if (focused) onPrimary else Color.BLACK)
                }
                styleCopy(false)
                setOnFocusChangeListener { _, hasFocus -> styleCopy(hasFocus) }
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
