package com.lagradost.cloudstream3.syncproviders

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.splitUrlParameters
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.security.SecureRandom

data class AuthLoginPage(

    val url: String,

    val payload: String? = null,
)

@Serializable
data class AuthToken(
    @JsonProperty("accessToken") @SerialName("accessToken")
    val accessToken: String? = null,
    @JsonProperty("refreshToken") @SerialName("refreshToken")
    val refreshToken: String? = null,
    @JsonProperty("accessTokenLifetime") @SerialName("accessTokenLifetime")
    val accessTokenLifetime: Long? = null,
    @JsonProperty("refreshTokenLifetime") @SerialName("refreshTokenLifetime")
    val refreshTokenLifetime: Long? = null,
    @JsonProperty("payload") @SerialName("payload")
    val payload: String? = null,
) {
    fun isAccessTokenExpired(marginSec: Long = 10L) =
        accessTokenLifetime != null && unixTime + marginSec >= accessTokenLifetime

    fun isRefreshTokenExpired(marginSec: Long = 10L) =
        refreshTokenLifetime != null && unixTime + marginSec >= refreshTokenLifetime
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthUser(
    @JsonProperty("name") @SerialName("name")
    val name: String,

    @JsonProperty("id") @SerialName("id")
    val id: Int,
    @JsonProperty("profilePicture") @SerialName("profilePicture")
    val profilePicture: String? = null,
    @JsonProperty("profilePictureHeaders") @JsonAlias("profilePictureHeader")
    @SerialName("profilePictureHeaders") @JsonNames("profilePictureHeader")
    val profilePictureHeaders: Map<String, String>? = null,
)

@Serializable
data class AuthData(
    @JsonProperty("user") @SerialName("user") val user: AuthUser,
    @JsonProperty("token") @SerialName("token") val token: AuthToken,
)

data class AuthPinData(
    val deviceCode: String,
    val userCode: String,

    val verificationUrl: String,

    val expiresIn: Int,

    val interval: Int,
)

data class AuthLoginRequirement(
    val password: Boolean = false,
    val username: Boolean = false,
    val email: Boolean = false,
    val server: Boolean = false,
)

@Serializable
data class AuthLoginResponse(
    @JsonProperty("password") @SerialName("password") val password: String?,
    @JsonProperty("username") @SerialName("username") val username: String?,
    @JsonProperty("email") @SerialName("email") val email: String?,
    @JsonProperty("server") @SerialName("server") val server: String?,
)

abstract class AuthAPI {
    open val name: String = "NONE"
    open val idPrefix: String = "NONE"

    open val icon: Int? = null

    open val requiresLogin: Boolean = true

    open val createAccountUrl: String? = null

    open val redirectUrlIdentifier: String? = null

    open val hasOAuth2: Boolean = false

    open val hasPin: Boolean = false

    open val hasInApp: Boolean = false

    open val inAppLoginRequirement: AuthLoginRequirement? = null

    companion object {
        val unixTime: Long
            get() = APIHolder.unixTime

        val unixTimeMs: Long
            get() = unixTimeMS

        fun splitRedirectUrl(redirectUrl: String): Map<String, String> {
            return splitUrlParameters(
                redirectUrl.replace(APP_STRING, "https").replace("/#", "?")
            )
        }

        fun generateCodeVerifier(): String {
            val secureRandom = SecureRandom()
            val codeVerifierBytes = ByteArray(96)
            secureRandom.nextBytes(codeVerifierBytes)
            return base64Encode(codeVerifierBytes).trimEnd('=')
                .replace("+", "-").replace("/", "_").replace("\n", "")
        }
    }

    @Throws
    open fun isValidRedirectUrl(url: String): Boolean =
        redirectUrlIdentifier != null && url.contains("/$redirectUrlIdentifier")

    @Throws
    open suspend fun login(redirectUrl: String, payload: String?): AuthToken? =
        throw NotImplementedError()

    @Throws
    open fun loginRequest(): AuthLoginPage? = throw NotImplementedError()

    @Throws
    open suspend fun pinRequest(): AuthPinData? = throw NotImplementedError()

    @Throws
    open suspend fun refreshToken(token: AuthToken): AuthToken? = throw NotImplementedError()

    @Throws
    open suspend fun login(payload: AuthPinData): AuthToken? = throw NotImplementedError()

    @Throws
    open suspend fun login(form: AuthLoginResponse): AuthToken? = throw NotImplementedError()

    @Throws
    open suspend fun user(token: AuthToken?): AuthUser? = throw NotImplementedError()

    @Throws
    open suspend fun invalidateToken(token: AuthToken): Nothing = throw NotImplementedError()
}
