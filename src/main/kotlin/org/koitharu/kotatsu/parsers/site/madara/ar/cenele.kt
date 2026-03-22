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

	// MadaraParser يضيف "?style=list" لكل رابط فصل
	// هذا يُغيّر طريقة عرض الصفحة ويُفسد قراءة المحتوى النصي
	// نُفرّغه حتى يبقى URL الفصل نظيفاً
	override val stylePage = ""

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		val content = doc.selectFirst("div.text-left") ?: return null

		// إزالة العناصر المخفية التي يضيفها الموقع لحماية المحتوى من السرقة
		// (spans وparagraphs مع aria-hidden أو role=presentation تحتوي نصوص مشوهة)
		content.select(
			"span[aria-hidden=true], " +
				"span[role=presentation], " +
				"p[aria-hidden=true], " +
				"p[role=presentation], " +
				"input[type=hidden], " +
				"script, style, ins, iframe, noscript, " +
				".adsbygoogle, .google-auto-placed, " +
				"[id^=ezoic], [id^=pf-], [id^=bg-ssp]",
		).remove()

		// إزالة الفقرات الفارغة بعد تنظيف العناصر المخفية
		content.select("p").forEach { p ->
			if (p.text().trim().isEmpty()) p.remove()
		}

		val title = doc.selectFirst("h3.chapter-name")?.text()?.trim()
			?: chapter.title
			?: ""

		return NovelChapterContent(
			html = buildString {
				if (title.isNotBlank()) append("<h1>$title</h1>")
				append(content.html())
			},
			images = emptyList(),
		)
	}
}
