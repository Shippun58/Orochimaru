package ani.sanin.connections.tmdb

import ani.sanin.media.Franchise
import ani.sanin.media.FranchiseEntry
import ani.sanin.media.FranchiseType
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Serializable
data class TmdbMedia(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    val popularity: Double = 0.0,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("belongs_to_collection") val collection: TmdbCollection? = null,
    val adult: Boolean = false,
    val imdbId: String? = null,
    val sourceAddonUrl: String? = null
) {
    val displayTitle: String get() = title ?: name ?: ""
    val year: String get() = (releaseDate ?: firstAirDate ?: "").take(4)
    val type: String get() = mediaType ?: if (title != null) "movie" else "tv"

    val sortDate: String? get() = releaseDate ?: firstAirDate
}

@Serializable
data class TmdbPage<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0
)

@Serializable
data class TmdbFindResult(
    @SerialName("movie_results") val movieResults: List<TmdbMedia> = emptyList(),
    @SerialName("tv_results") val tvResults: List<TmdbMedia> = emptyList(),
)

@Serializable
data class TmdbProvider(
    @SerialName("provider_id") val id: Int = 0,
    @SerialName("provider_name") val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("display_priority") val priority: Int = 9999
) {
    val displayName: String get() = name.orEmpty()
}

private const val DEFAULT_PROVIDER_REGION = "US"

private const val ANIMATION_GENRE = "16"

private const val UPCOMING_WINDOW_DAYS = 370

private const val MAX_PAGE = 500

private const val TYPE_MOVIE = "movie"
private const val TYPE_TV = "tv"
private const val TYPE_ANIMATION = "animation"

private const val TV_FRANCHISE_SEEDS = 20

private const val MOVIE_SEED_PAGES_PER_BATCH = 4

private const val FRANCHISE_CONCURRENCY = 6

private val MOVIE_GENRES = mapOf(
    28 to "Action",
    12 to "Adventure",
    16 to "Animation",
    35 to "Comedy",
    80 to "Crime",
    99 to "Documentary",
    18 to "Drama",
    10751 to "Family",
    14 to "Fantasy",
    36 to "History",
    27 to "Horror",
    10402 to "Music",
    9648 to "Mystery",
    10749 to "Romance",
    878 to "Science Fiction",
    53 to "Thriller",
    10752 to "War",
    37 to "Western",
)

private val TV_GENRES = mapOf(
    10759 to "Action & Adventure",
    16 to "Animation",
    35 to "Comedy",
    80 to "Crime",
    99 to "Documentary",
    18 to "Drama",
    10751 to "Family",
    10762 to "Kids",
    9648 to "Mystery",
    10763 to "News",
    10764 to "Reality",
    10765 to "Sci-Fi & Fantasy",
    10766 to "Soap",
    10767 to "Talk",
    10768 to "War & Politics",
    37 to "Western",
)

private const val VOTE_FLOOR_MIN = 200

private val VOTE_FLOOR = listOf("vote_count.gte" to VOTE_FLOOR_MIN.toString())

private val POPULAR_PROVIDER_IDS = intArrayOf(
    8,
    9,
    119,
    337,
    350,
    283,
    1899,
    15,
    531,
    386,
    2336,
    232
)

private val NOT_A_SUBSCRIPTION = setOf(
    2,
    3,
    10,
    68,
    192,
    175,
    2285
)

private fun isSubscribableService(id: Int, name: String): Boolean {
    if (id in NOT_A_SUBSCRIPTION) return false
    val n = name.lowercase().trim()
    return !n.endsWith("channel") && !n.endsWith("store") &&
        !n.endsWith("kids") && !n.contains("with ads")
}

@Serializable
data class TmdbDetail(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val status: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    val popularity: Double = 0.0,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int = 0,
    val runtime: Int? = null,
        val genres: List<TmdbGenre> = emptyList(),
        @SerialName("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
        @SerialName("networks") val networks: List<TmdbCompany> = emptyList(),
        val images: TmdbImages? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    val credits: TmdbCredits? = null,
    @SerialName("created_by") val createdBy: List<TmdbCreatedBy> = emptyList(),
    val recommendations: TmdbPage<TmdbMedia>? = null,
    @SerialName("videos") val videos: TmdbVideoPage? = null,
    @SerialName("keywords") val keywords: TmdbKeywordPage? = null,
    val seasons: List<TmdbSeason> = emptyList(),
    @SerialName("belongs_to_collection") val collection: TmdbCollection? = null,
    @SerialName("last_episode_to_air") val lastEpisodeToAir: TmdbEpisode? = null,
    @SerialName("next_episode_to_air") val nextEpisodeToAir: TmdbEpisode? = null
) {
    val displayTitle: String get() = title ?: name ?: ""
    val year: String get() = (releaseDate ?: firstAirDate ?: "").take(4)
    val runtimeMinutes: Int? get() = runtime?.takeIf { it > 0 }
    val sortDate: String? get() = releaseDate ?: firstAirDate
}

@Serializable
data class TmdbCollection(
    val id: Int,
    val name: String? = null,
    val parts: List<TmdbMedia> = emptyList()
)

@Serializable
data class TmdbImages(
    val logos: List<TmdbImage> = emptyList(),
    val backdrops: List<TmdbImage> = emptyList(),
    val posters: List<TmdbImage> = emptyList()
)

@Serializable
data class TmdbImage(@SerialName("file_path") val filePath: String? = null)

@Serializable
data class TmdbGenre(val id: Int, val name: String)

@Serializable
data class TmdbKeyword(val id: Int, val name: String)

@Serializable
data class TmdbCompany(
    val id: Int = 0,
    val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null
)

@Serializable
data class TmdbVideo(
    @SerialName("key") val key: String? = null,
    val name: String? = null,
    val site: String? = null,
    val type: String? = null,
    val official: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val size: Int = 0
)

@Serializable
data class TmdbVideoPage(val results: List<TmdbVideo> = emptyList())

@Serializable
data class TmdbKeywordPage(val keywords: List<TmdbKeyword> = emptyList())

@Serializable
data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("facebook_id") val facebookId: String? = null,
    @SerialName("instagram_id") val instagramId: String? = null,
    @SerialName("twitter_id") val twitterId: String? = null
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList()
)

@Serializable
data class TmdbCast(
    val id: Int,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 0
)

@Serializable
data class TmdbPerson(
    val id: Int,
    val name: String,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    @SerialName("also_known_as") val alsoKnownAs: List<String> = emptyList(),
    @SerialName("imdb_id") val imdbId: String? = null,

    @SerialName("combined_credits") val combinedCredits: TmdbCombinedCredits? = null
)

@Serializable
data class TmdbPersonImage(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0
)

@Serializable
data class TmdbPersonImages(val profiles: List<TmdbPersonImage> = emptyList())

@Serializable
data class TmdbCombinedCredits(
    val cast: List<TmdbMedia> = emptyList(),
    val crew: List<TmdbMedia> = emptyList()
)

@Serializable
data class TmdbCrew(
    val id: Int,
    val name: String,
    val job: String? = null,
    val department: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
data class TmdbCreatedBy(
    val id: Int,
    val name: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
data class TmdbSeason(
    val id: Int,
    val name: String? = null,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null
)

@Serializable
data class TmdbEpisode(
    val id: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val runtime: Int? = null
) {
    val runtimeMinutes: Int? get() = runtime?.takeIf { it > 0 }
}

@Serializable
private data class TmdbGenrePage(val genres: List<TmdbGenre> = emptyList())

@Serializable
private data class TmdbSeasonDetail(val episodes: List<TmdbEpisode> = emptyList())

object Tmdb {
    private val json = Json { ignoreUnknownKeys = true }

    private class CacheEntry(val body: String, val at: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()
    private const val CACHE_TTL: Long = 15 * 60 * 1000L
    private const val CACHE_MAX = 64
    private val client get() = Injekt.get<NetworkHelper>().client
    private val timeoutClient by lazy {
        client.newBuilder()
            .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    var lastError: String? = null
        private set
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"

    val apiKey: String get() = PrefManager.getVal(PrefName.TmdbApiKey)

    private fun url(path: String, vararg query: Pair<String, String>): String {
        val q = buildString {
            append("api_key=").append(apiKey)
            for ((k, v) in query) append("&").append(k).append("=").append(v)
        }
        return "$BASE$path?$q"
    }

    internal suspend fun get(path: String, vararg query: Pair<String, String>): String? =
        withContext(Dispatchers.IO) {
            val fullUrl = url(path, *query)
            val now = System.currentTimeMillis()
            cache[fullUrl]?.takeIf { now - it.at < CACHE_TTL }?.let { return@withContext it.body }
            val startedAt = System.currentTimeMillis()
            runCatching {
                val request = Request.Builder().url(fullUrl).build()
                timeoutClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string()
                    val elapsedMs = System.currentTimeMillis() - startedAt
                    if (resp.isSuccessful) {
                        lastError = null
                        if (elapsedMs >= 1500L) Logger.log("TMDB slow request path=" + path + " status=" + resp.code + " elapsedMs=" + elapsedMs)
                        body
                    } else {
                        lastError = "HTTP ${resp.code}"
                        Logger.log("TMDB request failed path=" + path + " status=" + resp.code + " elapsedMs=" + elapsedMs)
                        null
                    }
                }
            }.getOrElse { ex ->
                lastError = ex.message ?: ex.javaClass.simpleName
                Logger.log("TMDB request exception path=" + path + " elapsedMs=" + (System.currentTimeMillis() - startedAt) + " error=" + (ex.message ?: ex.javaClass.simpleName))
                null
            }?.let { body ->
                if (cache.size >= CACHE_MAX) cache.clear()
                cache[fullUrl] = CacheEntry(body, now)
                body
            }
        }

    suspend fun detailGenres(mediaType: String, id: Int): List<TmdbGenre> {
        val body = get("/$mediaType/$id") ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbDetail>(body).genres }.getOrDefault(emptyList())
    }

    suspend fun search(query: String, page: Int = 1): List<TmdbMedia> {
        val body = get("/search/multi", "query" to query, "page" to page.toString())
            ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body).results }
            .getOrDefault(emptyList())
            .filter { it.mediaType != "person" }
    }

    suspend fun findByImdb(imdbId: String, mediaType: String? = null): TmdbMedia? {
        val normalized = imdbId.trim()
        if (normalized.isEmpty()) return null
        val body = get("/find/$normalized", "external_source" to "imdb_id") ?: return null
        val parsed = runCatching { json.decodeFromString<TmdbFindResult>(body) }.getOrNull() ?: return null
        return when (mediaType) {
            "movie" -> parsed.movieResults.firstOrNull()
            "tv" -> parsed.tvResults.firstOrNull()
            else -> parsed.movieResults.firstOrNull() ?: parsed.tvResults.firstOrNull()
        }
    }

    suspend fun trending(mediaType: String = "all", timeWindow: String = "week"): List<TmdbMedia> {
        val body = get("/trending/$mediaType/$timeWindow") ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body).results }
            .getOrDefault(emptyList())
    }

    suspend fun onTheAir(page: Int = 1): List<TmdbMedia> {
        val body = get("/tv/on_the_air", "page" to page.toString()) ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body).results }
            .getOrDefault(emptyList())
            .filter { it.posterPath != null }
    }

    suspend fun discover(
        mediaType: String = "movie",
        genres: String? = null,
        sort: String? = null,
        year: Int? = null,
        keywords: String? = null,
        page: Int = 1,
        extra: List<Pair<String, String>> = emptyList()
    ): List<TmdbMedia> = discoverPage(
        mediaType, genres, sort, year, keywords, page, extra
    ).results

    suspend fun discoverPage(
        mediaType: String = "movie",
        genres: String? = null,
        sort: String? = null,
        year: Int? = null,
        keywords: String? = null,
        page: Int = 1,
        extra: List<Pair<String, String>> = emptyList()
    ): TmdbPage<TmdbMedia> {
        val query = mutableListOf("page" to page.toString())
        genres?.let { query.add("with_genres" to it) }
        sort?.let { query.add("sort_by" to it) }
        year?.let {
            val key = if (mediaType == TYPE_TV) "first_air_date_year" else "primary_release_year"
            query.add(key to it.toString())
        }
        keywords?.let { query.add("with_keywords" to it) }
        query.addAll(extra)
        val body = get("/discover/$mediaType", *query.toTypedArray())
            ?: return TmdbPage(page = page)
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body) }
            .getOrDefault(TmdbPage(page = page))
    }

    suspend fun popular(page: Int = 1): List<TmdbMedia> {
        val movies = get("/movie/popular", "page" to page.toString())
            ?.let { runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(it).results }.getOrDefault(emptyList()) }
            ?: emptyList()
        val shows = get("/tv/popular", "page" to page.toString())
            ?.let { runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(it).results }.getOrDefault(emptyList()) }
            ?: emptyList()
        return movies + shows
    }

    suspend fun similar(mediaType: String, id: Int): List<TmdbMedia> {
        val body = get("/$mediaType/$id/similar") ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body).results }
            .getOrDefault(emptyList())
    }

    suspend fun topRated(page: Int = 1): List<TmdbMedia> {
        val movies = get("/movie/top_rated", "page" to page.toString())
            ?.let { runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(it).results }.getOrDefault(emptyList()) }
            ?: emptyList()
        val shows = get("/tv/top_rated", "page" to page.toString())
            ?.let { runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(it).results }.getOrDefault(emptyList()) }
            ?: emptyList()
        return movies + shows
    }

    enum class ExploreRow { IN_CINEMA, TRENDING, TOP_RATED, LATEST_RELEASE, UPCOMING, POPULAR }

    suspend fun exploreRow(
        row: ExploreRow,
        mediaType: String,
        page: Int = 1
    ): List<TmdbMedia> {
        val animation = mediaType == TYPE_ANIMATION
        val type = if (animation) TYPE_MOVIE else mediaType
        val genres = if (animation) ANIMATION_GENRE else null
        val result = when (row) {
            ExploreRow.IN_CINEMA -> when {
                type == TYPE_TV -> list("/tv/airing_today", "page" to page.toString())

                genres != null -> discover(
                    type, genres = genres, sort = "primary_release_date.desc", page = page,
                    extra = listOf("primary_release_date.lte" to todayIso())
                )
                else -> list("/movie/now_playing", "page" to page.toString())
            }
            ExploreRow.TRENDING -> when {

                genres != null -> {
                    val animationGenres = (1..4).flatMap { p ->
                        list("/trending/all/week", "page" to p.toString())
                    }.filter { ANIMATION_GENRE.toInt() in it.genreIds }
                        .distinctBy { it.id }
                    animationGenres.drop((page - 1) * 20).take(20)
                }
                type == TYPE_TV -> list("/trending/tv/week", "page" to page.toString())
                else -> list("/trending/movie/week", "page" to page.toString())
            }
            ExploreRow.TOP_RATED -> when {
                genres != null -> discover(
                    type, genres = genres, sort = "vote_average.desc", page = page,
                    extra = VOTE_FLOOR
                )
                else -> list("/$type/top_rated", "page" to page.toString())
            }
            ExploreRow.LATEST_RELEASE -> discover(
                type, genres = genres, page = page,
                sort = if (type == TYPE_TV) "first_air_date.desc" else "primary_release_date.desc",
                extra = listOf(
                    (if (type == TYPE_TV) "first_air_date.lte" else "primary_release_date.lte")
                        to todayIso()
                )
            )

            ExploreRow.UPCOMING -> discover(
                type, genres = genres, page = page,
                sort = "popularity.desc",
                extra = listOf(
                    (if (type == TYPE_TV) "first_air_date.gte" else "primary_release_date.gte")
                        to todayIso(),
                    (if (type == TYPE_TV) "first_air_date.lte" else "primary_release_date.lte")
                        to todayPlusDays(UPCOMING_WINDOW_DAYS)
                )
            )
            ExploreRow.POPULAR -> when {
                genres != null -> discover(
                    type, genres = genres, sort = "popularity.desc", page = page
                )
                else -> list("/$type/popular", "page" to page.toString())
            }
        }

        return result.filter { it.posterPath != null }
    }

    suspend fun popularPage(
        mediaType: String,
        page: Int = 1
    ): TmdbPage<TmdbMedia> {
        val animation = mediaType == TYPE_ANIMATION
        val type = if (animation) TYPE_MOVIE else mediaType
        val genres = if (animation) ANIMATION_GENRE else null

        val wanted = page.coerceIn(1, MAX_PAGE)
        val result = if (genres != null) {
            discoverPage(
                type, genres = genres, sort = "popularity.desc", page = wanted
            )
        } else {
            listPage("/$type/popular", "page" to wanted.toString())
        }
        return result.copy(totalPages = result.totalPages.coerceIn(1, MAX_PAGE))
    }

    private suspend fun list(path: String, vararg query: Pair<String, String>): List<TmdbMedia> =
        listPage(path, *query).results

    private suspend fun listPage(
        path: String,
        vararg query: Pair<String, String>
    ): TmdbPage<TmdbMedia> {
        val body = get(path, *query) ?: return TmdbPage()
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body) }
            .getOrDefault(TmdbPage())
    }

    suspend fun watchProviders(region: String = DEFAULT_PROVIDER_REGION): List<TmdbProvider> {
        val byId = LinkedHashMap<Int, TmdbProvider>()
        for (endpoint in listOf("/watch/providers/movie", "/watch/providers/tv")) {
            val body = get(endpoint, "watch_region" to region) ?: continue
            val results = runCatching {
                json.decodeFromString<TmdbPage<TmdbProvider>>(body).results
            }.getOrDefault(emptyList())
            for (p in results) {
                val name = p.displayName
                if (p.id == 0 || name.isEmpty()) continue
                if (!isSubscribableService(p.id, name)) continue

                byId.putIfAbsent(p.id, p)
            }
        }
        return byId.values.sortedWith(
            compareBy(
                { POPULAR_PROVIDER_IDS.indexOf(it.id).let { i -> if (i < 0) POPULAR_PROVIDER_IDS.size else i } },
                { it.priority },
                { it.displayName }
            )
        )
    }

    suspend fun providerTitles(
        providerId: Int,
        mediaType: String,
        page: Int = 1,
        region: String = DEFAULT_PROVIDER_REGION
    ): List<TmdbMedia> = discover(
        mediaType = mediaType,
        sort = "popularity.desc",
        page = page,
        extra = listOf(
            "with_watch_providers" to providerId.toString(),
            "watch_region" to region
        )
    )

    private fun todayIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())

    private fun todayPlusDays(days: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, days)
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
    }

    suspend fun latestMovies(page: Int = 1): List<TmdbMedia> =
        discover(
            sort = "primary_release_date.desc",
            page = page,
            extra = listOf("primary_release_date.lte" to todayIso())
        )

    suspend fun latestSeries(page: Int = 1): List<TmdbMedia> =
        discover(
            "tv",
            sort = "first_air_date.desc",
            page = page,
            extra = listOf("first_air_date.lte" to todayIso())
        )

    suspend fun searchKeywords(query: String): List<TmdbKeyword> {
        val body = get("/search/keyword", "query" to query) ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPage<TmdbKeyword>>(body).results }
            .getOrDefault(emptyList())
    }

    suspend fun genres(): List<TmdbGenre> {
        val movieGenres = get("/genre/movie/list")
            ?.let { runCatching { json.decodeFromString<TmdbGenrePage>(it).genres }.getOrDefault(emptyList()) }
            ?: emptyList()
        val tvGenres = get("/genre/tv/list")
            ?.let { runCatching { json.decodeFromString<TmdbGenrePage>(it).genres }.getOrDefault(emptyList()) }
            ?: emptyList()
        return (movieGenres + tvGenres).distinctBy { it.id }.sortedBy { it.name }
    }

    suspend fun detail(mediaType: String, id: Int): TmdbDetail? {
        val body = get(
            "/$mediaType/$id",
            "language" to "en-US",
            "append_to_response" to "images,external_ids,credits,recommendations,videos,keywords"
        ) ?: return null
        return runCatching { json.decodeFromString<TmdbDetail>(body) }.getOrNull()
    }

    suspend fun videos(mediaType: String, id: Int): List<TmdbVideo> {
        val body = get("/$mediaType/$id/videos", "language" to "en-US") ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbVideoPage>(body).results }.getOrDefault(emptyList())
    }

    suspend fun seasons(mediaType: String, id: Int): List<TmdbSeason> {
        val detail = detail(mediaType, id) ?: return emptyList()
        return detail.seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }
    }

    suspend fun episodes(mediaType: String, id: Int, season: Int): List<TmdbEpisode> {
        val body = get("/$mediaType/$id/season/$season", "language" to "en-US") ?: return emptyList()
        return runCatching {
            json.decodeFromString<TmdbSeasonDetail>(body).episodes
        }.getOrDefault(emptyList())
    }

    suspend fun collection(id: Int): List<TmdbMedia> = collectionDetail(id)?.parts
        ?.sortedBy { it.releaseDate }
        .orEmpty()

    private suspend fun collectionDetail(id: Int): TmdbCollection? {
        val body = get("/collection/$id") ?: return null
        return runCatching { json.decodeFromString<TmdbCollection>(body) }.getOrNull()
    }

    data class FranchiseBatch(
        val cards: List<Franchise>,
        val hasMore: Boolean,

        val nextPage: Int,
    )

    suspend fun franchiseCards(
        mediaType: String,
        page: Int = 1,
        onCard: suspend (Franchise) -> Unit = {},
    ): FranchiseBatch {

        if (mediaType == TYPE_TV) return tvFranchiseCards(page, onCard)

        val animation = mediaType == TYPE_ANIMATION
        val media = if (animation) TYPE_MOVIE else mediaType
        val genres = if (animation) ANIMATION_GENRE else null

        var totalPages = 0
        val seeds = mutableListOf<TmdbMedia>()
        for (p in page until page + MOVIE_SEED_PAGES_PER_BATCH) {
            if (p > MAX_PAGE) break
            val res = discoverPage(media, genres = genres, sort = "popularity.desc", page = p)
            totalPages = res.totalPages
            if (res.results.isEmpty()) break
            seeds += res.results
        }
        if (seeds.isEmpty()) {
            return FranchiseBatch(
                emptyList(),
                hasMore = false,
                nextPage = page + MOVIE_SEED_PAGES_PER_BATCH
            )
        }

        val collectionIds = resolveCollectionIds(seeds)
        if (collectionIds.isEmpty()) {

            return FranchiseBatch(
                emptyList(),
                hasMore = page + MOVIE_SEED_PAGES_PER_BATCH <= totalPages,
                nextPage = page + MOVIE_SEED_PAGES_PER_BATCH
            )
        }

        val gate = Semaphore(FRANCHISE_CONCURRENCY)
        val cards = mutableListOf<Franchise>()
        coroutineScope {

            val pending = collectionIds.map { id ->
                async { gate.withPermit { runCatching { collectionDetail(id) }.getOrNull() } }
            }
            for (job in pending) {
                val card = job.await()?.toFranchiseCard() ?: continue
                cards += card
                onCard(card)
            }
        }
        return FranchiseBatch(
            cards,
            hasMore = page + MOVIE_SEED_PAGES_PER_BATCH <= totalPages,
            nextPage = page + MOVIE_SEED_PAGES_PER_BATCH
        )
    }

    private fun TmdbCollection.toFranchiseCard(): Franchise? {
        if (parts.isEmpty()) return null

        val ordered = parts.sortedBy { it.sortDate }.map { part ->
            FranchiseEntry(
                year = part.year,
                sortYear = part.sortDate?.take(4)?.toIntOrNull(),
                posterUrl = imageUrl(part.posterPath, 342),
                title = part.displayTitle,
                backdropUrl = imageUrl(part.backdropPath, 780),

                synopsis = part.overview.nonBlank(),

                tmdbId = part.id,
                tmdbType = TYPE_MOVIE,
                type = FranchiseType.MOVIE,
            )
        }
        if (ordered.isEmpty()) return null

        val latest = ordered.last()

        return Franchise(
            name = name ?: latest.title,

            bannerUrl = latest.backdropUrl ?: latest.posterUrl,
            entries = ordered,
        )
    }

    private suspend fun resolveCollectionIds(seeds: List<TmdbMedia>): List<Int> {
        if (seeds.isEmpty()) return emptyList()

        val gate = Semaphore(FRANCHISE_CONCURRENCY)
        val ids = coroutineScope {
            seeds.map { seed ->
                async {
                    gate.withPermit {
                        runCatching { detail(TYPE_MOVIE, seed.id) }.getOrNull()?.collection?.id
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        Logger.log(
            "Franchise(movie) seeds ${seeds.size} resolved to ${ids.size} collections " +
                "(${ids.distinct().size} distinct)"
        )
        return ids.distinct()
    }

    private suspend fun tvFranchiseCards(
        page: Int,
        onCard: suspend (Franchise) -> Unit,
    ): FranchiseBatch {
        if (page > MAX_PAGE) return FranchiseBatch(emptyList(), hasMore = false, nextPage = page)
        val res = discoverPage(TYPE_TV, sort = "popularity.desc", page = page)
        if (res.results.isEmpty()) {
            return FranchiseBatch(emptyList(), hasMore = false, nextPage = page + 1)
        }

        val gate = Semaphore(FRANCHISE_CONCURRENCY)
        val cards = mutableListOf<Franchise>()
        coroutineScope {

            val pending = res.results.take(TV_FRANCHISE_SEEDS).map { show ->
                async { gate.withPermit { runCatching { detail(TYPE_TV, show.id) }.getOrNull() } }
            }
            for (job in pending) {
                val card = job.await()?.toFranchiseCard() ?: continue
                cards += card
                onCard(card)
            }
        }
        return FranchiseBatch(cards, hasMore = page < res.totalPages, nextPage = page + 1)
    }

private fun String?.nonBlank() = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun TmdbDetail.toFranchiseCard(): Franchise? {
        val seasons = seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }
        if (seasons.isEmpty()) return null

        val banner = imageUrl(backdropPath, 780) ?: imageUrl(posterPath, 500)

        val showSynopsis = overview.nonBlank()
        val entries = seasons.mapIndexed { index, season ->
            FranchiseEntry(

                year = season.airDate?.take(4).orEmpty(),
                sortYear = season.airDate?.take(4)?.toIntOrNull(),
                posterUrl = imageUrl(season.posterPath, 342) ?: imageUrl(posterPath, 342),
                title = season.name ?: "Season ${season.seasonNumber}",
                backdropUrl = banner,
                synopsis = if (index == 0) showSynopsis else null,
                airDate = season.airDate,
                tmdbId = id,
                tmdbType = TYPE_TV,
                seasonNumber = season.seasonNumber,
                type = FranchiseType.SEQUENCE,
            )
        }

        return Franchise(
            name = displayTitle,
            bannerUrl = banner ?: entries.last().posterUrl,
            entries = entries,
        )
    }

    suspend fun genreBannerUrl(genreId: Int): String? {
        val res = discover(
            mediaType = "movie",
            genres = genreId.toString(),
            sort = "popularity.desc",
            page = 1
        )
        return res.firstNotNullOfOrNull {
            imageUrl(it.backdropPath, 780) ?: imageUrl(it.posterPath, 342)
        }
    }

    fun genreNames(media: TmdbMedia): List<String> {
        val table = if (media.type == TYPE_TV) TV_GENRES else MOVIE_GENRES
        return media.genreIds.mapNotNull { table[it] }
    }

    fun imageUrl(path: String?, width: Int = 500): String? {
        if (path.isNullOrBlank()) return null

        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val slash = if (path.startsWith("/")) "" else "/"
        return "$IMG/w$width$slash$path"
    }

    fun logoUrl(detail: TmdbDetail): String? = pickLogo(detail.images)

    suspend fun logoUrl(mediaType: String, id: Int): String? {
        val body = get("/$mediaType/$id/images", "include_image_language" to "en,null") ?: return null
        val images = runCatching { json.decodeFromString<TmdbImages>(body) }.getOrNull() ?: return null
        return pickLogo(images)
    }

    private fun pickLogo(images: TmdbImages?): String? {
        val logos = images?.logos.orEmpty().filter { !it.filePath.isNullOrBlank() && !it.filePath!!.endsWith(".svg", true) }
        val chosen = logos.minByOrNull { abs(it.filePath!!.hashCode()) } ?: return null
        return imageUrl(chosen.filePath, 780)
    }

    private fun abs(i: Int): Int = if (i == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(i)

    suspend fun person(id: Int): TmdbPerson? {
        val body = get("/person/$id") ?: return null
        return runCatching { json.decodeFromString<TmdbPerson>(body) }.getOrNull()
    }

    suspend fun personProfiles(id: Int): List<TmdbPersonImage> {
        val body = get("/person/$id/images") ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPersonImages>(body).profiles }
            .getOrDefault(emptyList())
            .filter { !it.filePath.isNullOrBlank() }
            .sortedByDescending { it.voteAverage }
    }

    suspend fun personCredits(id: Int): List<TmdbMedia> {
        val body = get("/person/$id", "append_to_response" to "combined_credits")
            ?: return emptyList()
        val credits = runCatching { json.decodeFromString<TmdbPerson>(body) }
            .getOrNull()?.combinedCredits ?: return emptyList()
        val best = LinkedHashMap<Int, TmdbMedia>()
        for (m in credits.cast) {
            val seen = best[m.id]
            if (seen == null || m.voteCount > seen.voteCount) best[m.id] = m
        }
        return best.values
            .filter { it.voteCount >= VOTE_FLOOR_MIN }
            .sortedByDescending { it.voteAverage }
    }

}
