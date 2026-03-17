package org.koitharu.kotatsu.parsers.site.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@MangaSourceParser("REWAYAT", "نادي الروايات", "ar", ContentType.NOVEL)
internal class Rewayat(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.REWAYAT, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("rewayat.club")
    private val apiDomain get() = "api.$domain"

    override val availableSortOrders: Set<SortOrder> = linkedSetOf(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = fetchAvailableTags()
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
            ),
        )
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val json = webClient.httpGet("https://$apiDomain/api/genres/").parseJson()
        val results = json.getJSONArray("results")
        return results.mapJSONToSet { obj ->
            MangaTag(
                title = obj.getString("name"),
                key = obj.getInt("id").toString(),
                source = source,
            )
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(apiDomain)
            append("/api/novels/?page=")
            append(page)
            append("&ordering=")
            append(
                when (order) {
                    SortOrder.POPULARITY   -> "-num_chapters"
                    SortOrder.UPDATED      -> "-last_updated"
                    SortOrder.NEWEST       -> "-id"
                    SortOrder.ALPHABETICAL -> "english"
                    else                   -> "-num_chapters"
                },
            )
            if (!filter.query.isNullOrEmpty()) {
                append("&search=")
                append(filter.query.urlEncoded())
            }
            filter.tags.forEach { tag ->
                append("&genres=")
                append(tag.key)
            }
            filter.states.forEach { state ->
                append("&status=")
                append(
                    when (state) {
                        MangaState.ONGOING  -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        else -> return@forEach
                    },
                )
            }
        }
        val json = webClient.httpGet(url).parseJson()
        return json.getJSONArray("results").mapJSON { obj ->
            val slug = obj.getString("slug")
            val novelUrl = "/novel/$slug"
            Manga(
                id = generateUid(novelUrl),
                title = obj.getStringOrNull("arabic") ?: obj.getString("english"),
                altTitles = setOfNotNull(obj.getStringOrNull("english")),
                url = novelUrl,
                publicUrl = "https://$domain$novelUrl",
                rating = obj.getFloatOrDefault("rating", 0f) / 5f,
                contentRating = ContentRating.SAFE,
                coverUrl = obj.getStringOrNull("poster_url")?.toCoverUrl(),
                tags = emptySet(),
                state = when (obj.getStringOrNull("status")) {
                    "ongoing"   -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else        -> null
                },
                authors = setOfNotNull(obj.getStringOrNull("author")),
                source = source,
            )
        }
    }

    private fun String.toCoverUrl() =
        if (startsWith("http")) this else "https://$apiDomain$this"

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfterLast("/")
        val json = webClient.httpGet("https://$apiDomain/api/novels/$slug/").parseJson()
        val chapters = fetchAllChapters(slug)
        val tags = json.optJSONArray("genres")?.mapJSONToSet { g ->
            MangaTag(
                title = g.getString("name"),
                key = g.getInt("id").toString(),
                source = source,
            )
        } ?: emptySet()
        val authors = json.optJSONArray("contributors")?.mapJSONNotNullToSet { c ->
            c.getStringOrNull("name")
        } ?: manga.authors
        return manga.copy(
            title = json.getStringOrNull("arabic") ?: json.getString("english"),
            altTitles = setOfNotNull(json.getStringOrNull("english")),
            description = json.getStringOrNull("synopsis"),
            coverUrl = json.getStringOrNull("poster_url")?.toCoverUrl() ?: manga.coverUrl,
            largeCoverUrl = json.getStringOrNull("poster_url")?.toCoverUrl(),
            rating = json.getFloatOrDefault("rating", 0f) / 5f,
            state = when (json.getStringOrNull("status")) {
                "ongoing"   -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                else        -> manga.state
            },
            authors = authors,
            tags = tags,
            chapters = chapters,
        )
    }

    private suspend fun fetchAllChapters(novelSlug: String): List<MangaChapter> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val baseUrl = "https://$apiDomain/api/chapters/$novelSlug/?ordering=number"
        val firstJson = webClient.httpGet("$baseUrl&page=1").parseJson()
        val totalCount = firstJson.getInt("count")
        val chapterPageSize = firstJson.getJSONArray("results").length().takeIf { it > 0 } ?: 10
        val totalPages = (totalCount + chapterPageSize - 1) / chapterPageSize

        fun parseChapters(results: org.json.JSONArray): List<MangaChapter> =
            results.mapJSON { obj ->
                val number = obj.getInt("number")
                val chapterUrl = "/novel/$novelSlug/$number"
                MangaChapter(
                    id = generateUid(chapterUrl),
                    title = obj.getStringOrNull("title") ?: "الفصل $number",
                    number = number.toFloat(),
                    volume = 0,
                    url = chapterUrl,
                    scanlator = null,
                    uploadDate = obj.getStringOrNull("date")?.let {
                        runCatching { dateFormat.parse(it)?.time ?: 0L }.getOrDefault(0L)
                    } ?: 0L,
                    branch = null,
                    source = source,
                )
            }

        if (totalPages <= 1) {
            return parseChapters(firstJson.getJSONArray("results"))
        }

        val allChapters = Array<List<MangaChapter>>(totalPages) { emptyList() }
        allChapters[0] = parseChapters(firstJson.getJSONArray("results"))
        coroutineScope {
            val jobs = (2..totalPages).map { page ->
                async(Dispatchers.IO) {
                    val json = webClient.httpGet("$baseUrl&page=$page").parseJson()
                    page to parseChapters(json.getJSONArray("results"))
                }
            }
            for ((page, chapters) in jobs.awaitAll()) {
                allChapters[page - 1] = chapters
            }
        }
        return allChapters.flatMap { it }
    }

    // مطلوبة من PagedMangaParser لكن غير مستخدمة للـ NOVEL
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

    override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
        val parts = chapter.url.removePrefix("/novel/").split("/")
        if (parts.size < 2) return null
        val novelSlug = parts[0]
        val number = parts[1]
        val json = webClient.httpGet(
            "https://$apiDomain/api/chapters/$novelSlug/$number/",
        ).parseJson()
        val title = json.getStringOrNull("title") ?: chapter.title ?: ""
        val contentArray = json.optJSONArray("content")
            ?: return NovelChapterContent(html = "<p>محتوى الفصل غير متاح</p>")

        val paragraphs = mutableListOf<String>()
        for (i in 0 until contentArray.length()) {
            val innerArray = contentArray.optJSONArray(i) ?: continue
            for (j in 0 until innerArray.length()) {
                val para = innerArray.optString(j, "").trim()
                if (para.isNotEmpty()) paragraphs.add(para)
            }
        }
        return NovelChapterContent(
            html = buildChapterHtml(title, paragraphs),
            images = emptyList(),
        )
    }

    private fun buildChapterHtml(title: String, paragraphs: List<String>): String {
        return buildString {
            if (title.isNotBlank()) {
                append("<h1>")
                append(title)
                append("</h1>")
            }
            paragraphs.forEach { para ->
                if (!para.trimStart().startsWith("<")) {
                    append("<p>")
                    append(para)
                    append("</p>")
                } else {
                    append(para)
                }
            }
        }
    }
}
