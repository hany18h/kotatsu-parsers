package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.NovelChapterContent
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl

@MangaSourceParser("CENELE", "فضاء الروايات", "ar", ContentType.OTHER)
internal class Cenele(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.CENELE, "cenele.com") {

	override val listUrl = "novel/"
	override val tagPrefix = "novel-genre/"
	override val datePattern = "MMMM d, yyyy"

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		val content = doc.selectFirst("div.text-left") ?: return null

		// إزالة العناصر المخفية التي يضيفها الموقع لحماية المحتوى من السرقة
		// هذه العناصر تحتوي نصوص مشوهة وتُفسد المحتوى الحقيقي
		content.select(
			"span[aria-hidden=true], " +
				"span[role=presentation], " +
				"p[aria-hidden=true], " +
				"p[role=presentation], " +
				"script, style, ins, iframe, noscript, " +
				".adsbygoogle, .google-auto-placed, " +
				"[id^=ezoic], [id^=pf-], [id^=bg-ssp]",
		).remove()

		// إزالة الـ input المخفية (url الفصل)
		content.select("input[type=hidden]").remove()

		// إزالة الفقرات الفارغة أو التي تحتوي مسافات فقط
		content.select("p").forEach { p ->
			val text = p.text().trim()
			if (text.isEmpty() || text == "\u00a0") p.remove()
		}

		// عنوان الفصل من h3.chapter-name (داخل div#chapter-XXXXX)
		val title = doc.selectFirst("h3.chapter-name")?.text()?.trim()
			?: chapter.title
			?: ""

		return NovelChapterContent(
			html = buildString {
				if (title.isNotBlank()) {
					append("<h1>")
					append(title)
					append("</h1>")
				}
				append(content.html())
			},
			images = emptyList(),
		)
	}
}
