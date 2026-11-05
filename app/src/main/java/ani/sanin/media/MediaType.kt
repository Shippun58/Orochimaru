package ani.sanin.media

interface Type {
    fun asText(): String
}

enum class MediaType : Type {
    ANIME;

    override fun asText(): String {
        return when (this) {
            ANIME -> "Anime"
        }
    }

    companion object {
        fun fromText(string: String): MediaType? {
            return when (string) {
                "Anime" -> ANIME
                else -> {
                    null
                }
            }
        }
    }
}

