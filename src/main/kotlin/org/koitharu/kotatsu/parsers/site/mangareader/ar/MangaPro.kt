package org.koitharu.kotatsu.parsers.site.mangareader.ar

import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.EnumSet

@MangaSourceParser("MANGAPRO", "MangaPro", "ar")
internal class MangaPro(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAPRO, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("prochan.net")
    
    private val apiUrl = "https://api.promanga.net/"
    private val webUrl = "https://prochan.net/"
    
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val tag = when (order) {
            SortOrder.UPDATED -> "latestUpdate"
            SortOrder.POPULARITY -> "popular"
            SortOrder.NEWEST -> "newest"
            SortOrder.RATING -> "rating"
            else -> "latestUpdate"
        }
        
        val url = buildString {
            append(apiUrl)
            append("api/posts?page=")
            append(page)
            append("&perPage=20")
            
            if (!filter.query.isNullOrEmpty()) {
                append("&searchTerm=")
                append(filter.query.urlEncoded())
            } else {
                append("&searchTerm=")
            }
            
            append("&isNovel=false&tag=")
            append(tag)
        }
        
        val response = webClient.httpGet(url).parseJson()
        val posts = response.getJSONArray("posts")
        
        return List(posts.length()) { i ->
            val post = posts.getJSONObject(i)
            val id = post.getInt("id")
            val slug = post.getString("slug")
            
            Manga(
                id = generateUid(id),
                url = "/series/${post.getString("seriesType").lowercase()}/$id/$slug",
                publicUrl = "$webUrl/series/${post.getString("seriesType").lowercase()}/$id/$slug",
                title = post.getString("postTitle"),
                altTitles = emptySet(),
                coverUrl = post.getString("featuredImage"),
                rating = post.optDouble("averageRating", -1.0).toFloat() / 2f,
                tags = emptySet(),
                state = when (post.optString("seriesStatus")) {
                    "ONGOING" -> MangaState.ONGOING
                    "COMPLETED" -> MangaState.FINISHED
                    "HIATUS" -> MangaState.PAUSED
                    else -> null
                },
                authors = setOfNotNull(post.optString("author").takeIf { it.isNotEmpty() }),
                isNsfw = false,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        // استخراج ID من URL
        val id = manga.url.split("/").getOrNull(3)?.toIntOrNull() 
            ?: throw IllegalArgumentException("Invalid manga URL: ${manga.url}")
        
        val url = "${apiUrl}api/post?postId=$id"
        val response = webClient.httpGet(url).parseJson()
        val post = response.getJSONObject("post")
        
        val chapters = post.getJSONArray("chapters")
        val chaptersList = List(chapters.length()) { i ->
            val chapter = chapters.getJSONObject(i)
            
            // تخطي الفصول المقفلة
            if (chapter.optBoolean("isLocked", false)) {
                return@List null
            }
            
            val chapterId = chapter.getInt("id")
            val chapterNumber = chapter.getDouble("number").toFloat()
            
            MangaChapter(
                id = generateUid(chapterId),
                name = chapter.getString("title"),
                number = chapterNumber,
                volume = 0,
                url = "/api/chapter?chapterId=$chapterId",
                scanlator = null,
                uploadDate = parseDate(chapter.getString("createdAt")),
                branch = null,
                source = source,
            )
        }.filterNotNull().reversed()
        
        val genres = post.getJSONArray("genres")
        val tags = List(genres.length()) { i ->
            val genre = genres.getJSONObject(i)
            MangaTag(
                key = genre.getString("name"),
                title = genre.getString("name"),
                source = source,
            )
        }.toSet()
        
        return manga.copy(
            title = post.getString("postTitle"),
            altTitles = setOfNotNull(
                post.optString("alternativeTitles").takeIf { it.isNotEmpty() }
            ),
            description = post.getString("postContent").replace(Regex("<[^>]*>"), ""),
            coverUrl = post.getString("featuredImage"),
            tags = tags,
            authors = setOfNotNull(post.optString("author").takeIf { it.isNotEmpty() }),
            state = when (post.optString("seriesStatus")) {
                "ONGOING" -> MangaState.ONGOING
                "COMPLETED" -> MangaState.FINISHED
                "HIATUS" -> MangaState.PAUSED
                else -> null
            },
            chapters = chaptersList,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = apiUrl + chapter.url.removePrefix("/")
        val response = webClient.httpGet(url).parseJson()
        val chapterData = response.getJSONObject("chapter")
        val images = chapterData.getJSONArray("images")
        
        return List(images.length()) { i ->
            val image = images.getJSONObject(i)
            val imageUrl = image.getString("url")
            
            MangaPage(
                id = generateUid("$imageUrl#$i"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseDate(dateString: String): Long {
        return try {
            val instant = if (dateString.contains("T")) {
                java.time.Instant.parse(dateString)
            } else {
                java.time.LocalDate.parse(dateString).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
            }
            instant.toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }
}
