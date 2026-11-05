package ani.sanin.util

object LanguageFlag {

    private fun countryCodeToFlag(country: String): String {
        val code = country.uppercase().take(2)
        if (code.length < 2) return ""
        return buildString {
            for (ch in code) {
                append(Character.toChars(0x1F1E6 + (ch.code - 'A'.code)))
            }
        }
    }

    fun flagForLanguage(lang: String): String {

        val dashMatch = Regex("^[a-zA-Z]{2}-([A-Za-z]{2}|\\d{3})$").find(lang)
        if (dashMatch != null) {
            val country = dashMatch.groupValues[1]

            if (country.length == 2) return countryCodeToFlag(country)
        }

        val bare = Regex("^([a-zA-Z]{2})$").find(lang)?.groupValues?.get(1)
        if (bare != null) {
            val mapped = LANGUAGE_TO_COUNTRY[bare.lowercase()]
            if (mapped != null) return countryCodeToFlag(mapped)
        }

        val nameKey = lang.trim().lowercase()
        val mappedCountry = FULL_NAME_TO_COUNTRY[nameKey]
        if (mappedCountry != null) return countryCodeToFlag(mappedCountry)

        return ""
    }

    private val LANGUAGE_TO_COUNTRY = mapOf(
        "en" to "US", "ja" to "JP", "ko" to "KR", "zh" to "CN",
        "es" to "ES", "fr" to "FR", "de" to "DE", "it" to "IT",
        "pt" to "PT", "ru" to "RU", "ar" to "SA", "hi" to "IN",
        "th" to "TH", "vi" to "VN", "tr" to "TR", "pl" to "PL",
        "nl" to "NL", "sv" to "SE", "da" to "DK", "fi" to "FI",
        "no" to "NO", "uk" to "UA", "cs" to "CZ", "el" to "GR",
        "he" to "IL", "ro" to "RO", "hu" to "HU", "id" to "ID",
        "ms" to "MY", "tl" to "PH", "bn" to "BD", "ur" to "PK",
    )

    private val FULL_NAME_TO_COUNTRY = mapOf(
        "english" to "US", "japanese" to "JP", "korean" to "KR",
        "chinese" to "CN", "spanish" to "ES", "french" to "FR",
        "german" to "DE", "italian" to "IT", "portuguese" to "PT",
        "russian" to "RU", "arabic" to "SA", "hindi" to "IN",
        "thai" to "TH", "vietnamese" to "VN", "turkish" to "TR",
        "polish" to "PL", "dutch" to "NL", "swedish" to "SE",
        "danish" to "DK", "finnish" to "FI", "norwegian" to "NO",
        "ukrainian" to "UA", "czech" to "CZ", "greek" to "GR",
        "hebrew" to "IL", "romanian" to "RO", "hungarian" to "HU",
        "indonesian" to "ID", "malay" to "MY", "filipino" to "PH",
        "bengali" to "BD", "urdu" to "PK", "tamil" to "IN",
        "telugu" to "IN", "punjabi" to "IN", "marathi" to "IN",
    )
}
