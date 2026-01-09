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

@MangaSourceParser("MANGAMELLO", "Manga Mello", "ar", ContentType.MANGA)
internal class MangaMello(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAMELLO, 20) {

    override val configKeyDomain = ConfigKey.Domain("plus.mangamello.com")
    
    override val iconUrl =
        "https://raw.githubusercontent.com/hany18h/kotatsu-parsers/master/src/main/kotlin/org/koitharu/kotatsu/parsers/icons/MangamelloPlus.webp"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = LinkedHashSet(
        listOf(
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
        )
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    // Custom headers for MangaMello API
    private fun getApiHeaders(): Headers {
        return Headers.Builder()
            .add("accept", "application/json")
            .add("authorization", "Bearer null")
            .add("content-type", "application/json")
            .add("installer", "com.google.android.packageinstaller")
            .add("user-agent", "Dart/3.3 (dart:io)")
            .add("vsesion", "1.1.7")
            .add("zone", TimeZone.getDefault().id)
            .build()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val headers = getApiHeaders()
        
        // Handle search
        if (!filter.query.isNullOrEmpty()) {
            val searchUrl = "https://plus.mangamello.com/api/v1/mangas/search?per_page=40&title=${filter.query.urlEncoded()}"
            val response = webClient.httpGet(searchUrl, headers).parseJson()
            val results = response.getJSONArray("data")
            return (0 until results.length()).map { i ->
                parseMangaFromSearchJson(results.getJSONObject(i))
            }
        }

        // Handle listing with sort
        val sortParam = when (order) {
            SortOrder.POPULARITY -> "views"
            else -> "updated_at"
        }

        val url = "https://plus.mangamello.com/api/v1/mangas?sort_by=$sortParam&page=$page"
        val response = webClient.httpGet(url, headers).parseJson()
        val results = response.getJSONArray("data")

        return (0 until results.length()).map { i ->
            parseMangaFromJson(results.getJSONObject(i))
        }
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val id = json.getInt("id")
        val title = json.optString("title", "")
        val imageUrl = json.optString("img", "")
        val rate = json.optString("rate", "0").toFloatOrNull() ?: 0f
        val normalizedRating = if (rate > 0) rate / 10f else RATING_UNKNOWN

        return Manga(
            id = generateUid(id.toString()),
            url = "/api/v1/mangas/$id",
            publicUrl = "https://plus.mangamello.com/manga/$id",
            coverUrl = imageUrl,
            title = title,
            altTitles = emptySet(),
            rating = normalizedRating,
            tags = emptySet(),
            authors = emptySet(),
            state = null,
            source = source,
            contentRating = ContentRating.SAFE,
        )
    }

    private fun parseMangaFromSearchJson(json: JSONObject): Manga {
        val id = json.getInt("id")
        val title = json.optString("title", "")
        val imageUrl = json.optString("img", "")
        val averageRate = json.optString("average_rate", "0").toFloatOrNull() ?: 0f
        val normalizedRating = if (averageRate > 0) averageRate / 10f else RATING_UNKNOWN

        // Parse genres if available
        val tags = mutableSetOf<MangaTag>()
        val genresArray = json.optJSONArray("genres")
        if (genresArray != null) {
            for (i in 0 until genresArray.length()) {
                val genre = genresArray.getJSONObject(i)
                tags.add(
                    MangaTag(
                        key = genre.optInt("id", 0).toString(),
                        title = genre.optString("name", ""),
                        source = source
                    )
                )
            }
        }

        return Manga(
            id = generateUid(id.toString()),
            url = "/api/v1/mangas/$id",
            publicUrl = "https://plus.mangamello.com/manga/$id",
            coverUrl = imageUrl,
            title = title,
            altTitles = emptySet(),
            rating = normalizedRating,
            tags = tags,
            authors = emptySet(),
            state = null,
            source = source,
            contentRating = ContentRating.SAFE,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val mangaId = manga.url.substringAfterLast("/")
        val headers = getApiHeaders()
        val chaptersDeferred = async { getChapters(mangaId) }

        // Get manga details
        val detailUrl = "https://plus.mangamello.com${manga.url}"
        val response = webClient.httpGet(detailUrl, headers).parseJson()
        val data = response.getJSONObject("data")

        val title = data.optString("title", manga.title)
        val summary = data.optString("summary", "").nullIfEmpty()
        val imageUrl = data.optString("img", manga.coverUrl)
        val year = data.optString("year", "").nullIfEmpty()
        
        val isCompleted = data.optInt("is_completed", 0)
        val state = when (isCompleted) {
            1 -> MangaState.FINISHED
            else -> MangaState.ONGOING
        }

        val tenRate = data.optString("ten_rate", "0").toFloatOrNull() ?: 0f
        val rating = if (tenRate > 0) tenRate / 10f else manga.rating

        manga.copy(
            title = title,
            description = summary,
            coverUrl = imageUrl,
            state = state,
            rating = rating,
            chapters = chaptersDeferred.await(),
        )
    }

    private suspend fun getChapters(mangaId: String): List<MangaChapter> {
        val headers = getApiHeaders()
        val chaptersUrl = "https://plus.mangamello.com/api/v1/mangas/$mangaId/chapters?per_page=2000"
        val response = webClient.httpGet(chaptersUrl, headers).parseJson()
        val chaptersArray = response.getJSONArray("data")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        val chapters = mutableListOf<MangaChapter>()

        for (i in 0 until chaptersArray.length()) {
            val item = chaptersArray.getJSONObject(i)
            val chapterId = item.getInt("id")
            val chapterMangaId = item.getInt("manga_id")
            val order = item.optString("order", "").toFloatOrNull() ?: (i + 1f)
            val title = item.optString("title", "")
            val createdAt = item.optString("created_at", "")

            val uploadDate = try {
                dateFormat.parse(createdAt)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }

            chapters.add(
                MangaChapter(
                    id = generateUid(chapterId.toString()),
                    title = title,
                    number = order,
                    volume = 0,
                    url = "/api/v1/mangas/$chapterMangaId/chapters/$chapterId?relations=chapterImages",
                    uploadDate = uploadDate,
                    source = source,
                    scanlator = null,
                    branch = null
                )
            )
        }

        // ✅ الترتيب الصحيح: من الأقدم للأحدث (ascending)
        return chapters.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val headers = getApiHeaders()
        val fullUrl = "https://plus.mangamello.com${chapter.url}"
        val response = webClient.httpGet(fullUrl, headers).parseJson()
        val data = response.getJSONObject("data")
        val imagesArray = data.getJSONArray("chapterImages")

        return (0 until imagesArray.length()).mapNotNull { i ->
            val imageObj = imagesArray.getJSONObject(i)
            val imageUrl = imageObj.optString("src", "").nullIfEmpty()
                ?: imageObj.optString("originalSrc", "").nullIfEmpty()
                ?: return@mapNotNull null

            MangaPage(
                id = generateUid("${chapter.id}-$i"),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }
}
