package ani.sanin.cloudstream

import ani.sanin.Mapper
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

@Serializable
private data class CsLinkEnvelope(
    val link: String,
    val extra: Map<String, String> = emptyMap()
)

object CsAnimeProtocol {
    private val json = Mapper.json

    fun encodeLink(link: String, extra: Map<String, String>?): String {
        if (extra.isNullOrEmpty()) return link
        return json.encodeToString(CsLinkEnvelope(link, extra.toMap()))
    }

    fun decodeLink(value: String): Pair<String, Map<String, String>?> {
        if (!value.trimStart().startsWith("{")) return value to null
        return runCatching {
            val envelope = json.decodeFromString<CsLinkEnvelope>(value)
            envelope.link to envelope.extra.takeIf { it.isNotEmpty() }
        }.getOrDefault(value to null)
    }

    fun decodeMap(value: String?): Map<String, String> {
        if (value.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(value) as? JsonObject ?: return emptyMap()
            obj.entries.mapNotNull { (key, element) ->
                (element as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun encodeSubtitles(items: List<Pair<String, String>>): String? {
        if (items.isEmpty()) return null
        return buildJsonArray {
            items.forEach { (url, language) ->
                add(buildJsonObject {
                    put("url", url)
                    put("language", language)
                    put("type", subtitleType(url))
                })
            }
        }.toString()
    }

    private fun subtitleType(url: String): String = when {
        url.contains(".ass", ignoreCase = true) || url.contains(".ssa", ignoreCase = true) -> "ass"
        url.contains(".srt", ignoreCase = true) -> "srt"
        else -> "vtt"
    }
}
