package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("CENELE", "فضاء الروايات", "ar", ContentType.NOVEL)
internal class Cenele(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CENELE, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("cenele.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val orderParam = when (order) {
			SortOrder.UPDATED      -> "latest"
			SortOrder.POPULARITY   -> "views"
			SortOrder.ALPHABETICAL -> "alphabet"
			SortOrder.NEWEST       -> "new-manga"
			else                   -> "latest"
		}

		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/?s=")
					append(filter.query.urlEncoded())
					append("&post_type=wp-manga&page=")
					append(page)
				}
				filter.tags.isNotEmpty() -> {
					val tag = filter.tags.oneOrThrowIfMany()!!
					append("/novel-genre/")
					append(tag.key)
					append("/page/")
					append(page)
					append("/?m_orderby=")
					append(orderParam)
					filter.states.oneOrThrowIfMany()?.let {
						append("&status=")
						append(if (it == MangaState.FINISHED) "end" else "ongoing")
					}
				}
				else -> {
					append("/novel/page/")
					append(page)
					append("/?m_orderby=")
					append(orderParam)
					filter.states.oneOrThrowIfMany()?.let {
						append("&status=")
						append(if (it == MangaState.FINISHED) "end" else "ongoing")
					}
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select(".page-item-detail.manga").map { item ->
			val a = item.selectFirstOrThrow("h3.h5 a")
			val href = a.attrAsRelativeUrl("href")
			val img = item.selectFirst("img")
			Manga(
				id = generateUid(href),
				title = a.text().trim(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = img?.src(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/novel/").parseHtml()
		return doc.select(".menu-item-object-novel-genre a, .genres-list a, .genre-list a")
			.mapNotNullToSet { a ->
				val key = a.attr("href").removeSuffix("/").substringAfterLast("/")
				if (key.isEmpty()) return@mapNotNullToSet null
				MangaTag(
					key = key,
					title = a.text().trim(),
					source = source,
				)
			}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val statusText = doc.selectFirst(".post-status .summary-content")?.text()?.trim().orEmpty()
		val state = when {
			statusText.contains("مستمر", ignoreCase = true) ||
				statusText.contains("OnGoing", ignoreCase = true) -> MangaState.ONGOING
			statusText.contains("مكتمل", ignoreCase = true) ||
				statusText.contains("Completed", ignoreCase = true) ||
				statusText.contains("End", ignoreCase = true) -> MangaState.FINISHED
			else -> null
		}

		val authors = doc.select(".author-content a")
			.mapNotNullToSet { it.text().trim().ifEmpty { null } }

		val tags = doc.select(".genres-content a").mapToSet { a ->
			MangaTag(
				key = a.attr("href").removeSuffix("/").substringAfterLast("/"),
				title = a.text().trim(),
				source = source,
			)
		}

		val rating = doc.selectFirst(".total_votes")
			?.attr("data-score")?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN

		val coverUrl = doc.selectFirst(".summary_image img")?.src() ?: manga.coverUrl
		val altTitle = doc.selectFirst("div.manga-name-or")?.text()?.trim()

		return manga.copy(
			title = doc.selectFirst("div.post-title h1")?.text()?.trim() ?: manga.title,
			altTitles = setOfNotNull(altTitle?.ifEmpty { null }),
			description = doc.selectFirst("div.summary__content")?.html(),
			coverUrl = coverUrl,
			largeCoverUrl = coverUrl,
			rating = rating,
			state = state,
			authors = authors,
			tags = tags,
			chapters = parseChapters(doc),
		)
	}

	private suspend fun parseChapters(doc: Document): List<MangaChapter> {
		// Try inline chapter list first (some Madara sites embed it directly)
		val inlineItems = doc.select("ul.main li.wp-manga-chapter")
		if (inlineItems.isNotEmpty()) {
			return parseChapterElements(inlineItems)
		}

		// Fallback: load via AJAX (standard Madara behaviour)
		val mangaId = doc.selectFirst("#manga-chapters-holder")?.attr("data-id")
			?: return emptyList()
		val ajaxDoc = webClient.httpPost(
			"https://$domain/wp-admin/admin-ajax.php",
			mapOf(
				"action" to "manga_get_chapters",
				"manga"  to mangaId,
			),
		).parseHtml()
		return parseChapterElements(ajaxDoc.select("ul.main li.wp-manga-chapter"))
	}

	private fun parseChapterElements(elements: org.jsoup.select.Elements): List<MangaChapter> {
		val dateFormat    = SimpleDateFormat("MMMM dd, yyyy", Locale("ar"))
		val dateFormatEn  = SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH)
		val dateFormatShort = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)

		return elements.mapIndexedNotNull { index, li ->
			val a = li.selectFirst("a") ?: return@mapIndexedNotNull null
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapIndexedNotNull null
			val chapterName = a.text().trim()
			val dateText = li.selectFirst(".chapter-release-date i, .chapter-release-date")
				?.text()?.trim().orEmpty()

			val number = Regex("""(\d+\.?\d*)""").find(chapterName)
				?.value?.toFloatOrNull()
				?: (elements.size - index).toFloat()

			val uploadDate =
				runCatching { dateFormat.parse(dateText)?.time }.getOrNull()
					?: runCatching { dateFormatEn.parse(dateText)?.time }.getOrNull()
					?: runCatching { dateFormatShort.parse(dateText)?.time }.getOrNull()
					?: 0L

			MangaChapter(
				id = generateUid(href),
				title = chapterName,
				number = number,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}.reversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		// Madara novel reading page — try selectors in priority order
		val contentElement =
			doc.selectFirst("div.text-left")
				?: doc.selectFirst("div.epcontent.entry-content")
				?: doc.selectFirst("div.reading-content .page-break")
					?.parent()  // reading-content itself
				?: doc.selectFirst("div.reading-content")
				?: doc.selectFirst(".chapter-content")
				?: return null

		// Remove ads, scripts, injected iframes
		contentElement.select(
			"script, style, ins, iframe, noscript, " +
				".code-block, .adsbygoogle, .google-auto-placed, " +
				"[id^=ezoic], [id^=pf-], [class*=sharedaddy]",
		).remove()

		// Drop empty / whitespace-only / page-number-only paragraphs
		contentElement.select("p").forEach { p ->
			val text = p.text().trim()
			if (text.isEmpty() || text == "\u00a0" || text.matches(Regex("^\\s*\\d+\\s*$"))) {
				p.remove()
			}
		}

		val title = doc.selectFirst("h1.entry-title, .wp-manga-chapter h1")
			?.text()?.trim()
			?: chapter.title
			?: ""

		val html = buildString {
			if (title.isNotBlank()) {
				append("<h1>")
				append(title)
				append("</h1>")
			}
			append(contentElement.html())
		}

		return NovelChapterContent(
			html = html,
			images = emptyList(),
		)
	}
}
