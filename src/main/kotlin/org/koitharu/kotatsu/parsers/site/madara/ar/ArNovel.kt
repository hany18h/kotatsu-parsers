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

@MangaSourceParser("ARNOVEL", "Ar Novel", "ar", ContentType.OTHER)
internal class ArNovel(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ARNOVEL, "ar-no.com") {

	override val listUrl = "novels/"
	override val tagPrefix = "novel-genre/"
	override val datePattern = "MMMM d, yyyy"
	override val stylePage = ""

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val cleanUrl = chapter.url
			.replace("?style=list", "")
			.replace("&style=list", "")
			.toAbsoluteUrl(domain)

		val doc = webClient.httpGet(cleanUrl).parseHtml()

		val content = doc.selectFirst("div.text-left") ?: return null

		// إزالة العناصر المخفية (حماية الموقع من السرقة)
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
