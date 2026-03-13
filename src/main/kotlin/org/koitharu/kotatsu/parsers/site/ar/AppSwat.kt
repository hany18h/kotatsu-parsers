package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGASWAT", "Manga Swat", "ar", ContentType.MANGA)
internal class MangaSwat(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGASWAT, 20),
    MangaParserAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("meshmanga.com")

    // ─── Auth ──────────────────────────────────────────────────────────────────

    override val authUrl: String
        get() = "https://meshmanga.com/accounts/login/"

    override suspend fun isAuthorized(): Boolean {
        return try {
            val response = webClient.httpGet("https://appswat.com/v2/api/v1/users/me/").parseJson()
            response.has("id")
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getUsername(): String {
        val response = webClient.httpGet("https://appswat.com/v2/api/v1/users/me/").parseJson()
        return response.optString("username", "").ifEmpty {
            throw AuthRequiredException(source)
        }
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
        val response = webClient.httpGet("https://appswat.com/v2/api/v2/genres/").parseJsonArray()
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
                filter.tags.forEach { tag ->
                    append("&genres=${tag.key}")
                }
            }
        }

        val response = webClient.httpGet(url).parseJson()
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

        val status = json.getJSONObject("status")
        val state = when (status.getString("name")) {
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
        val chaptersDeferred = async { getChapters(seriesId) }

        val detailUrl = "https://appswat.com/v2/api/v2/series/?type=131&page=1"
        val response = webClient.httpGet(detailUrl).parseJson()
        val results = response.getJSONArray("results")

        var updatedManga = manga
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            if (item.getInt("id").toString() == seriesId) {
                updatedManga = parseMangaFromJson(item)
                break
            }
        }

        return@coroutineScope updatedManga.copy(
            chapters = chaptersDeferred.await(),
        )
    }

    // ─── Pages ─────────────────────────────────────────────────────────────────

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfter("/chapters/")
        val response = webClient.httpGet("https://appswat.com/v2/api/v2/chapters/$chapterId/").parseJson()
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
            val url = "https://appswat.com/v2/api/v2/chapters/?page=$page&serie=$seriesId&order_by=-order&page_size=20"
            val response = webClient.httpGet(url).parseJson()
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
