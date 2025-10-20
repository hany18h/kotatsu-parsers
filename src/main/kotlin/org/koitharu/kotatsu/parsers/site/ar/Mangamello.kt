package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Mangamello - موقع مانجا عربي
 * API-based site
 */
@MangaSourceParser("MANGAMELLO", "Mangamello", "ar")
internal class Mangamello(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAMELLO, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("mangamello.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isSearchSupported = true,
        )

    private val apiUrl: String
        get() = "https://$domain/v1/mangas"

    // القوائم الرئيسية
    override suspend fun getListPage(page: Int, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            return search(filter.query)
        }

        val sortBy = when (filter.sortOrder) {
            SortOrder.POPULARITY -> "views"
            else -> "updated_at"
        }

        val url = "$apiUrl?sort_by=$sortBy&page=$page"
        val json = webClient.httpGet(url).parseJson()

        val dataArray = json.getJSONArray("data")
        return (0 until dataArray.length()).map { i ->
            val item = dataArray.getJSONObject(i)
            parseMangaItem(item)
        }
    }

    // البحث
    private suspend fun search(query: String): List<Manga> {
        val url = "$apiUrl/search?per_page=40&title=${query.urlEncoded()}"
        val json = webClient.httpGet(url).parseJson()

        val dataArray = json.getJSONArray("data")
        return (0 until dataArray.length()).map { i ->
            val item = dataArray.getJSONObject(i)
            parseMangaItem(item)
        }
    }

    // تحويل JSON item إلى Manga
    private fun parseMangaItem(item: JSONObject): Manga {
        val id = item.getInt("id")
        val relUrl = "/v1/mangas/$id"

        return Manga(
            id = generateUid(relUrl),
            url = relUrl,
            publicUrl = "https://$domain/mangas/$id",
            title = item.getString("title"),
            altTitles = null,
            coverUrl = item.optString("img").takeIf { it.isNotBlank() },
            rating = item.optDouble("average_rate", -1.0).let { 
                if (it > 0) (it / 2).toFloat() else RATING_UNKNOWN 
            },
            tags = item.optJSONArray("genres")?.let { genres ->
                (0 until genres.length()).mapNotNull { idx ->
                    genres.optJSONObject(idx)?.optString("name")?.let { name ->
                        MangaTag(key = name, title = name, source = source)
                    }
                }.toSet()
            } ?: emptySet(),
            author = null,
            state = null,
            source = source,
            isNsfw = false,
        )
    }

    // التفاصيل
    override suspend fun getDetails(manga: Manga): Manga {
        val infoUrl = "https://$domain${manga.url}"
        val chaptersUrl = "https://$domain${manga.url}/chapters?per_page=2000"

        val infoJson = webClient.httpGet(infoUrl).parseJson()
        val chaptersJson = webClient.httpGet(chaptersUrl).parseJson()

        val data = infoJson.getJSONObject("data")
        val chaptersArray = chaptersJson.getJSONArray("data")

        val chapters = (0 until chaptersArray.length()).mapNotNull { i ->
            val chapter = chaptersArray.optJSONObject(i) ?: return@mapNotNull null
            
            val mangaId = chapter.optInt("manga_id", 0)
            val chapterId = chapter.optInt("id", 0)
            if (mangaId == 0 || chapterId == 0) return@mapNotNull null

            val chapterOrder = chapter.optDouble("order", 0.0)
            val chapterTitle = chapter.optString("title", "")
            
            val relUrl = "/v1/mangas/$mangaId/chapters/$chapterId?relations=chapterImages"
            
            MangaChapter(
                id = generateUid(relUrl),
                title = chapterTitle.ifBlank { "Chapter $chapterOrder" },
                number = chapterOrder.toFloat(),
                volume = 0,
                url = relUrl,
                scanlator = null,
                uploadDate = parseDate(chapter.optString("created_at")),
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }.reversed()

        return manga.copy(
            title = data.getString("title"),
            description = data.optString("summary").takeIf { it.isNotBlank() },
            coverUrl = data.optString("img").takeIf { it.isNotBlank() } ?: manga.coverUrl,
            rating = data.optDouble("ten_rate", -1.0).let {
                if (it > 0) (it / 2).toFloat() else manga.rating
            },
            state = when (data.optInt("is_completed", 0)) {
                1 -> MangaState.FINISHED
                else -> MangaState.ONGOING
            },
            chapters = chapters,
        )
    }

    // الصفحات
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = "https://$domain${chapter.url}"
        val json = webClient.httpGet(chapterUrl).parseJson()

        val data = json.getJSONObject("data")
        val imagesArray = data.optJSONArray("chapterImages")
            ?: data.optJSONArray("chapter_images")
            ?: throw Exception("No images found in chapter")

        return (0 until imagesArray.length()).mapNotNull { i ->
            val img = imagesArray.optJSONObject(i) ?: return@mapNotNull null
            
            val imageUrl = img.optString("src").takeIf { it.isNotBlank() }
                ?: img.optString("originalSrc").takeIf { it.isNotBlank() }
                ?: img.optString("original_src").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    // دالة مساعدة لتحويل التاريخ
    private fun parseDate(dateString: String?): Long {
        if (dateString.isNullOrBlank()) return 0
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.parse(dateString)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
