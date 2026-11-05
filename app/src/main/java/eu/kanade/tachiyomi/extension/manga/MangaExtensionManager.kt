package eu.kanade.tachiyomi.extension.manga

import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

open class MangaExtensionManager {
    val installedExtensionsFlow: StateFlow<List<MangaExtension.Installed>> =
        MutableStateFlow(emptyList())

    fun updateInstallStep(downloadId: Long, step: InstallStep) {}
    fun setInstalling(downloadId: Long) {}
}
