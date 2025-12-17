package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGATUK", "Mangatuk", "ar")
internal class Mangatuk(context: MangaLoaderContext) : 
    MadaraParser(context, MangaParserSource.MANGATUK, "mangatuk.com", pageSize = 20) {

    // 🔓 تجاوز الفصول المدفوعة - جعلها مجانية للجميع في التطبيق
    override suspend fun getDetails(manga: org.koitharu.kotatsu.parsers.model.Manga): org.koitharu.kotatsu.parsers.model.Manga {
        val details = super.getDetails(manga)
        
        // إزالة علامات "premium" من جميع الفصول
        val freeChapters = details.chapters?.map { chapter ->
            // جعل كل الفصول مجانية بإزالة أي علامات premium
            chapter.copy(
                url = if (chapter.url.contains("#")) {
                    // تحويل الروابط المقفلة (#) إلى روابط حقيقية
                    chapter.url.replace("#", "").ifEmpty { 
                        "${manga.url}/${chapter.name}/" 
                    }
                } else {
                    chapter.url
                }
            )
        }
        
        return details.copy(chapters = freeChapters)
    }

    // 🎯 استخراج الصور من CSS background-image
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        
        // التحقق من أن الرابط ليس "#"
        if (fullUrl.contains("#")) {
            // محاولة إصلاح الرابط
            val fixedUrl = fullUrl.replace("#", "")
            if (fixedUrl.isNotEmpty()) {
                return getPagesFromUrl(fixedUrl)
            }
        }
        
        return getPagesFromUrl(fullUrl)
    }
    
    private suspend fun getPagesFromUrl(url: String): List<MangaPage> {
        val doc = webClient.httpGet(url).parseHtml()
        val pages = mutableListOf<MangaPage>()
        
        // 1️⃣ محاولة استخراج من <style> tag
        val styleTag = doc.select("style").firstOrNull { 
            it.html().contains("background-image: url(") 
        }
        
        if (styleTag != null) {
            val cssContent = styleTag.html()
            val regex = """\.manga-page\.image-\d+\s*\{[^}]*background-image:\s*url\(['"]?(.*?)['"]?\)""".toRegex()
            val matches = regex.findAll(cssContent)
            
            matches.forEach { match ->
                val imageUrl = match.groupValues[1].trim()
                if (isValidImageUrl(imageUrl)) {
                    pages.add(createMangaPage(imageUrl))
                }
            }
        }
        
        // 2️⃣ إذا فشلت المحاولة الأولى، جرب من div.page-break
        if (pages.isEmpty()) {
            val readingContent = doc.selectFirst("div.reading-content")
            readingContent?.select("div.page-break")?.forEach { pageDiv ->
                // محاولة من inline style
                val style = pageDiv.attr("style")
                if (style.isNotEmpty()) {
                    val bgRegex = """background-image:\s*url\(['"]?(.*?)['"]?\)""".toRegex()
                    val match = bgRegex.find(style)
                    
                    if (match != null) {
                        val imageUrl = match.groupValues[1].trim()
                        if (isValidImageUrl(imageUrl)) {
                            pages.add(createMangaPage(imageUrl))
                        }
                    }
                }
                
                // محاولة من <img> tag (fallback)
                if (pages.isEmpty()) {
                    val img = pageDiv.selectFirst("img")
                    if (img != null) {
                        val imgUrl = img.src()?.toRelativeUrl(domain) 
                            ?: img.attr("data-src")
                            ?: img.attr("data-lazy-src")
                        
                        if (imgUrl.isNotEmpty() && isValidImageUrl(imgUrl)) {
                            pages.add(createMangaPage(imgUrl))
                        }
                    }
                }
            }
        }
        
        // 3️⃣ الطريقة الأخيرة - استخدام JavaScript array
        if (pages.isEmpty()) {
            val scriptContent = doc.select("script").joinToString("\n") { it.html() }
            if (scriptContent.contains("var images = [")) {
                val jsRegex = """var images = \[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val match = jsRegex.find(scriptContent)
                
                if (match != null) {
                    val imagesArray = match.groupValues[1]
                    val urlRegex = """"(https?://[^"]+)"""".toRegex()
                    
                    urlRegex.findAll(imagesArray).forEach { urlMatch ->
                        val imageUrl = urlMatch.groupValues[1]
                        if (isValidImageUrl(imageUrl)) {
                            pages.add(createMangaPage(imageUrl))
                        }
                    }
                }
            }
        }
        
        // إذا فشلت كل المحاولات
        return pages.ifEmpty {
            super.getPages(chapter)
        }
    }
    
    // ✅ التحقق من أن الرابط صالح وليس صورة حماية
    private fun isValidImageUrl(url: String): Boolean {
        return url.isNotEmpty() 
            && !url.contains("protection-warning")
            && !url.contains("placeholder")
            && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("/"))
    }
    
    // 🔨 إنشاء MangaPage من URL
    private fun createMangaPage(url: String): MangaPage {
        val absoluteUrl = if (url.startsWith("/")) {
            "https://$domain$url"
        } else {
            url
        }
        
        return MangaPage(
            id = generateUid(absoluteUrl),
            url = absoluteUrl,
            preview = null,
            source = source
        )
    }
}
