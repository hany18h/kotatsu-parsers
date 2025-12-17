package org.koitharu.kotatsu.parsers.site.madara.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGATUK", "Mangatuk", "ar")
internal class Mangatuk(context: MangaLoaderContext) : 
    MadaraParser(context, MangaParserSource.MANGATUK, "mangatuk.com", pageSize = 20) {

    // 🔓 فتح الفصول المدفوعة - تجاوز نظام العملات للتطبيق
    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val chapters = super.getChapters(manga, doc)
        
        // إصلاح روابط الفصول المقفلة (التي تحتوي على #)
        return chapters.map { chapter ->
            if (chapter.url.contains("#") || chapter.url.endsWith("#")) {
                // استخراج رقم الفصل من العنوان
                val chapterNumber = chapter.title.trim()
                val fixedUrl = "${manga.url}$chapterNumber/$stylePage"
                chapter.copy(url = fixedUrl)
            } else {
                chapter
            }
        }
    }

    // 🔓 فتح الفصول المدفوعة - طريقة بديلة عند استخدام Ajax
    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
        val chapters = super.loadChapters(mangaUrl, document)
        
        return chapters.map { chapter ->
            if (chapter.url?.contains("#") == true || chapter.url?.endsWith("#") == true) {
                val chapterNumber = chapter.title.trim()
                val fixedUrl = "$mangaUrl$chapterNumber/$stylePage"
                chapter.copy(url = fixedUrl)
            } else {
                chapter
            }
        }
    }

    // 🎯 استخراج الصور من CSS background-image
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        
        // محاولة 1: استخراج من JavaScript array
        val pages = extractPagesFromJavaScript(doc)
        if (pages.isNotEmpty()) return pages
        
        // محاولة 2: استخراج من CSS <style> tag
        val cssPages = extractPagesFromCSS(doc)
        if (cssPages.isNotEmpty()) return cssPages
        
        // محاولة 3: استخراج من inline style attributes
        val inlinePages = extractPagesFromInlineStyle(doc)
        if (inlinePages.isNotEmpty()) return inlinePages
        
        // محاولة 4: الطريقة الافتراضية (img tags)
        return try {
            super.getPages(chapter)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // استخراج من JavaScript: var images = [...]
    private fun extractPagesFromJavaScript(doc: Document): List<MangaPage> {
        val pages = mutableListOf<MangaPage>()
        
        doc.select("script").forEach { script ->
            val content = script.html()
            if (content.contains("var images = [")) {
                // استخراج محتوى المصفوفة
                val regex = """var images = \[(.*?)\];""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(content)
                
                if (match != null) {
                    val imagesArray = match.groupValues[1]
                    // استخراج كل الروابط من المصفوفة
                    val urlRegex = """"(https?://[^"]+)"""".toRegex()
                    
                    urlRegex.findAll(imagesArray).forEach { urlMatch ->
                        val imageUrl = urlMatch.groupValues[1]
                            .replace("\\/", "/") // إصلاح الـ escaped slashes
                        
                        if (isValidImageUrl(imageUrl)) {
                            pages.add(createMangaPage(imageUrl))
                        }
                    }
                }
            }
        }
        
        return pages
    }
    
    // استخراج من CSS <style> tag
    private fun extractPagesFromCSS(doc: Document): List<MangaPage> {
        val pages = mutableListOf<MangaPage>()
        
        doc.select("style").forEach { style ->
            val cssContent = style.html()
            if (cssContent.contains("background-image: url(")) {
                // استخراج جميع background-image URLs
                val regex = """\.manga-page\.image-\d+\s*\{[^}]*background-image:\s*url\(['"]?(.*?)['"]?\)""".toRegex()
                
                regex.findAll(cssContent).forEach { match ->
                    val imageUrl = match.groupValues[1].trim()
                    if (isValidImageUrl(imageUrl)) {
                        pages.add(createMangaPage(imageUrl))
                    }
                }
            }
        }
        
        return pages
    }
    
    // استخراج من inline style attributes
    private fun extractPagesFromInlineStyle(doc: Document): List<MangaPage> {
        val pages = mutableListOf<MangaPage>()
        
        doc.select("div.page-break, div.manga-page").forEach { div ->
            val style = div.attr("style")
            if (style.contains("background-image")) {
                val regex = """background-image:\s*url\(['"]?(.*?)['"]?\)""".toRegex()
                val match = regex.find(style)
                
                if (match != null) {
                    val imageUrl = match.groupValues[1].trim()
                    if (isValidImageUrl(imageUrl)) {
                        pages.add(createMangaPage(imageUrl))
                    }
                }
            }
        }
        
        return pages
    }
    
    // التحقق من صلاحية رابط الصورة
    private fun isValidImageUrl(url: String): Boolean {
        return url.isNotEmpty() 
            && !url.contains("protection-warning")
            && !url.contains("placeholder")
            && (url.startsWith("http://") || url.startsWith("https://"))
    }
    
    // إنشاء MangaPage
    private fun createMangaPage(url: String): MangaPage {
        return MangaPage(
            id = generateUid(url),
            url = url,
            preview = null,
            source = source,
        )
    }
}
