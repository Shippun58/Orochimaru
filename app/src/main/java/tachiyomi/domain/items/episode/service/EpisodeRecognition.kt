package tachiyomi.domain.items.episode.service

object EpisodeRecognition {

    private const val NUMBER_PATTERN = """([0-9]+)(\.[0-9]+)?(\.?[a-z]+)?"""

    private val basic = Regex("""(?<=ep\.) *$NUMBER_PATTERN""")

    private val number = Regex(NUMBER_PATTERN)

    private val unwanted = Regex("""\b(?:v|ver|vol|version|volume|season|s)[^a-z]?[0-9]+""")

    private val unwantedWhiteSpace = Regex("""\s(?=extra|special|omake)""")

    fun parseEpisodeNumber(
        animeTitle: String,
        episodeName: String,
        episodeNumber: Float? = null
    ): Float {

        if (episodeNumber != null && (episodeNumber == -2f || episodeNumber > -1f)) {
            return episodeNumber
        }

        var name = episodeName.lowercase()

        name = name.replace(animeTitle.lowercase(), "").trim()

        name = name.replace(',', '.').replace('-', '.')

        name = unwantedWhiteSpace.replace(name, "")

        name = unwanted.replace(name, "")

        basic.find(name)?.let { return getEpisodeNumberFromMatch(it) }

        number.find(name)?.let { return getEpisodeNumberFromMatch(it) }

        return episodeNumber ?: -1f
    }

    private fun getEpisodeNumberFromMatch(match: MatchResult): Float {
        return match.let {
            val initial = it.groups[1]?.value?.toFloat()!!
            val subChapterDecimal = it.groups[2]?.value
            val subChapterAlpha = it.groups[3]?.value
            val addition = checkForDecimal(subChapterDecimal, subChapterAlpha)
            initial.plus(addition)
        }
    }

    private fun checkForDecimal(decimal: String?, alpha: String?): Float {
        if (!decimal.isNullOrEmpty()) {
            return decimal.toFloat()
        }

        if (!alpha.isNullOrEmpty()) {
            if (alpha.contains("extra")) {
                return .99f
            }

            if (alpha.contains("omake")) {
                return .98f
            }

            if (alpha.contains("special")) {
                return .97f
            }

            val trimmedAlpha = alpha.trimStart('.')
            if (trimmedAlpha.length == 1) {
                return parseAlphaPostFix(trimmedAlpha[0])
            }
        }

        return .0f
    }

    private fun parseAlphaPostFix(alpha: Char): Float {
        val number = alpha.code - ('a'.code - 1)
        if (number >= 10) return 0f
        return number / 10f
    }
}
