package com.lagradost.cloudstream3.utils

import java.util.Locale

fun getCurrentLocale(): String = java.util.Locale.getDefault().toLanguageTag()

fun localizedLanguageName(ietfTag: String, localizedTo: String): String? {
    val localeOfLangCode = Locale.forLanguageTag(ietfTag)
    val localeOfLocalizeTo = Locale.forLanguageTag(localizedTo)
    val displayName = localeOfLangCode.getDisplayName(localeOfLocalizeTo)

    val langCodeWithCountry = "${localeOfLangCode.language} ("
    val failed =
        displayName.equals(ietfTag, ignoreCase = true) ||
        displayName.contains(langCodeWithCountry, ignoreCase = true)

    return if (failed) null else displayName
}