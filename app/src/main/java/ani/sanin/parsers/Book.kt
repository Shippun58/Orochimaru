package ani.sanin.parsers

import ani.sanin.FileUrl
import java.io.Serializable

data class Book(
    val name: String = "",
    val link: String = "",
    val coverUrl: FileUrl? = null,
    val chapters: List<Any> = emptyList(),
    val links: List<Any> = emptyList(),
) : Serializable
