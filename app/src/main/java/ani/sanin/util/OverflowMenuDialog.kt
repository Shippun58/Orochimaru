package ani.sanin.util

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import ani.sanin.R
import ani.sanin.getThemeColor

data class OverflowMenuItem(
    val label: String,
    val iconRes: Int,
    val action: () -> Unit,
)

fun showOverflowMenu(anchor: View, items: List<OverflowMenuItem>) {
    if (items.isEmpty()) return
    val context = anchor.context
    val density = context.resources.displayMetrics.density
    val iconTint = ColorStateList.valueOf(
        context.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
    )
    val textColor = context.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
    val menuTypeface = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            (8 * density).toInt(),
            (8 * density).toInt(),
            (8 * density).toInt(),
            (8 * density).toInt(),
        )
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    var dialogRef: AlertDialog? = null
    val rows = mutableListOf<LinearLayout>()
    val ripple = TypedValue()

    items.forEach { item ->
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            ripple,
            true,
        )
        val row = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (52 * density).toInt(),
            )
            setPadding(
                (16 * density).toInt(),
                0,
                (16 * density).toInt(),
                0,
            )
            setBackgroundResource(ripple.resourceId)
            isFocusable = true
            isClickable = true
        }

        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (24 * density).toInt(),
                (24 * density).toInt(),
            )
            setImageResource(item.iconRes)
            imageTintList = iconTint
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(context).apply {
            text = item.label
            textSize = 15f
            setTextColor(textColor)
            typeface = menuTypeface
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = (16 * density).toInt() }
        }

        row.addView(icon)
        row.addView(label)
        row.setOnClickListener {
            dialogRef?.dismiss()
            item.action()
        }
        FocusEffectUtil.applyFocusListener(row)
        container.addView(row)
        rows.add(row)
    }

    rows.forEachIndexed { index, row ->
        row.nextFocusUpId = if (index == 0) row.id else rows[index - 1].id
        row.nextFocusDownId = if (index == rows.lastIndex) row.id else rows[index + 1].id
        row.nextFocusLeftId = row.id
        row.nextFocusRightId = row.id
    }
    val firstRow = rows.first()

    context.customAlertDialog()
        .setCustomView(container)
        .setCancelable(true)
        .setWidthPx((280 * density).toInt())
        .attach { dialog ->
            dialogRef = dialog
            dialog.setCanceledOnTouchOutside(true)
        }
        .setOnShowListener {
            firstRow.post { firstRow.requestFocus() }
        }
        .show()
}
