package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGASWAT", "Manga Swat", "ar", ContentType.MANGA)
internal class MangaSwat(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGASWAT, 20) {

    override val configKeyDomain = ConfigKey.Domain("meshmanga.com")

    private val configKeyUsername = ConfigKey.Username()
    private val configKeyPassword = ConfigKey.Password()

    // Token cache
    @Volatile private var accessToken: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var tokenExpiry: Long = 0L
    private val tokenMutex = Mutex()

    // ─── Config ────────────────────────────────────────────────────────────────

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(configKeyUsername)
        keys.add(configKeyPassword)
    }

    // ─── Auth ──────────────────────────────────────────────────────────────────

    private suspend fun getValidToken(): String? {
        // Fast path - return cached token without lock
        val now = System.currentTimeMillis()
        val cached = accessToken
        if (cached != null && now < tokenExpiry) {
            return cached
        }

        // Slow path - need to refresh or login
        return tokenMutex.withLock {
            // Double check after acquiring lock
            val now2 = System.currentTimeMillis()
            val cached2 = accessToken
            if (cached2 != null && now2 < tokenExpiry) {
                return@withLock cached2
            }

            // Try refresh token first
            val refresh = refreshToken
            if (refresh != null) {
                try {
                    val response = webClient.httpPost(
                        "https://appswat.com/v2/api/v1/token/refresh/",
                        mapOf("refresh" to refresh),
                    ).parseJson()
                    val newToken = response.getString("access")
                    accessToken = newToken
                    tokenExpiry = now2 + (14 * 60 * 1000L)
                    return@withLock newToken
                } catch (e: Exception) {
                    refreshToken = null
                    accessToken = null
                }
            }

            // Login with username/password
            val username = config[configKeyUsername].orEmpty()
            val password = config[configKeyPassword].orEmpty()
            if (username.isEmpty() || password.isEmpty()) return@withLock null

            try {
                val response = webClient.httpPost(
                    "https://appswat.com/v2/api/v1/token/",
                    mapOf(
                        "username" to username,
                        "password" to password,
                    ),
                ).parseJson()
                val newToken = response.getString("access")
                accessToken = newToken
                refreshToken = response.getString("refresh")
                tokenExpiry = now2 + (14 * 60 * 1000L)
                newToken
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun buildAuthHeaders(): Headers? {
        val token = getValidToken() ?: return null
        return Headers.Builder()
            .add("Authorization", "Bearer $token")
            .build()
    }

    // ─── Filter ────────────────────────────────────────────────────────────────

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = LinkedHashSet(
        listOf(
            SortOrder.RELEVANCE,
            SortOrder.POPULARITY,
            SortOrder.RATING,
        )
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val response = webClient.httpGet(
            "https://appswat.com/v2/api/v2/genres/",
            buildAuthHeaders(),
        ).parseJsonArray()
        val tags = mutableSetOf<MangaTag>()
        for (i in 0 until response.length()) {
            val genreObj = response.getJSONObject(i)
            tags.add(
                MangaTag(
                    key = genreObj.getInt("id").toString(),
                    title = genreObj.getString("name"),
                    source = source,
                )
            )
        }
        return tags
    }

    // ─── List ──────────────────────────────────────────────────────────────────

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://appswat.com/v2/api/v2/series/?type=131&page=$page")
            if (!filter.query.isNullOrEmpty()) {
                append("&search=${filter.query.urlEncoded()}")
            }
            when (order) {
                SortOrder.POPULARITY -> append("&order_by=followers_count")
                SortOrder.RATING -> append("&order_by=-rating")
                else -> {}
            }
            if (filter.tags.isNotEmpty()) {
                filter.tags.forEach { tag -> append("&genres=${tag.key}") }
            }
        }

        val response = webClient.httpGet(url, buildAuthHeaders()).parseJson()
        val results = response.getJSONArray("results")
        return (0 until results.length()).map { i ->
            parseMangaFromJson(results.getJSONObject(i))
        }
    }

    // ─── Parse Manga ───────────────────────────────────────────────────────────

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val id = json.getInt("id")
        val title = json.getString("title")
        val slug = json.getString("slug")

        val state = when (json.getJSONObject("status").getString("name")) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            else -> null
        }

        val poster = json.getJSONObject("poster")
        val coverUrl = poster.optString("medium").nullIfEmpty()
            ?: poster.optString("thumbnail").nullIfEmpty()

        val rating = json.optString("rating", "0.0").toFloatOrNull() ?: 0f
        val normalizedRating = if (rating > 0) rating / 10f else RATING_UNKNOWN

        val genres = json.getJSONArray("genres")
        val tags = mutableSetOf<MangaTag>()
        for (i in 0 until genres.length()) {
            val genre = genres.getJSONObject(i)
            tags.add(
                MangaTag(
                    key = genre.getInt("id").toString(),
                    title = genre.getString("name"),
                    source = source,
                )
            )
        }

        val authors = mutableSetOf<String>()
        json.optJSONObject("translator")?.let { authors.add(it.getString("name")) }
        json.optJSONObject("editor")?.let { authors.add(it.getString("name")) }

        return Manga(
            id = generateUid(id.toString()),
            url = "/series/$id",
            publicUrl = "https://meshmanga.com/series/$slug/",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            rating = normalizedRating,
            tags = tags,
            authors = authors,
            state = state,
            source = source,
            contentRating = ContentRating.SAFE,
        )
    }

    // ─── Details ───────────────────────────────────────────────────────────────

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val seriesId = manga.url.substringAfter("/series/")
        val headers = buildAuthHeaders()
        val chaptersDeferred = async { getChapters(seriesId, headers) }

        val response = webClient.httpGet(
            "https://appswat.com/v2/api/v2/series/?type=131&page=1",
            headers,
        ).parseJson()
        val results = response.getJSONArray("results")

        var updatedManga = manga
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            if (item.getInt("id").toString() == seriesId) {
                updatedManga = parseMangaFromJson(item)
                break
            }
        }

        updatedManga.copy(chapters = chaptersDeferred.await())
    }

    // ─── Pages ─────────────────────────────────────────────────────────────────

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfter("/chapters/")
        // Use v1 API for chapters - this is what requires authentication
        val response = webClient.httpGet(
            "https://appswat.com/v2/api/v1/chapters/$chapterId/",
            buildAuthHeaders(),
        ).parseJson()
        val images = response.getJSONArray("images")

        return (0 until images.length()).map { i ->
            val imageObj = images.getJSONObject(i)
            MangaPage(
                id = generateUid("$chapterId-${imageObj.getInt("order")}"),
                url = imageObj.getString("image"),
                preview = null,
                source = source,
            )
        }
    }

    // ─── Chapters ──────────────────────────────────────────────────────────────

    private suspend fun getChapters(seriesId: String, headers: Headers?): List<MangaChapter> {
        val allChapters = mutableListOf<JSONObject>()
        var page = 1

        while (true) {
            val url = "https://appswat.com/v2/api/v2/chapters/?page=$page&serie=$seriesId&order_by=-order&page_size=20"
            val response = webClient.httpGet(url, headers).parseJson()
            val results = response.getJSONArray("results")

            if (results.length() == 0) break
            for (i in 0 until results.length()) {
                allChapters.add(results.getJSONObject(i))
            }
            if (response.isNull("next")) break
            page++
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        return allChapters.mapIndexedNotNull { index, item ->
            val chapterId = item.getInt("id")
            val chapterNumber = item.optString("chapter", "").toFloatOrNull() ?: (index + 1f)
            val uploadDate = try {
                dateFormat.parse(item.getString("created_at"))?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
            MangaChapter(
                id = generateUid(chapterId.toString()),
                title = item.getString("title"),
                number = chapterNumber,
                volume = 0,
                url = "/chapters/$chapterId",
                uploadDate = uploadDate,
                source = source,
                scanlator = null,
                branch = null,
            )
        }.reversed()
    }
}
