package ani.sanin.connections.simkl

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.sanin.logError
import ani.sanin.snackString
import ani.sanin.startMainActivity
import ani.sanin.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Login : AppCompatActivity() {

    companion object {
        private const val TAG = "SimklLogin"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()

        val data: Uri? = intent?.data
        Log.d(TAG, "onCreate: data=$data")

        if (data == null) {
            Log.e(TAG, "intent.data is null")
            snackString("Simkl login failed: no response URI")
            logError(Exception("Simkl login: intent.data is null"), snackbar = false)
            startMainActivity(this)
            return
        }

        var code = data.getQueryParameter("code")
        if (code == null) {
            val fragment = data.encodedFragment ?: data.toString()
            Log.d(TAG, "No query code, checking fragment: $fragment")
            val match = Regex("""(?<=code=)[^&#]+""").find(fragment)
            if (match != null) {
                code = match.value
            }
        }

        Log.d(TAG, "Extracted code: ${code?.take(10)}...")

        if (code == null) {
            Log.e(TAG, "No code in URI: $data")
            snackString("Simkl login failed: no code in response")
            logError(Exception("Simkl login: no code in $data"), snackbar = false)
            startMainActivity(this)
            return
        }

        snackString("Logging in to Simkl...")
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Exchanging code for token...")
                val token = withContext(Dispatchers.IO) {
                    Simkl.exchangeCode(code)
                }

                if (token == null) {
                    Log.e(TAG, "exchangeCode returned null")
                    snackString("Simkl login failed: token exchange returned null")
                    startMainActivity(this@Login)
                    return@launch
                }

                if (token.accessToken == null) {
                    Log.e(TAG, "token.accessToken is null, token=$token")
                    snackString("Simkl login failed: no access token in response")
                    startMainActivity(this@Login)
                    return@launch
                }

                Log.d(TAG, "Token received, setting token")
                Simkl.token = token.accessToken
                Log.d(TAG, "Simkl.token set: ${Simkl.token?.take(15)}...")

                Log.d(TAG, "Fetching user data...")
                val user = withContext(Dispatchers.IO) {
                    Simkl.fetchUserData()
                }

                Log.d(TAG, "fetchUserData returned: user=${user != null}")
                Log.d(TAG, "Simkl state: name=${Simkl.username}, avatar=${Simkl.avatar?.take(80)} userid=${Simkl.userid}")

                if (Simkl.username == null || Simkl.username == "") {
                    Log.d(TAG, "Username empty, retrying fetchUserData in 1s...")
                    kotlinx.coroutines.delay(1000)
                    withContext(Dispatchers.IO) { Simkl.fetchUserData() }
                    Log.d(TAG, "Retry result: name=${Simkl.username}")
                }

                snackString("Logged in as ${Simkl.username ?: "Simkl user"}")
                startMainActivity(this@Login)

            } catch (e: Exception) {
                Log.e(TAG, "Login exception", e)
                logError(e)
                startMainActivity(this@Login)
            }
        }
    }
}
