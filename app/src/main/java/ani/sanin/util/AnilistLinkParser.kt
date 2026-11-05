package ani.sanin.util
object AnilistLinkParser {
    data class AnilistLink(
        val id: Int,
        val type: MediaType,
        val url: String,
        val startIndex: Int,
        val endIndex: Int
    )
    enum class MediaType {
        ANIME
    }
    private val ANIME_URL_PATTERN = Regex("""https://anilist\.co/anime/(\d+)/?\S*""")
    fun extractAnilistLinks(text: String): List<AnilistLink> {
        val links = mutableListOf<AnilistLink>()

        ANIME_URL_PATTERN.findAll(text).forEach { matchResult ->
            val id = matchResult.groupValues[1].toIntOrNull()
            if (id != null) {
                links.add(
                    AnilistLink(
                        id = id,
                        type = MediaType.ANIME,
                        url = matchResult.value,
                        startIndex = matchResult.range.first,
                        endIndex = matchResult.range.last + 1
                    )
                )
            }
        }

        return links.sortedBy { it.startIndex }
    }
    fun removeAnilistUrlsFromHtml(html: String): String {
        var result = html

        val linkTagPattern = Regex("""<a\s+href=["'](https://anilist\.co/(?:anime|manga)/\d+[^"']*)["'][^>]*>\s*\1\s*</a>""")
        result = linkTagPattern.replace(result, "")

        val linkTagWithTextPattern = Regex("""<a\s+href=["']https://anilist\.co/(?:anime|manga)/\d+[^"']*["'][^>]*>(.*?)</a>""")
        result = linkTagWithTextPattern.replace(result) { matchResult ->
            matchResult.groupValues[1]
        }

        result = ANIME_URL_PATTERN.replace(result, "")

        result = result.replace(Regex("""\s{2,}"""), " ")

        result = result.replace(Regex("""\s+([.,!?;:])"""), "$1")

        result = result.replace(Regex("""(<br>\s*){3,}"""), "<br><br>")

        result = result.trim()

        return result
    }

    fun replaceAnilistUrlsInHtml(html: String, titleMap: Map<Int, String>): String {
        var result = html

        val linkTagSamePattern = Regex("""<a\s+href=["'](https://anilist\.co/(?:anime|manga)/(\d+)[^"']*)["'][^>]*>\s*\1\s*</a>""")
        result = linkTagSamePattern.replace(result) { matchResult ->
            val id = matchResult.groupValues[2].toIntOrNull()
            titleMap[id]?.let { "<u><b>$it</b></u>" } ?: ""
        }

        val linkTagWithTextPattern = Regex("""<a\s+href=["']https://anilist\.co/(?:anime|manga)/\d+[^"']*["'][^>]*>(.*?)</a>""")
        result = linkTagWithTextPattern.replace(result) { matchResult ->
            matchResult.groupValues[1]
        }

        result = ANIME_URL_PATTERN.replace(result) { matchResult ->
            val id = matchResult.groupValues[1].toIntOrNull()
            titleMap[id]?.let { "<u><b>$it</b></u>" } ?: ""
        }

        result = result.replace(Regex("""\s{2,}"""), " ")
        result = result.replace(Regex("""\s+([.,!?;:])"""), "$1")
        result = result.replace(Regex("""(<br>\s*){3,}"""), "<br><br>")
        result = result.trim()

        return result
    }
}
