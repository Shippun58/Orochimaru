package ani.sanin.home

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import ani.sanin.R
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil

data class EditorialCardData(
    val title: String,
    val meta: String = "",
    val imageUrl: String? = null,
    val externalUrl: String? = null
)

data class EditorialShelfData(
    val title: String,
    val note: String,
    val cards: List<EditorialCardData>,
    val placeholderCount: Int = 3
)

object EditorialShelfRenderer {
    fun render(context: Context, host: LinearLayout, shelves: List<EditorialShelfData>) {
        host.removeAllViews()
        shelves.filter { it.cards.isNotEmpty() || it.placeholderCount > 0 }.forEach { shelf ->
            val section = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val heading = TextView(context).apply {
                text = shelf.title
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getColorCompat(com.google.android.material.R.attr.colorOnSurface))
            }
            section.addView(heading)
            val note = TextView(context).apply {
                text = shelf.note
                textSize = 12f
                alpha = 0.72f
                setPadding(0, dp(context, 2), 0, dp(context, 8))
                setTextColor(context.getColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            section.addView(note)

            val scroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                isFillViewport = false
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
            }
            val cards = if (shelf.cards.isNotEmpty()) shelf.cards else emptyList()
            cards.forEach { row.addView(card(context, it)) }
            if (cards.isEmpty()) {
                repeat(shelf.placeholderCount.coerceAtLeast(1)) { index ->
                    row.addView(card(context, EditorialCardData(
                        title = when (index) {
                            0 -> "Artwork loading"
                            1 -> "More picks soon"
                            else -> "Your next obsession"
                        },
                        meta = "Preview placeholder"
                    )))
                }
            }
            scroll.addView(row)
            section.addView(scroll)
            host.addView(section)
        }
    }

    private fun card(context: Context, data: EditorialCardData): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = false
            background = context.getDrawable(R.drawable.bg_editorial_card_focus)
            layoutParams = LinearLayout.LayoutParams(dp(context, 132), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(context, 10)
                bottomMargin = dp(context, 8)
            }
            setPadding(dp(context, 5))
        }
        val art = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 172)
            )
            background = context.getDrawable(R.drawable.bg_editorial_artwork)
        }
        val placeholder = ImageView(context).apply {
            setImageResource(R.drawable.ic_editorial_artwork_unavailable)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(context, 38))
            alpha = 0.68f
            contentDescription = "Artwork unavailable"
        }
        art.addView(placeholder, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        if (!data.imageUrl.isNullOrBlank()) {
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = data.title
            }
            art.addView(image, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            image.loadImage(data.imageUrl)
        }
        card.addView(art)
        val title = TextView(context).apply {
            text = data.title.ifBlank { "Untitled pick" }
            textSize = 12.5f
            maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColorCompat(android.R.attr.textColorPrimary))
            setPadding(dp(context, 2), dp(context, 7), dp(context, 2), 0)
        }
        card.addView(title)
        if (data.meta.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = data.meta
                textSize = 10.5f
                maxLines = 1
                alpha = 0.7f
                setTextColor(context.getColorCompat(android.R.attr.textColorSecondary))
                setPadding(dp(context, 2), dp(context, 3), dp(context, 2), dp(context, 4))
            })
        }
        if (!data.externalUrl.isNullOrBlank()) {
            card.isClickable = true
            card.setOnClickListener {
                runCatching {
                    context.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(data.externalUrl)
                    ))
                }
            }
        }
        FocusEffectUtil.applyFocusListener(card)
        return card
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun Context.getColorCompat(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, value.resourceId)
        else value.data
    }
}
