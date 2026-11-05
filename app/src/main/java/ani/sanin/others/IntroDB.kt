package ani.sanin.others

import ani.sanin.Mapper
import ani.sanin.client
import ani.sanin.tryWithSuspend
import ani.sanin.util.Logger
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object IntroDB {

    suspend fun getResult(
        imdbId: String,
        season: Int?,
        episode: Int?,
        isMovie: Boolean = false
    ): List<AniSkip.Stamp>? {
        if (!isMovie && (season == null || episode == null)) {
            Logger.log("IntroDB: movie-mode flag unset and no season/episode, skipping")
            return null
        }
        val url = if (isMovie) {
            "https://api.introdb.app/segments?imdb_id=$imdbId&is_movie=true"
        } else {
            "https://api.introdb.app/segments?imdb_id=$imdbId&season=$season&episode=$episode"
        }
        Logger.log("IntroDB: getResult start imdbId=$imdbId season=$season episode=$episode isMovie=$isMovie url=$url")
        return tryWithSuspend {
            val started = java.lang.System.currentTimeMillis()
            val a = withTimeoutOrNull(10_000L) { client.get(url) }
            val elapsed = java.lang.System.currentTimeMillis() - started
            if (a == null) {
                Logger.log("IntroDB: TIMEOUT after ${elapsed}ms for $url")
                return@tryWithSuspend null
            }
            Logger.log("IntroDB: response code=${a.code} in ${elapsed}ms for $url")
            if (a.code != 200) {
                Logger.log("IntroDB: non-200 response, skipping")
                return@tryWithSuspend null
            }
            val res = try {
                Mapper.json.decodeFromString<IntroDBResponse>(a.text)
            } catch (e: Exception) {
                Logger.log("IntroDB: parse error: ${e.message}")
                return@tryWithSuspend null
            }
            val epTag = if (isMovie) "imdb-movie" else "$season-$episode"
            val stamps = listOfNotNull(
                res.intro?.toStamp("op", "introdb-$epTag-intro"),
                res.recap?.toStamp("recap", "introdb-$epTag-recap"),
                res.outro?.toStamp("ed", "introdb-$epTag-outro"),
                res.postCredits?.toStamp("ed", "introdb-$epTag-postcredits")
            )
            Logger.log(
                "IntroDB: FOUND ${stamps.size} stamps via api.introdb.app " +
                    "intro=${res.intro?.let { "${it.startSec}-${it.endSec}s" }} " +
                    "recap=${res.recap?.let { "${it.startSec}-${it.endSec}s" }} " +
                    "outro=${res.outro?.let { "${it.startSec}-${it.endSec}s" }} " +
                    "post=${res.postCredits?.let { "${it.startSec}-${it.endSec}s" }}"
            )
            if (stamps.isEmpty()) null else stamps
        }
    }

    private fun Segment.toStamp(skipType: String, skipId: String) = AniSkip.Stamp(
        interval = AniSkip.AniSkipInterval(startTime = startSec, endTime = endSec),
        skipType = skipType,
        skipId = skipId,

        episodeLength = 0.0
    )

    @Serializable
    data class IntroDBResponse(
        @SerialName("imdb_id") val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val intro: Segment? = null,
        val recap: Segment? = null,
        val outro: Segment? = null,
        @SerialName("post_credits") val postCredits: Segment? = null
    )

    @Serializable
    data class Segment(
        @SerialName("start_sec") val startSec: Double,
        @SerialName("end_sec") val endSec: Double,
        @SerialName("start_ms") val startMs: Long? = null,
        @SerialName("end_ms") val endMs: Long? = null,
        val confidence: Double? = null,
        @SerialName("submission_count") val submissionCount: Int? = null
    )
}
