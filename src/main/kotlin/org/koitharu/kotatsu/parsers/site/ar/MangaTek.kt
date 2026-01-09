package org.koitharu.kotatsu.parsers.site.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGATEK", "MangaTek", "ar", ContentType.MANGA)
internal class MangaTek(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGATEK, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("api.mangatek.com")

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

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain.replace("api.", ""))
            append("/manga-list")
            
            when {
                !filter.query.isNullOrEmpty() -> {
                    append("?search=")
                    append(filter.query.urlEncoded())
                }
                else -> {
                    append("?sort=")
                    append(
                        when (order) {
                            SortOrder.POPULARITY -> "views"
                            SortOrder.ALPHABETICAL -> "title&sortOrder=ASC"
                            else -> "latest"
                        }
                    )
                }
            }
            
            if (page > 1) {
                append("&page=")
                append(page)
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        
        return doc.select("div.grid a.manga-card").mapNotNull { card ->
            val link = card.attr("href")
            val slug = link.removePrefix("/manga/")
            
            // الإصلاح: نختار h3 الموجود داخل div.absolute.bottom-0 فقط
            val title = card.selectFirst("div.absolute.bottom-0 h3")?.text()?.trim()
                ?: return@mapNotNull null
            
            // إصلاح التقييم: نختار span الذي يحتوي على i.fa-star
            val ratingText = card.selectFirst("span:has(i.fa-star) span")?.text()
            val rating = ratingText?.toFloatOrNull()?.div(10) ?: RATING_UNKNOWN
            
            Manga(
                id = generateUid(slug),
                url = slug,
                publicUrl = "https://${domain.replace("api.", "")}$link",
                title = title,
                coverUrl = card.selectFirst("img")?.src(),
                altTitles = emptySet(),
                rating = rating,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "https://${domain.replace("api.", "")}/manga/${manga.url}"
        val doc = webClient.httpGet(url).parseHtml()
        
        // Extract title
        val title = doc.selectFirst("h1")?.text() ?: manga.title
        
        // Extract description
        val description = doc.selectFirst("div.grid p, p.text-gray-300")?.text()
        
        // Extract status
        val statusText = doc.selectFirst("span.border")?.text()
        val state = when {
            statusText?.contains("مستمر") == true -> MangaState.ONGOING
            statusText?.contains("مكتمل") == true -> MangaState.FINISHED
            statusText?.contains("متوقف") == true -> MangaState.PAUSED
            else -> null
        }
        
        // Extract tags
        val tags = doc.select("div.flex.gap-2 span.text-gray-300").mapNotNullToSet { tag ->
            val tagName = tag.text().trim()
            if (tagName.isEmpty()) return@mapNotNullToSet null
            MangaTag(
                key = tagName,
                title = tagName,
                source = source
            )
        }
        
        // Extract rating
        val ratingText = doc.selectFirst("span:has(i.fa-star)")?.text()
        val rating = ratingText?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull()?.div(10) ?: manga.rating
        
        // Extract chapters
        val chapters = doc.select("div.manga-chapter a, div.grid a[href^='/reader/']").mapChapters(reversed = true) { index, element ->
            val chapterUrl = element.attr("href")
            val chapterTitle = element.selectFirst("h3")?.text() ?: "Chapter ${index + 1}"
            
            // Extract chapter number from title like "الفصل 240"
            val chapterNumber = chapterTitle.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: (index + 1f)
            
            // Extract date
            val dateText = element.selectFirst("span:has(i.fa-calendar-alt)")?.text()
                ?: element.selectFirst("p.text-sm")?.text()
            
            val uploadDate = parseDate(dateText)
            
            MangaChapter(
                id = generateUid(chapterUrl),
                title = chapterTitle,
                number = chapterNumber,
                volume = 0,
                url = chapterUrl,
                uploadDate = uploadDate,
                source = source,
                scanlator = null,
                branch = null,
            )
        }
        
        return manga.copy(
            title = title,
            description = description,
            state = state,
            tags = tags,
            rating = rating,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = "https://${domain.replace("api.", "")}${chapter.url}"
        val doc = webClient.httpGet(fullUrl).parseHtml()
        
        return doc.select("div.manga-page img[src], div.manga-page img[data-src]").mapIndexed { index, img ->
            val imageUrl = img.attr("src").ifEmpty { img.attr("data-src") }
            
            MangaPage(
                id = generateUid("${chapter.id}-$index"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseDate(dateText: String?): Long {
        if (dateText.isNullOrEmpty()) return 0L
        
        return try {
            val formats = listOf(
                SimpleDateFormat("dd/MM/yyyy", Locale.US),
                SimpleDateFormat("yyyy-MM-dd", Locale.US),
            )
            
            for (format in formats) {
                try {
                    return format.parse(dateText)?.time ?: 0L
                } catch (_: Exception) {
                    continue
                }
            }
            0L
        } catch (e: Exception) {
            0L
        }
    }
}
