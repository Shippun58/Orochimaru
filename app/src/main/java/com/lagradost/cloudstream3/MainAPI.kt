@file:Suppress(
    "UNUSED",
    "UnusedReceiverParameter",
    "MemberVisibilityCanBePrivate"
)

package com.lagradost.cloudstream3

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.Coroutines.mainWork
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromCodeToLangTagIETF
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromLanguageToTagIETF
import com.lagradost.nicehttp.RequestBodyTypes
import io.ktor.http.Url
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.format.char
import kotlinx.datetime.format.parse
import kotlinx.datetime.toInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.jvm.JvmName
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
    message = "This API is only available on prerelease builds. " +
              "Using it will cause CloudStream stable to crash.",
    level = RequiresOptIn.Level.ERROR
)
annotation class Prerelease

@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
    message = "This API is marked as internal and should not be used by extensions. " +
              "Using it could cause catastrophic build or runtime errors and may " +
              "be changed or removed at any time.",
    level = RequiresOptIn.Level.ERROR
)
annotation class InternalAPI

@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
    message = "Only use this if you know what you are doing and you need to bypass the SSL certificate checks. Never use this for sensitive network requests such as logins.",
    level = RequiresOptIn.Level.WARNING
)
annotation class UnsafeSSL

@InternalAPI
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SkipSerializationTest

const val AllLanguagesName = "universal"

const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

class ErrorLoadingException(message: String? = null) : Exception(message)

val json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

val mapper = JsonMapper.builder().addModule(kotlinModule())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()!!

object APIHolder {
    val unixTimeMS: Long
        get() = Clock.System.now().toEpochMilliseconds()
    val unixTime: Long
        get() = unixTimeMS / 1000L

    val allProviders = atomicListOf<MainAPI>()

    fun initAll() {
        allProviders.withLock {
            for (api in allProviders) {
                api.init()
            }
        }
        apiMap = null
    }

    fun String.capitalize(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    var apis: AtomicList<MainAPI> = atomicListOf()
    var apiMap: Map<String, Int>? = null

    fun addPluginMapping(plugin: MainAPI) {
        apis.withLock {
            apis = apis + plugin
        }
        initMap(true)
    }

    fun removePluginMapping(plugin: MainAPI) {
        apis.withLock {
            apis = apis.filter { it != plugin }
        }
        initMap(true)
    }

    private fun initMap(forcedUpdate: Boolean = false) {
        apis.withLock {
            if (apiMap == null || forcedUpdate)
                apiMap = apis.mapIndexed { index, api -> api.name to index }.toMap()
        }
    }

    fun getApiFromNameNull(apiName: String?): MainAPI? {
        if (apiName == null) return null
        return allProviders.withLock {
            initMap()
            apis.withLock {
                apiMap?.get(apiName)?.let { apis.getOrNull(it) }

                ?: allProviders.firstOrNull { it.name == apiName }
            }
        }
    }

    fun getApiFromUrlNull(url: String?): MainAPI? {
        if (url == null) return null
        return allProviders.withLock {
            allProviders.firstOrNull { url.startsWith(it.mainUrl) }
        }
    }

    suspend fun getCaptchaToken(url: String, key: String, referer: String? = null): String? {
        try {
            val _url = Url(url)
            val domain = base64Encode(
                (_url.protocol.name + "://" + _url.host + ":443").encodeToByteArray(),
            ).replace("\n", "").replace("=", ".")

            val vToken =
                app.get(
                    "https://www.google.com/recaptcha/api.js?render=$key",
                    referer = referer,
                    cacheTime = 0
                )
                    .text
                    .substringAfter("releases/")
                    .substringBefore("/")
            val recapToken =
                app.get("https://www.google.com/recaptcha/api2/anchor?ar=1&hl=en&size=invisible&cb=cs3&k=$key&co=$domain&v=$vToken")
                    .document
                    .selectFirst("#recaptcha-token")?.attr("value")
            if (recapToken != null) {
                return app.post(
                    "https://www.google.com/recaptcha/api2/reload?k=$key",
                    data = mapOf(
                        "v" to vToken,
                        "k" to key,
                        "c" to recapToken,
                        "co" to domain,
                        "sa" to "",
                        "reason" to "q"
                    ), cacheTime = 0
                ).text
                    .substringAfter("rresp\",\"")
                    .substringBefore("\"")
            }
        } catch (e: Exception) {
            logError(e)
        }
        return null
    }

    private var trackerCache: HashMap<String, AniSearch> = hashMapOf()

    suspend fun getTracker(
        titles: List<String>,
        types: Set<TrackerType>?,
        year: Int?,
    ): Tracker? = getTracker(titles, types, year, false)

    suspend fun getTracker(
        titles: List<String>,
        types: Set<TrackerType>?,
        year: Int?,
        lessAccurate: Boolean
    ): Tracker? {
        return try {
            require(titles.isNotEmpty()) { "titles must no be empty when calling getTracker" }

            val mainTitle = titles[0]
            val search =
                trackerCache[mainTitle]
                    ?: searchAnilist(mainTitle)?.also {
                        trackerCache[mainTitle] = it
                    } ?: return null

            val res = search.data?.page?.media?.find { media ->
                val matchingYears = year == null || media.seasonYear == year
                val matchingTitles = media.title?.let { title ->
                    titles.any { userTitle ->
                        title.isMatchingTitles(userTitle)
                    }
                } ?: false

                val matchingTypes = types?.any { it.name.equals(media.format, true) } == true
                if (lessAccurate) matchingTitles || matchingTypes && matchingYears else matchingTitles && matchingTypes && matchingYears
            } ?: return null

            Tracker(
                res.idMal,
                null,
                res.id.toString(),
                res.coverImage?.extraLarge ?: res.coverImage?.large,
                res.bannerImage
            )
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    private suspend fun searchAnilist(
        title: String?,
    ): AniSearch? {
        val query = """
        query (
          ${'$'}page: Int = 1
          ${'$'}search: String
          ${'$'}sort: [MediaSort] = [POPULARITY_DESC, SCORE_DESC]
          ${'$'}type: MediaType
        ) {
          Page(page: ${'$'}page, perPage: 20) {
            media(
              search: ${'$'}search
              sort: ${'$'}sort
              type: ${'$'}type
            ) {
              id
              idMal
              title { romaji english }
              coverImage { extraLarge large }
              bannerImage
              seasonYear
              format
            }
          }
        }
    """.trimIndent().trim()

        val data = mapOf(
            "query" to query,
            "variables" to mapOf(
                "search" to title,
                "sort" to "SEARCH_MATCH",
                "type" to "ANIME",
            )
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        return app.post("https://graphql.anilist.co", requestBody = data)
            .parsedSafe<AniSearch>()
    }
}

const val PROVIDER_STATUS_KEY = "PROVIDER_STATUS_KEY"
const val PROVIDER_STATUS_BETA_ONLY = 3
const val PROVIDER_STATUS_SLOW = 2
const val PROVIDER_STATUS_OK = 1
const val PROVIDER_STATUS_DOWN = 0

@Serializable
data class ProvidersInfoJson(
    @JsonProperty("name") @SerialName("name") var name: String,
    @JsonProperty("url") @SerialName("url") var url: String,
    @JsonProperty("credentials") @SerialName("credentials") var credentials: String? = null,
    @JsonProperty("status") @SerialName("status") var status: Int,
)

@Serializable
data class SettingsJson(
    @JsonProperty("enableAdult") @SerialName("enableAdult") var enableAdult: Boolean = false,
)

data class MainPageData(
    val name: String,
    val data: String,
    val horizontalImages: Boolean = false
)

data class MainPageRequest(
    val name: String,
    val data: String,
    val horizontalImages: Boolean,

)

fun mainPage(url: String, name: String, horizontalImages: Boolean = false): MainPageData {
    return MainPageData(name = name, data = url, horizontalImages = horizontalImages)
}

fun mainPageOf(vararg elements: MainPageData): List<MainPageData> {
    return elements.toList()
}

fun mainPageOf(vararg elements: Pair<String, String>): List<MainPageData> {
    return elements.map { (url, name) -> MainPageData(name = name, data = url) }
}

fun newHomePageResponse(
    name: String,
    list: List<SearchResponse>,
    hasNext: Boolean? = null,
): HomePageResponse {
    @Suppress("DEPRECATION_ERROR")
    return HomePageResponse(
        listOf(HomePageList(name, list)),
        hasNext = hasNext ?: list.isNotEmpty()
    )
}

fun newHomePageResponse(
    data: MainPageRequest,
    list: List<SearchResponse>,
    hasNext: Boolean? = null,
): HomePageResponse {
    @Suppress("DEPRECATION_ERROR")
    return HomePageResponse(
        listOf(HomePageList(data.name, list, data.horizontalImages)),
        hasNext = hasNext ?: list.isNotEmpty()
    )
}

fun newHomePageResponse(list: HomePageList, hasNext: Boolean? = null): HomePageResponse {
    @Suppress("DEPRECATION_ERROR")
    return HomePageResponse(listOf(list), hasNext = hasNext ?: list.list.isNotEmpty())
}

fun newHomePageResponse(list: List<HomePageList>, hasNext: Boolean? = null): HomePageResponse {
    @Suppress("DEPRECATION_ERROR")
    return HomePageResponse(list, hasNext = hasNext ?: list.any { it.list.isNotEmpty() })
}

fun newSearchResponseList(
    list: List<SearchResponse>,
    hasNext: Boolean? = null,
): SearchResponseList {
    @Suppress("DEPRECATION_ERROR")
    return SearchResponseList(
        list,
        hasNext = hasNext ?: list.isNotEmpty()
    )
}

fun List<SearchResponse>.toNewSearchResponseList(hasNext: Boolean? = null) : SearchResponseList {
    return newSearchResponseList(this, hasNext)
}

abstract class MainAPI {
    companion object {
        var overrideData: HashMap<String, ProvidersInfoJson>? = null
        var settingsForProvider: SettingsJson = SettingsJson()
    }

    fun init() {
        overrideData?.get(this::class.simpleName)?.let { data ->
            overrideWithNewData(data)
        }
    }

    fun overrideWithNewData(data: ProvidersInfoJson) {
        if (!canBeOverridden) return
        this.name = data.name
        if (data.url.isNotBlank() && data.url != "NONE")
            this.mainUrl = data.url
        this.storedCredentials = data.credentials
    }

    open var name = "NONE"

    open var mainUrl = "NONE"
    open var storedCredentials: String? = null
    open var canBeOverridden: Boolean = true

    open var sequentialMainPage: Boolean = false

    open var sequentialMainPageDelay: Long = 0L

    open var sequentialMainPageScrollDelay: Long = 0L

    var lastHomepageRequest: Long = 0L

    open var lang = "en"

    open val instantLinkLoading = false

    open val hasChromecastSupport = true

    open val hasDownloadSupport = true

    open val usesWebView = false

    var sourcePlugin: String? = null

    open val hasMainPage = false
    open val hasQuickSearch = false

    open val loadLinksTimeoutMs: Long? = null

    open val getMainPageTimeoutMs: Long? = null

    open val searchTimeoutMs: Long? = null

    open val quickSearchTimeoutMs: Long? = null

    open val loadTimeoutMs: Long? = null

    open val supportedSyncNames = setOf<SyncIdName>()

    open val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Anime,
        TvType.OVA,
    )

    open val vpnStatus = VPNStatus.None
    open val providerType = ProviderType.DirectProvider

    open val mainPage = listOf(MainPageData("", "", false))

    open suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        throw NotImplementedError()
    }

    open suspend fun search(query: String, page: Int): SearchResponseList? {
        val searchResults = search(query) ?: return null

        return newSearchResponseList(
            searchResults,
            false
        )
    }

    open suspend fun search(query: String): List<SearchResponse>? {
        throw NotImplementedError()
    }

    open suspend fun quickSearch(query: String): List<SearchResponse>? {
        throw NotImplementedError()
    }

    open suspend fun load(url: String): LoadResponse? {
        throw NotImplementedError()
    }

    open suspend fun extractorVerifierJob(extractorData: String?) {
        throw NotImplementedError()
    }

    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        throw NotImplementedError()
    }

    open fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return null
    }

    open suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        return null
    }
}

fun base64Decode(string: String): String {

    val bytes = base64DecodeArray(string)
    return buildString(bytes.size) {
        for (b in bytes) {
            append((b.toInt() and 0xFF).toChar())
        }
    }
}

fun base64DecodeArray(string: String): ByteArray {
    return Base64.decode(string)
}

fun base64Encode(array: ByteArray): String {
    return Base64.encode(array)
}

fun MainAPI.fixUrlNull(url: String?): String? {
    if (url.isNullOrEmpty()) {
        return null
    }
    return fixUrl(url)
}

fun MainAPI.fixUrl(url: String): String {
    if (url.startsWith("http") ||

        url.startsWith("{\"") || url.startsWith("[")
    ) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return mainUrl + url
        }
        return "$mainUrl/$url"
    }
}

fun sortUrls(urls: Set<ExtractorLink>): List<ExtractorLink> {
    return urls.sortedBy { t -> -t.quality }
}

@Prerelease
fun splitUrlParameters(url: Url): Map<String, String> {
    return url.parameters.entries().associate { (key, values) -> key to values.firstOrNull().orEmpty() }
}

@Prerelease
fun splitUrlParameters(url: String): Map<String, String> {
    return splitUrlParameters(Url(url))
}

fun capitalizeString(str: String): String {
    return capitalizeStringNullable(str) ?: str
}

fun capitalizeStringNullable(str: String?): String? {
    if (str == null)
        return null
    return try {
        str.replaceFirstChar(Char::titlecase)
    } catch (e: Exception) {
        str
    }
}

fun fixTitle(str: String): String {
    return str.split(" ").joinToString(" ") {
        it.lowercase()
            .replaceFirstChar(Char::titlecase)
    }
}

@Deprecated(
    message = "Use newJsContext or evalJs instead.",
    level = DeprecationLevel.WARNING,
)
suspend fun getRhinoContext(): org.mozilla.javascript.Context {
    return Coroutines.mainWork {
        val rhino = org.mozilla.javascript.Context.enter()
        rhino.initSafeStandardObjects()
        rhino.setInterpretedMode(true)
        rhino
    }
}

fun imdbUrlToId(url: String): String? {
    return Regex("/title/(tt[0-9]*)").find(url)?.groupValues?.get(1)
        ?: Regex("tt[0-9]{5,}").find(url)?.groupValues?.get(0)
}

fun imdbUrlToIdNullable(url: String?): String? {
    if (url == null) return null
    return imdbUrlToId(url)
}

enum class ProviderType {
    MetaProvider,
    DirectProvider,
}

enum class VPNStatus {
    None,
    MightBeNeeded,
    Torrent,
}

enum class ShowStatus {
    Completed,
    Ongoing,
}

enum class DubStatus(val id: Int) {
    None(-1),
    Dubbed(1),
    Subbed(0),
}

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Serializable
class Score private constructor(

    @JsonProperty("data") @SerialName("data") private val data: Int,
) {
    override fun hashCode(): Int = this.data.hashCode()
    override fun equals(other: Any?): Boolean = other is Score && this.data == other.data

    @Deprecated(
        "toOld() is deprecated. Use other Score methods instead.",
        level = DeprecationLevel.ERROR
    )
    fun toOld(): Int = toInt(10000)

    fun toByte(maxScore: Int): Byte = toLong(maxScore).toByte()

    fun toInt(maxScore: Int = 10): Int = toLong(maxScore).toInt()

    fun toLong(maxScore: Int = 10): Long = (data.toLong() * maxScore.toLong()) / MAX.toLong()

    fun toFloat(maxScore: Int = 10): Float =
        (data.toFloat() / MAX.toFloat()) * maxScore.toFloat()

    fun toDouble(maxScore: Int = 10): Double =
        (data.toDouble() / MAX.toDouble()) * maxScore.toDouble()

    override fun toString(): String = this.toString(10)

    @Throws(IllegalArgumentException::class)
    fun toStringNull(
        minScore: Double,
        maxScore: Int,
        decimals: Int = 1,
        removeTrailingZeros: Boolean = true,
        decimalChar: Char = '.'
    ): String? {
        if (toDouble() < minScore) return null
        return toString(maxScore, decimals, removeTrailingZeros, decimalChar)
    }

    @Throws(IllegalArgumentException::class)
    fun toString(
        maxScore: Int,
        decimals: Int = 1,
        removeTrailingZeros: Boolean = true,
        decimalChar: Char = '.'
    ): String {
        require(maxScore in 1..1000) {
            "maxScore ∈ [1,1000]"
        }
        require(decimals in 0..MAX_ZEROS) {
            "decimals ∈ [0,$MAX_ZEROS]"
        }
        var number = data.toLong() * maxScore.toLong()
        val chars = CharArray(MAX_ZEROS + 6)

        for (i in chars.indices) {
            chars[i] = (number % 10L).toInt().digitToChar()
            number /= 10L
        }

        var trailingZeros = MAX_ZEROS - decimals
        for (i in (MAX_ZEROS - decimals) until chars.size) {
            if (chars[i] != '0') {
                break
            }
            trailingZeros += 1
        }

        var leadingZeros = 0
        for (i in chars.indices.reversed()) {
            if (chars[i] != '0') {
                break
            }
            leadingZeros += 1
        }

        val stringBuilder = StringBuilder()
        for (i in maxOf(MAX_ZEROS, (chars.size - leadingZeros - 1)) downTo MAX_ZEROS) {
            stringBuilder.append(chars[i])
        }

        val end = if (removeTrailingZeros) {
            maxOf(MAX_ZEROS - decimals, trailingZeros)
        } else {
            MAX_ZEROS - decimals
        }

        if (end <= MAX_ZEROS - 1) {
            stringBuilder.append(decimalChar)
            for (i in MAX_ZEROS - 1 downTo end) {
                stringBuilder.append(chars[i])
            }
        }

        return stringBuilder.toString()
    }

    companion object {
        const val MAX: Int = 1000_000_000
        const val MIN: Int = 0
        const val MAX_ZEROS: Int = 9
        private const val TAG: String = "Score"

        @Deprecated(
            "Score.fromOld is deprecated. Use other Score.from* methods instead.",
            level = DeprecationLevel.ERROR
        )
        fun fromOld(value: Int?): Score? {
            if (value == null) return null
            if (value < 0 || value > 10000) {
                com.lagradost.api.Log.w(TAG, "old: $value ∉ [0, 10000]")
                return null
            }
            return Score(value * 100_000)
        }

        fun from(value: Int?, maxScore: Int): Score? {
            if (value == null) {
                return null
            }
            if (value < 0 || value > maxScore) {
                com.lagradost.api.Log.w(TAG, "fromInt: $value ∉ [0, $maxScore]")
                return null
            }
            return Score((MAX / maxScore) * value)
        }

        fun from(value: Double?, maxScore: Int): Score? {
            if (value == null) {
                return null
            }
            if (value < 0.0 || value > maxScore) {
                com.lagradost.api.Log.w(TAG, "fromDouble: $value ∉ [0.0, $maxScore]")
                return null
            }
            return Score(((MAX / maxScore).toDouble() * value).roundToInt())
        }

        fun from(value: Float?, maxScore: Int): Score? {
            if (value == null) {
                return null
            }
            if (value < 0.0 || value > maxScore) {
                com.lagradost.api.Log.w(TAG, "fromFloat: $value ∉ [0.0f, $maxScore]")
                return null
            }
            return Score(((MAX / maxScore).toFloat() * value).roundToInt())
        }

        fun from(value: String?, maxScore: Int): Score? =
            from(value?.trim()?.toDoubleOrNull()?.absoluteValue, maxScore)

        fun from5(value: Int?): Score? = from(value, 5)

        fun from10(value: Int?): Score? = from(value, 10)

        fun from100(value: Int?): Score? = from(value, 100)

        fun from5(value: Double?): Score? = from(value, 5)

        fun from10(value: Double?): Score? = from(value, 10)

        fun from100(value: Double?): Score? = from(value, 100)

        fun from5(value: Float?): Score? = from(value, 5)

        fun from10(value: Float?): Score? = from(value, 10)

        fun from100(value: Float?): Score? = from(value, 100)

        fun from5(value: String?): Score? = from(value, 5)

        fun from10(value: String?): Score? = from(value, 10)

        fun from100(value: String?): Score? = from(value, 100)
    }
}

@Suppress("UNUSED_PARAMETER")
enum class TvType(value: Int?) {
    Movie(1),
    AnimeMovie(2),
    TvSeries(3),
    Cartoon(4),
    Anime(5),
    OVA(6),
    Torrent(7),
    Documentary(8),
    AsianDrama(9),
    Live(10),
    NSFW(11),
    Others(12),
    Music(13),
    AudioBook(14),

    CustomMedia(15),

    Audio(16),
    Podcast(17),
    Video(18),
}

enum class AutoDownloadMode(val value: Int) {
    Disable(0),
    FilterByLang(1),
    All(2),
    NsfwOnly(3);

    companion object {
        infix fun getEnum(value: Int): AutoDownloadMode? =
            entries.firstOrNull { it.value == value }
    }
}

fun TvType.isMovieType(): Boolean {
    return when (this) {
        TvType.AnimeMovie,
        TvType.Live,
        TvType.Movie,
        TvType.Torrent,
        TvType.Video -> true

        else -> false
    }
}

fun TvType.isAudioType(): Boolean {
    return when (this) {
        TvType.Audio,
        TvType.AudioBook,
        TvType.Music,
        TvType.Podcast -> true

        else -> false
    }
}

fun TvType.isLiveStream(): Boolean {
    return this == TvType.Live
}

fun TvType.isAnimeOp(): Boolean {
    return this == TvType.Anime || this == TvType.OVA
}

@ConsistentCopyVisibility
data class SubtitleFile private constructor(
    var lang: String,
    var url: String,
    var headers: Map<String, String>?
) {
    @Deprecated("Use newSubtitleFile method", level = DeprecationLevel.WARNING)
    constructor(lang: String, url: String) : this(lang = lang, url = url, headers = null)

    val langTag: String?
        get() = fromCodeToLangTagIETF(lang) ?: fromLanguageToTagIETF(lang, true)

    fun copy(
        lang: String = this.lang, url: String = this.url
    ): SubtitleFile = SubtitleFile(lang = lang, url = url, headers = this.headers)
}

suspend fun newSubtitleFile(
    lang: String,
    url: String,
    initializer: suspend SubtitleFile.() -> Unit = { }
): SubtitleFile {
    @Suppress("DEPRECATION")
    val builder = SubtitleFile(
        lang, url
    )
    builder.initializer()

    return builder
}

@ConsistentCopyVisibility
@Serializable
data class AudioFile internal constructor(
    @JsonProperty("url") @SerialName("url") var url: String,
    @JsonProperty("headers") @SerialName("headers") var headers: Map<String, String>? = null,
)

suspend fun newAudioFile(
    url: String,
    initializer: suspend AudioFile.() -> Unit = { }
): AudioFile {
    val builder = AudioFile(url)
    builder.initializer()
    return builder
}

data class HomePageResponse
@Deprecated("Use newHomePageResponse method", level = DeprecationLevel.ERROR)
constructor(
    val items: List<HomePageList>,
    val hasNext: Boolean = false
)

data class HomePageList(
    val name: String,
    var list: List<SearchResponse>,
    val isHorizontalImages: Boolean = false
)

data class SearchResponseList
@Deprecated("Use newSearchResponseList method", level = DeprecationLevel.ERROR)
constructor(
    val items: List<SearchResponse>,
    val hasNext: Boolean = false
)

@Suppress("UNUSED_PARAMETER")
enum class SearchQuality(value: Int?) {
    Cam(1),
    CamRip(2),
    HdCam(3),
    Telesync(4),
    WorkPrint(5),
    Telecine(6),
    HQ(7),
    HD(8),
    HDR(9),
    BlueRay(10),
    DVD(11),
    SD(12),
    FourK(13),
    UHD(14),
    SDR(15),
    WebRip(16)
}

fun getQualityFromString(string: String?): SearchQuality? {

    val check = (string ?: return null).trim().lowercase().replace(" ", "")

    return when (check) {
        "cam" -> SearchQuality.Cam
        "camrip" -> SearchQuality.CamRip
        "hdcam" -> SearchQuality.HdCam
        "hdtc" -> SearchQuality.HdCam
        "hdts" -> SearchQuality.HdCam
        "highquality" -> SearchQuality.HQ
        "hq" -> SearchQuality.HQ
        "highdefinition" -> SearchQuality.HD
        "hdrip" -> SearchQuality.HD
        "hd" -> SearchQuality.HD
        "hdtv" -> SearchQuality.HD
        "rip" -> SearchQuality.CamRip
        "telecine" -> SearchQuality.Telecine
        "tc" -> SearchQuality.Telecine
        "telesync" -> SearchQuality.Telesync
        "ts" -> SearchQuality.Telesync
        "dvd" -> SearchQuality.DVD
        "dvdrip" -> SearchQuality.DVD
        "dvdscr" -> SearchQuality.DVD
        "blueray" -> SearchQuality.BlueRay
        "bluray" -> SearchQuality.BlueRay
        "blu" -> SearchQuality.BlueRay
        "fhd" -> SearchQuality.HD
        "br" -> SearchQuality.BlueRay
        "standard" -> SearchQuality.SD
        "sd" -> SearchQuality.SD
        "4k" -> SearchQuality.FourK
        "uhd" -> SearchQuality.UHD
        "blue" -> SearchQuality.BlueRay
        "wp" -> SearchQuality.WorkPrint
        "workprint" -> SearchQuality.WorkPrint
        "webrip" -> SearchQuality.WebRip
        "webdl" -> SearchQuality.WebRip
        "web" -> SearchQuality.WebRip
        "hdr" -> SearchQuality.HDR
        "sdr" -> SearchQuality.SDR
        else -> null
    }
}

fun MainAPI.updateUrl(url: String): String {
    return try {
        val original = Url(url)
        val updated = Url(mainUrl)

        URLBuilder().apply {
            takeFrom(updated)
            user = original.user
            password = original.password
            encodedPath = original.encodedPath
            fragment = original.fragment

            parameters.clear()
            parameters.appendAll(original.parameters)
        }.buildString()
    } catch (t: Throwable) {
        logError(t)
        url
    }
}

interface SearchResponse {
    val name: String
    val url: String
    val apiName: String
    var type: TvType?
    var posterUrl: String?
    var posterHeaders: Map<String, String>?
    var id: Int?
    var quality: SearchQuality?
    var score: Score?
}

fun MainAPI.newTorrentSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Torrent,
    fix: Boolean = true,
    initializer: TorrentSearchResponse.() -> Unit = { },
): TorrentSearchResponse {
    @Suppress("DEPRECATION_ERROR")
    val builder = TorrentSearchResponse(
        name = name,
        url = if (fix) fixUrl(url) else url,
        apiName = this.name,
        type = type,

        posterUrl = null
    )
    builder.initializer()
    return builder
}

fun MainAPI.newMovieSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    fix: Boolean = true,
    initializer: MovieSearchResponse.() -> Unit = { },
): MovieSearchResponse {
    @Suppress("DEPRECATION_ERROR")
    val builder = MovieSearchResponse(name, if (fix) fixUrl(url) else url, this.name, type)
    builder.initializer()

    return builder
}

fun MainAPI.newLiveSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Live,
    fix: Boolean = true,
    initializer: LiveSearchResponse.() -> Unit = { },
): LiveSearchResponse {
    @Suppress("DEPRECATION_ERROR")
    val builder = LiveSearchResponse(
        name = name,
        url = if (fix) fixUrl(url) else url,
        apiName = this.name,
        type = type
    )
    builder.initializer()
    return builder
}

fun MainAPI.newTvSeriesSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    fix: Boolean = true,
    initializer: TvSeriesSearchResponse.() -> Unit = { },
): TvSeriesSearchResponse {
    @Suppress("DEPRECATION_ERROR")
    val builder = TvSeriesSearchResponse(name, if (fix) fixUrl(url) else url, this.name, type)
    builder.initializer()

    return builder
}

fun MainAPI.newAnimeSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    fix: Boolean = true,
    initializer: AnimeSearchResponse.() -> Unit = { },
): AnimeSearchResponse {
    @Suppress("DEPRECATION_ERROR")
    val builder = AnimeSearchResponse(name, if (fix) fixUrl(url) else url, this.name, type)
    builder.initializer()

    return builder
}

fun SearchResponse.addQuality(quality: String) {
    this.quality = getQualityFromString(quality)
}

fun SearchResponse.addPoster(url: String?, headers: Map<String, String>? = null) {
    this.posterUrl = url
    this.posterHeaders = headers
}

fun LoadResponse.addPoster(url: String?, headers: Map<String, String>? = null) {
    this.posterUrl = url
    this.posterHeaders = headers
}

enum class ActorRole {
    Main,
    Supporting,
    Background,
}

data class Actor(
    val name: String,
    val image: String? = null,
)

data class ActorData(
    val actor: Actor,
    val role: ActorRole? = null,
    val roleString: String? = null,
    val voiceActor: Actor? = null,
)

data class AnimeSearchResponse
@Deprecated("Use newAnimeSearchResponse", level = DeprecationLevel.ERROR)
constructor(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,

    override var posterUrl: String? = null,
    var year: Int? = null,
    var dubStatus: MutableSet<DubStatus>? = null,

    var otherName: String? = null,
    var episodes: MutableMap<DubStatus, Int> = mutableMapOf(),

    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var score: Score? = null,
) : SearchResponse

fun AnimeSearchResponse.addDubStatus(status: DubStatus, episodes: Int? = null) {
    this.dubStatus = dubStatus?.also { it.add(status) } ?: mutableSetOf(status)
    if (this.type?.isMovieType() != true)
        if (episodes != null && episodes > 0)
            this.episodes[status] = episodes
}

fun AnimeSearchResponse.addDubStatus(isDub: Boolean, episodes: Int? = null) {
    addDubStatus(if (isDub) DubStatus.Dubbed else DubStatus.Subbed, episodes)
}

fun AnimeSearchResponse.addDub(episodes: Int?) {
    if (episodes == null || episodes <= 0) return
    addDubStatus(DubStatus.Dubbed, episodes)
}

fun AnimeSearchResponse.addSub(episodes: Int?) {
    if (episodes == null || episodes <= 0) return
    addDubStatus(DubStatus.Subbed, episodes)
}

fun AnimeSearchResponse.addDubStatus(
    dubExist: Boolean,
    subExist: Boolean,
    dubEpisodes: Int? = null,
    subEpisodes: Int? = null
) {
    if (dubExist)
        addDubStatus(DubStatus.Dubbed, dubEpisodes)

    if (subExist)
        addDubStatus(DubStatus.Subbed, subEpisodes)
}

fun AnimeSearchResponse.addDubStatus(status: String, episodes: Int? = null) {
    if (status.contains("(dub)", ignoreCase = true)) {
        addDubStatus(DubStatus.Dubbed, episodes)
    } else if (status.contains("(sub)", ignoreCase = true)) {
        addDubStatus(DubStatus.Subbed, episodes)
    }
}

data class TorrentSearchResponse
@Deprecated("Use newTorrentSearchResponse", level = DeprecationLevel.ERROR)
constructor(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType?,

    override var posterUrl: String?,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var score: Score? = null,
) : SearchResponse {
    @Suppress("DEPRECATION_ERROR")
    @Deprecated(
        "Use newTorrentSearchResponse",
        level = DeprecationLevel.ERROR
    )
    constructor(
        name: String,
        url: String,
        apiName: String,
        type: TvType?,
        posterUrl: String?,
        id: Int? = null,
        quality: SearchQuality? = null,
        posterHeaders: Map<String, String>? = null
    ) : this(name, url, apiName, type, posterUrl, id, quality, posterHeaders, null)
}

data class MovieSearchResponse
@Deprecated("Use newMovieSearchResponse", level = DeprecationLevel.ERROR)
constructor(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,

    override var posterUrl: String? = null,
    var year: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var score: Score? = null,
) : SearchResponse {
    @Suppress("DEPRECATION_ERROR")
    @Deprecated(
        "Use newMovieSearchResponse",
        level = DeprecationLevel.ERROR
    )
    constructor(
        name: String,
        url: String,
        apiName: String,
        type: TvType?,
        posterUrl: String?,
        year: Int? = null,
        id: Int? = null,
        quality: SearchQuality? = null,
        posterHeaders: Map<String, String>? = null
    ) : this(name, url, apiName, type, posterUrl, id, year, quality, posterHeaders, null)
}

data class LiveSearchResponse
@Deprecated("Use newLiveSearchResponse", level = DeprecationLevel.ERROR)
constructor(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,

    override var posterUrl: String? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    var lang: String? = null,
    override var score: Score? = null,
) : SearchResponse {
    @Suppress("DEPRECATION_ERROR")
    @Deprecated(
        "Use newLiveSearchResponse",
        level = DeprecationLevel.ERROR
    )
    constructor(
        name: String,
        url: String,
        apiName: String,
        type: TvType?,
        posterUrl: String?,
        id: Int? = null,
        quality: SearchQuality? = null,
        posterHeaders: Map<String, String>? = null,
        lang: String? = null,
    ) : this(name, url, apiName, type, posterUrl, id, quality, posterHeaders, lang, null)
}

data class TvSeriesSearchResponse
@Deprecated("Use newTvSeriesSearchResponse", level = DeprecationLevel.ERROR)
constructor(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,

    override var posterUrl: String? = null,
    var year: Int? = null,
    var episodes: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var score: Score? = null,
) : SearchResponse {
    @Suppress("DEPRECATION_ERROR")
    @Deprecated(
        "Use newTvSeriesSearchResponse",
        level = DeprecationLevel.ERROR
    )
    constructor(
        name: String,
        url: String,
        apiName: String,
        type: TvType?,
        posterUrl: String?,
        year: Int? = null,
        episodes: Int? = null,
        id: Int? = null,
        quality: SearchQuality? = null,
        posterHeaders: Map<String, String>? = null,
    ) : this(
        name,
        url,
        apiName,
        type,
        posterUrl,
        year,
        episodes,
        id,
        quality,
        posterHeaders,
        null
    )
}

data class TrailerData(
    val extractorUrl: String,
    val referer: String?,
    val raw: Boolean,
    val headers: Map<String, String> = mapOf(),

)

interface LoadResponse {
    var name: String
    var url: String
    var apiName: String
    var type: TvType
    var posterUrl: String?
    var year: Int?
    var plot: String?

    var score: Score?
    var tags: List<String>?
    var duration: Int?
    var trailers: MutableList<TrailerData>

    var recommendations: List<SearchResponse>?
    var actors: List<ActorData>?
    var comingSoon: Boolean
    var syncData: MutableMap<String, String>
    var posterHeaders: Map<String, String>?
    var backgroundPosterUrl: String?

    var logoUrl: String?
    var contentRating: String?

    var uniqueUrl: String

    @Deprecated(
        "`rating` is the old scoring system, use score instead",
        replaceWith = ReplaceWith("score"),
        level = DeprecationLevel.ERROR
    )
    var rating: Int?
        set(value) {
            @Suppress("DEPRECATION_ERROR")
            this.score = Score.fromOld(value)
        }
        @Suppress("DEPRECATION_ERROR")
        get() = score?.toOld()

    companion object {
        var malIdPrefix = ""

        var kitsuIdPrefix = ""
        var aniListIdPrefix = ""
        var simklIdPrefix = ""
        var isTrailersEnabled = true

        fun addIdToString(idString: String?, database: SimklSyncServices, id: String?): String? {
            if (id == null) return idString
            return (readIdFromString(idString) + mapOf(database to id)).toJson()
        }

        fun readIdFromString(idString: String?): Map<SimklSyncServices, String> {
            return tryParseJson<Map<SimklSyncServices, String>>(idString) ?: return emptyMap()
        }

        fun LoadResponse.isMovie(): Boolean {
            return this.type.isMovieType() || this is MovieLoadResponse
        }

        @JvmName("addActorNames")
        fun LoadResponse.addActors(actors: List<String>?) {
            this.actors = actors?.map { ActorData(Actor(it)) }
        }

        @JvmName("addActors")
        fun LoadResponse.addActors(actors: List<Pair<Actor, String?>>?) {
            this.actors = actors?.map { (actor, role) -> ActorData(actor, roleString = role) }
        }

        @JvmName("addActorsRole")
        fun LoadResponse.addActors(actors: List<Pair<Actor, ActorRole?>>?) {
            this.actors = actors?.map { (actor, role) -> ActorData(actor, role = role) }
        }

        private fun LoadResponse.addSimklId(
            database: SimklSyncServices,
            id: String?
        ) {
            safe {
                this.syncData[simklIdPrefix] =
                    addIdToString(this.syncData[simklIdPrefix], database, id.toString())
                        ?: return@safe
            }
        }

        @JvmName("addActorsOnly")
        fun LoadResponse.addActors(actors: List<Actor>?) {
            this.actors = actors?.map { actor -> ActorData(actor) }
        }

        fun LoadResponse.getMalId(): String? {
            return this.syncData[malIdPrefix]
        }

        fun LoadResponse.getKitsuId(): String? {
            return this.syncData[kitsuIdPrefix]
        }
        fun LoadResponse.getAniListId(): String? {
            return this.syncData[aniListIdPrefix]
        }

        fun LoadResponse.getImdbId(): String? {
            return safe {
                readIdFromString(this.syncData[simklIdPrefix])[SimklSyncServices.Imdb]
            }
        }

        fun LoadResponse.getTMDbId(): String? {
            return safe {
                readIdFromString(this.syncData[simklIdPrefix])[SimklSyncServices.Tmdb]
            }
        }

        fun LoadResponse.addMalId(id: Int?) {
            this.syncData[malIdPrefix] = (id ?: return).toString()
            this.addSimklId(SimklSyncServices.Mal, id.toString())
        }

        fun LoadResponse.addKitsuId(id: Int?) {
            this.syncData[kitsuIdPrefix] = (id ?: return).toString()
        }

        fun LoadResponse.addAniListId(id: Int?) {
            this.syncData[aniListIdPrefix] = (id ?: return).toString()
            this.addSimklId(SimklSyncServices.AniList, id.toString())
        }

        fun LoadResponse.addSimklId(id: Int?) {
            this.addSimklId(SimklSyncServices.Simkl, id.toString())
        }

        fun LoadResponse.addImdbUrl(url: String?) {
            addImdbId(imdbUrlToIdNullable(url))
        }

        @Suppress("RedundantSuspendModifier")
        suspend fun LoadResponse.addTrailer(
            trailerUrl: String?,
            referer: String? = null,
            addRaw: Boolean = false
        ) {
            if (!isTrailersEnabled || trailerUrl.isNullOrBlank()) return
            this.trailers.add(TrailerData(trailerUrl, referer, addRaw))

        }

        @Suppress("RedundantSuspendModifier")
        suspend fun LoadResponse.addTrailer(
            trailerUrl: String?,
            referer: String? = null,
            addRaw: Boolean = false,
            headers: Map<String, String> = mapOf()
        ) {
            if (!isTrailersEnabled || trailerUrl.isNullOrBlank()) return
            this.trailers.add(TrailerData(trailerUrl, referer, addRaw, headers))
        }

        @Suppress("RedundantSuspendModifier")
        suspend fun LoadResponse.addTrailer(
            trailerUrls: List<String>?,
            referer: String? = null,
            addRaw: Boolean = false
        ) {
            if (!isTrailersEnabled || trailerUrls == null) return
            trailers.addAll(trailerUrls.map { TrailerData(it, referer, addRaw) })

        }

        fun LoadResponse.addImdbId(id: String?) {

            this.addSimklId(SimklSyncServices.Imdb, id)
        }

        @Suppress("UNUSED_PARAMETER")
        fun LoadResponse.addTraktId(id: String?) {

        }

        @Suppress("UNUSED_PARAMETER")
        fun LoadResponse.addKitsuId(id: String?) {

        }

        fun LoadResponse.addTMDbId(id: String?) {

            this.addSimklId(SimklSyncServices.Tmdb, id)
        }

        fun LoadResponse.addScore(score: String?, maxValue: Int = 10) {
            this.score = Score.from(score, maxValue)
        }

        fun LoadResponse.addScore(score: Score?) {
            this.score = score
        }

        @Deprecated(
            "Use addScore",
            replaceWith = ReplaceWith("addScore"),
            level = DeprecationLevel.ERROR
        )
        fun LoadResponse.addRating(text: String?) {
            this.score = Score.from10(text)
        }

        @Deprecated(
            "Use addScore",
            replaceWith = ReplaceWith("addScore"),
            level = DeprecationLevel.ERROR
        )
        fun LoadResponse.addRating(value: Int?) {
            @Suppress("DEPRECATION_ERROR")
            this.score = Score.fromOld(value)
        }

        fun LoadResponse.addDuration(input: String?) {
            this.duration = getDurationFromString(input) ?: this.duration
        }
    }
}

fun getDurationFromString(input: String?): Int? {
    val cleanInput = input?.trim()?.replace(" ", "") ?: return null

    Regex("(\\d+\\shr)|(\\d+\\shour)|(\\d+\\smin)|(\\d+\\ssec)").findAll(input).let { values ->
        var seconds = 0
        values.forEach {
            val timeText = it.value
            if (timeText.isNotBlank()) {
                val time = timeText.filter { s -> s.isDigit() }.trim().toInt()
                val scale = timeText.filter { s -> !s.isDigit() }.trim()

                val timeval = when (scale) {
                    "hr", "hour" -> time * 60 * 60
                    "min" -> time * 60
                    "sec" -> time
                    else -> 0
                }
                seconds += timeval
            }
        }
        if (seconds > 0) {
            return seconds / 60
        }
    }
    Regex("([0-9]*)h.*?([0-9]*)m").find(cleanInput)?.groupValues?.let { values ->
        if (values.size == 3) {
            val hours = values[1].toIntOrNull()
            val minutes = values[2].toIntOrNull()
            if (minutes != null && hours != null) {
                return hours * 60 + minutes
            }
        }
    }
    Regex("([0-9]*)m").find(cleanInput)?.groupValues?.let { values ->
        if (values.size == 2) {
            val returnValue = values[1].toIntOrNull()
            if (returnValue != null) {
                return returnValue
            }
        }
    }
    return null
}

fun LoadResponse?.isEpisodeBased(): Boolean {
    if (this == null) return false
    return this is EpisodeResponse && this.type.isEpisodeBased()
}

fun LoadResponse?.isAnimeBased(): Boolean {
    if (this == null) return false
    return (this.type == TvType.Anime || this.type == TvType.OVA)
}

fun TvType?.isEpisodeBased(): Boolean {
    return when (this) {
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Cartoon,
        TvType.TvSeries -> true

        else -> false
    }
}

fun TvType.getFolderPrefix(): String {
    return when (this) {
        TvType.Anime -> "Anime"
        TvType.AnimeMovie -> "Movies"
        TvType.AsianDrama -> "AsianDramas"
        TvType.Audio -> "Audio"
        TvType.AudioBook -> "AudioBooks"
        TvType.Cartoon -> "Cartoons"
        TvType.CustomMedia -> "Media"
        TvType.Documentary -> "Documentaries"
        TvType.Live -> "LiveStreams"
        TvType.Movie -> "Movies"
        TvType.Music -> "Music"
        TvType.NSFW -> "NSFW"
        TvType.OVA -> "OVAs"
        TvType.Others -> "Others"
        TvType.Podcast -> "Podcasts"
        TvType.Torrent -> "Torrents"
        TvType.TvSeries -> "TVSeries"
        TvType.Video -> "Videos"
    }
}

data class NextAiring(
    val episode: Int,
    val unixTime: Long,
    val season: Int? = null,
)

@Serializable
data class SeasonData(
    @JsonProperty("season") @SerialName("season") val season: Int,
    @JsonProperty("name") @SerialName("name") val name: String? = null,
    @JsonProperty("displaySeason") @SerialName("displaySeason") val displaySeason: Int? = null,
)

interface EpisodeResponse {
    var showStatus: ShowStatus?
    var nextAiring: NextAiring?
    var seasonNames: List<SeasonData>?
    fun getLatestEpisodes(): Map<DubStatus, Int?>

    fun getTotalEpisodeIndex(episode: Int, season: Int): Int
}

@JvmName("addSeasonNamesString")
fun EpisodeResponse.addSeasonNames(names: List<String>) {
    this.seasonNames = if (names.isEmpty()) null else names.mapIndexed { index, s ->
        SeasonData(
            season = index + 1,
            s
        )
    }
}

@JvmName("addSeasonNamesSeasonData")
fun EpisodeResponse.addSeasonNames(names: List<SeasonData>) {
    this.seasonNames = names.ifEmpty { null }
}

data class TorrentLoadResponse
@Deprecated("Use newTorrentLoadResponse method", level = DeprecationLevel.ERROR)
constructor(
    override var name: String,
    override var url: String,
    override var apiName: String,
    var magnet: String?,
    var torrent: String?,
    override var plot: String?,
    override var type: TvType = TvType.Torrent,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String = url
) : LoadResponse

suspend fun MainAPI.newTorrentLoadResponse(
    name: String,
    url: String,
    magnet: String? = null,
    torrent: String? = null,
    initializer: suspend TorrentLoadResponse.() -> Unit = { }
): TorrentLoadResponse {
    @Suppress("DEPRECATION_ERROR")
    val builder = TorrentLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        magnet = magnet,
        torrent = torrent,

        plot = null,
        comingSoon = magnet.isNullOrBlank() && torrent.isNullOrBlank()
    )
    builder.initializer()
    return builder
}

data class AnimeLoadResponse
@Deprecated("Use newAnimeLoadResponse method", level = DeprecationLevel.ERROR)
constructor(
    var engName: String? = null,
    var japName: String? = null,
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType,

    override var posterUrl: String? = null,
    override var year: Int? = null,

    var episodes: MutableMap<DubStatus, List<Episode>> = mutableMapOf(),
    override var showStatus: ShowStatus? = null,

    override var plot: String? = null,
    override var tags: List<String>? = null,
    var synonyms: List<String>? = null,

    override var score: Score? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var nextAiring: NextAiring? = null,
    override var seasonNames: List<SeasonData>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String = url
) : LoadResponse, EpisodeResponse {

    override fun getLatestEpisodes(): Map<DubStatus, Int?> {
        return episodes.map { (status, episodes) ->
            val maxSeason = episodes.maxOfOrNull { it.season ?: Int.MIN_VALUE }
                .takeUnless { it == Int.MIN_VALUE }
            status to episodes
                .filter { it.season == maxSeason }
                .maxOfOrNull { it.episode ?: Int.MIN_VALUE }
                .takeUnless { it == Int.MIN_VALUE }
        }.toMap()
    }

    override fun getTotalEpisodeIndex(episode: Int, season: Int): Int {
        val displayMap = this.seasonNames?.associate { it.season to it.displaySeason } ?: emptyMap()

        return this.episodes.maxOf { (_, episodes) ->
            episodes.count { episodeData ->

                val episodeSeason =
                    displayMap[episodeData.season] ?: episodeData.season ?: Int.MIN_VALUE

                episodeSeason in 1..<season
            }
        } + episode
    }
}

fun AnimeLoadResponse.addEpisodes(status: DubStatus, episodes: List<Episode>?) {
    if (episodes.isNullOrEmpty()) return
    this.episodes[status] = (this.episodes[status] ?: emptyList()) + episodes
}

suspend fun MainAPI.newAnimeLoadResponse(
    name: String,
    url: String,
    type: TvType,
    comingSoonIfNone: Boolean = true,
    initializer: suspend AnimeLoadResponse.() -> Unit = { },
): AnimeLoadResponse {
    @Suppress("DEPRECATION_ERROR")
    val builder = AnimeLoadResponse(name = name, url = url, apiName = this.name, type = type)
    builder.initializer()
    if (comingSoonIfNone) {
        builder.comingSoon = true
        for (key in builder.episodes.keys)
            if (!builder.episodes[key].isNullOrEmpty()) {
                builder.comingSoon = false
                break
            }
    }
    return builder
}

data class LiveStreamLoadResponse
@Deprecated("Use newLiveStreamLoadResponse method", level = DeprecationLevel.ERROR)
constructor(
    override var name: String,
    override var url: String,
    override var apiName: String,
    var dataUrl: String,

    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,

    override var type: TvType = TvType.Live,
    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String = url
) : LoadResponse

suspend fun MainAPI.newLiveStreamLoadResponse(
    name: String,
    url: String,
    dataUrl: String,
    initializer: suspend LiveStreamLoadResponse.() -> Unit = { }
): LiveStreamLoadResponse {
    @Suppress("DEPRECATION_ERROR")
    val builder = LiveStreamLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        dataUrl = dataUrl,
        comingSoon = dataUrl.isBlank()
    )
    builder.initializer()
    return builder
}

data class MovieLoadResponse
@Deprecated("Use newMovieLoadResponse method", level = DeprecationLevel.ERROR)
constructor(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType,
    var dataUrl: String,

    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,

    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String = url
) : LoadResponse

suspend fun <T> MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType,
    data: T?,
    initializer: suspend MovieLoadResponse.() -> Unit = { }
): MovieLoadResponse {

    if (data is String) return newMovieLoadResponse(
        name,
        url,
        type,
        dataUrl = data,
        initializer = initializer
    )
    val dataUrl = data?.toJson() ?: ""

    @Suppress("DEPRECATION_ERROR")
    val builder = MovieLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        dataUrl = dataUrl,
        comingSoon = dataUrl.isBlank()
    )
    builder.initializer()
    return builder
}

suspend fun MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType,
    dataUrl: String,
    initializer: suspend MovieLoadResponse.() -> Unit = { }
): MovieLoadResponse {
    @Suppress("DEPRECATION_ERROR")
    val builder = MovieLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        dataUrl = dataUrl,
        comingSoon = dataUrl.isBlank()
    )
    builder.initializer()
    return builder
}

data class Episode
@Deprecated("Use newEpisode method", level = DeprecationLevel.ERROR)
constructor(
    var data: String,
    var name: String? = null,
    var season: Int? = null,
    var episode: Int? = null,
    var posterUrl: String? = null,
    var score: Score? = null,
    var description: String? = null,
    var date: Long? = null,
    var runTime: Int? = null,
) {
    @Deprecated(
        "`rating` is the old scoring system, use score instead",
        replaceWith = ReplaceWith("score"),
        level = DeprecationLevel.ERROR
    )
    var rating: Int?
        set(value) {
            this.score = Score.from(value, 100)
        }
        get() = score?.toInt(100)
}

@OptIn(FormatStringsInDatetimeFormats::class)
fun Episode.addDate(date: String?, format: String = "yyyy-MM-dd") {
    if (date.isNullOrBlank()) return
    this.date = runCatching {

        runCatching { Instant.parse(date).toEpochMilliseconds() }
            .getOrElse {
                val fmt = DateTimeComponents.Format { byUnicodePattern(format) }

                val components = runCatching {
                    DateTimeComponents.parse(date, fmt)
                }.recoverCatching {
                    DateTimeComponents.parse(date.trimStart().take(format.length), fmt)
                }.getOrThrow()

                runCatching { components.toInstantUsingOffset().toEpochMilliseconds() }
                    .recoverCatching { components.toLocalDateTime().toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() }
                    .getOrElse { components.toLocalDate().atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds() }
            }
    }.onFailure { logError(it) }.getOrNull()
}

fun Episode.addDate(date: LocalDate?) {
    this.date = date?.atStartOfDayIn(TimeZone.currentSystemDefault())?.toEpochMilliseconds()
}

fun Episode.addDate(date: Instant?) {
    this.date = date?.toEpochMilliseconds()
}

@Deprecated(
    message = "Use addDate with LocalDate, Instant, or String instead.",
    level = DeprecationLevel.WARNING,
)
fun Episode.addDate(date: java.util.Date?) {
    this.date = date?.time
}

fun MainAPI.newEpisode(
    url: String,
    initializer: Episode.() -> Unit = { },
    fix: Boolean = true,
): Episode {
    @Suppress("DEPRECATION_ERROR")
    val builder = Episode(
        data = if (fix) fixUrl(url) else url
    )
    builder.initializer()
    return builder
}

fun <T> MainAPI.newEpisode(
    data: T,
    initializer: Episode.() -> Unit = { }
): Episode {
    if (data is String) return newEpisode(
        url = data,
        initializer = initializer
    )

    @Suppress("DEPRECATION_ERROR")
    val builder = Episode(
        data = data?.toJson() ?: throw ErrorLoadingException("invalid newEpisode")
    )
    builder.initializer()
    return builder
}

interface IDownloadableMinimum {
    val url: String
    val referer: String
    val headers: Map<String, String>
}

enum class SimklSyncServices(val originalName: String) {
    Simkl("simkl"),
    Imdb("imdb"),
    Tmdb("tmdb"),
    AniList("anilist"),
    Mal("mal"),
}

data class TvSeriesLoadResponse
@Deprecated("Use newTvSeriesLoadResponse method", level = DeprecationLevel.ERROR)
constructor(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType,
    var episodes: List<Episode>,

    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,

    override var showStatus: ShowStatus? = null,
    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var nextAiring: NextAiring? = null,
    override var seasonNames: List<SeasonData>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String = url
) : LoadResponse, EpisodeResponse {
    override fun getLatestEpisodes(): Map<DubStatus, Int?> {
        val maxSeason =
            episodes.maxOfOrNull { it.season ?: Int.MIN_VALUE }.takeUnless { it == Int.MIN_VALUE }
        val max = episodes
            .filter { it.season == maxSeason }
            .maxOfOrNull { it.episode ?: Int.MIN_VALUE }
            .takeUnless { it == Int.MIN_VALUE }
        return mapOf(DubStatus.None to max)
    }

    override fun getTotalEpisodeIndex(episode: Int, season: Int): Int {
        val displayMap = this.seasonNames?.associate { it.season to it.displaySeason } ?: emptyMap()

        return episodes.count { episodeData ->

            val episodeSeason =
                displayMap[episodeData.season] ?: episodeData.season ?: Int.MIN_VALUE

            episodeSeason in 1..<season
        } + episode
    }
}

suspend fun MainAPI.newTvSeriesLoadResponse(
    name: String,
    url: String,
    type: TvType,
    episodes: List<Episode>,
    initializer: suspend TvSeriesLoadResponse.() -> Unit = { }
): TvSeriesLoadResponse {
    @Suppress("DEPRECATION_ERROR")
    val builder = TvSeriesLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        episodes = episodes,
        comingSoon = episodes.isEmpty(),
    )
    builder.initializer()
    return builder
}

fun fetchUrls(text: String?): List<String> {
    if (text.isNullOrEmpty()) {
        return listOf()
    }
    val linkRegex =
        Regex("""(https?://(www\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b([-a-zA-Z0-9()@:%_+.~#?&/=]*))""")
    return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
}

fun isUpcoming(dateString: String?): Boolean {
    return runCatching {
        val fmt = DateTimeComponents.Format {
            year(); char('-'); monthNumber(); char('-'); day()
        }
        val components = DateTimeComponents.parse(dateString ?: return false, fmt)

        val instant = runCatching { components.toInstantUsingOffset() }
            .recoverCatching { components.toLocalDateTime().toInstant(TimeZone.currentSystemDefault()) }
            .getOrElse { components.toLocalDate().atStartOfDayIn(TimeZone.currentSystemDefault()) }
        Clock.System.now() < instant
    }.onFailure { logError(it) }.getOrElse { false }
}

@Deprecated(
    "toRatingInt() is deprecated. Use new score API instead.",
    level = DeprecationLevel.ERROR
)
fun String?.toRatingInt(): Int? =
    this?.replace(" ", "")?.trim()?.toDoubleOrNull()?.absoluteValue?.times(1000f)?.toInt()

data class Tracker(
    val malId: Int? = null,
    val kitsuId: String? = null,
    val aniId: String? = null,
    val image: String? = null,
    val cover: String? = null,
)

@Serializable
data class AniSearch(
    @JsonProperty("data") @SerialName("data") var data: Data? = Data(),
) {
    @Serializable
    data class Data(
        @JsonProperty("Page") @SerialName("Page") var page: Page? = Page(),
    ) {
        @Serializable
        data class Page(
            @JsonProperty("media") @SerialName("media") var media: ArrayList<Media> = arrayListOf(),
        ) {
            @Serializable
            data class Media(
                @JsonProperty("title") @SerialName("title") var title: Title? = null,
                @JsonProperty("id") @SerialName("id") var id: Int? = null,
                @JsonProperty("idMal") @SerialName("idMal") var idMal: Int? = null,
                @JsonProperty("seasonYear") @SerialName("seasonYear") var seasonYear: Int? = null,
                @JsonProperty("format") @SerialName("format") var format: String? = null,
                @JsonProperty("coverImage") @SerialName("coverImage") var coverImage: CoverImage? = null,
                @JsonProperty("bannerImage") @SerialName("bannerImage") var bannerImage: String? = null,
            ) {
                @Serializable
                data class CoverImage(
                    @JsonProperty("extraLarge") @SerialName("extraLarge") var extraLarge: String? = null,
                    @JsonProperty("large") @SerialName("large") var large: String? = null,
                )

                @Serializable
                data class Title(
                    @JsonProperty("romaji") @SerialName("romaji") var romaji: String? = null,
                    @JsonProperty("english") @SerialName("english") var english: String? = null,
                ) {
                    fun isMatchingTitles(title: String?): Boolean {
                        if (title == null) return false
                        return english.equals(title, true) || romaji.equals(title, true)
                    }
                }
            }
        }
    }
}

enum class TrackerType {
    MOVIE,
    TV,
    TV_SHORT,
    ONA,
    OVA,
    SPECIAL,
    MUSIC;

    companion object {
        fun getTypes(type: TvType): Set<TrackerType> {
            return when (type) {
                TvType.Movie -> setOf(MOVIE)
                TvType.AnimeMovie -> setOf(MOVIE)
                TvType.TvSeries -> setOf(TV, TV_SHORT)
                TvType.Anime -> setOf(TV, TV_SHORT, ONA, OVA)
                TvType.OVA -> setOf(OVA, SPECIAL, ONA)
                TvType.Others -> setOf(MUSIC)
                else -> emptySet()
            }
        }
    }
}
