package org.koitharu.kotatsu.parsers.site.zeistmanga.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
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

	override val selectTags = "div.chaptertags a, article div.mt-15 a"
	
	// تحديث selector الصفحات لدعم data-src و src
	override val selectPage = "div.check-box img"

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		return doc.selectFirstOrThrow("#PageList1").select("ul li a").mapToSet {
			MangaTag(
				key = it.attr("href").substringAfterLast('/').substringBefore('?'),
				title = it.text(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		
		val state = doc.selectFirst("div.y6x11p:contains(الحالة) .dt") 
			?: doc.selectFirst("div.y6x11p:contains(Status) .dt")
		val mangaState = state?.text()?.lowercase().let {
			when (it) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				in abandoned -> MangaState.ABANDONED
				in paused -> MangaState.PAUSED
				else -> null
			}
		}
		
		val author = doc.selectFirst("div.y6x11p:contains(الكاتب) .dt") 
			?: doc.selectFirst("div.y6x11p:contains(Author) .dt")
		
		val description = doc.select("div.max-w.pen p").firstOrNull { p ->
			val text = p.text()
			text.isNotEmpty() && 
			!text.contains("الفصل") && 
			!text.contains("Chapter") &&
			!text.contains("إذا كانت") &&
			text.length > 50
		}?.text() ?: ""
		
		val chaptersDeferred = async { loadChapters(manga.url, doc) }
		
		manga.copy(
			authors = author?.text()?.let { setOf(it) } ?: emptySet(),
			tags = doc.select(selectTags).mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast("label/").substringBefore("?"),
					title = a.text().toTitleCase(),
					source = source,
				)
			},
			description = description,
			state = mangaState,
			chapters = chaptersDeferred.await(),
		)
	}

	override suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
		val seriesName = doc.selectFirst("h1.m-0 a[rel='tag']")?.text()
			?: doc.selectFirst("ol[itemtype*=BreadcrumbList] li:nth-child(2) a span")?.text()
			?: doc.selectFirst("div.tac a[rel='tag']")?.text()
			?: doc.selectFirst("h1 a[data]")?.attr("data")
			?: run {
				val breadcrumbLinks = doc.select("ol[itemtype*=BreadcrumbList] li a")
				if (breadcrumbLinks.size >= 2) {
					breadcrumbLinks[1].text()
				} else {
					doc.parseFailed("Could not find series name")
				}
			}

		val url = buildString {
			append("https://")
			append(domain)
			append("/feeds/posts/default/-/")
			append(seriesName.urlEncoded())
			append("?alt=json&orderby=published&max-results=9999")
		}
		
		val raw = webClient.httpGet(url).parseRaw()

		// لو الرد مش JSON (يعني صفحة Cloudflare/challenge أو أي HTML تاني) رجّع قايمة فاضية بدل الكراش
		if (raw.isBlank() || !raw.trimStart().startsWith("{")) {
			return emptyList()
		}

		val json = JSONObject(raw).getJSONObject("feed")

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
			
			val slug = mangaUrl.substringAfterLast('/').substringBefore('?').substringBefore(".html")
			val slugChapter = href.substringAfterLast('/').substringBefore('?').substringBefore(".html")
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

	// Override getPages لدعم data-src
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		
		return doc.select(selectPage).mapNotNull { img ->
			// محاولة الحصول على الصورة من data-src أولاً، ثم src
			val url = img.attr("data-src").ifEmpty { 
				img.attr("src") 
			}
			
			if (url.isNotEmpty() && url != "data:image/png;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs=") {
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			} else {
				null
			}
		}
	}
}
