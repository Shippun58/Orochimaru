package ani.sanin.media.comments

private val GIF_IMAGE_REGEX =
    Regex("""!\[[^\]]*\]\((https?://(?:[^()\s]|\([^()\s]*\))*)\)""")

private val GIF_ALT_MARKDOWN_REGEX =
    Regex("""!\[[^\]]*gif[^\]]*\]\([^)]*\)""", RegexOption.IGNORE_CASE)

private val GIF_URL_MARKDOWN_REGEX =
    Regex("""!\[[^\]]*\]\([^)]*\.gif[^)]*\)""", RegexOption.IGNORE_CASE)

private val GIF_BARE_MARKDOWN_REGEX =
    Regex("""!\[[^\]]*gif[^\]]*\]""", RegexOption.IGNORE_CASE)

private val GIF_UNCLOSED_MARKDOWN_REGEX =
    Regex("""!\[[^\]]*gif[^\]]*\]\(https?://[^)]*""", RegexOption.IGNORE_CASE)

data class GifCommentContent(
    val text: String,
    val gifUrl: String?,
    val gifAbove: Boolean,
)

fun parseGifCommentContent(content: String): GifCommentContent {
    val match = GIF_IMAGE_REGEX.find(content)
    if (match != null) {
        val gifUrl = match.groupValues.getOrNull(1)
        val gifAbove = content.substring(0, match.range.first).isBlank()
        return GifCommentContent(
            text = stripGifMarkdown(content.replace(GIF_IMAGE_REGEX, "")),
            gifUrl = gifUrl,
            gifAbove = gifAbove,
        )
    }
    return GifCommentContent(
        text = stripGifMarkdown(content),
        gifUrl = null,
        gifAbove = false,
    )
}

fun stripGifMarkdown(text: String): String = text
    .replace(GIF_ALT_MARKDOWN_REGEX, "")
    .replace(GIF_URL_MARKDOWN_REGEX, "")
    .replace(GIF_UNCLOSED_MARKDOWN_REGEX, "")
    .replace(GIF_BARE_MARKDOWN_REGEX, "")
    .trim()
