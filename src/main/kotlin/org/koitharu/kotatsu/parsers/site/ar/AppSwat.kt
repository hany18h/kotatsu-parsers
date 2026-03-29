package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
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

    private val apiBaseUrl = "https://appswat.com/v2/api/v2"

    // CSRF token cache
    private var csrfToken: String? = null
    private val csrfMutex = Mutex()

    // ─── Headers ───────────────────────────────────────────────────────────────

    private val apiHeaders: Headers
        get() = Headers.Builder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Origin", "https://meshmanga.com")
            .add("Referer", "https://meshmanga.com/")
            .add("User-Agent", "ktor-client")
            .build()

    // ─── CSRF Token ────────────────────────────────────────────────────────────

    private suspend fun getCsrfToken(): String = csrfMutex.withLock {
        val cached = csrfToken
        if (!cached.isNullOrEmpty()) return@withLock cached

        val response = webClient.httpGet("https://meshmanga.com/", apiHeaders)
        val html = response.body?.string() ?: ""
        val token = Jsoup.parse(html)
            .selectFirst("head meta[name*=csrf-token]")
            ?.attr("content")
            .orEmpty()

        csrfToken = token
        token
    }

    private suspend fun buildPostHeaders(): Headers {
        val token = getCsrfToken()
        return Headers.Builder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Origin", "https://meshmanga.com")
            .add("Referer", "https://meshmanga.com/")
            .add("User-Agent", "ktor-client")
            .add("X-CSRF-TOKEN", token)
            .build()
    }

    // ─── Intercept ─────────────────────────────────────────────────────────────

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (response.header("Content-Type")?.contains("text/html") == true) {
            val html = response.peekBody(Long.MAX_VALUE).string()
            val token = Jsoup.parse(html)
                .selectFirst("head meta[name*=csrf-token]")
                ?.attr("content")
            if (!token.isNullOrEmpty()) {
                csrfToken = token
            }
        }

        return response
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
        val response = webClient.httpGet("$apiBaseUrl/genres/", apiHeaders).parseJsonArray()
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
            append("$apiBaseUrl/series/?page=$page")
            if (!filter.query.isNullOrEmpty()) {
                append("&search=${filter.query.urlEncoded()}")
            }
            when (order) {
                SortOrder.POPULARITY -> append("&order_by=-followers_count")
                SortOrder.RATING -> append("&order_by=-rating")
                else -> {}
            }
            if (filter.tags.isNotEmpty()) {
                filter.tags.forEach { tag -> append("&genres=${tag.key}") }
            }
        }

        val response = webClient.httpGet(url, apiHeaders).parseJson()
        val results = response.getJSONArray("results")
        return (0 until results.length()).map { i ->
            parseMangaFromJson(results.getJSONObject(i))
        }
    }

    // ─── Parse Manga ───────────────────────────────────────────────────────────

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val id = json.optInt("id").takeIf { it != 0 }
            ?: json.optInt("serie_id")
        val title = json.getString("title")
        val slug = json.getString("slug")

        val state = json.optJSONObject("status")?.let {
            when (it.getString("name").lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                else -> null
            }
        }

        val poster = json.getJSONObject("poster")
        val coverUrl = poster.optString("medium").nullIfEmpty()
            ?: poster.optString("thumbnail").nullIfEmpty()

        val rating = json.optString("rating", "0.0").toFloatOrNull() ?: 0f
        val normalizedRating = if (rating > 0) rating / 10f else RATING_UNKNOWN

        val genres = json.optJSONArray("genres")
        val tags = mutableSetOf<MangaTag>()
        if (genres != null) {
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
        val chaptersDeferred = async { getChapters(seriesId) }

        val response = webClient.httpGet(
            "$apiBaseUrl/series/$seriesId/",
            apiHeaders,
        ).parseJson()

        val updatedManga = parseMangaFromJson(response)
        updatedManga.copy(chapters = chaptersDeferred.await())
    }

    // ─── Pages ─────────────────────────────────────────────────────────────────

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfter("/chapters/").trimEnd('/')
        val response = webClient.httpGet(
            "$apiBaseUrl/chapters/$chapterId/",
            apiHeaders,
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

    private suspend fun getChapters(seriesId: String): List<MangaChapter> {
        val allChapters = mutableListOf<JSONObject>()
        var page = 1

        while (true) {
            // ✅ FIX: جلب تصاعدي (order) بدل تنازلي (-order) وحذف .reversed() اللي كان يعكسه مرتين
            val url = "$apiBaseUrl/chapters/?serie=$seriesId&order_by=order&page_size=200&page=$page"
            val response = webClient.httpGet(url, apiHeaders).parseJson()
            val results = response.getJSONArray("results")

            if (results.length() == 0) break
            for (i in 0 until results.length()) {
                allChapters.add(results.getJSONObject(i))
            }
            if (response.isNull("next")) break
            page++
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        // ✅ FIX: الـ index الآن صح لأن القائمة تصاعدية من البداية
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
        }
        // ✅ FIX: حذف .reversed() — مش محتاجه خالص
    }
}
