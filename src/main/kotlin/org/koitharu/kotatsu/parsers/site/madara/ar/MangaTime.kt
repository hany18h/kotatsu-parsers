package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGATIME", "MangaTime", "ar")
internal class MangaTime(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGATIME, 20) {

    override val configKeyDomain = ConfigKey.Domain("mangatime.org")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.NEWEST,
        SortOrder.UPDATED
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isSearchSupported = true
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {
        val skip = page * 20
        val limit = skip + 20

        val sortParam = when (order) {
            SortOrder.NEWEST -> "Newest"
            SortOrder.UPDATED -> "Newest"
            SortOrder.ALPHABETICAL -> "A-Z"
            else -> "A-Z"
        }

        val url = buildString {
            append("https://")
            append(domain)
            append("/categories/$skip/$limit/$sortParam/")
            
            filter.query?.let {
                append("?search=")
                append(it.urlEncoded())
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: org.jsoup.nodes.Document): List<Manga> {
        val elements = doc.select("div.UpdatedTitle-module_titleWrapper_2EQIT")
        
        return elements.mapNotNull { div ->
            try {
                val link = div.selectFirst("a.manga-link") ?: return@mapNotNull null
                val href = link.attrAsRelativeUrl("href")
                
                val title = link.attr("data-manga-name").trim()
                if (title.isEmpty()) return@mapNotNull null

                val coverDiv = div.selectFirst("div.product__item__pic_catogary")
                val coverUrl = coverDiv?.attr("data-setbg-high") 
                    ?: coverDiv?.attr("data-setbg-low")

                val tags = div.select("div.UpdatedTitle-module_titleDescription_Cf0hO ul li a")
                    .mapNotNullToSet { a ->
                        MangaTag(
                            key = a.attr("href").substringAfterLast("/"),
                            title = a.text().toTitleCase(),
                            source = source
                        )
                    }

                Manga(
                    id = generateUid(href),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    coverUrl = coverUrl?.toAbsoluteUrl(domain),
                    title = title,
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    tags = tags,
                    authors = emptySet(),
                    state = null,
                    source = source,
                    isNsfw = false
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.selectFirst("div.anime__details__title h3")?.text() 
            ?: manga.title

        val cover = doc.selectFirst("div.anime__details__pic")
            ?.attr("data-setbg")
            ?.toAbsoluteUrl(domain)

        val description = doc.selectFirst("div.anime__details__text p")?.text()

        val tags = doc.select("div.anime__details__widget li:contains(التصنيفات:)")
            .firstOrNull()
            ?.text()
            ?.substringAfter(":")
            ?.split(",")
            ?.mapNotNullToSet { tagName ->
                val cleanName = tagName.trim()
                if (cleanName.isNotEmpty()) {
                    MangaTag(
                        key = cleanName.lowercase(),
                        title = cleanName,
                        source = source
                    )
                } else null
            } ?: emptySet()

        val stateText = doc.selectFirst("li:contains(الحالة:)")
            ?.selectFirst(".summary-content")
            ?.text()
            ?.lowercase()

        val state = when {
            stateText?.contains("مستمرة") == true -> MangaState.ONGOING
            stateText?.contains("مكتملة") == true -> MangaState.FINISHED
            else -> null
        }

        val chapters = getChapters(manga.url, doc)

        return manga.copy(
            title = title,
            coverUrl = cover ?: manga.coverUrl,
            description = description,
            tags = tags,
            state = state,
            chapters = chapters
        )
    }

    private suspend fun getChapters(
        mangaUrl: String,
        doc: org.jsoup.nodes.Document
    ): List<MangaChapter> {
        val relativeFormat = SimpleDateFormat("d MMMM، yyyy", Locale("ar"))
        val absoluteFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)
        
        return doc.select("ul.chapter-list > li:not(.hidden-chapter)").mapChapters(reversed = true) { i, li ->
            val a = li.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            
            val name = a.attr("title").ifEmpty {
                li.selectFirst("p.title3")?.text() ?: "Chapter ${i + 1}"
            }
            
            val dateText = li.selectFirst("p.title2")?.text()

            MangaChapter(
                id = generateUid(href),
                url = href,
                title = name,
                number = i + 1f,
                volume = 0,
                branch = null,
                uploadDate = parseChapterDate(relativeFormat, absoluteFormat, dateText),
                scanlator = null,
                source = source
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        
        return doc.select("#chapter-pages img.chapter-page")
            .mapNotNull { img ->
                val url = img.attr("data-src").ifEmpty { 
                    img.attr("src") 
                }.toRelativeUrl(domain)
                
                if (url.isNotEmpty()) {
                    MangaPage(
                        id = generateUid(url),
                        url = url,
                        preview = null,
                        source = source
                    )
                } else null
            }
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED
        )
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/categories/").parseHtml()
        
        return doc.select("div.genres_wrap ul li a, header ul.second-menu li a")
            .mapNotNullToSet { a ->
                val href = a.attr("href")
                if (href.contains("/categories/")) {
                    MangaTag(
                        key = href.substringAfterLast("/"),
                        title = a.text().toTitleCase(),
                        source = source
                    )
                } else null
            }
    }

    private fun parseChapterDate(
        relativeFormat: java.text.DateFormat,
        absoluteFormat: java.text.DateFormat, 
        date: String?
    ): Long {
        if (date.isNullOrEmpty()) return 0

        val d = date.lowercase().trim()
        
        return when {
            d.contains("days ago") || d.contains("day ago") -> {
                val number = Regex("""\d+""").find(d)?.value?.toIntOrNull() ?: return 0
                Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -number)
                }.timeInMillis
            }
            
            d.contains("منذ") || d.contains("ago") -> {
                val number = Regex("""\d+""").find(d)?.value?.toIntOrNull() ?: return 0
                val cal = Calendar.getInstance()
                when {
                    d.contains("hour") || d.contains("ساعة") -> 
                        cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
                    d.contains("day") || d.contains("يوم") -> 
                        cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
                    d.contains("month") || d.contains("شهر") -> 
                        cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
                    else -> 0
                }
            }
            
            d.matches(Regex("""\w{3}\s+\d{1,2},\s+\d{4}""")) -> {
                try {
                    absoluteFormat.parse(date)?.time ?: 0
                } catch (e: Exception) {
                    0
                }
            }
            
            else -> {
                try {
                    relativeFormat.parse(date)?.time ?: 0
                } catch (e: Exception) {
                    0
                }
            }
        }
    }
}
