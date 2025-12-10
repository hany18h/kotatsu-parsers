package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAPRO", "MangaPro", "ar")
internal class MangaPro(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAPRO, 20) {

    override val configKeyDomain = ConfigKey.Domain("prochan.net")
    
    private val apiUrl = "https://api.promanga.net/"
    private val webUrl = "https://prochan.net/"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
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

        val json = webClient.httpGet(url).parseJson()
        val posts = json.getJSONArray("posts")

        return List(posts.length()) { i ->
            val post = posts.getJSONObject(i)
            parseMangaFromJson(post)
        }
    }

    private fun parseMangaFromJson(post: JSONObject): Manga {
        val id = post.getInt("id")
        val slug = post.getString("slug")
        val seriesType = post.optString("seriesType", "manga").lowercase(Locale.ROOT)
        val relativeUrl = "/series/$seriesType/$id/$slug"

        return Manga(
            id = generateUid(id.toLong()),
            url = relativeUrl,
            publicUrl = webUrl + relativeUrl.removePrefix("/"),
            title = post.getString("postTitle"),
            altTitles = emptySet(),
            coverUrl = post.getString("featuredImage"),
            rating = post.optDouble("averageRating", -1.0).toFloat().div(2f),
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

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.split("/").getOrNull(3)?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid manga URL: ${manga.url}")

        val url = "${apiUrl}api/post?postId=$id"
        val json = webClient.httpGet(url).parseJson()
        val post = json.getJSONObject("post")

        val chaptersArray = post.getJSONArray("chapters")
        val chapters = mutableListOf<MangaChapter>()

        for (i in 0 until chaptersArray.length()) {
            val chapter = chaptersArray.getJSONObject(i)
            
            if (chapter.optBoolean("isLocked", false)) {
                continue
            }

            val chapterId = chapter.getInt("id")
            val chapterNumber = chapter.getDouble("number").toFloat()

            chapters.add(
                MangaChapter(
                    id = generateUid(chapterId.toLong()),
                    name = chapter.getString("title"),
                    number = chapterNumber,
                    volume = 0,
                    url = "/api/chapter?chapterId=$chapterId",
                    scanlator = null,
                    uploadDate = parseDate(chapter.getString("createdAt")),
                    branch = null,
                    source = source,
                )
            )
        }

        val genresArray = post.getJSONArray("genres")
        val tags = mutableSetOf<MangaTag>()
        for (i in 0 until genresArray.length()) {
            val genre = genresArray.getJSONObject(i)
            val genreName = genre.getString("name")
            tags.add(
                MangaTag(
                    key = genreName,
                    title = genreName,
                    source = source,
                )
            )
        }

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
            chapters = chapters.reversed(),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = apiUrl + chapter.url.removePrefix("/")
        val json = webClient.httpGet(url).parseJson()
        val chapterData = json.getJSONObject("chapter")
        val imagesArray = chapterData.getJSONArray("images")

        return List(imagesArray.length()) { i ->
            val image = imagesArray.getJSONObject(i)
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
            if (dateString.contains("T")) {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(dateString)?.time ?: 0L
            } else {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateString)?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}
