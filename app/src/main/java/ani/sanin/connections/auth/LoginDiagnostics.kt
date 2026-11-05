package ani.sanin.connections.auth

import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


object LoginDiagnostics {
    private const val KEY_LAST_LOGIN_TIMESTAMP = "login_last_timestamp"
    private const val KEY_LOGIN_METHOD = "login_method"
    private const val KEY_APP_VERSION = "login_app_version"

    enum class LoginMethod {
        QR_CODE,
        TOKEN_PASTE,
        OAUTH_BROWSER
    }

    
    fun recordLogin(method: LoginMethod) {
        PrefManager.setVal(PrefName.LastLoginTimestamp, System.currentTimeMillis())
        PrefManager.setVal(PrefName.LoginMethod, method.name)
        PrefManager.setVal(PrefName.LoginAppVersion, getAppVersion())
    }

    
    fun getLastLoginTimestamp(): Long {
        return PrefManager.getVal(PrefName.LastLoginTimestamp, 0L)
    }

    
    fun getLoginMethod(): String {
        return PrefManager.getVal(PrefName.LoginMethod, "UNKNOWN")
    }

    
    fun getAppVersion(): String {
        return try {
            val packageInfo = ani.sanin.currContext()?.packageManager
                ?.getPackageInfo(ani.sanin.currContext()?.packageName ?: "", 0)
            packageInfo?.versionName ?: "UNKNOWN"
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    
    fun getLastLoginFormatted(): String {
        val timestamp = getLastLoginTimestamp()
        if (timestamp == 0L) return "Never"
        
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    
    fun getDiagnosticsSummary(): Map<String, String> {
        return mapOf(
            "Last Login" to getLastLoginFormatted(),
            "Login Method" to getLoginMethod(),
            "App Version" to getAppVersion()
        )
    }
}
