package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("MANGAPRO", "MangaPro", "ar")
internal class MangaPro(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANGAPRO, "prochan.pro", pageSize = 20, searchPageSize = 10) {
    
    override val listUrl = "/series"
    
    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )
    
    // Override الـ selectors للموقع الجديد
    override val selectMangaList = "div.grid a[href^='/series/']"
    
    override fun parseMangaList(docs: Document): List<Manga> {
        return docs.select(selectMangaList).mapNotNull { a ->
            val href = a.attr("href")
            if (href.isNullOrEmpty()) return@mapNotNull null
            
            val relativeUrl = href.toRelativeUrl(domain)
            
            // استخراج العنوان من h3
            val title = a.selectFirst("h3")?.text() ?: return@mapNotNull null
            
            // استخراج الصورة - نأخذ الصورة الأولى (mobile أو desktop)
            val coverUrl = a.selectFirst("img")?.attr("src")
            
            Manga(
                id = generateUid(relativeUrl),
                url = relativeUrl,
                title = title,
                altTitles = emptySet(),
                publicUrl = a.absUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = coverUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }
    
    // Override getDetails لأن البنية الجديدة مختلفة تمامًا
    override suspend fun getDetails(manga: Manga): Manga {
        val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        // الموقع الجديد قد يستخدم JSON أو structure مختلف
        // هذا fallback بسيط - قد تحتاج لتعديله حسب صفحة التفاصيل الفعلية
        
        val title = docs.selectFirst("h1")?.text() ?: manga.title
        val description = docs.selectFirst("p.text-sm, div.description")?.text()
        
        // محاولة استخراج الفصول إذا كانت موجودة
        val chapters = docs.select("a[href*='/chapter/'], a[href*='/ch/']").mapIndexed { index, element ->
            val url = element.attrAsRelativeUrl("href")
            MangaChapter(
                id = generateUid(url),
                title = element.text(),
                url = url,
                number = (index + 1).toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }.reversed()
        
        return manga.copy(
            title = title,
            description = description,
            chapters = chapters,
        )
    }
}
