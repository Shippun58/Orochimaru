package ani.sanin.connections.mal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import ani.sanin.R
import ani.sanin.client
import ani.sanin.currContext
import ani.sanin.openLinkInBrowser
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.tryWithSuspend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom

object MAL {
    val query: MALQueries = MALQueries()
    val jikan: JikanQueries = JikanQueries()
    const val clientId = "5f1bd6321acb6a4db3cc92bc33df58f4"
    var username: String? = null
    var avatar: String? = null
    var token: String? = null
    var userid: Int? = null
    var episodesWatched: Int? = null
    var chaptersRead: Int? = null

    fun loginIntent(context: Context) {
        val codeVerifierBytes = ByteArray(96)
        SecureRandom().nextBytes(codeVerifierBytes)
        val codeChallenge = Base64.encodeToString(codeVerifierBytes, Base64.DEFAULT).trimEnd('=')
            .replace("+", "-")
            .replace("/", "_")
            .replace("\n", "")

        PrefManager.setVal(PrefName.MALCodeChallenge, codeChallenge)
        val request =
            "https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=$clientId&code_challenge=$codeChallenge&redirect_uri=sanin://mal"
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(request))
            )
        } catch (_: ActivityNotFoundException) {
            openLinkInBrowser(request)
        }
    }

    private suspend fun refreshToken(): ResponseToken? {
        return tryWithSuspend {
            val token = PrefManager.getNullableVal<ResponseToken>(PrefName.MALToken, null)
                ?: throw Exception(currContext()?.getString(R.string.refresh_token_load_failed))
            val res = client.post(
                "https://myanimelist.net/v1/oauth2/token",
                data = mapOf(
                    "client_id" to clientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to (token.refreshToken ?: ""),
                    "redirect_uri" to "sanin://mal"
                )
            ).parsed<ResponseToken>()
            if (!res.isValid) {
                throw Exception(
                    "MAL: ${res.error ?: res.errorDescription ?: "returned an invalid token response"}"
                )
            }
            saveResponse(res)
            return@tryWithSuspend res
        }
    }

    suspend fun getSavedToken(): Boolean {
        return tryWithSuspend(false) {
            var res: ResponseToken =
                PrefManager.getNullableVal<ResponseToken>(PrefName.MALToken, null)
                    ?: return@tryWithSuspend false
            if (System.currentTimeMillis() > (res.expiresIn ?: 0L))
                res = refreshToken()
                    ?: throw Exception(currContext()?.getString(R.string.refreshing_token_failed))
            if (res.accessToken.isNullOrBlank())
                return@tryWithSuspend false
            token = res.accessToken
            username = PrefManager.getVal(PrefName.MALUserName, null as String?)
            avatar = PrefManager.getVal(PrefName.MALAvatar, null as String?)
            return@tryWithSuspend true
        } ?: false
    }

    fun removeSavedToken() {
        token = null
        username = null
        userid = null
        avatar = null
        episodesWatched = null
        chaptersRead = null
        PrefManager.removeVal(PrefName.MALToken)
    }

    fun saveResponse(res: ResponseToken) {
        res.expiresIn = (res.expiresIn ?: 0L) + System.currentTimeMillis()
        PrefManager.setVal(PrefName.MALToken, res)
    }

    @Serializable
    data class ResponseToken(
        @SerialName("token_type") val tokenType: String? = null,
        @SerialName("expires_in") var expiresIn: Long? = null,
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("error") val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    ) : java.io.Serializable {
        val isValid: Boolean
            get() = !accessToken.isNullOrBlank() && error.isNullOrBlank()
        companion object {
            private const val serialVersionUID = 1L
        }
    }

}
