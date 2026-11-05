package eu.kanade.tachiyomi.util.system

import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {

    val comparator = compareBy<String>(
        { getDisplayName(it) },
        { it == "all" },
    )

    fun getSourceDisplayName(lang: String?): String {
        return when (lang) {
            LAST_USED_KEY -> "Last used"
            PINNED_KEY -> "Pinned"
            "other" -> "Other"
            "all" -> "Multi"
            else -> getDisplayName(lang)
        }
    }

    fun getDisplayName(lang: String?): String {
        if (lang == null) {
            return ""
        }

        val locale = when (lang) {
            "" -> LocaleListCompat.getAdjustedDefault()[0]
            "zh-CN" -> Locale.forLanguageTag("zh-Hans")
            "zh-TW" -> Locale.forLanguageTag("zh-Hant")
            else -> Locale.forLanguageTag(lang)
        }
        return locale!!.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
    }

    fun getDefaultEnabledLanguages(): Set<String> {
        return setOf("all", "en", Locale.getDefault().language)
    }

    fun getSimpleLocaleDisplayName(): String {
        val sp = Locale.getDefault().language.split("_", "-")
        return Locale(sp[0]).getDisplayLanguage(LocaleListCompat.getDefault()[0]!!)
    }
}

internal const val PINNED_KEY = "pinned"
internal const val LAST_USED_KEY = "last_used"
