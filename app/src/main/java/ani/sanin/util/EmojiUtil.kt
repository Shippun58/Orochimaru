package ani.sanin.util

import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

object EmojiUtil {

    fun filter(text: String): String {
        if (PrefManager.getVal(PrefName.Emoji)) return text
        return text.replace(emojiRegex, "").trim()
    }

    private val emojiRegex = Regex(
        "[\\u00A9\\u00AE\\u2000-\\u3300\\uD83C[\\uD000-\\uDFFF]\\uD83D[\\uD000-\\uDFFF]\\uD83E[\\uD000-\\uDFFF]]"
    )
}
