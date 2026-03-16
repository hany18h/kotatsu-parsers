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

    // الموقع لا يدعم المانجا المشابهة — نتجنب الخطأ
    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        // جلب الـ manga ID من الصفحة مباشرة
        val mangaId = doc.selectFirst("div#manga-chapters-holder")?.attr("data-id")
            ?: doc.selectFirst("div.nhv-manga-chapters")?.attr("data-post-id")
            ?: doc.selectFirst("[data-manga-id]")?.attr("data-manga-id")
            ?: ""

        val href = doc.selectFirst("head meta[property='og:url']")
            ?.attr("content")?.toRelativeUrl(domain) ?: manga.url

        val chapters = if (mangaId.isNotEmpty()) {
            loadChaptersWithId(mangaId)
        } else {
            // fallback: جلب الفصول من الصفحة مباشرة
            getChapters(manga, doc)
        }

        val desc = doc.select(selectDesc).html()

        val stateDiv = doc.selectFirst(selectState)
            ?.selectLast("div.summary-content")
            ?: doc.selectFirst("div.manga-status .nhv-meta-value")

        val state = stateDiv?.let {
            when (it.text().lowercase()) {
                in ongoing -> MangaState.ONGOING
                in finished -> MangaState.FINISHED
                in abandoned -> MangaState.ABANDONED
                in paused -> MangaState.PAUSED
                else -> null
            }
        }

        return manga.copy(
            title = doc.selectFirst("h1")?.textOrNull() ?: manga.title,
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            tags = doc.body().select(selectGenre).mapToSet { a ->
                MangaTag(
                    key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
                    title = a.text().toTitleCase(),
                    source = source,
                )
            },
            description = desc,
            state = state,
            chapters = chapters,
            contentRating = ContentRating.SAFE,
        )
    }

    private suspend fun loadChaptersWithId(mangaId: String): List<MangaChapter> {
        val url = "https://$domain/wp-admin/admin-ajax.php"
        val postData = "action=manga_get_chapters&manga=$mangaId"
        val doc = webClient.httpPost(url, postData).parseHtml()
        val dateFormat = java.text.SimpleDateFormat(datePattern, sourceLocale)
        return doc.select(selectChapter).mapChapters(reversed = true) { i, li ->
            val a = li.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val dateText = li.selectFirst(selectDate)?.text()
            val name = a.selectFirst("p")?.text() ?: a.ownText()
            MangaChapter(
                id = generateUid(href),
                url = href + stylePage,
                title = name,
                number = i + 1f,
                volume = 0,
                branch = null,
                uploadDate = parseChapterDate(dateFormat, dateText),
                scanlator = null,
                source = source,
            )
        }
    }

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

        // إزالة العناصر غير المرغوبة والـ watermarks
        doc.select(
            "span[aria-hidden=true], p[aria-hidden=true], " +
                "div.chapter-warning, div.nhv-support-box, " +
                "div.nhv-support-divider, div.nhv-reading-topbar, " +
                "div.sidebar-tools, script, style, iframe"
        ).remove()

        val contentDiv = doc.selectFirst("div.text-left") ?: return null

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
            paragraphs.forEach { append("<p>$it</p>") }
            append("</body></html>")
        }
    }

    private fun String.toDataUrl(): String {
        val encoded = context.encodeBase64(toByteArray(StandardCharsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }
}
