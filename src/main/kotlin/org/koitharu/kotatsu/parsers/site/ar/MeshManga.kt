package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * MeshManga (Swat Manga) - موقع مانجا عربي
 * Website: https://meshmanga.com
 * Note: This site uses React/Next.js and loads content dynamically
 */
@MangaSourceParser("MESHMANGA", "MeshManga", "ar")
internal class MeshManga(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MESHMANGA, 36) {

    override val configKeyDomain = ConfigKey.Domain("meshmanga.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.NEWEST
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)

            when {
                !filter.query.isNullOrEmpty() -> {
                    append("/search?q=")
                    append(filter.query.urlEncoded())
                    append("&page=")
                    append(page)
                }
                else -> {
                    append("/browse?")
                    
                    // Sort order
                    append("sort=")
                    append(
                        when (order) {
                            SortOrder.ALPHABETICAL -> "name"
                            SortOrder.POPULARITY -> "popular"
                            SortOrder.NEWEST -> "created"
                            SortOrder.UPDATED -> "updated"
                            else -> "updated"
                        }
                    )

                    // Type filter
                    filter.types.oneOrThrowIfMany()?.let {
                        append("&type=")
                        append(
                            when (it) {
                                ContentType.MANGA -> "manga"
                                ContentType.MANHWA -> "manhwa"
                                ContentType.MANHUA -> "manhua"
                                else -> "manga"
                            }
                        )
                    }

                    // Status filter
                    filter.states.oneOrThrowIfMany()?.let {
                        append("&status=")
                        append(
                            when (it) {
                                MangaState.ONGOING -> "ongoing"
                                MangaState.FINISHED -> "completed"
                                else -> "ongoing"
                            }
                        )
                    }

                    append("&page=")
                    append(page)
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        // محاولة البحث في الـ JSON المدمج في الصفحة
        val scriptData = doc.select("script#__NEXT_DATA__").firstOrNull()?.data()
        if (scriptData != null) {
            try {
                val jsonRoot = JSONObject(scriptData)
                val props = jsonRoot.optJSONObject("props")
                val pageProps = props?.optJSONObject("pageProps")
                val mangaArray = pageProps?.optJSONArray("mangas") 
                    ?: pageProps?.optJSONArray("items")
                    ?: pageProps?.optJSONArray("results")

                if (mangaArray != null && mangaArray.length() > 0) {
                    return parseMangaFromJson(mangaArray)
                }
            } catch (e: Exception) {
                // تجاهل وحاول طريقة أخرى
            }
        }

        // Fallback: محاولة البحث في HTML العادي
        val mangaElements = doc.select(
            ".manga-item, .manga-card, .grid > a, .grid > div > a, " +
            "[class*='manga'], [class*='card'], .post-item"
        )

        if (mangaElements.isEmpty()) {
            return emptyList()
        }

        return mangaElements.mapNotNull { element ->
            val link = element.selectFirst("a") ?: element
            val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            
            if (!href.contains("/manga/") && !href.contains("/series/")) {
                return@mapNotNull null
            }

            val title = element.selectFirst("h3, h2, .title, [class*='title']")?.text()
                ?: link.attr("title")
                ?: return@mapNotNull null

            val img = element.selectFirst("img")
            val coverUrl = img?.src() ?: img?.attr("data-src") ?: ""

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = coverUrl,
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                contentRating = ContentRating.SAFE,
                source = source,
            )
        }
    }

    private fun parseMangaFromJson(mangaArray: JSONArray): List<Manga> {
        return (0 until mangaArray.length()).mapNotNull { i ->
            val item = mangaArray.optJSONObject(i) ?: return@mapNotNull null
            
            val slug = item.optString("slug", "")
            val id = item.optString("id", slug)
            if (slug.isBlank() && id.isBlank()) return@mapNotNull null

            val url = "/manga/${slug.ifBlank { id }}"
            val title = item.optString("title", "") 
                ?: item.optString("name", "")
            if (title.isBlank()) return@mapNotNull null

            val coverUrl = item.optString("cover", "")
                .let { if (it.startsWith("http")) it else "https://$domain$it" }

            Manga(
                id = generateUid(url),
                url = url,
                publicUrl = url.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = coverUrl,
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                contentRating = ContentRating.SAFE,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        // محاولة قراءة البيانات من NEXT_DATA
        val scriptData = doc.select("script#__NEXT_DATA__").firstOrNull()?.data()
        if (scriptData != null) {
            try {
                val jsonRoot = JSONObject(scriptData)
                val props = jsonRoot.optJSONObject("props")
                val pageProps = props?.optJSONObject("pageProps")
                val mangaData = pageProps?.optJSONObject("manga")
                    ?: pageProps?.optJSONObject("series")

                if (mangaData != null) {
                    return parseMangaDetails(manga, mangaData, doc)
                }
            } catch (e: Exception) {
                // تجاهل
            }
        }

        // Fallback للـ HTML العادي
        return parseMangaDetailsFromHtml(manga, doc)
    }

    private fun parseMangaDetails(manga: Manga, data: JSONObject, doc: Document): Manga {
        val description = data.optString("description", null)
            ?.takeIf { it.isNotBlank() }

        val author = data.optString("author", null)
            ?.takeIf { it.isNotBlank() }

        val status = data.optString("status", "")
        val state = when (status.lowercase()) {
            "ongoing", "مستمرة" -> MangaState.ONGOING
            "completed", "مكتملة" -> MangaState.FINISHED
            else -> null
        }

        val chaptersArray = data.optJSONArray("chapters") ?: JSONArray()
        val chapters = parseChaptersFromJson(chaptersArray)

        return manga.copy(
            description = description,
            authors = setOfNotNull(author),
            state = state,
            chapters = chapters,
        )
    }

    private fun parseMangaDetailsFromHtml(manga: Manga, doc: Document): Manga {
        val description = doc.selectFirst(
            ".description, .summary, [class*='description'], [class*='summary']"
        )?.text()

        val author = doc.selectFirst(
            ".author, [class*='author']"
        )?.text()

        val statusText = doc.selectFirst(
            ".status, [class*='status']"
        )?.text() ?: ""

        val state = when {
            statusText.contains("مستمرة", ignoreCase = true) || 
            statusText.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
            statusText.contains("مكتملة", ignoreCase = true) || 
            statusText.contains("completed", ignoreCase = true) -> MangaState.FINISHED
            else -> null
        }

        val chapters = parseChaptersFromHtml(doc)

        return manga.copy(
            description = description,
            authors = setOfNotNull(author),
            state = state,
            chapters = chapters,
        )
    }

    private fun parseChaptersFromJson(chaptersArray: JSONArray): List<MangaChapter> {
        return (0 until chaptersArray.length()).mapNotNull { i ->
            val chap = chaptersArray.optJSONObject(i) ?: return@mapNotNull null
            
            val chapterId = chap.optString("id", "")
            val chapterNum = chap.optString("chapter", "0")
            val title = chap.optString("title", "Chapter $chapterNum")
            
            val url = "/chapter/$chapterId"
            
            MangaChapter(
                id = generateUid(url),
                url = url,
                title = title,
                number = chapterNum.toFloatOrNull() ?: (i + 1).toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }.reversed()
    }

    private fun parseChaptersFromHtml(doc: Document): List<MangaChapter> {
        return doc.select(
            ".chapter-item, .chapter-list > a, [class*='chapter']"
        ).mapIndexedNotNull { index, element ->
            val link = element.selectFirst("a") ?: element
            val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapIndexedNotNull null
            
            val title = element.selectFirst(".chapter-title, h3, h4")?.text()
                ?: link.text()
                ?: "Chapter ${index + 1}"

            MangaChapter(
                id = generateUid(url),
                url = url,
                title = title,
                number = (index + 1).toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        // محاولة قراءة من NEXT_DATA
        val scriptData = doc.select("script#__NEXT_DATA__").firstOrNull()?.data()
        if (scriptData != null) {
            try {
                val jsonRoot = JSONObject(scriptData)
                val props = jsonRoot.optJSONObject("props")
                val pageProps = props?.optJSONObject("pageProps")
                val pagesArray = pageProps?.optJSONArray("pages")
                    ?: pageProps?.optJSONArray("images")

                if (pagesArray != null && pagesArray.length() > 0) {
                    return (0 until pagesArray.length()).map { i ->
                        val pageUrl = pagesArray.getString(i)
                        val fullUrl = if (pageUrl.startsWith("http")) {
                            pageUrl
                        } else {
                            "https://$domain$pageUrl"
                        }
                        
                        MangaPage(
                            id = generateUid(fullUrl),
                            url = fullUrl,
                            preview = null,
                            source = source,
                        )
                    }
                }
            } catch (e: Exception) {
                // تجاهل
            }
        }

        // Fallback للصور العادية
        return doc.select("img[src*='/chapters/'], img[data-src*='/chapters/'], .reader img")
            .mapNotNull { img ->
                val url = img.src() ?: img.attr("data-src")
                if (url.isBlank()) return@mapNotNull null
                
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
    }
}
