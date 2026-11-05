package ani.sanin.settings.saving.internal

import kotlin.reflect.KClass

data class Pref(
    val prefLocation: Location,
    val type: KClass<*>,
    val default: Any
)

enum class Location(val location: String, val exportable: Boolean) {
    General("ani.sanin.general", true),
    UI("ani.sanin.ui", true),
    Player("ani.sanin.player", true),
    Reader("ani.sanin.reader", true),
    NovelReader("ani.sanin.novelReader", true),
    Irrelevant("ani.sanin.irrelevant", false),
    AnimeDownloads("animeDownloads", false),
    Protected("ani.sanin.protected", true),
}
