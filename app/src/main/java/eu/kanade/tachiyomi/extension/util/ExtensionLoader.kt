package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import ani.sanin.connections.crashlytics.CrashlyticsInterface
import ani.sanin.media.MediaType
import ani.sanin.util.Logger
import dalvik.system.PathClassLoader
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AnimeLoadResult
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.system.getApplicationIcon
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Locale

internal object ExtensionLoader {

    private val preferences: SourcePreferences by injectLazy()
    private val loadNsfwSource by lazy {
        preferences.showNsfwSource().get()
    }

    private const val ANIME_PACKAGE = "tachiyomi.animeextension"

    private const val XX_METADATA_SOURCE_CLASS = ".class"
    private const val XX_METADATA_SOURCE_FACTORY = ".factory"
    private const val XX_METADATA_NSFW = "n.nsfw"
    private const val XX_METADATA_HAS_README = ".hasReadme"
    private const val XX_METADATA_HAS_CHANGELOG = ".hasChangelog"
    const val ANIME_LIB_VERSION_MIN = 12
    const val ANIME_LIB_VERSION_MAX = 20

    val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES else 0)

    fun loadAnimeExtensions(context: Context): List<AnimeLoadResult> {
        val pkgManager = context.packageManager

        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }

        val extPkgs = installedPkgs.filter { isPackageAnExtension(MediaType.ANIME, it) }

        if (extPkgs.isEmpty()) return emptyList()

        return runBlocking {
            val deferred = extPkgs.map {
                async { loadAnimeExtension(context, it.packageName, it) }
            }
            deferred.map { it.await() }
        }
    }

    fun loadAnimeExtensionFromPkgName(context: Context, pkgName: String): AnimeLoadResult {
        val pkgInfo = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
        } catch (error: PackageManager.NameNotFoundException) {
            Logger.log(error)
            return AnimeLoadResult.Error
        }
        if (!isPackageAnExtension(MediaType.ANIME, pkgInfo)) {
            Logger.log("Tried to load a package that wasn't a extension ($pkgName)")
            return AnimeLoadResult.Error
        }
        return loadAnimeExtension(context, pkgName, pkgInfo)
    }

    private fun loadAnimeExtension(
        context: Context,
        pkgName: String,
        pkgInfo: PackageInfo
    ): AnimeLoadResult {
        val pkgManager = context.packageManager

        val appInfo = try {
            pkgManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
        } catch (error: PackageManager.NameNotFoundException) {
            Logger.log(error)
            return AnimeLoadResult.Error
        }

        val extName = pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Aniyomi: ")
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        if (versionName.isNullOrEmpty()) {
            Logger.log("Missing versionName for extension $extName")
            return AnimeLoadResult.Error
        }

        val rawLib = appInfo.metaData?.get("tachiyomix.extensionLib")
            ?: appInfo.metaData?.get("tachiyomi.animeextensionLib")
            ?: appInfo.metaData?.get("aniyomi.animeextensionLib")
        val libVersion = when (rawLib) {
            is Number -> rawLib.toDouble().takeUnless { it == 0.0 }
            is String -> rawLib.toDoubleOrNull()
            else -> null
        } ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        val majorLibVersion = libVersion?.toInt()
        if (libVersion == null || majorLibVersion == null || majorLibVersion < ANIME_LIB_VERSION_MIN || majorLibVersion > ANIME_LIB_VERSION_MAX) {
            Logger.log(
                "Lib version is $libVersion, while only versions " +
                        "$ANIME_LIB_VERSION_MIN to $ANIME_LIB_VERSION_MAX are allowed"
            )
            return AnimeLoadResult.Error
        }

        val isNsfw = appInfo.metaData.getInt("$ANIME_PACKAGE$XX_METADATA_NSFW") == 1
        if (!loadNsfwSource && isNsfw) {
            Logger.log("NSFW extension $pkgName not allowed")
            return AnimeLoadResult.Error
        }

        val hasReadme = appInfo.metaData.getInt("$ANIME_PACKAGE$XX_METADATA_HAS_README", 0) == 1
        val hasChangelog =
            appInfo.metaData.getInt("$ANIME_PACKAGE$XX_METADATA_HAS_CHANGELOG", 0) == 1

        val classLoader = try{
            PathClassLoader(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Throwable) {
            Logger.log("Extension load error: $extName")
            Injekt.get<CrashlyticsInterface>().logException(e)
            return AnimeLoadResult.Error
        }

        val sources = appInfo.metaData.getString("$ANIME_PACKAGE$XX_METADATA_SOURCE_CLASS")!!
            .split(";")
            .map {
                val sourceClass = it.trim()
                if (sourceClass.startsWith(".")) {
                    pkgInfo.packageName + sourceClass
                } else {
                    sourceClass
                }
            }
            .flatMap {
                try {
                    when (val obj = Class.forName(it, false, classLoader).getDeclaredConstructor()
                        .newInstance()) {
                        is AnimeSource -> listOf(obj)
                        is AnimeSourceFactory -> obj.createSources()
                        else -> throw Exception("Unknown source class type! ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    Logger.log("Extension load error: $extName ($it)")
                    return AnimeLoadResult.Error
                }
            }

        val langs = sources.filterIsInstance<AnimeCatalogueSource>()
            .map { it.lang }
            .toSet()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        val extension = AnimeExtension.Installed(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            isNsfw = isNsfw,
            hasReadme = hasReadme,
            hasChangelog = hasChangelog,
            sources = sources,
            pkgFactory = appInfo.metaData.getString("$ANIME_PACKAGE$XX_METADATA_SOURCE_FACTORY"),
            isUnofficial = true,
            icon = context.getApplicationIcon(pkgName),
        )
        return AnimeLoadResult.Success(extension)
    }

    private fun isPackageAnExtension(type: MediaType, pkgInfo: PackageInfo): Boolean {
        val meta = pkgInfo.applicationInfo?.metaData
        val hasFeature = pkgInfo.reqFeatures.orEmpty().any {
            when (type) {
                MediaType.ANIME -> it.name == ANIME_PACKAGE || it.name == "aniyomi.animeextension" || it.name == "tachiyomi.animeextension" || it.name == "tachiyomix.animeextension"
                else -> false
            }
        }
        if (hasFeature) return true

        return when (type) {
            MediaType.ANIME -> pkgInfo.packageName.startsWith("eu.kanade.tachiyomi.animeextension") ||
                    meta?.containsKey("tachiyomi.animeextension.class") == true ||
                    meta?.containsKey("tachiyomix.animeextension.class") == true
            else -> false
        }
    }
}
