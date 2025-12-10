package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGAPRO", "MangaPro", "ar")
internal class MangaPro(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANGAPRO, "prochan.net", pageSize = 20, searchPageSize = 10) {
    
    override val listUrl = "/series"
    
    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )
    
    // تجاوز دالة getDetails لأن البنية مختلفة تماماً
    override suspend fun getDetails(manga: Manga): Manga {
        val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        val title = docs.selectFirst("h1")?.text() ?: manga.title
        val description = docs.selectFirst("p.whitespace-pre-line")?.text()
        
        // استخراج الفصول من الروابط
        val chapters = docs.select("a[href*='/series/'][href*='/'][class*='flex']")
            .mapNotNull { link ->
                val href = link.attr("href")
                if (!href.contains("/series/")) return@mapNotNull null
                
                // التحقق من تنسيق URL: /series/manga/552/slug/31017/7
                val urlParts = href.split("/").filter { it.isNotEmpty() }
                if (urlParts.size < 5) return@mapNotNull null
                
                // استخراج عنوان الفصل
                val chapterTitle = link.selectFirst("span.break-words")?.text()
                    ?: link.selectFirst("span.font-semibold")?.text()
                    ?: return@mapNotNull null
                
                // استخراج رقم الفصل من العنوان أو من URL
                val chapterNumber = Regex("\\d+(\\.\\d+)?").findAll(chapterTitle)
                    .lastOrNull()?.value?.toFloatOrNull() 
                    ?: urlParts.lastOrNull()?.toFloatOrNull() 
                    ?: 0f
                
                MangaChapter(
                    id = generateUid(href),
                    title = chapterTitle,
                    url = href,
                    number = chapterNumber,
                    volume = 0,
                    scanlator = null,
                    uploadDate = 0,
                    branch = null,
                    source = source,
                )
            }
            .distinctBy { it.url }
            .sortedByDescending { it.number }
        
        return manga.copy(
            title = title,
            description = description,
            chapters = chapters,
        )
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
    val docs = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
    val pages = mutableListOf<MangaPage>()
    
    // استخراج جميع الصور من container الرئيسي
    val imageContainer = docs.selectFirst("div.flex.flex-col") 
        ?: docs.selectFirst("div[class*='flex'][class*='flex-col']")
    
    if (imageContainer != null) {
        // البحث عن جميع img tags داخل الـ container
        imageContainer.select("img[src]").forEach { img ->
            val src = img.attr("src")
            if (src.isNotEmpty() && (
                src.contains("app.prochan.net") || 
                src.contains("cdn1.prochan.net") ||
                src.contains("cdn2.prochan.net") ||
                src.contains("cdn3.prochan.net")
            )) {
                val fullUrl = if (src.startsWith("http")) src else "https:$src"
                pages.add(
                    MangaPage(
                        id = generateUid(fullUrl),
                        url = fullUrl,
                        preview = null,
                        source = source,
                    )
                )
            }
        }
    }
    
    // Fallback: إذا لم نجد صور، نبحث في كامل الصفحة
    if (pages.isEmpty()) {
        docs.select("img[src*='prochan.net']").forEach { img ->
            val src = img.attr("src")
            if (src.isNotEmpty() && !src.contains("series-cards")) { // تجنب صور الكفر
                val fullUrl = if (src.startsWith("http")) src else "https:$src"
                pages.add(
                    MangaPage(
                        id = generateUid(fullUrl),
                        url = fullUrl,
                        preview = null,
                        source = source,
                    )
                )
            }
        }
    }
    
    // إزالة التكرار
    return pages.distinctBy { it.url.substringBefore("?") }
}

    }
