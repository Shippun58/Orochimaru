package ani.sanin.media

import ani.sanin.parsers.BaseParser

enum class SearchSourceCategory { API, BUILT_IN, EXTENSION }

data class SearchSource(
    val key: String,
    val label: String,
    val category: SearchSourceCategory,
    val parser: BaseParser? = null,
) {
    companion object {
        const val ANILIST = "anilist"
        const val MAL = "mal"
        const val KITSU = "kitsu"
        const val ALL = "__all__"
    }
}
