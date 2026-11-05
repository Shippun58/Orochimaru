package ani.sanin.media

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.mal.MAL
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.tryWithSuspend
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import ani.sanin.media.anime.Anime
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.simkl.Simkl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ani.sanin.connections.tmdb.TmdbMedia

class OtherDetailsViewModel : ViewModel() {
    private val character: MutableLiveData<Character> = MutableLiveData(null)
    fun getCharacter(): LiveData<Character> = character
    suspend fun loadCharacter(m: Character) {
        if (character.value != null) return
        if (PrefManager.getVal(PrefName.RescueMode)) {
            tryWithSuspend {
                val jikanData = MAL.jikan.getCharacterFull(m.id)
                if (jikanData != null) {
                    m.description = jikanData.about

                    parseCharacterAboutFields(m, jikanData.about)

                    jikanData.images?.jpg?.largeImageUrl?.let { m.image = it }
                        ?: jikanData.images?.jpg?.imageUrl?.let { m.image = it }

                    val roles = arrayListOf<Media>()

                    jikanData.anime.forEach { role ->
                        role.anime?.let { animeEntry ->
                            val media = Media(
                                id = animeEntry.malId,
                                idMAL = animeEntry.malId,
                                name = animeEntry.title,
                                nameRomaji = animeEntry.title ?: "",
                                userPreferredName = animeEntry.title ?: "",
                                cover = animeEntry.images?.jpg?.largeImageUrl ?: animeEntry.images?.jpg?.imageUrl,
                                isAdult = false,
                                format = "TV",
                                anime = Anime(null, null, null)
                            )
                            media.relation = role.role
                            roles.add(media)
                        }
                    }

                    roles.sortByDescending { it.idMAL }
                    m.roles = roles

                    val voiceActors = arrayListOf<Author>()
                    jikanData.voices.forEach { voice ->
                        voice.person?.let { person ->
                            val vActor = Author(
                                id = person.malId,
                                name = person.name,
                                image = person.images?.jpg?.imageUrl,
                                role = voice.language
                            )
                            voiceActors.add(vActor)
                        }
                    }
                    m.voiceActor = voiceActors
                }
            }
            character.postValue(m)
            return
        }
        character.postValue(Anilist.query.getCharacterDetails(m))
    }

    private fun parseCharacterAboutFields(m: Character, about: String?) {
        if (about.isNullOrBlank()) return
        val lines = about.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Age:", ignoreCase = true) && m.age == null) {
                m.age = trimmed.substringAfter(":").trim()
            }
            if (trimmed.startsWith("Gender:", ignoreCase = true) && m.gender == null) {
                m.gender = trimmed.substringAfter(":").trim()
            }
            if (trimmed.startsWith("Birthday:", ignoreCase = true) && m.dateOfBirth == null) {
                val raw = trimmed.substringAfter(":").trim()
                if (raw.isNotBlank()) {
                    m.dateOfBirth = ani.sanin.connections.anilist.api.FuzzyDate()
                    m.description = about.replace(trimmed, "").trim()
                    try {
                        val parts = raw.replace(Regex("[^\\w\\s,]"), "").trim().split("\\s+".toRegex())
                        if (parts.isNotEmpty()) {
                            val months = mapOf(
                                "january" to 1, "february" to 2, "march" to 3, "april" to 4,
                                "may" to 5, "june" to 6, "july" to 7, "august" to 8,
                                "september" to 9, "october" to 10, "november" to 11, "december" to 12
                            )
                            val monthNum = months[parts[0].lowercase()]
                            val day = if (parts.size > 1) parts[1].filter { it.isDigit() }.toIntOrNull() else null
                            m.dateOfBirth = ani.sanin.connections.anilist.api.FuzzyDate(
                                month = monthNum,
                                day = day
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private val studio: MutableLiveData<Studio> = MutableLiveData(null)
    fun getStudio(): LiveData<Studio> = studio
    suspend fun loadStudio(m: Studio) {
        if (studio.value != null) return
        if (PrefManager.getVal(PrefName.RescueMode)) {
            tryWithSuspend {
                val yearMedia = mutableMapOf<String, ArrayList<Media>>()
                val seenIds = mutableSetOf<Int>()
                var page = 1
                var hasNext = true

                while (hasNext && page <= 10) {
                    val resp = MAL.jikan.getProducerAnime(m.id.toIntOrNull() ?: 0, page) ?: break
                    resp.data.forEach { jikanData ->
                        if (!seenIds.contains(jikanData.malId)) {
                            seenIds.add(jikanData.malId)
                            val media = Media(jikanData, isAnime = true)
                            val status = jikanData.status?.uppercase() ?: ""
                            val year = jikanData.aired?.from?.take(4) ?: "TBA"
                            val title = if (status.contains("CANCEL")) "CANCELLED" else year
                            if (!yearMedia.containsKey(title)) yearMedia[title] = arrayListOf()
                            yearMedia[title]?.add(media)
                        }
                    }
                    hasNext = resp.pagination?.hasNextPage == true
                    if (hasNext) {
                        kotlinx.coroutines.delay(350)
                        page++
                    }
                }

                if (yearMedia.contains("CANCELLED")) {
                    val cancelled = yearMedia["CANCELLED"]!!
                    yearMedia.remove("CANCELLED")
                    yearMedia["CANCELLED"] = cancelled
                }
                m.yearMedia = yearMedia
            }
            studio.postValue(m)
            return
        }
        if (studio.value == null) studio.postValue(Anilist.query.getStudioDetails(m))
    }

    private val author: MutableLiveData<Author> = MutableLiveData(null)
    fun getAuthor(): LiveData<Author> = author
    suspend fun loadAuthor(m: Author) {
        if (author.value != null) return
        if (PrefManager.getVal(PrefName.RescueMode)) {
            tryWithSuspend {
                val jikanData = MAL.jikan.getPersonFull(m.id)
                if (jikanData != null) {
                    if (!jikanData.about.isNullOrBlank()) {
                        m.about = jikanData.about
                    }

                    jikanData.images?.jpg?.largeImageUrl?.let { m.image = it }
                        ?: jikanData.images?.jpg?.imageUrl?.let { m.image = it }

                    if (!jikanData.birthday.isNullOrBlank()) {
                        try {
                            val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                            val parsed = isoFormat.parse(jikanData.birthday.take(19))
                            if (parsed != null) {
                                val displayFormat = java.text.SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                                m.dateOfBirth = displayFormat.format(parsed)
                            }
                        } catch (_: Exception) {
                            m.dateOfBirth = jikanData.birthday.substringBefore("T")
                        }
                    }

                    val yearMedia = mutableMapOf<String, ArrayList<Media>>()

                    jikanData.anime.forEach { role ->
                        role.anime?.let { animeEntry ->
                            val positionKey = role.position?.ifBlank { "Other" } ?: "Other"
                            val title = "$positionKey (Anime)"
                            if (!yearMedia.containsKey(title)) yearMedia[title] = arrayListOf()
                            val media = Media(
                                id = animeEntry.malId,
                                idMAL = animeEntry.malId,
                                name = animeEntry.title,
                                nameRomaji = animeEntry.title ?: "",
                                userPreferredName = animeEntry.title ?: "",
                                cover = animeEntry.images?.jpg?.largeImageUrl ?: animeEntry.images?.jpg?.imageUrl,
                                isAdult = false,
                                format = "TV",
                                anime = Anime(null, null, null)
                            )
                            media.relation = role.position
                            yearMedia[title]?.add(media)
                        }
                    }

                    val voiceMediaMap = mutableMapOf<Int, Media>()
                    jikanData.voices.forEach { voice ->
                        voice.anime?.let { animeEntry ->
                            val characterInfo = if (voice.character != null) {
                                "${voice.character.name ?: ""}${if (!voice.role.isNullOrBlank()) " (${voice.role})" else ""}"
                            } else ""

                            val existing = voiceMediaMap[animeEntry.malId]
                            if (existing != null) {
                                if (characterInfo.isNotEmpty()) {
                                    existing.relation = if (existing.relation.isNullOrBlank()) {
                                        characterInfo
                                    } else {
                                        "${existing.relation} / $characterInfo"
                                    }
                                }
                            } else {
                                val media = Media(
                                    id = animeEntry.malId,
                                    idMAL = animeEntry.malId,
                                    name = animeEntry.title,
                                    nameRomaji = animeEntry.title ?: "",
                                    userPreferredName = animeEntry.title ?: "",
                                    cover = animeEntry.images?.jpg?.largeImageUrl ?: animeEntry.images?.jpg?.imageUrl,
                                    isAdult = false,
                                    format = "TV",
                                    anime = Anime(null, null, null)
                                )
                                media.relation = characterInfo
                                voiceMediaMap[animeEntry.malId] = media
                            }
                        }
                    }
                    if (voiceMediaMap.isNotEmpty()) {
                        yearMedia["Voice Roles (Anime)"] = ArrayList(voiceMediaMap.values)
                    }

                    yearMedia.forEach { (_, list) ->
                        list.sortByDescending { it.idMAL }
                    }

                    val sortedYearMedia = linkedMapOf<String, ArrayList<Media>>()
                    yearMedia.entries
                        .sortedByDescending { it.value.size }
                        .forEach { sortedYearMedia[it.key] = it.value }
                    m.yearMedia = sortedYearMedia

                    val characters = arrayListOf<Character>()
                    jikanData.voices.forEach { voice ->
                        voice.character?.let { charEntry ->
                            val c = Character(
                                id = charEntry.malId,
                                name = charEntry.name,
                                image = charEntry.images?.jpg?.imageUrl ?: charEntry.images?.webp?.imageUrl,
                                banner = null,
                                role = voice.role ?: "",
                                isFav = false
                            )
                            characters.add(c)
                        }
                    }
                    m.character = characters
                }
            }
            author.postValue(m)
            return
        }
        author.postValue(Anilist.query.getAuthorDetails(m))
    }

    private var cachedAllCalendarData: Map<String, MutableList<Media>>? = null
    private var cachedLibraryCalendarData: Map<String, MutableList<Media>>? = null
    private val calendar: MutableLiveData<Map<String, MutableList<Media>>> = MutableLiveData(null)
    fun getCalendar(): LiveData<Map<String, MutableList<Media>>> = calendar
    suspend fun loadCalendar(showOnlyLibrary: Boolean = false) {
        val isMovieMode = PrefManager.getVal<String>(PrefName.ContentMode) == "movie_tv"
        if (isMovieMode) {
            loadCalendarForMovieMode()
            return
        }
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            loadCalendarFromJikan(showOnlyLibrary)
        } else {
            loadCalendarFromAnilist(showOnlyLibrary)
        }
    }

    private suspend fun loadCalendarForMovieMode() {
        val df = DateFormat.getDateInstance(DateFormat.FULL)
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val (simklShows, simklMovies) = coroutineScope {
            val shows = async { try { Simkl.getShowLibrary() } catch (_: Exception) { emptyList<Simkl.SimklWatchedItem>() } }
            val movies = async { try { Simkl.getMovieLibrary() } catch (_: Exception) { emptyList<Simkl.SimklWatchedItem>() } }
            shows.await() to movies.await()
        }
        val simklIdMap = mutableMapOf<Int, String>()
        val simklItemMap = mutableMapOf<Int, Simkl.SimklWatchedItem>()
        simklShows.filter { it.status != null }.forEach { item ->
            item.ids?.tmdb?.let { simklIdMap[it] = item.status!!; simklItemMap[it] = item }
        }
        simklMovies.filter { it.status != null }.forEach { item ->
            item.ids?.tmdb?.let { simklIdMap[it] = item.status!!; simklItemMap[it] = item }
        }

        fun parseResults(jsonStr: String): List<List<Any?>> {
            if (jsonStr.isBlank()) return emptyList()
            return runCatching {
                val arr = org.json.JSONObject(jsonStr).optJSONArray("results") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    val id = obj.optInt("id", 0)
                    if (id == 0) return@mapNotNull null
                    val title = obj.optString("name", obj.optString("title", "")).ifBlank { null }
                        ?: return@mapNotNull null
                    val rawDate = obj.optString("air_date", "")
                        .ifBlank { obj.optString("first_air_date", "") }
                        .ifBlank { obj.optString("release_date", "") }
                    listOf(
                        id,
                        title,
                        obj.optString("poster_path", "").ifBlank { null },
                        rawDate.takeIf { it.length >= 10 }?.substring(0, 10)
                    )
                }
            }.getOrDefault(emptyList())
        }

        val allMap = LinkedHashMap<String, MutableList<Media>>()
        val seenIds = mutableSetOf<Int>()

        fun addEntry(dayKey: String, tmdbId: Int, title: String, posterPath: String?, relation: String, type: String) {
            if (!seenIds.add(tmdbId)) return
            val media = Media(
                id = tmdbId,
                name = title,
                nameRomaji = title,
                userPreferredName = title,
                isAdult = false,
                anime = null,
                cover = Tmdb.imageUrl(posterPath, 300),
                banner = Tmdb.imageUrl(posterPath, 780),
                status = simklIdMap[tmdbId]
            )
            media.tmdbType = type
            media.relation = relation

            val simklItem = simklItemMap[tmdbId]
            if (simklItem != null) {
                media.userStatus = simklItem.status
            }
            allMap.getOrPut(dayKey) { mutableListOf() }.add(media)
        }

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        fun dayKey(offset: Int): Pair<String, String> {
            val day = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            return dayFmt.format(day.time) to df.format(day.time)
        }

        val daysSinceMonday = (today.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        val startOffset = -daysSinceMonday - 7
        val allWeekDays = (startOffset until startOffset + 21).map { dayKey(it) }

        suspend fun fetchDays(days: List<Pair<String, String>>): Map<String, MutableList<Media>> {
            val batchMap = LinkedHashMap<String, MutableList<Media>>()
            val batchSeen = mutableSetOf<Int>()

            fun addBatchEntry(dayKey: String, tmdbId: Int, title: String, posterPath: String?, relation: String, type: String) {
                if (!batchSeen.add(tmdbId)) return
                val media = Media(
                    id = tmdbId, name = title, nameRomaji = title, userPreferredName = title,
                    isAdult = false, anime = null,
                    cover = Tmdb.imageUrl(posterPath, 300), banner = Tmdb.imageUrl(posterPath, 780),
                    status = simklIdMap[tmdbId]
                )
                media.tmdbType = type; media.relation = relation
                val simklItem = simklItemMap[tmdbId]
                if (simklItem != null) media.userStatus = simklItem.status
                batchMap.getOrPut(dayKey) { mutableListOf() }.add(media)
            }

            coroutineScope {
                val tvmazeDeferred = days.map { (iso, _) ->
                    async {
                        val schedBody = try {
                            val conn = java.net.URL("https://api.tvmaze.com/schedule?date=$iso&country=US")
                                .openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 3000; conn.readTimeout = 3000
                            conn.inputStream.bufferedReader().use { it.readText() }
                        } catch (_: Exception) { "[]" }
                        parseTvmazeSchedule(schedBody)
                    }
                }
                val tmdbDeferred = days.map { (iso, label) ->
                    async {
                        val body = try {
                            Tmdb.get("/discover/tv", "air_date.gte" to iso, "air_date.lte" to iso, "sort_by" to "popularity.desc", "include_empty" to "false")
                        } catch (_: Exception) { null }
                        label to parseResults(body ?: "")
                    }
                }

                val tvmazeSchedule = mutableMapOf<String, MutableList<Triple<Int, Int, String>>>()
                tvmazeDeferred.awaitAll().flatten().forEach { (name, triple) ->
                    tvmazeSchedule.getOrPut(name) { mutableListOf() }.add(triple)
                }

                tmdbDeferred.awaitAll().forEach { (label, results) ->
                    results.forEach { entry ->
                        val tmdbId = entry[0] as Int; val showTitle = entry[1] as String
                        val lowerName = showTitle.lowercase()
                        val strippedName = lowerName.replace(Regex("\\s*\\(.*?\\)$"), "").trim()
                        val mazeInfo: MutableList<Triple<Int, Int, String>>? =
                            tvmazeSchedule[lowerName] ?: tvmazeSchedule[strippedName]
                            ?: tvmazeSchedule.entries.firstOrNull { (k, _) -> k.contains(strippedName) || strippedName.contains(k) }?.value
                        val relation = if (mazeInfo != null && mazeInfo.isNotEmpty()) {
                            val t = mazeInfo.first(); "S${t.first}E${t.second}\n${t.third}"
                        } else "New episode"
                        addBatchEntry(label, tmdbId, showTitle, entry[2] as String?, relation, "tv")
                    }
                }
            }
            return batchMap
        }

        val initialDays = allWeekDays.filter { (iso, _) ->
            val d = try { dayFmt.parse(iso) } catch (_: Exception) { null }
            d != null && (d.time >= today.timeInMillis - 86400000L)
        }.take(2)

        if (initialDays.isNotEmpty()) {
            val initial = fetchDays(initialDays)
            initial.forEach { (k, v) -> allMap.getOrPut(k) { mutableListOf() }.addAll(v) }
            calendar.postValue(LinkedHashMap(allMap))
        }

        val remainingDays = allWeekDays.toSet() - initialDays.toSet()
        if (remainingDays.isNotEmpty()) {
            val remaining = fetchDays(remainingDays.toList())
            remaining.forEach { (k, v) -> allMap.getOrPut(k) { mutableListOf() }.addAll(v) }
        }

        val moviesBody = try { Tmdb.get("/movie/upcoming") } catch (_: Exception) { null }
        parseResults(moviesBody ?: "").forEach { entry ->
            val dateIso = entry[3] as String? ?: return@forEach
            allWeekDays.firstOrNull { it.first == dateIso }?.let { (_, label) ->
                addEntry(label, entry[0] as Int, entry[1] as String, entry[2] as String?, "Movie\n${dateIso}", "movie")
            }
        }

        cachedAllCalendarData = allMap
        cachedLibraryCalendarData = allMap
        calendar.postValue(allMap)
    }

    private fun parseTvmazeSchedule(json: String): List<Pair<String, Triple<Int, Int, String>>> {
        val result = mutableListOf<Pair<String, Triple<Int, Int, String>>>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val ep = arr.getJSONObject(i)
                val showName = ep.optJSONObject("show")?.optString("name", "")?.lowercase() ?: continue
                val season = ep.optInt("season", 0)
                val number = ep.optInt("number", 0)
                val airtime = ep.optString("airtime", "").ifBlank { null } ?: continue
                if (season > 0 && number > 0) {
                    result.add(showName to Triple(season, number, airtime))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private suspend fun loadCalendarFromAnilist(showOnlyLibrary: Boolean) {
        if (cachedAllCalendarData == null || cachedLibraryCalendarData == null) {

            val base = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val daysSinceMonday = (base.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            val startCal = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -daysSinceMonday - 7) }
            val endCal = (base.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, -daysSinceMonday + 13)
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
            }
            val res = Anilist.query.recentlyUpdated(
                startCal.timeInMillis / 1000,
                endCal.timeInMillis / 1000,
            )
            val df = DateFormat.getDateInstance(DateFormat.FULL)
            val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
            val allMap = mutableMapOf<String, MutableList<Media>>()
            val libraryMap = mutableMapOf<String, MutableList<Media>>()
            val idMap = mutableMapOf<String, MutableList<Int>>()

            val userId = Anilist.userid ?: 0
            val userLibrary = Anilist.query.getMediaLists(true, userId)
            val libraryMediaIds = userLibrary.flatMap { it.value }.map { it.id }

            res.forEach {
                val v = it.relation?.split(",")?.map { i -> i.toLong() }!!
                val dateInfo = df.format(Date(v[1] * 1000))
                val timeInfo = tf.format(Date(v[1] * 1000))
                val list = allMap.getOrPut(dateInfo) { mutableListOf() }
                val libraryList = if (libraryMediaIds.contains(it.id)) {
                    libraryMap.getOrPut(dateInfo) { mutableListOf() }
                } else {
                    null
                }
                val idList = idMap.getOrPut(dateInfo) { mutableListOf() }
                it.relation = "Episode ${v[0]}\n$timeInfo"
                if (!idList.contains(it.id)) {
                    idList.add(it.id)
                    list.add(it)
                    libraryList?.add(it)
                }
            }

            cachedAllCalendarData = allMap
            cachedLibraryCalendarData = libraryMap
        }

        val cacheToUse: Map<String, MutableList<Media>> = if (showOnlyLibrary) {
            cachedLibraryCalendarData ?: emptyMap()
        } else {
            cachedAllCalendarData ?: emptyMap()
        }
        calendar.postValue(cacheToUse)
    }

    private suspend fun loadCalendarFromJikan(showOnlyLibrary: Boolean) {
        if (cachedAllCalendarData == null || cachedLibraryCalendarData == null) {
            val jikan = MAL.jikan
            val df = DateFormat.getDateInstance(DateFormat.FULL)

            val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
            val dayNames = arrayOf(
                "sunday", "monday", "tuesday", "wednesday",
                "thursday", "friday", "saturday"
            )

            val allMap = mutableMapOf<String, MutableList<Media>>()
            val libraryMap = mutableMapOf<String, MutableList<Media>>()

            val watchingMalIds = mutableSetOf<Int>()
            val watchedEpisodesMap = mutableMapOf<Int, Int>()
            if (MAL.token != null) {
                tryWithSuspend {
                    var offset = 0
                    do {
                        val listResp = MAL.query.getUserAnimeList(
                            status = "watching", limit = 100, offset = offset
                        )
                        listResp?.data?.forEach { entry ->
                            watchingMalIds.add(entry.node.id)
                            watchedEpisodesMap[entry.node.id] =
                                entry.listStatus?.numEpisodesWatched ?: 0
                        }
                        offset += 100
                    } while (listResp?.paging?.next != null)
                }
            }

            val baseCal = Calendar.getInstance()
            val daysSinceMonday = (baseCal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            val startOffset = -daysSinceMonday - 7
            for (offsetDay in startOffset until startOffset + 21) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, offsetDay)
                val dayName = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
                val dateStr = df.format(cal.time)

                val allMedia = mutableListOf<Media>()
                val seenIds = mutableSetOf<Int>()
                var page = 1
                var hasNext = true
                while (hasNext) {
                    val resp = jikan.getSchedules(
                        filter = dayName, page = page, limit = 25
                    ) ?: break
                    resp.data.forEach { jikanData ->
                        if (!seenIds.contains(jikanData.malId)) {
                            seenIds.add(jikanData.malId)
                            val media = Media(jikanData, isAnime = true)
                            media.userProgress = watchedEpisodesMap[jikanData.malId]

                            val fromDateStr = jikanData.aired?.from
                            var airedEps: Int? = null
                            if (fromDateStr != null) {
                                try {
                                    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                                    val startDate = isoFormat.parse(fromDateStr.take(19))
                                    if (startDate != null) {
                                        val targetTime = cal.time.time
                                        val diff = targetTime - startDate.time
                                        if (diff >= 0) {
                                            val weeks = (diff / (7L * 24 * 60 * 60 * 1000)).toInt()
                                            var estimated = weeks + 1
                                            if (jikanData.episodes != null && estimated > jikanData.episodes) {
                                                estimated = jikanData.episodes
                                            }

                                            val watched = watchedEpisodesMap[jikanData.malId] ?: 0
                                            if (watched > estimated) {
                                                estimated = watched
                                            }
                                            airedEps = estimated
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                            media.anime?.nextAiringEpisode = airedEps

                            val nextEp = watchedEpisodesMap[jikanData.malId]?.let { it + 1 }

                            var timeStr = ""
                            var sortTime = Int.MAX_VALUE
                            val bTime = jikanData.broadcast?.time
                            val bTz = jikanData.broadcast?.timezone
                            if (!bTime.isNullOrBlank() && !bTz.isNullOrBlank()) {
                                try {
                                    val parser = SimpleDateFormat("HH:mm", Locale.US)
                                    parser.timeZone = TimeZone.getTimeZone(bTz)
                                    val parsedTime = parser.parse(bTime)
                                    if (parsedTime != null) {
                                        val targetCal = Calendar.getInstance(TimeZone.getTimeZone(bTz))
                                        targetCal.time = cal.time
                                        val timeCal = Calendar.getInstance(TimeZone.getTimeZone(bTz))
                                        timeCal.time = parsedTime
                                        targetCal.set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY))
                                        targetCal.set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE))

                                        tf.timeZone = TimeZone.getDefault()
                                        timeStr = tf.format(targetCal.time)

                                        val localCal = Calendar.getInstance()
                                        localCal.time = targetCal.time
                                        sortTime = localCal.get(Calendar.HOUR_OF_DAY) * 60 + localCal.get(Calendar.MINUTE)
                                    }
                                } catch (_: Exception) {
                                    timeStr = "$bTime $bTz"
                                }
                            } else if (!bTime.isNullOrBlank()) {
                                timeStr = bTime
                            }

                            media.relation = if (nextEp != null) {
                                "Episode $nextEp" + (if (timeStr.isNotBlank()) "\n$timeStr" else "")
                            } else {
                                timeStr.ifBlank { null }
                            }
                            media.userUpdatedAt = sortTime.toLong()
                            allMedia.add(media)
                        }
                    }
                    hasNext = resp.pagination?.hasNextPage == true

                    kotlinx.coroutines.delay(350)
                    page++
                }

                allMedia.sortBy { it.userUpdatedAt ?: Long.MAX_VALUE }

                allMap[dateStr] = allMedia
                val libList = allMedia.filter { watchingMalIds.contains(it.id) }.toMutableList()
                if (libList.isNotEmpty()) {
                    libraryMap[dateStr] = libList
                }
            }

            cachedAllCalendarData = allMap
            cachedLibraryCalendarData = libraryMap
        }

        val cacheToUse: Map<String, MutableList<Media>> = if (showOnlyLibrary) {
            cachedLibraryCalendarData ?: emptyMap()
        } else {
            cachedAllCalendarData ?: emptyMap()
        }
        calendar.postValue(cacheToUse)
    }
}