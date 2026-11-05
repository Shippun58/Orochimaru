package eu.kanade.tachiyomi.extension.util

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ani.sanin.media.MediaType
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.util.system.getSerializableExtraCompat
import eu.kanade.tachiyomi.util.system.hasMiuiPackageInstaller
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class ExtensionInstallActivity : AppCompatActivity() {

    private var ignoreUntil = 0L
    private var ignoreResult = false
    private var hasIgnoredResult = false

    private var mediaType: MediaType? = null
    private var addonType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.hasExtra(ExtensionInstaller.EXTRA_EXTENSION_TYPE))
            mediaType =
                intent.getSerializableExtraCompat<MediaType>(ExtensionInstaller.EXTRA_EXTENSION_TYPE)
        if (intent.hasExtra(ExtensionInstaller.EXTRA_ADDON_TYPE))
            addonType =
                intent.getSerializableExtraCompat<String>(ExtensionInstaller.EXTRA_ADDON_TYPE)

        @Suppress("DEPRECATION")
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(intent.data, intent.type)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (hasMiuiPackageInstaller) {
            ignoreResult = true
            ignoreUntil = System.nanoTime() + 1.seconds.inWholeNanoseconds
        }

        val onInstallResult = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result: ActivityResult ->
            if (ignoreResult && System.nanoTime() < ignoreUntil) {
                hasIgnoredResult = true
                return@registerForActivityResult
            }
            checkInstallationResult(result.resultCode)
            finish()
        }

        try {
            onInstallResult.launch(installIntent)
        } catch (error: Exception) {

            toast(error.message)
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasIgnoredResult) {
            checkInstallationResult(RESULT_CANCELED)
            finish()
        }
    }

    @Suppress("all")
    private fun checkInstallationResult(resultCode: Int) {
        val downloadId = intent.extras!!.getLong(ExtensionInstaller.EXTRA_DOWNLOAD_ID)
        val newStep = when (resultCode) {
            RESULT_OK -> InstallStep.Installed
            RESULT_CANCELED -> InstallStep.Idle
            else -> InstallStep.Error
        }
        if (mediaType == MediaType.ANIME) {
            Injekt.get<AnimeExtensionManager>().updateInstallStep(downloadId, newStep)
        }
    }
}
