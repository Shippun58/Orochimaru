package ani.sanin.settings

import android.content.Context
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.customAlertDialog
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension

object AnimeTypeFilter {

    fun classify(ext: AnimeExtension.Available): String {
        val n = ext.name.lowercase()
        if (ext.isNsfw) {
            if ("hentai" in n) return "Hentai"
            return "NSFW"
        }
        return when {
            "dong" in n -> "Donghua"
            "manga" in n -> "Manga"
            "jellyfin" in n || "stremio" in n || "torbox" in n -> "Aggregator"
            else -> "Anime"
        }
    }

    fun optionsFor(extensions: List<AnimeExtension.Available>): List<String> {
        val types = linkedSetOf<String>()
        extensions.forEach { types.add(classify(it)) }
        return listOf("All") + types.toList().sorted()
    }

    fun current(): String = PrefManager.getVal(PrefName.AnimeTypeFilter)

    fun matches(ext: AnimeExtension.Available): Boolean {
        val filter = current().lowercase()
        if (filter == "all") return true
        return classify(ext).lowercase() == filter
    }

    fun show(context: Context, options: List<String>, onChanged: () -> Unit) {
        val idx = options.indexOfFirst { it.equals(current(), true) }.coerceAtLeast(0)
        context.customAlertDialog().apply {
            setTitle("Filter")
            singleChoiceItems(options.toTypedArray(), idx) { selected ->
                PrefManager.setVal(PrefName.AnimeTypeFilter, options[selected])
                onChanged()
            }
            setNegButton("Cancel")
            show()
        }
    }
}
