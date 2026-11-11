package ani.sanin.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
        container.addView(
            MaterialButton(context).apply {
                text = "Copy manifest URL"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (14 * density).toInt() }
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
