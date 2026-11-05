package eu.kanade.tachiyomi.extension.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import ani.sanin.media.MediaType
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AnimeLoadResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import tachiyomi.core.util.lang.launchNow

internal class ExtensionInstallReceiver : BroadcastReceiver() {

    private var animeListener: AnimeListener? = null
    private var type: MediaType? = null

    fun register(context: Context) {
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun setAnimeListener(listener: AnimeListener): ExtensionInstallReceiver {
        this.type = MediaType.ANIME
        animeListener = listener
        return this
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (isReplacing(intent)) return

                launchNow {
                    when (type) {
                        MediaType.ANIME -> {
                            when (val result = getAnimeExtensionFromIntent(context, intent)) {
                                is AnimeLoadResult.Success -> animeListener?.onExtensionInstalled(
                                    result.extension
                                )

                                is AnimeLoadResult.Untrusted -> animeListener?.onExtensionUntrusted(
                                    result.extension
                                )

                                else -> {}
                            }
                        }
                        else -> {}
                    }
                }
            }

            Intent.ACTION_PACKAGE_REPLACED -> {
                launchNow {
                    when (type) {
                        MediaType.ANIME -> {
                            when (val result = getAnimeExtensionFromIntent(context, intent)) {
                                is AnimeLoadResult.Success -> animeListener?.onExtensionUpdated(
                                    result.extension
                                )

                                else -> {}
                            }
                        }
                        else -> {}
                    }
                }
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                if (isReplacing(intent)) return

                val pkgName = getPackageNameFromIntent(intent)
                if (pkgName != null) {
                    when (type) {
                        MediaType.ANIME -> {
                            animeListener?.onPackageUninstalled(pkgName)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun getAnimeExtensionFromIntent(
        context: Context,
        intent: Intent?
    ): AnimeLoadResult {
        val pkgName = getPackageNameFromIntent(intent)
        if (pkgName == null) {
            Logger.log("Package name not found")
            return AnimeLoadResult.Error
        }
        return GlobalScope.async(Dispatchers.Default, CoroutineStart.DEFAULT) {
            ExtensionLoader.loadAnimeExtensionFromPkgName(
                context,
                pkgName,
            )
        }.await()
    }

    interface AnimeListener {
        fun onExtensionInstalled(extension: AnimeExtension.Installed)
        fun onExtensionUpdated(extension: AnimeExtension.Installed)
        fun onExtensionUntrusted(extension: AnimeExtension.Untrusted)
        fun onPackageUninstalled(pkgName: String)
    }

    companion object {

        val filter
            get() = IntentFilter().apply {
                priority = 100
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }

        fun isReplacing(intent: Intent): Boolean {
            return intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        }

        fun getPackageNameFromIntent(intent: Intent?): String? {
            return intent?.data?.encodedSchemeSpecificPart ?: return null
        }
    }
}
