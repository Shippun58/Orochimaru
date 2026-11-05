package ani.sanin.cloudstream

import android.content.Context
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.customAlertDialog
import java.util.Locale

object CsTypeFilter {

    val options = listOf("All", "Anime", "Movie", "TV", "Live", "Other")

    fun current(): String = PrefManager.getVal(PrefName.CloudStreamTypeFilter)

    fun matches(type: String): Boolean {
        val filter = current().lowercase(Locale.ROOT)
        val t = type.lowercase(Locale.ROOT)
        return when (filter) {
            "all" -> true
            "anime" -> t == "anime"
            "movie" -> t == "movie"
            "tv" -> t == "tv" || t == "series" || t.contains("series")
            "live" -> t.contains("live")
            "other" -> t !in listOf("anime", "movie", "tv", "series", "live")
            else -> true
        }
    }

    fun show(context: Context, onChanged: () -> Unit) {
        val idx = options.indexOfFirst { it.equals(current(), true) }.coerceAtLeast(0)
        context.customAlertDialog().apply {
            setTitle("Filter")
            singleChoiceItems(options.toTypedArray(), idx) { selected ->
                PrefManager.setVal(PrefName.CloudStreamTypeFilter, options[selected])
                onChanged()
            }
            setNegButton("Cancel")
            show()
        }
    }
}
