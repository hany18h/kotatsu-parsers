package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    private val apiBaseUrl = "https://appswat.com/v2/api/v2"

    // ─── Headers ───────────────────────────────────────────────────────────────

    private val apiHeaders: Headers
        get() = Headers.Builder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Origin", "https://meshmanga.com")
            .add("Referer", "https://meshmanga.com/")
            .add("User-Agent", "ktor-client")
            .build()

    private suspend fun apiText(url: String): String = requestGate.get(url) {
        webClient.httpGet(url, apiHeaders).parseRaw()
    }

    private companion object {
        val requestGate = SwatRequestGate()
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
            SortOrder.UPDATED,
            SortOrder.RELEVANCE,
            SortOrder.POPULARITY,
            SortOrder.RATING,
        )
    )

    override val defaultSortOrder: SortOrder = SortOrder.POPULARITY

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val response = org.json.JSONArray(apiText("$apiBaseUrl/genres/"))
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
        if (order == SortOrder.UPDATED && filter.query.isNullOrBlank() && filter.tags.isEmpty()) return getUpdatedListPage(page, filter)
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

        val response = JSONObject(apiText(url))
        val results = response.getJSONArray("results")
        return (0 until results.length()).map { i ->
            parseMangaFromJson(results.getJSONObject(i))
        }
    }

    private suspend fun getUpdatedListPage(page: Int, filter: MangaListFilter): List<Manga> {
        val start = (page.coerceAtLeast(1) - 1) * 20
        val targetSize = start + 20
        val series = LinkedHashMap<Int, Manga>()
        val query = filter.query?.trim().orEmpty()
        var chapterPage = 1

        while (series.size < targetSize) {
            val response = JSONObject(apiText("$apiBaseUrl/chapters/?page=$chapterPage&page_size=200&order_by=-created_at"))
            val chapters = response.getJSONArray("results")
            if (chapters.length() == 0) break
            for (i in 0 until chapters.length()) {
                val seriesJson = chapters.getJSONObject(i).optJSONObject("serie") ?: continue
                val id = seriesJson.optInt("id")
                if (id == 0 || series.containsKey(id)) continue
                val manga = parseMangaFromJson(seriesJson)
                if (query.isNotEmpty() && !manga.title.contains(query, ignoreCase = true)) continue
                if (filter.tags.any { selected -> manga.tags.none { it.key == selected.key } }) continue
                series[id] = manga
                if (series.size >= targetSize) break
            }
            if (response.isNull("next")) break
            chapterPage++
        }

        return series.values.drop(start).take(20)
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

        val response = JSONObject(apiText("$apiBaseUrl/series/$seriesId/"))

        val updatedManga = parseMangaFromJson(response)
        updatedManga.copy(chapters = chaptersDeferred.await())
    }

    // ─── Pages ─────────────────────────────────────────────────────────────────

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfter("/chapters/").trimEnd('/')
        val response = JSONObject(apiText("$apiBaseUrl/chapters/$chapterId/"))
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
            val response = JSONObject(apiText(url))
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
