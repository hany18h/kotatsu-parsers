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

    override val configKeyDomain = ConfigKey.Domain("mangatek.com")

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
            append(domain)
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
            if (link.isEmpty()) return@mapNotNull null
            
            val slug = link.removePrefix("/manga/")
            
            val title = card.selectFirst("h3")?.text()?.trim()
            if (title.isNullOrEmpty()) return@mapNotNull null
            
            val ratingElement = card.selectFirst("span:has(i.fa-star) > span:not(:has(i))")
            val rating = ratingElement?.text()?.toFloatOrNull()?.div(10) ?: RATING_UNKNOWN
            
            Manga(
                id = generateUid(slug),
                url = slug,
                publicUrl = "https://$domain$link",
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
        val url = "https://$domain/manga/${manga.url}"
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
        
        // ===== الإصلاح: جلب جميع الفصول من كل الصفحات =====
        val chapters = fetchAllChapters(manga.url)
        
        return manga.copy(
            title = title,
            description = description,
            state = state,
            tags = tags,
            rating = rating,
            chapters = chapters,
        )
    }

    /**
     * جلب جميع الفصول من كل الصفحات
     * يتعامل مع الـ pagination الخاص بالموقع
     */
    private suspend fun fetchAllChapters(mangaSlug: String): List<MangaChapter> {
        val allChapters = mutableListOf<MangaChapter>()
        var currentPage = 1
        var hasMorePages = true
        
        while (hasMorePages) {
            val pageUrl = "https://$domain/manga/$mangaSlug?page=$currentPage"
            val doc = webClient.httpGet(pageUrl).parseHtml()
            
            // استخراج الفصول من الصفحة الحالية
            val chapterElements = doc.select("div.manga-chapter a, div.grid a[href^='/reader/']")
            
            if (chapterElements.isEmpty()) {
                hasMorePages = false
                break
            }
            
            chapterElements.forEach { element ->
                val chapterUrl = element.attr("href")
                if (chapterUrl.isEmpty()) return@forEach
                
                val chapterTitle = element.selectFirst("h3")?.text() ?: "Chapter"
                
                // استخراج رقم الفصل من العنوان مثل "الفصل 240" أو "Chapter 240"
                val chapterNumber = chapterTitle
                    .replace(Regex("[^0-9.]"), "")
                    .toFloatOrNull() 
                    ?: allChapters.size.toFloat() + 1f
                
                // استخراج التاريخ
                val dateText = element.selectFirst("span:has(i.fa-calendar-alt)")?.text()
                    ?: element.selectFirst("p.text-sm")?.text()
                
                val uploadDate = parseDate(dateText)
                
                allChapters.add(
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
                )
            }
            
            // التحقق من وجود صفحة تالية
            // نفترض أن الموقع يعرض أقصى عدد محدد من الفصول في كل صفحة
            // إذا كانت عدد الفصول في الصفحة الحالية أقل من الحد الأقصى، فهذا يعني أننا وصلنا للنهاية
            val chaptersPerPage = 50 // قد تحتاج لتعديل هذا الرقم حسب الموقع
            
            if (chapterElements.size < chaptersPerPage) {
                hasMorePages = false
            } else {
                currentPage++
                
                // حماية من اللوب اللانهائي (حد أقصى 100 صفحة)
                if (currentPage > 100) {
                    hasMorePages = false
                }
            }
        }
        
        // ترتيب الفصول من الأحدث للأقدم (أو العكس حسب الموقع)
        return allChapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = "https://$domain${chapter.url}"
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
