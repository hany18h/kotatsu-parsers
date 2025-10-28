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
 * Mangamello - موقع مانجا عربي (API-based)
 */
@MangaSourceParser("MANGAMELLO", "Mangamello", "ar")
internal class Mangamello(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAMELLO, pageSize = 40) {

    override val configKeyDomain = ConfigKey.Domain("mangamello.com")
    
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    private val baseApiUrl: String
        get() = "https://$domain/api/v1/mangas"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // إذا كان هناك بحث
        if (!filter.query.isNullOrEmpty()) {
            return searchManga(filter.query, page)
        }

        // وإلا استخدم الترتيب
        val sortBy = when (order) {
            SortOrder.UPDATED -> "updated_at"
            SortOrder.POPULARITY -> "views"
            else -> "updated_at"
        }

        val url = "$baseApiUrl?sort_by=$sortBy&page=$page"
        val json = webClient.httpGet(url).parseJson()

        return parseMangaList(json)
    }

    private suspend fun searchManga(query: String, page: Int): List<Manga> {
        val url = "$baseApiUrl/search?per_page=40&title=${query.urlEncoded()}"
        val json = webClient.httpGet(url).parseJson()

        return parseSearchResults(json)
    }

    private fun parseMangaList(json: JSONObject): List<Manga> {
        val dataArray = json.optJSONArray("data") ?: return emptyList()
        val mangaList = mutableListOf<Manga>()

        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val mangaId = item.optInt("id", 0)
            if (mangaId == 0) continue

            val relUrl = "/api/v1/mangas/$mangaId"
            mangaList.add(
                Manga(
                    id = generateUid(relUrl),
                    url = relUrl,
                    publicUrl = "https://$domain/manga/$mangaId",
                    title = item.optString("title", "Unknown"),
                    altTitles = emptySet(),
                    coverUrl = item.optString("img", ""),
                    rating = item.optDouble("rate", 0.0).toFloat().div(10).takeIf { it > 0 }
                        ?: RATING_UNKNOWN,
                    tags = emptySet(),
                    authors = emptySet(),
                    state = null,
                    source = source,
                    contentRating = ContentRating.SAFE,
                )
            )
        }

        return mangaList
    }

    private fun parseSearchResults(json: JSONObject): List<Manga> {
        val dataArray = json.optJSONArray("data") ?: return emptyList()
        val mangaList = mutableListOf<Manga>()

        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val mangaId = item.optInt("id", 0)
            if (mangaId == 0) continue

            val relUrl = "/api/v1/mangas/$mangaId"
            
            // استخراج الأنواع (genres)
            val genresArray = item.optJSONArray("genres")
            val tags = mutableSetOf<MangaTag>()
            if (genresArray != null) {
                for (j in 0 until genresArray.length()) {
                    val genre = genresArray.optJSONObject(j)
                    val genreName = genre?.optString("name")
                    if (genreName != null) {
                        tags.add(
                            MangaTag(
                                title = genreName,
                                key = genreName.lowercase(),
                                source = source,
                            )
                        )
                    }
                }
            }

            mangaList.add(
                Manga(
                    id = generateUid(relUrl),
                    url = relUrl,
                    publicUrl = "https://$domain/manga/$mangaId",
                    title = item.optString("title", "Unknown"),
                    altTitles = emptySet(),
                    coverUrl = item.optString("img", ""),
                    rating = item.optDouble("average_rate", 0.0).toFloat().div(10)
                        .takeIf { it > 0 } ?: RATING_UNKNOWN,
                    tags = tags,
                    authors = emptySet(),
                    state = null,
                    source = source,
                    contentRating = ContentRating.SAFE,
                )
            )
        }

        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val infoUrl = "https://$domain${manga.url}"
        val mangaId = manga.url.substringAfterLast("/")
        val chaptersUrl = "https://$domain/api/v1/mangas/$mangaId/chapters?per_page=2000"

        val infoJson = webClient.httpGet(infoUrl).parseJson()
        val chaptersJson = webClient.httpGet(chaptersUrl).parseJson()

        val data = infoJson.optJSONObject("data") ?: infoJson

        val chapters = parseChapters(chaptersJson, mangaId)

        return manga.copy(
            title = data.optString("title", manga.title),
            description = data.optString("summary", null),
            coverUrl = data.optString("img", manga.coverUrl),
            state = when (data.optInt("is_completed", 0)) {
                1 -> MangaState.FINISHED
                else -> MangaState.ONGOING
            },
            rating = data.optDouble("ten_rate", 0.0).toFloat().div(10)
                .takeIf { it > 0 } ?: manga.rating,
            chapters = chapters,
        )
    }

    private fun parseChapters(json: JSONObject, mangaId: String): List<MangaChapter> {
        val dataArray = json.optJSONArray("data") ?: return emptyList()
        val chapters = mutableListOf<MangaChapter>()

        for (i in 0 until dataArray.length()) {
            val chapter = dataArray.optJSONObject(i) ?: continue
            val chapterId = chapter.optInt("id", 0)
            if (chapterId == 0) continue

            // استخدام order أو title كرقم الفصل
            val chapterNumber = chapter.optDouble("order", 0.0).takeIf { it > 0 }
                ?: chapter.optString("title", "0").toDoubleOrNull()
                ?: (i + 1).toDouble()
            
            val chapterTitle = chapter.optString("title", "")

            val relUrl = "/api/v1/mangas/$mangaId/chapters/$chapterId?relations=chapterImages"

            // تحويل التاريخ من ISO 8601
            val dateString = chapter.optString("created_at", null)
            val uploadDate = dateString?.let {
                try {
                    // صيغة ISO 8601: 2024-01-15T10:30:00.000000Z
                    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    format.parse(it.substringBefore("."))?.time ?: 0
                } catch (e: Exception) {
                    0L
                }
            } ?: 0L

            chapters.add(
                MangaChapter(
                    id = generateUid(relUrl),
                    title = chapterTitle.ifBlank { "Chapter ${chapterNumber.toInt()}" },
                    number = chapterNumber.toFloat(),
                    volume = 0,
                    url = relUrl,
                    scanlator = null,
                    uploadDate = uploadDate,
                    branch = null,
                    source = source,
                )
            )
        }

        // ترتيب الفصول من الأحدث للأقدم
        return chapters.sortedByDescending { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = "https://$domain${chapter.url}"
        val json = webClient.httpGet(chapterUrl).parseJson()

        val data = json.optJSONObject("data") ?: throw Exception("No chapter data found")
        val imagesArray = data.optJSONArray("chapterImages")
            ?: throw Exception("No chapter images found")

        val pages = mutableListOf<MangaPage>()

        for (i in 0 until imagesArray.length()) {
            val imageObj = imagesArray.optJSONObject(i) ?: continue
            
            // استخدام src أو originalSrc (كما في الكود الأصلي)
            val imageUrl = imageObj.optString("src").takeIf { it.isNotBlank() }
                ?: imageObj.optString("originalSrc").takeIf { it.isNotBlank() }
                ?: continue

            pages.add(
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source,
                )
            )
        }

        if (pages.isEmpty()) {
            throw Exception("No images found in chapter")
        }

        return pages
    }
}
