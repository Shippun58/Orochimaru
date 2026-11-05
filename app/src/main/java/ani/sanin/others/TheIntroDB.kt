package ani.sanin.others

import ani.sanin.Mapper
import ani.sanin.client
import ani.sanin.tryWithSuspend
import ani.sanin.util.Logger
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object TheIntroDB {

    suspend fun getResult(
        imdbId: String? = null,
        tmdbId: Int? = null,
        tvdbId: Long? = null,
        season: Int?,
        episode: Int?,
        isMovie: Boolean = false,
        durationSec: Long = 0
    ): List<AniSkip.Stamp>? {

        val idParams = buildList {
            if (tvdbId != null && tvdbId > 0) add("tvdb_id=$tvdbId")
            if (tmdbId != null && tmdbId > 0) add("tmdb_id=$tmdbId")
            if (!imdbId.isNullOrBlank()) add("imdb_id=$imdbId")
        }
        if (idParams.isEmpty()) {
            Logger.log("TheIntroDB: no usable id, skipping")
            return null
        }
        return tryWithSuspend {
            for ((attempt, idParam) in idParams.withIndex()) {
                var url = "https://api.theintrodb.org/v4/media?$idParam"
                if (!isMovie && season != null && episode != null) {
                    url += "&season=$season&episode=$episode"
                }
                Logger.log("TheIntroDB: getResult start attempt=${attempt + 1}/${idParams.size} url=$url durationSec=$durationSec")
                val started = java.lang.System.currentTimeMillis()
                val a = withTimeoutOrNull(10_000L) { client.get(url) }
                val elapsed = java.lang.System.currentTimeMillis() - started
                if (a == null) {

                    Logger.log("TheIntroDB: TIMEOUT after ${elapsed}ms for $url")
                    return@tryWithSuspend null
                }
                Logger.log("TheIntroDB: response code=${a.code} in ${elapsed}ms for $url")
                if (a.code != 200 || !a.text.trim().startsWith("{")) {
                    Logger.log("TheIntroDB: non-200 or unexpected body, trying next id")
                    continue
                }
                val res = try {
                    Mapper.json.decodeFromString<Response>(a.text)
                } catch (e: Exception) {
                    Logger.log("TheIntroDB: parse error: ${e.message}")
                    continue
                }
                val stamps = buildList {
                    res.intro?.forEachIndexed { i, seg -> valid(seg, durationSec)?.let { (s, e) -> add(stamp(s, e, "op", "theintrodb-intro-$i")) } }
                    res.recap?.forEachIndexed { i, seg -> valid(seg, durationSec)?.let { (s, e) -> add(stamp(s, e, "recap", "theintrodb-recap-$i")) } }
                    res.credits?.forEachIndexed { i, seg -> valid(seg, durationSec)?.let { (s, e) -> add(stamp(s, e, "ed", "theintrodb-credits-$i")) } }
                    (res.preview ?: res.previews)?.forEachIndexed { i, seg -> valid(seg, durationSec)?.let { (s, e) -> add(stamp(s, e, "other", "theintrodb-preview-$i")) } }
                }
                Logger.log(
                    "TheIntroDB: FOUND ${stamps.size} stamps for $url " +
                        "intro=${res.intro?.size ?: "null"} recap=${res.recap?.size ?: "null"} " +
                        "credits=${res.credits?.size ?: "null"}"
                )
                if (stamps.isNotEmpty()) return@tryWithSuspend stamps
            }
            Logger.log("TheIntroDB: no stamps for ${idParams.size} id(s)")
            null
        }
    }

    private fun valid(seg: Segment, durationSec: Long): Pair<Long, Long>? {
        val start = seg.startMs ?: 0L
        var end = seg.endMs ?: -1L
        if (end < 0) {
            if (durationSec <= 0) {
                Logger.log("TheIntroDB: dropped open-ended segment ${seg.startMs ?: "null"}-null (duration unknown)")
                return null
            }
            end = durationSec * 1000
        }
        val maxEnd = if (durationSec > 0) durationSec * 1000 + 30_000 else Long.MAX_VALUE
        val ok = start >= 0 && end > start && end <= maxEnd
        if (!ok) {
            Logger.log(
                "TheIntroDB: dropped implausible segment " +
                    "${seg.startMs ?: "null"}-${seg.endMs ?: "null"}ms (durationSec=$durationSec)"
            )
        }
        return if (ok) start to end else null
    }

    private fun stamp(startMs: Long, endMs: Long, skipType: String, skipId: String) = AniSkip.Stamp(
        interval = AniSkip.AniSkipInterval(startTime = startMs / 1000.0, endTime = endMs / 1000.0),
        skipType = skipType,
        skipId = skipId,

        episodeLength = 0.0
    )

    @Serializable
    data class Segment(
        @SerialName("start_ms") val startMs: Long? = null,
        @SerialName("end_ms") val endMs: Long? = null
    )

    @Serializable
    data class Response(
        val tvdb_id: Long? = null,
        val tmdb_id: Long? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val intro: List<Segment>? = null,
        val recap: List<Segment>? = null,
        val credits: List<Segment>? = null,
        val preview: List<Segment>? = null,
        val previews: List<Segment>? = null
    )
}
