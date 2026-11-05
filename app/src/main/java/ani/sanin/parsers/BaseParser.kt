package ani.sanin.parsers

import android.graphics.drawable.Drawable
import ani.sanin.FileUrl
import ani.sanin.R
import ani.sanin.currContext
import ani.sanin.media.Media
import ani.sanin.settings.saving.PrefManager
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga
import me.xdrop.fuzzywuzzy.FuzzySearch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Serializable
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.system.measureTimeMillis

abstract class BaseParser {

    open val name: String = ""

    open val saveName: String = ""

    open val hostUrl: String = ""

    open val isNSFW = false

    open val language = "English"

    open val icon: Drawable? = null

    open val iconUrl: String? = null

    abstract suspend fun search(query: String): List<ShowResponse>

    open suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        var response: ShowResponse? = loadSavedShowResponse(mediaObj.id)
        if (response != null && this !is OfflineMangaParser) {
            saveShowResponse(mediaObj.id, response, true)
        } else {
            setUserText("Searching : ${mediaObj.mainName()}")
            Logger.log("Searching : ${mediaObj.mainName()}")
            val results = search(mediaObj.mainName())

            results.forEach {
                Logger.log("Result: ${it.name}")
            }
            val sortedResults = if (results.isNotEmpty()) {
                results.sortedByDescending {
                    FuzzySearch.ratio(
                        it.name.lowercase(),
                        mediaObj.mainName().lowercase()
                    )
                }
            } else {
                emptyList()
            }
            response = sortedResults.firstOrNull()

            if (response == null || FuzzySearch.ratio(
                    response.name.lowercase(),
                    mediaObj.mainName().lowercase()
                ) < 100
            ) {
                setUserText("Searching : ${mediaObj.nameRomaji}")
                Logger.log("Searching : ${mediaObj.nameRomaji}")
                val romajiResults = search(mediaObj.nameRomaji)
                val sortedRomajiResults = if (romajiResults.isNotEmpty()) {
                    romajiResults.sortedByDescending {
                        FuzzySearch.ratio(
                            it.name.lowercase(),
                            mediaObj.nameRomaji.lowercase()
                        )
                    }
                } else {
                    emptyList()
                }
                val closestRomaji = sortedRomajiResults.firstOrNull()
                Logger.log("Closest match from RomajiResults: ${closestRomaji?.name ?: "None"}")

                response = if (response == null) {
                    Logger.log("No exact match found in results. Using closest match from RomajiResults.")
                    closestRomaji
                } else {
                    val romajiRatio = FuzzySearch.ratio(
                        closestRomaji?.name?.lowercase() ?: "",
                        mediaObj.nameRomaji.lowercase()
                    )
                    val mainNameRatio = FuzzySearch.ratio(
                        response.name.lowercase(),
                        mediaObj.mainName().lowercase()
                    )
                    Logger.log("Fuzzy ratio for closest match in results: $mainNameRatio for ${response.name.lowercase()}")
                    Logger.log("Fuzzy ratio for closest match in RomajiResults: $romajiRatio for ${closestRomaji?.name?.lowercase() ?: "None"}")

                    if (romajiRatio > mainNameRatio) {
                        Logger.log("RomajiResults has a closer match. Replacing response.")
                        closestRomaji
                    } else {
                        Logger.log("Results has a closer or equal match. Keeping existing response.")
                        response
                    }
                }

            }
            saveShowResponse(mediaObj.id, response)
        }
        return response
    }

    fun ping(): Triple<Int, Int?, String> {
        val client = OkHttpClient()
        var statusCode = 0
        var responseTime: Int? = null
        var responseMessage = ""
        println("Pinging $name at $hostUrl")
        try {
            val request = Request.Builder()
                .url(hostUrl)
                .build()
            responseTime = measureTimeMillis {
                client.newCall(request).execute().use { response ->
                    statusCode = response.code
                    responseMessage = response.message.ifEmpty { "None" }
                }
            }.toInt()
        } catch (e: Exception) {
            Logger.log("Failed to ping $name")
            statusCode = -1
            responseMessage = if (e.message.isNullOrEmpty()) "None" else e.message!!
            Logger.log(e)
        }
        return Triple(statusCode, responseTime, responseMessage)
    }

    open suspend fun loadSavedShowResponse(mediaId: Int): ShowResponse? {
        checkIfVariablesAreEmpty()
        return PrefManager.getNullableCustomVal(
            "${saveName}_$mediaId",
            null,
            ShowResponse::class.java
        )
    }

    open fun saveShowResponse(mediaId: Int, response: ShowResponse?, selected: Boolean = false) {
        if (response != null) {
            checkIfVariablesAreEmpty()
            setUserText(
                "${
                    if (selected) currContext()!!.getString(R.string.selected) else currContext()!!.getString(
                        R.string.found
                    )
                } : ${response.name}"
            )
            PrefManager.setCustomVal("${saveName}_$mediaId", response)
        }
    }

    fun checkIfVariablesAreEmpty() {
        if (hostUrl.isEmpty()) throw UninitializedPropertyAccessException("Cannot find any installed extensions")
        if (name.isEmpty()) throw UninitializedPropertyAccessException("Cannot find any installed extensions")
        if (saveName.isEmpty()) throw UninitializedPropertyAccessException("Cannot find any installed extensions")
    }

    open var showUserText = ""
    open var showUserTextListener: ((String) -> Unit)? = null

    fun setUserText(string: String) {
        showUserText = string
        showUserTextListener?.invoke(showUserText)
    }

    fun encode(input: String): String = URLEncoder.encode(input, "utf-8").replace("+", "%20")
    fun decode(input: String): String = URLDecoder.decode(input, "utf-8")

    val defaultImage = "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/default.jpg"
}

data class ShowResponse(
    val name: String,
    val link: String,
    val coverUrl: FileUrl,

    val otherNames: List<String> = listOf(),

    val total: Int? = null,

    val extra: MutableMap<String, String>? = null,

    val sAnime: SAnime? = null,

    val sManga: SManga? = null
) : Serializable {
    constructor(
        name: String,
        link: String,
        coverUrl: String,
        otherNames: List<String> = listOf(),
        total: Int? = null,
        extra: MutableMap<String, String>? = null
    ) : this(name, link, FileUrl(coverUrl), otherNames, total, extra)

    constructor(
        name: String,
        link: String,
        coverUrl: String,
        otherNames: List<String> = listOf(),
        total: Int? = null
    ) : this(name, link, FileUrl(coverUrl), otherNames, total)

    constructor(name: String, link: String, coverUrl: String, otherNames: List<String> = listOf())
            : this(name, link, FileUrl(coverUrl), otherNames)

    constructor(name: String, link: String, coverUrl: String)
            : this(name, link, FileUrl(coverUrl))

    constructor(name: String, link: String, coverUrl: String, sAnime: SAnime)
            : this(name, link, FileUrl(coverUrl), sAnime = sAnime)

    constructor(name: String, link: String, coverUrl: String, sManga: SManga)
            : this(name, link, FileUrl(coverUrl), sManga = sManga)

    companion object {
        private const val serialVersionUID = 1L
    }
}

