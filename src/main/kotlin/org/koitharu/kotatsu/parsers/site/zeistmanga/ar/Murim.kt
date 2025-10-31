package org.koitharu.kotatsu.parsers.site.zeistmanga.ar

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import java.text.SimpleDateFormat

@MangaSourceParser("MURIM", "Murim", "ar")
internal class Murim(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.MURIM, "www.murim.site") {
	
	override val sateOngoing: String = "مستمرة"
	override val sateFinished: String = "مكتمل"
	override val sateAbandoned: String = "متروك"

	override val selectTags = "div.tac a[data], .post-share + div a[rel='tag']"
	
	override val selectPage = "div.check-box img"

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		// الأقسام موجودة في PageList1
		return doc.selectFirstOrThrow("#PageList1").select("ul li a").mapToSet {
			MangaTag(
				key = it.attr("href").substringAfterLast('/').substringBefore('?'),
				title = it.text(),
				source = source,
			)
		}
	}

	override suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
		// البحث عن اسم السلسلة من العنوان
		val seriesName = doc.selectFirst("h1 a[data]")?.attr("data") 
			?: doc.selectFirst("div.tac a[data]")?.attr("data")
			?: doc.parseFailed("Could not find series name")

		val url = buildString {
			append("https://")
			append(domain)
			append("/feeds/posts/default/-/")
			append(seriesName.urlEncoded())
			append("?alt=json&orderby=published&max-results=9999")
		}
		
		val json = webClient.httpGet(url).parseJson().getJSONObject("feed")
		
		if (!json.toString().contains("\"entry\":")) {
			return emptyList()
		}
		
		val entries = json.getJSONArray("entry").asTypedList<JSONObject>().reversed()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		
		return entries.mapIndexedNotNull { i, j ->
			val name = j.getJSONObject("title").getString("\$t")
			val href = j.getJSONArray("link").asTypedList<JSONObject>()
				.first { it.getString("rel") == "alternate" }
				.getString("href")
			val dateText = j.getJSONObject("published").getString("\$t").substringBefore("T")
			
			// تجاهل صفحة المانجا الرئيسية
			val slug = mangaUrl.substringAfterLast('/')
			val slugChapter = href.substringAfterLast('/')
			if (slug == slugChapter) {
				return@mapIndexedNotNull null
			}
			
			MangaChapter(
				id = generateUid(href),
				url = href,
				title = name,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = dateFormat.parseSafe(dateText),
				scanlator = null,
				source = source,
			)
		}
	}
}
