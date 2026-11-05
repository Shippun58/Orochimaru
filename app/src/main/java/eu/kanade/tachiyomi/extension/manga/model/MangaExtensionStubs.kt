package eu.kanade.tachiyomi.extension.manga.model

import android.graphics.drawable.Drawable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

open class MangaExtension {
    data class Available(
        val name: String = "",
        val pkgName: String = "",
        val versionName: String = "",
        val versionCode: Long = 0,
        val libVersion: Double = 0.0,
        val lang: String = "",
        val isNsfw: Boolean = false,
        val hasReadme: Boolean = false,
        val hasChangelog: Boolean = false,
        val sources: List<AvailableMangaSources> = emptyList(),
        val apkName: String = "",
        val repository: String = "",
        val iconUrl: String = "",
    )

    data class Installed(
        val name: String = "",
        val pkgName: String = "",
        val versionName: String = "",
        val versionCode: Long = 0,
        val sources: List<AvailableMangaSources> = emptyList(),
        val isNsfw: Boolean = false,
        val isUnofficial: Boolean = false,
        val icon: Drawable? = null,
    )
}

data class AvailableMangaSources(
    val id: Long = 0,
    val lang: String = "",
    val name: String = "",
    val baseUrl: String = "",
)

open class MangaExtensionManager {
    val installedExtensionsFlow: StateFlow<List<MangaExtension.Installed>> =
        MutableStateFlow(emptyList())
}
