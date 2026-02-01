package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONArray
import org.json.JSONObject
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
        
        // إزالة العناصر المزعجة
        cleanDocument(doc)
        
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
        
        // إزالة العناصر المزعجة
        cleanDocument(doc)
        
        val title = doc.selectFirst("h1")?.text() ?: manga.title
        
        // تحسين استخراج الوصف مع تنظيفه من الرسائل غير المرغوب فيها
        val description = extractCleanDescription(doc)
        
        val statusText = doc.selectFirst("span.border")?.text()
        val state = when {
            statusText?.contains("مستمر") == true -> MangaState.ONGOING
            statusText?.contains("مكتمل") == true -> MangaState.FINISHED
            statusText?.contains("متوقف") == true -> MangaState.PAUSED
            else -> null
        }
        
        val tags = doc.select("div.flex.gap-2 span.text-gray-300").mapNotNullToSet { tag ->
            val tagName = tag.text().trim()
            if (tagName.isEmpty()) return@mapNotNullToSet null
            MangaTag(
                key = tagName,
                title = tagName,
                source = source
            )
        }
        
        val ratingText = doc.selectFirst("span:has(i.fa-star)")?.text()
        val rating = ratingText?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull()?.div(10) ?: manga.rating
        
        val chapters = fetchChaptersFromApi(manga.url)
        
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
     * تنظيف الوثيقة من العناصر المزعجة
     */
    private fun cleanDocument(doc: org.jsoup.nodes.Document) {
        // إزالة إشعارات مانع الإعلانات
        doc.select("[class*='adblock'], [id*='adblock'], [class*='ad-block'], [id*='ad-block']").remove()
        
        // إزالة الإشعارات والتنبيهات
        doc.select(".alert, .notice, .warning, .notification").remove()
        
        // إزالة overlays و modals
        doc.select(".overlay, .modal, .popup, [class*='overlay'], [id*='overlay']").remove()
        
        // إزالة رسائل التحذير الشائعة
        doc.select("div:contains(مانع الإعلانات), div:contains(ad blocker), div:contains(AdBlock)").remove()
        doc.select("div:contains(قم بتعطيل), div:contains(Please disable), div:contains(turn off)").remove()
        
        // إزالة الإعلانات
        doc.select(".ad, .ads, .advertisement, [class*='ad-'], [id*='ad-']").remove()
        
        // إزالة scripts غير ضرورية
        doc.select("script:not([src])").remove()
    }

    /**
     * استخراج وصف نظيف بدون رسائل مزعجة
     */
    private fun extractCleanDescription(doc: org.jsoup.nodes.Document): String? {
        val descriptionElement = doc.selectFirst("div.grid p, p.text-gray-300, div.description, div.synopsis")
        
        if (descriptionElement != null) {
            var description = descriptionElement.text().trim()
            
            // إزالة الجمل المتعلقة بمانع الإعلانات والرسائل المزعجة
            val unwantedPhrases = listOf(
                "يرجى تعطيل مانع الإعلانات",
                "قم بإيقاف مانع الإعلانات",
                "Please disable",
                "AdBlock",
                "ad blocker",
                "turn off your ad blocker",
                "disable your adblocker",
                "يبدو أنك تستخدم",
                "نرجو منك",
                "للمتابعة",
                "to continue"
            )
            
            for (phrase in unwantedPhrases) {
                // إزالة الجملة الكاملة التي تحتوي على العبارة
                val regex = Regex("[^.!?]*$phrase[^.!?]*[.!?]?", RegexOption.IGNORE_CASE)
                description = description.replace(regex, "")
            }
            
            // تنظيف المسافات الزائدة
            description = description.replace(Regex("\\s+"), " ").trim()
            
            return if (description.isNotEmpty()) description else null
        }
        
        return null
    }

    /**
     * جلب الفصول من API الخاص بالموقع
     */
    private suspend fun fetchChaptersFromApi(mangaSlug: String): List<MangaChapter> {
        val pageUrl = "https://$domain/manga/$mangaSlug"
        val doc = webClient.httpGet(pageUrl).parseHtml()
        
        // تنظيف الوثيقة
        cleanDocument(doc)
        
        val scriptContent = doc.select("astro-island[component-url*='MangaChaptersLoader']")
            .attr("props")
        
        if (scriptContent.isEmpty()) {
            return parseChaptersFromHtml(doc)
        }
        
        return try {
            val chapters = mutableListOf<MangaChapter>()
            
            // استخراج الفصول من JSON
            val chapterPattern = """"chapter_number"\s*:\s*\[0,\s*"([^"]+)"\]""".toRegex()
            val chapterMatches = chapterPattern.findAll(scriptContent)
            
            chapterMatches.forEach { match ->
                val chapterNum = match.groupValues[1]
                
                chapters.add(
                    MangaChapter(
                        id = generateUid("$mangaSlug-$chapterNum"),
                        title = "الفصل $chapterNum",
                        number = chapterNum.toFloatOrNull() ?: 0f,
                        volume = 0,
                        url = "/reader/$mangaSlug/$chapterNum",
                        uploadDate = 0L,
                        source = source,
                        scanlator = null,
                        branch = null,
                    )
                )
            }
            
            chapters.reversed()
        } catch (e: Exception) {
            parseChaptersFromHtml(doc)
        }
    }

    /**
     * Fallback: تحليل الفصول من HTML مباشرة
     */
    private fun parseChaptersFromHtml(doc: org.jsoup.nodes.Document): List<MangaChapter> {
        return doc.select("div.manga-chapter a, div.grid a[href^='/reader/']").mapNotNull { element ->
            val chapterUrl = element.attr("href")
            if (chapterUrl.isEmpty()) return@mapNotNull null
            
            val chapterTitle = element.selectFirst("h3")?.text() ?: "Chapter"
            val chapterNumber = chapterTitle
                .replace(Regex("[^0-9.]"), "")
                .toFloatOrNull() ?: 0f
            
            val dateText = element.selectFirst("span:has(i.fa-calendar-alt)")?.text()
                ?: element.selectFirst("p.text-sm")?.text()
            
            MangaChapter(
                id = generateUid(chapterUrl),
                title = chapterTitle,
                number = chapterNumber,
                volume = 0,
                url = chapterUrl,
                uploadDate = parseDate(dateText),
                source = source,
                scanlator = null,
                branch = null,
            )
        }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = "https://$domain${chapter.url}"
        val doc = webClient.httpGet(fullUrl).parseHtml()
        
        // تنظيف صفحة القراءة من العناصر المزعجة
        cleanDocument(doc)
        
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
