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
internal class Mangatuk(
    context: MangaLoaderContext
) : MadaraParser(
    context,
    MangaParserSource.MANGATUK,
    "mangatuk.com",
    pageSize = 20
) {

    // فتح الفصول المقفولة
    override suspend fun getChapters(
        manga: Manga,
        doc: Document
    ): List<MangaChapter> {
        val chapters = super.getChapters(manga, doc)

        return chapters.map { chapter ->
            val url = chapter.url ?: return@map chapter

            if (url.contains("#") || url.endsWith("#")) {
                val chapterNumber = chapter.title.trim()
                val fixedUrl = "${manga.url}$chapterNumber/$stylePage"
                chapter.copy(url = fixedUrl)
            } else {
                chapter
            }
        }
    }

    // فتح الفصول المقفولة (Ajax)
    override suspend fun loadChapters(
        mangaUrl: String,
        document: Document
    ): List<MangaChapter> {
        val chapters = super.loadChapters(mangaUrl, document)

        return chapters.map { chapter ->
            val url = chapter.url ?: return@map chapter

            if (url.contains("#") || url.endsWith("#")) {
                val chapterNumber = chapter.title.trim()
                val fixedUrl = "$mangaUrl$chapterNumber/$stylePage"
                chapter.copy(url = fixedUrl)
            } else {
                chapter
            }
        }
    }

    // استخراج الصفحات
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = chapter.url ?: return emptyList()
        val fullUrl = url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val jsPages = extractPagesFromJavaScript(doc)
        if (jsPages.isNotEmpty()) return jsPages

        val cssPages = extractPagesFromCSS(doc)
        if (cssPages.isNotEmpty()) return cssPages

        val inlinePages = extractPagesFromInlineStyle(doc)
        if (inlinePages.isNotEmpty()) return inlinePages

        return try {
            super.getPages(chapter)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractPagesFromJavaScript(doc: Document): List<MangaPage> {
        val pages = mutableListOf<MangaPage>()

        doc.select("script").forEach { script ->
            val content = script.html()
            if (!content.contains("var images = [")) return@forEach

            val regex = """var images = \[(.*?)\];""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(content) ?: return@forEach
            val imagesArray = match.groupValues[1]

            val urlRegex = """"(https?://[^"]+)"""".toRegex()
            urlRegex.findAll(imagesArray).forEach { m ->
                val imageUrl = m.groupValues[1].replace("\\/", "/")
                if (isValidImageUrl(imageUrl)) {
                    pages.add(createMangaPage(imageUrl))
                }
            }
        }

        return pages
    }

    private fun extractPagesFromCSS(doc: Document): List<MangaPage> {
        val pages = mutableListOf<MangaPage>()

        doc.select("style").forEach { style ->
            val css = style.html()
            if (!css.contains("background-image")) return@forEach

            val regex =
                """background-image:\s*url\(['"]?(.*?)['"]?\)""".toRegex()

            regex.findAll(css).forEach { match ->
                val imageUrl = match.groupValues[1].trim()
                if (isValidImageUrl(imageUrl)) {
                    pages.add(createMangaPage(imageUrl))
                }
            }
        }

        return pages
    }

    private fun extractPagesFromInlineStyle(doc: Document): List<MangaPage> {
        val pages = mutableListOf<MangaPage>()

        doc.select("div.page-break, div.manga-page").forEach { div ->
            val style = div.attr("style")
            if (!style.contains("background-image")) return@forEach

            val regex =
                """background-image:\s*url\(['"]?(.*?)['"]?\)""".toRegex()
            val match = regex.find(style) ?: return@forEach

            val imageUrl = match.groupValues[1].trim()
            if (isValidImageUrl(imageUrl)) {
                pages.add(createMangaPage(imageUrl))
            }
        }

        return pages
    }

    private fun isValidImageUrl(url: String): Boolean {
        return url.isNotEmpty()
            && !url.contains("protection-warning")
            && !url.contains("placeholder")
            && (url.startsWith("http://") || url.startsWith("https://"))
    }

    private fun createMangaPage(url: String): MangaPage {
        return MangaPage(
            id = generateUid(url),
            url = url,
            preview = null,
            source = source
        )
    }
}
