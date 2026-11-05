package ani.sanin.media

import ani.sanin.client
import ani.sanin.connections.anilist.AnilistQueries
import ani.sanin.others.IdMappers
import ani.sanin.media.anime.Anime
import ani.sanin.media.anime.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ani.sanin.util.Logger

object EpisodeMapper {

    data class SeasonEpisode(val season: Int, val episode: Int)

    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val episodeCache = HashMap<String, SeasonEpisode>()

    suspend fun mapEpisode(media: Media, episodeNumber: Int, episode: Episode? = null): SeasonEpisode {

        val cacheKey = buildString {
            append(media.id).append('-').append(episode?.number ?: episodeNumber)
            episode?.extra?.get("season")?.let { append("-s").append(it) }
            episode?.extra?.get("episode")?.let { append("-e").append(it) }
        }
        if (episodeCache.containsKey(cacheKey)) {
            return episodeCache[cacheKey]!!
        }
        val result = mapEpisodeInternal(media, episodeNumber, episode)
        episodeCache[cacheKey] = result
        return result
    }

    fun episodeNumberOf(selectedEpisode: String, episode: Episode?): Int =
        selectedEpisode.toIntOrNull()
            ?: Regex("""(?i)^S\d+E(\d+)$""").find(selectedEpisode.trim())
                ?.groupValues?.get(1)?.toIntOrNull()
            ?: episode?.extra?.get("episode")?.toIntOrNull()
            ?: 1

    private suspend fun mapEpisodeInternal(media: Media, episodeNumber: Int, episode: Episode? = null): SeasonEpisode {
        Logger.log("EpisodeMapper: Mapping ${media.userPreferredName} (ID: ${media.id}) Ep: $episodeNumber")

        val aniZipResult = getAniZipFromEpisode(episode, episodeNumber)
        if (aniZipResult != null) {
            Logger.log("EpisodeMapper: AniZip (from cache) mapping found: $aniZipResult")
            return aniZipResult
        }
        Logger.log("EpisodeMapper: No AniZip data in episode, falling back to API mapping")

        val apiMapping = getApiMapping(media, episodeNumber)
        if (apiMapping != null) {
            Logger.log("EpisodeMapper: API Mapping found: $apiMapping")
            return apiMapping
        }
        Logger.log("EpisodeMapper: No API Mapping found")

        val totalEpisodes = media.anime?.totalEpisodes ?: 0
        val isLongRunning = totalEpisodes > 300 || totalEpisodes == 0
        Logger.log("EpisodeMapper: TotalEp: $totalEpisodes, isLongRunning: $isLongRunning")

        if (isLongRunning && episodeNumber > 100) {
            Logger.log("EpisodeMapper: Using Continuous Mapping (Long Running & Ep > 100)")
            return getContinuousMapping(media, episodeNumber)
        }

        Logger.log("EpisodeMapper: Using Prequel Chain (in-season episode)")
        val chainResult = mapSeasonByPrequelChain(media, episodeNumber)
        if (chainResult != null) {
            Logger.log("EpisodeMapper: Prequel-chain mapping found: $chainResult")
            return chainResult
        }

        Logger.log("EpisodeMapper: Using Seasonal Offset")
        val calculatedSeason = getSeasonOffset(media)
        Logger.log("EpisodeMapper: Calculated Season: $calculatedSeason")
        return SeasonEpisode(calculatedSeason.coerceAtLeast(1), episodeNumber)
    }

    private fun getAniZipFromEpisode(episode: Episode?, episodeNumber: Int): SeasonEpisode? {
        val extra = episode?.extra ?: return null
        val season = extra["season"]?.toIntOrNull() ?: return null
        val epInSeason = extra["episode"]?.toIntOrNull() ?: episodeNumber
        return SeasonEpisode(season, epInSeason)
    }

    private suspend fun getApiMapping(media: Media, episodeNumber: Int): SeasonEpisode? {
        val ids = IdMappers.getIds(media.id)

        var mappings = ids?.tmdbMappings
        if (mappings != null) {
            val result = checkMappings(mappings, episodeNumber)
            if (result != null) return result
        }

        mappings = ids?.tvdbMappings
        if (mappings != null) {
            val result = checkMappings(mappings, episodeNumber)
            if (result != null) return result
        }

        Logger.log("EpisodeMapper: No API Mapping found")
        return null
    }

    private fun checkMappings(mappings: Map<String, String>, episodeNumber: Int): SeasonEpisode? {
        Logger.log("EpisodeMapper: Checking ${mappings.size} mappings")
        for ((key, value) in mappings) {
            try {

                val season = key.removePrefix("s").toIntOrNull() ?: continue

                val parts = value.split("-")
                val start = parts[0].removePrefix("e").toIntOrNull() ?: continue
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    parts[1].removePrefix("e").toIntOrNull() ?: Int.MAX_VALUE
                } else if (parts.size > 1) {
                    Int.MAX_VALUE
                } else {
                    start
                }

                if (episodeNumber in start..end) {
                    Logger.log("EpisodeMapper: Match found for $key: $value")
                    return SeasonEpisode(season, episodeNumber - start + 1)
                }
            } catch (e: Exception) {
                Logger.log("EpisodeMapper: Error parsing mapping $key=$value")
            }
        }
        return null
    }

    private suspend fun getContinuousMapping(media: Media, episodeNumber: Int): SeasonEpisode {
        Logger.log("EpisodeMapper: Entering getContinuousMapping")

        val apiMapping = getApiMapping(media, episodeNumber)
        if (apiMapping != null) return apiMapping

        val imdbId = IdMappers.getImdbId(media.id) ?: return SeasonEpisode(1, episodeNumber)

        if (imdbId == "tt0388629") {
            val onePieceResult = getTvMazeMapping(imdbId, episodeNumber)
            if (onePieceResult != null) return onePieceResult
        }

        val primaryResult = getImdbApiMapping(imdbId, episodeNumber)
        if (primaryResult != null) return primaryResult

        val backupResult = getTvMazeMapping(imdbId, episodeNumber)
        if (backupResult != null) return backupResult

        return SeasonEpisode(1, episodeNumber)
    }

    private suspend fun getImdbApiMapping(imdbId: String, absoluteEpisode: Int): SeasonEpisode? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.imdbapi.dev/titles/$imdbId/episodes"
                val response = client.get(url)

                if (response.code in 200..299) {
                    val data = jsonParser.decodeFromString<ImdbApiResult>(response.text)
                    val episodes = data.episodes

                    val index = absoluteEpisode - 1

                    if (index >= 0 && index < episodes.size) {
                        val ep = episodes[index]
                        return@withContext SeasonEpisode(ep.season, ep.episodeNumber)
                    }
                }
                return@withContext null
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    private suspend fun getTvMazeMapping(imdbId: String, absoluteEpisode: Int): SeasonEpisode? {
        return withContext(Dispatchers.IO) {
            try {
                var tvMazeId = 0

                if (imdbId == "tt0388629") {
                    tvMazeId = 1505
                } else if (imdbId == "tt0115135") {
                    tvMazeId = 331
                }

                else {
                    val lookupUrl = "https://api.tvmaze.com/lookup/shows?imdb=$imdbId"
                    val showResponse = client.get(lookupUrl)

                    if (showResponse.code in 200..299) {
                        val showData = jsonParser.decodeFromString<TvMazeShow>(showResponse.text)
                        tvMazeId = showData.id
                    } else if (showResponse.code in 300..399) {
                        val location = showResponse.headers["Location"]
                        tvMazeId = location?.substringAfterLast("/")?.toIntOrNull() ?: 0
                    }
                }

                if (tvMazeId == 0) return@withContext null

                val episodesUrl = "https://api.tvmaze.com/shows/$tvMazeId/episodes"
                val epsResponse = client.get(episodesUrl)
                val epsData = jsonParser.decodeFromString<List<TvMazeEpisode>>(epsResponse.text)

                val index = absoluteEpisode - 1

                if (index >= 0 && index < epsData.size) {
                    val ep = epsData[index]

                    val s = ep.season
                    val e = ep.number ?: (index + 1)

                    return@withContext SeasonEpisode(s, e)
                }

                return@withContext null

            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    private suspend fun mapSeasonByPrequelChain(media: Media, absoluteEpisode: Int): SeasonEpisode? {
        var current = media
        var season = 1
        var episodeOffset = 0
        var safety = 0

        while (safety < 20) {
            val prequel = current.relations?.find {
                (it.relation != null && (it.relation == "PREQUEL" || it.relation!!.startsWith("PREQUEL\n"))) &&
                        (it.format == "TV" || it.format == "TV_SHORT" || it.format == "ONA")
            } ?: break

            val fetchedPrequel = AnilistQueries().getMedia(prequel.id) ?: break
            val detailedPrequel = AnilistQueries().mediaDetails(fetchedPrequel)
            val prequelEpisodes = detailedPrequel.anime?.totalEpisodes ?: 0
            if (prequelEpisodes <= 0) return null
            episodeOffset += prequelEpisodes
            season++
            current = detailedPrequel
            safety++
        }

        val inSeason = absoluteEpisode - episodeOffset
        if (inSeason <= 0) {

            return SeasonEpisode(season, absoluteEpisode)
        }
        Logger.log(
            "EpisodeMapper: prequel chain → S$season E$inSeason " +
                "(abs=$absoluteEpisode offset=$episodeOffset)"
        )
        return SeasonEpisode(season, inSeason)
    }

    private suspend fun getSeasonOffset(media: Media): Int {

        var current = media
        var season = 1
        var safety = 0

        while (safety < 20) {
            val relations = current.relations

            val prequel = relations?.find {
                (it.relation != null && (it.relation == "PREQUEL" || it.relation!!.startsWith("PREQUEL\n"))) &&
                        (it.format == "TV" || it.format == "TV_SHORT" || it.format == "ONA")
            }

            if (prequel != null) {

                season++
                val fetchedPrequel = AnilistQueries().getMedia(prequel.id)
                if (fetchedPrequel != null) {
                    val detailedPrequel = AnilistQueries().mediaDetails(fetchedPrequel)
                    current = detailedPrequel
                } else {

                    break
                }
            } else {

                break
            }
            safety++
        }

        return season
    }
}

@Serializable
data class ImdbApiResult(
    val episodes: List<ImdbApiEpisode> = emptyList()
)

@Serializable
data class ImdbApiEpisode(
    val season: Int,
    val episodeNumber: Int
)

@Serializable
data class TvMazeShow(
    val id: Int,
    val name: String
)

@Serializable
data class TvMazeEpisode(
    val id: Int,
    val season: Int,
    val number: Int? = null
)