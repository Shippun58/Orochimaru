package ani.sanin.parsers.novel

import android.graphics.drawable.Drawable
import eu.kanade.tachiyomi.extension.InstallStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

open class NovelExtension {
    data class Available(
        val name: String = "",
        val pkgName: String = "",
        val apkName: String = "",
        val versionCode: Long = 0,
        val repository: String = "",
        val sources: List<AvailableNovelSources> = emptyList(),
        val iconUrl: String = "",
    )

    data class Installed(
        val name: String = "",
        val pkgName: String = "",
        val sources: List<AvailableNovelSources> = emptyList(),
        val icon: Drawable? = null,
    )
}

data class AvailableNovelSources(
    val id: Long = 0,
    val lang: String = "",
    val name: String = "",
    val baseUrl: String = "",
)

open class NovelExtensionManager {
    val installedExtensionsFlow: StateFlow<List<NovelExtension.Installed>> =
        MutableStateFlow(emptyList())

    fun updateInstallStep(downloadId: Long, step: InstallStep) {}
    fun setInstalling(downloadId: Long) {}
}
