package ani.sanin.util

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan

fun failedLabel(name: String): SpannableString {
    val mark = "❗\uFE0E"
    val label = SpannableString("$name $mark")
    val start = label.length - mark.length
    label.setSpan(
        ForegroundColorSpan(Color.parseColor("#FF5252")),
        start,
        label.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    return label
}
