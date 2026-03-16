package org.koitharu.kotatsu.parsers.site.madara.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.nio.charset.StandardCharsets

@MangaSourceParser("FADAA_ALRIWAYAT", "فضاء الروايات", "ar", ContentType.NOVEL)
internal class FadaaAlriwayat(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.FADAA_ALRIWAYAT, "cenele.com") {

    override val listUrl = "cont/"
    override val tagPrefix = "cont-genre/"
    override val datePattern = "MMMM d, yyyy"

    // الموقع يستخدم AJAX لتحميل الفصول
    override val postReq = true

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val content = getChapterContent(chapter) ?: return emptyList()
        return listOf(
            MangaPage(
                id = generateUid(chapter.url),
                url = content.html.toDataUrl(),
                preview = null,
                source = source,
            ),
        )
    }

    override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val chapterTitle = doc.selectFirst("h3.chapter-name")?.text()?.trim()
            ?: chapter.title ?: ""

        // إزالة العناصر غير المرغوبة
        doc.select(
            "span[aria-hidden=true], p[aria-hidden=true], " +
                "div.chapter-warning, " +
                "div.nhv-support-box, div.nhv-support-divider, " +
                "div.nhv-reading-topbar, div.sidebar-tools, " +
                "script, style, iframe"
        ).remove()

        val contentDiv = doc.selectFirst("div.text-left, div.reading-content") ?: return null

        val paragraphs = contentDiv.select("p")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        if (paragraphs.isEmpty()) return null

        return NovelChapterContent(
            html = buildChapterHtml(chapterTitle, paragraphs),
            images = emptyList(),
        )
    }

    private fun buildChapterHtml(title: String, paragraphs: List<String>): String {
        return buildString {
            append("<!DOCTYPE html><html dir=\"rtl\"><head>")
            append("<meta charset=\"utf-8\"/>")
            append("<style>")
            append("body{font-family:'Amiri','Traditional Arabic',serif;")
            append("padding:20px 24px;line-height:2.1;font-size:1.1rem;")
            append("background:#fff;color:#111;direction:rtl;text-align:right;}")
            append("h1{font-size:1.3rem;border-bottom:1px solid #ddd;")
            append("padding-bottom:8px;margin-bottom:20px;}")
            append("p{margin-bottom:1.3rem;}")
            append("</style></head><body>")
            if (title.isNotBlank()) append("<h1>$title</h1>")
            paragraphs.forEach { para ->
                append("<p>$para</p>")
            }
            append("</body></html>")
        }
    }

    private fun String.toDataUrl(): String {
        val encoded = context.encodeBase64(toByteArray(StandardCharsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }
}
