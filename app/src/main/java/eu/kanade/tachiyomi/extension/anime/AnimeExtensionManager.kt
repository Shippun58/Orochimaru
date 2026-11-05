package eu.kanade.tachiyomi.extension.anime

import android.content.Context
import android.graphics.drawable.Drawable
import ani.sanin.media.MediaType
import ani.sanin.snackString
import ani.sanin.util.Logger
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AnimeLoadResult
import eu.kanade.tachiyomi.extension.anime.model.AvailableAnimeSources
import eu.kanade.tachiyomi.extension.api.ExtensionGithubApi
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.util.preference.plusAssign
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rx.Observable
import tachiyomi.core.util.lang.launchNow
import tachiyomi.core.util.lang.withUIContext
import tachiyomi.domain.source.anime.model.AnimeSourceData
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class AnimeExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
) {

    var isInitialized = false
        private set

    private val api = ExtensionGithubApi()

    private val installer by lazy { ExtensionInstaller(context) }

    private val iconMap = mutableMapOf<String, Drawable>()

    private val _installedAnimeExtensionsFlow =
        MutableStateFlow(emptyList<AnimeExtension.Installed>())
    val installedExtensionsFlow = _installedAnimeExtensionsFlow.asStateFlow()

    private var subLanguagesEnabledOnFirstRun = preferences.enabledLanguages().isSet()

    fun getAppIconForSource(sourceId: Long): Drawable? {
        val pkgName =
            _installedAnimeExtensionsFlow.value.find { ext -> ext.sources.any { it.id == sourceId } }?.pkgName
        if (pkgName != null) {
            return iconMap[pkgName]
                ?: iconMap.getOrPut(pkgName) { context.packageManager.getApplicationIcon(pkgName) }
        }
        return null
    }

    private val _availableAnimeExtensionsFlow =
        MutableStateFlow(emptyList<AnimeExtension.Available>())
    val availableExtensionsFlow = _availableAnimeExtensionsFlow.asStateFlow()

    private var availableAnimeExtensionsSourcesData: Map<Long, AnimeSourceData> = emptyMap()

    private fun setupAvailableAnimeExtensionsSourcesDataMap(animeextensions: List<AnimeExtension.Available>) {
        if (animeextensions.isEmpty()) return
        availableAnimeExtensionsSourcesData = animeextensions
            .flatMap { ext -> ext.sources.map { it.toAnimeSourceData() } }
            .associateBy { it.id }
    }

    fun getSourceData(id: Long) = availableAnimeExtensionsSourcesData[id]

    private val _untrustedAnimeExtensionsFlow =
        MutableStateFlow(emptyList<AnimeExtension.Untrusted>())
    val untrustedExtensionsFlow = _untrustedAnimeExtensionsFlow.asStateFlow()

    init {
        initAnimeExtensions()
        ExtensionInstallReceiver().setAnimeListener(InstallationListener()).register(context)
    }

    private fun initAnimeExtensions() {
        val animeextensions = ExtensionLoader.loadAnimeExtensions(context)

        _installedAnimeExtensionsFlow.value = animeextensions
            .filterIsInstance<AnimeLoadResult.Success>()
            .map { it.extension }

        _untrustedAnimeExtensionsFlow.value = animeextensions
            .filterIsInstance<AnimeLoadResult.Untrusted>()
            .map { it.extension }

        isInitialized = true
    }

    suspend fun findAvailableExtensions() {
        val extensions: List<AnimeExtension.Available> = try {
            api.findAnimeExtensions()
        } catch (e: Exception) {
            Logger.log(e)
            withUIContext { snackString("Failed to get extensions list") }
            emptyList()
        }

        enableAdditionalSubLanguages(extensions)

        _availableAnimeExtensionsFlow.value = extensions
        updatedInstalledAnimeExtensionsStatuses(extensions)
        setupAvailableAnimeExtensionsSourcesDataMap(extensions)
    }

    private fun enableAdditionalSubLanguages(animeextensions: List<AnimeExtension.Available>) {
        if (subLanguagesEnabledOnFirstRun || animeextensions.isEmpty()) {
            return
        }

        val availableLanguages = animeextensions
            .flatMap(AnimeExtension.Available::sources)
            .distinctBy(AvailableAnimeSources::lang)
            .map(AvailableAnimeSources::lang)

        val deviceLanguage = Locale.getDefault().language
        val defaultLanguages = preferences.enabledLanguages().defaultValue()
        val languagesToEnable = availableLanguages.filter {
            it != deviceLanguage && it.startsWith(deviceLanguage)
        }

        preferences.enabledLanguages().set(defaultLanguages + languagesToEnable)
        subLanguagesEnabledOnFirstRun = true
    }

    private fun updatedInstalledAnimeExtensionsStatuses(availableAnimeExtensions: List<AnimeExtension.Available>) {
        if (availableAnimeExtensions.isEmpty()) {
            preferences.animeExtensionUpdatesCount().set(0)
            return
        }

        val mutInstalledAnimeExtensions = _installedAnimeExtensionsFlow.value.toMutableList()
        var changed = false

        for ((index, installedExt) in mutInstalledAnimeExtensions.withIndex()) {
            val pkgName = installedExt.pkgName
            val availableExt = availableAnimeExtensions.find { it.pkgName == pkgName }

            if (!installedExt.isUnofficial && availableExt == null && !installedExt.isObsolete) {
                mutInstalledAnimeExtensions[index] = installedExt.copy(isObsolete = true)
                changed = true
            } else if (availableExt != null) {
                val hasUpdate = installedExt.updateExists(availableExt)

                if (installedExt.hasUpdate != hasUpdate) {
                    mutInstalledAnimeExtensions[index] = installedExt.copy(hasUpdate = hasUpdate)
                    changed = true
                }
            }
        }
        if (changed) {
            _installedAnimeExtensionsFlow.value = mutInstalledAnimeExtensions
        }
        updatePendingUpdatesCount()
    }

    fun installExtension(extension: AnimeExtension.Available): Observable<InstallStep> {
        return installer.downloadAndInstall(
            api.getAnimeApkUrl(extension), extension.pkgName,
            extension.name, MediaType.ANIME
        )
    }

    fun updateExtension(extension: AnimeExtension.Installed): Observable<InstallStep> {
        val availableExt =
            _availableAnimeExtensionsFlow.value.find { it.pkgName == extension.pkgName }
                ?: return Observable.empty()
        return installExtension(availableExt)
    }

    fun cancelInstallUpdateExtension(extension: AnimeExtension) {
        installer.cancelInstall(extension.pkgName)
    }

    fun setInstalling(downloadId: Long) {
        installer.updateInstallStep(downloadId, InstallStep.Installing)
    }

    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        installer.updateInstallStep(downloadId, step)
    }

    fun uninstallExtension(pkgName: String) {
        installer.uninstallApk(pkgName)
    }

    private fun registerNewExtension(extension: AnimeExtension.Installed) {
        _installedAnimeExtensionsFlow.value += extension
    }

    private fun registerUpdatedExtension(extension: AnimeExtension.Installed) {
        val mutInstalledAnimeExtensions = _installedAnimeExtensionsFlow.value.toMutableList()
        val oldAnimeExtension = mutInstalledAnimeExtensions.find { it.pkgName == extension.pkgName }
        if (oldAnimeExtension != null) {
            mutInstalledAnimeExtensions -= oldAnimeExtension
        }
        mutInstalledAnimeExtensions += extension
        _installedAnimeExtensionsFlow.value = mutInstalledAnimeExtensions
    }

    private fun unregisterAnimeExtension(pkgName: String) {
        val installedAnimeExtension =
            _installedAnimeExtensionsFlow.value.find { it.pkgName == pkgName }
        if (installedAnimeExtension != null) {
            _installedAnimeExtensionsFlow.value -= installedAnimeExtension
        }
        val untrustedAnimeExtension =
            _untrustedAnimeExtensionsFlow.value.find { it.pkgName == pkgName }
        if (untrustedAnimeExtension != null) {
            _untrustedAnimeExtensionsFlow.value -= untrustedAnimeExtension
        }
    }

    private inner class InstallationListener : ExtensionInstallReceiver.AnimeListener {

        override fun onExtensionInstalled(extension: AnimeExtension.Installed) {
            registerNewExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
        }

        override fun onExtensionUpdated(extension: AnimeExtension.Installed) {
            registerUpdatedExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
        }

        override fun onExtensionUntrusted(extension: AnimeExtension.Untrusted) {
            _untrustedAnimeExtensionsFlow.value += extension
        }

        override fun onPackageUninstalled(pkgName: String) {
            unregisterAnimeExtension(pkgName)
            updatePendingUpdatesCount()
        }
    }

    private fun AnimeExtension.Installed.withUpdateCheck(): AnimeExtension.Installed {
        return if (updateExists()) {
            copy(hasUpdate = true)
        } else {
            this
        }
    }

    private fun AnimeExtension.Installed.updateExists(availableAnimeExtension: AnimeExtension.Available? = null): Boolean {
        val availableExt = availableAnimeExtension
            ?: _availableAnimeExtensionsFlow.value.find { it.pkgName == pkgName }
        if (availableExt == null) return false

        if (availableExt.versionName.isNotBlank() && availableExt.versionName == versionName) return false
        return (availableExt.versionCode > versionCode || availableExt.libVersion > (libVersion + 0.001))
    }

    private fun updatePendingUpdatesCount() {
        preferences.animeExtensionUpdatesCount()
            .set(_installedAnimeExtensionsFlow.value.count { it.hasUpdate })
    }
}
