package ani.sanin.parsers

import android.net.Uri
import ani.sanin.FileUrl
import ani.sanin.R
import ani.sanin.limitedAsyncMap
import ani.sanin.currContext
import ani.sanin.others.MalSyncBackup
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.tryWithSuspend
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.withTimeoutOrNull

abstract class AnimeParser : BaseParser() {

    abstract suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode>

    open suspend fun getLatestEpisode(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime,
        latest: Float
    ): Episode? {
        val episodes = loadEpisodes(animeLink, extra, sAnime)
        val max = episodes
            .maxByOrNull { it.number.toFloatOrNull() ?: 0f }
        return max
            ?.takeIf { latest < (it.number.toFloatOrNull() ?: 0.001f) }
    }

    abstract suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer>

    open suspend fun getVideoExtractor(server: VideoServer): VideoExtractor? {
        var domain = Uri.parse(server.embed.url).host ?: return null
        if (domain.startsWith("www.")) {
            domain = domain.substring(4)
        }

        return when (domain) {
            else -> {
                println("$name : No extractor found for: $domain | ${server.embed.url}")
                null
            }
        }
    }

    open val allowsPreloading = true

    open suspend fun loadByVideoServers(
        episodeUrl: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode,
        callback: (VideoExtractor) -> Unit,
        servers: List<VideoServer>? = null
    ) {
        tryWithSuspend(true) {
            val timeoutMs =
                PrefManager.getVal<Int>(PrefName.ServerLoadTimeoutSeconds).coerceIn(10, 25) * 1000L
            (servers ?: loadVideoServers(episodeUrl, extra, sEpisode)).limitedAsyncMap(concurrency = 4) {
                withTimeoutOrNull(timeoutMs) {
                    getVideoExtractor(it)?.apply {
                        tryWithSuspend(true) {
                            load()
                        }
                    }
                }?.let { extractor -> callback.invoke(extractor) }
            }
        }
    }

    open suspend fun loadSingleVideoServer(
        serverName: String,
        episodeUrl: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode,
        post: Boolean
    ): VideoExtractor? {
        return tryWithSuspend(post) {
            val servers = loadVideoServers(episodeUrl, extra, sEpisode)

            val target = servers.find { it.name == serverName }
                ?: servers.find { it.isPreferred }
                ?: servers.firstOrNull()
            target?.let {
                getVideoExtractor(it)?.apply {
                    load()
                }
            }
        }
    }

    open fun isDubAvailableSeparately(sourceLang: Int? = null): Boolean = false

    open var selectDub = false

    open val malSyncBackupName = ""

    override suspend fun loadSavedShowResponse(mediaId: Int): ShowResponse? {
        checkIfVariablesAreEmpty()
        val dub = if (isDubAvailableSeparately()) "_${if (selectDub) "dub" else "sub"}" else ""
        var loaded = PrefManager.getNullableCustomVal(
            "${saveName}${dub}_$mediaId",
            null,
            ShowResponse::class.java
        )
        if (loaded == null && malSyncBackupName.isNotEmpty())
            loaded = MalSyncBackup.get(mediaId, malSyncBackupName, selectDub)
                ?.also { saveShowResponse(mediaId, it, true) }
        return loaded
    }

    override fun saveShowResponse(mediaId: Int, response: ShowResponse?, selected: Boolean) {
        if (response != null) {
            checkIfVariablesAreEmpty()
            setUserText(
                "${
                    if (selected) currContext()!!.getString(R.string.selected) else currContext()!!.getString(
                        R.string.found
                    )
                } : ${response.name}"
            )
            val dub = if (isDubAvailableSeparately()) "_${if (selectDub) "dub" else "sub"}" else ""
            PrefManager.setCustomVal("${saveName}${dub}_$mediaId", response)
        }
    }
}

class EmptyAnimeParser : AnimeParser() {
    override val name: String = "None"
    override val saveName: String = "None"
    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> = emptyList()

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> = emptyList()

    override suspend fun search(query: String): List<ShowResponse> = emptyList()
}

data class Episode(

    val number: String,

    val link: String,

    val title: String? = null,
    val thumbnail: FileUrl? = null,
    val description: String? = null,
    val isFiller: Boolean = false,

    val extra: Map<String, String>? = null,

    val sEpisode: SEpisode? = null
) {
    constructor(
        number: String,
        link: String,
        title: String? = null,
        thumbnail: String,
        description: String? = null,
        isFiller: Boolean = false,
        extra: Map<String, String>? = null
    ) : this(number, link, title, FileUrl(thumbnail), description, isFiller, extra)

    constructor(
        number: String,
        link: String,
        title: String? = null,
        thumbnail: String,
        description: String? = null,
        isFiller: Boolean = false,
        extra: Map<String, String>? = null,
        sEpisode: SEpisode? = null
    ) : this(number, link, title, FileUrl(thumbnail), description, isFiller, extra, sEpisode)
}
