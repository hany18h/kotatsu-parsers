package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("KOLNOVEL", "KolNovel", "ar", ContentType.NOVEL)
internal class KolNovel(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOLNOVEL, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("kolnovel.com")

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
		val query = filter.query?.trim().orEmpty()
		val url = buildString {
			append("https://")
			append(domain)
			when {
				query.isNotEmpty() -> {
					append("/?s=")
					append(query.urlEncoded())
					append("&page=")
					append(page)
				}

				else -> {
					append("/series/?page=")
					append(page)
					append("&order=")
					append(
						when (order) {
							SortOrder.UPDATED -> "update"
							SortOrder.POPULARITY -> "popular"
							SortOrder.ALPHABETICAL -> "title"
							SortOrder.NEWEST -> "latest"
							else -> "update"
						},
					)
					filter.tags.oneOrThrowIfMany()?.let {
						append("&genre%5B%5D=")
						append(it.key.urlEncoded())
					}
					filter.states.oneOrThrowIfMany()?.let {
						append("&status=")
						append(
							when (it) {
								MangaState.ONGOING -> "ongoing"
								MangaState.FINISHED -> "completed"
								else -> ""
							},
						)
					}
				}
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		val articles = doc.select("article.kol-series-archive__item")
			.ifEmpty { doc.select(".listupd article.maindet") }
		return articles.mapNotNull { article ->
			val link = article.selectFirst("h3 a, a[data-kol-series-title-source], .kol-series-archive__cover")
				?: article.selectFirst(".mdthumb a")
				?: return@mapNotNull null
			val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = article.selectFirst("h3 a")?.text()?.trim().orEmpty()
				.ifEmpty { article.selectFirst(".mdinfo h2")?.text()?.trim().orEmpty() }
				.ifEmpty { article.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty() }
			if (title.isEmpty()) return@mapNotNull null
			val img = article.selectFirst(".kol-series-archive__cover img, .mdthumb img, img")
			val ratingText = article.selectFirst(".kol-series-archive__rating b")?.text()?.trim()
				?: article.selectFirst(".mdminf")?.ownText()?.trim()
			val rating = ratingText?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN
			val status = article.selectFirst(".kol-series-archive__status")?.text()
				?: article.selectFirst(".sertostat span")?.text().orEmpty()
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = rating,
				contentRating = null,
				coverUrl = img?.src(),
				tags = emptySet(),
				state = parseState(status),
				authors = emptySet(),
				source = source,
			)
		}.distinctBy(Manga::url)
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/series/").parseHtml()
		return doc.select("input[name='genre[]']").mapNotNullToSet { input ->
			val key = input.attr("value").trim()
			val title = input.parent()?.text()?.trim().orEmpty()
			if (key.isEmpty() || title.isEmpty()) return@mapNotNullToSet null
			MangaTag(key = key, title = title, source = source)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val statusText = (doc.selectFirst(".kol-series-editorial__quick-fact--status")
			?: doc.selectFirst(".sertostat span"))?.text()?.trim().orEmpty()
		val state = parseState(statusText)
		val byline = doc.selectFirst(".kol-series-editorial__byline")?.text()?.trim().orEmpty()
		val authors = byline.substringAfter("بقلم", "").trim().takeIf(String::isNotEmpty)?.let(::setOf)
			?: doc.select(".sertoauth .serl").mapNotNullToSet { row ->
				val label = row.selectFirst(".sername")?.text().orEmpty()
				if (label.contains("الكاتب") || label.contains("المؤلف")) row.selectFirst(".serval")?.text() else null
			}
		val tags = doc.select(".kol-series-editorial__genres a, .sertogenre a").mapToSet { a ->
			MangaTag(
				key = a.attr("href").removeSuffix("/").substringAfterLast("/"),
				title = a.text().trim(),
				source = source,
			)
		}

		val ratingValue = doc.selectFirst("meta[itemprop=ratingValue]")
			?.attr("content")?.toFloatOrNull()?.div(10f) ?: manga.rating

		val coverUrl = doc.selectFirst(".kol-series-editorial__cover img, .sertothumb img")?.src()
			?: doc.selectFirst("meta[property=og:image]")?.attr("content")
			?: manga.coverUrl
		val altTitle = (doc.selectFirst(".kol-series-editorial__alt-title")
			?: doc.selectFirst(".sertoinfo .alter"))?.text()?.trim()

		return manga.copy(
			title = (doc.selectFirst("h1.kol-series-editorial__title")
				?: doc.selectFirst("h1.entry-title"))?.text()?.trim() ?: manga.title,
			altTitles = setOfNotNull(altTitle),
			description = (doc.selectFirst(".kol-series-editorial__synopsis .kol-series-editorial__prose")
				?: doc.selectFirst(".sersys.entry-content"))?.html(),
			coverUrl = coverUrl,
			largeCoverUrl = coverUrl,
			rating = ratingValue,
			state = state,
			authors = authors,
			tags = tags,
			chapters = loadAllChapters(manga.url, doc),
		)
	}

	private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("ar"))
	private val dateFormatEn = SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH)
	private val dateFormatIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ENGLISH)
	private val dateFormatIsoNoMillis = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH)

	internal fun parseChapters(doc: Document): List<MangaChapter> {
		val currentRows = doc.select(".kol-series-chapter")
		if (currentRows.isEmpty()) {
			return doc.select(".eplister ul li").mapNotNull { row ->
				val link = row.selectFirst("a") ?: return@mapNotNull null
				val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
				val numberText = row.selectFirst(".epl-num")?.text().orEmpty()
				val number = numberText.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: return@mapNotNull null
				MangaChapter(
					id = generateUid(href),
					title = row.selectFirst(".epl-title")?.text().orEmpty().ifEmpty { numberText },
					number = number,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = parseChapterDate(row.selectFirst(".epl-date")?.text().orEmpty()),
					branch = null,
					source = source,
				)
			}.distinctBy(MangaChapter::url).sortedBy(MangaChapter::number)
		}
		return currentRows.mapNotNull { row ->
			val link = row.selectFirst("a[data-kol-chapter-transition-link], a[href]") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val numberText = row.selectFirst("[data-kol-chapter-number]")
				?.attr("data-kol-chapter-number")?.trim()
				?: Regex("""\d+(?:\.\d+)?""").find(link.text())?.value
			val number = numberText?.toFloatOrNull() ?: return@mapNotNull null
			val title = row.selectFirst("[data-kol-chapter-title]")?.text()?.trim().orEmpty()
			val dateText = row.selectFirst("time[data-kol-chapter-date]")?.attr("datetime")
				?: row.selectFirst("time")?.text().orEmpty()
			MangaChapter(
				id = generateUid(href),
				title = title.ifEmpty { "الفصل $numberText" },
				number = number,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseChapterDate(dateText),
				branch = null,
				source = source,
			)
		}.distinctBy(MangaChapter::url).sortedBy(MangaChapter::number)
	}

	private suspend fun loadAllChapters(mangaUrl: String, firstDocument: Document): List<MangaChapter> {
		val chapters = LinkedHashMap<String, MangaChapter>()
		val seriesUrl = mangaUrl.toAbsoluteUrl(domain).substringBefore('#').substringBefore('?')
		var document = firstDocument
		var page = 1
		while (page <= MAX_CHAPTER_PAGES) {
			parseChapters(document).forEach { chapters.putIfAbsent(it.url, it) }
			val nextPage = document.select("a[href*='chapter_page=']")
				.mapNotNull { link -> Regex("""[?&]chapter_page=(\d+)""").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
				.filter { it > page }
				.minOrNull() ?: break
			if (nextPage > MAX_CHAPTER_PAGES) break
			page = nextPage
			val pageUrl = "$seriesUrl" +
				"?chapter_volume=__kol_all__&chapter_search=&chapter_order=newest&chapter_page=$page#chapters"
			document = webClient.httpGet(pageUrl).parseHtml()
			if (document.select(".kol-series-chapter").isEmpty()) break
		}
		return chapters.values.sortedBy(MangaChapter::number)
	}

	private fun parseState(value: String): MangaState? = when {
		value.contains("مستمرة") || value.contains("مستمر") || value.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
		value.contains("مكتملة") || value.contains("مكتمل") || value.contains("completed", ignoreCase = true) -> MangaState.FINISHED
		value.contains("متوقفة") || value.contains("متوقف") || value.contains("paused", ignoreCase = true) -> MangaState.PAUSED
		else -> null
	}

	private fun parseChapterDate(dateText: String): Long {
		if (dateText.isBlank()) return 0L
		return parseDate(dateFormatIso, dateText)
			?: parseDate(dateFormatIsoNoMillis, dateText)
			?: runCatching { dateFormat.parse(dateText)?.time }.getOrNull()
			?: runCatching { dateFormatEn.parse(dateText)?.time }.getOrNull()
			?: 0L
	}

	private fun parseDate(format: SimpleDateFormat, value: String): Long? =
		synchronized(format) { runCatching { format.parse(value)?.time }.getOrNull() }

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val sourceElement = doc.selectFirst("#kol_content")
			?: doc.selectFirst(".epcontent.entry-content")
			?: return null
		val contentElement = sanitizeChapterElement(
			sourceElement = sourceElement,
			hiddenClasses = findHiddenChapterClasses(doc, sourceElement),
		)

		contentElement.select("p").forEach { p ->
			val text = p.text().trim()
			if (text.isEmpty() || text == "&nbsp;" || text.matches(ONLY_NUMBER)) {
				p.remove()
			}
		}
		contentElement.select("*").forEach { element ->
			element.removeAttr("class")
			element.removeAttr("id")
			element.removeAttr("style")
		}

		val title = doc.selectFirst("h1.entry-title")?.text().orEmpty()
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
			images = contentElement.select("img").mapNotNull { image ->
				image.src()?.let { url ->
					NovelImage(
						url = url,
						headers = mapOf(
							"Referer" to chapter.url.toAbsoluteUrl(domain),
							"User-Agent" to config[userAgentKey],
						),
					)
				}
			}.distinctBy(NovelImage::url),
		)
	}

	/**
	 * KolNovel currently appends the Shola support widget's stylesheet and
	 * JavaScript as bare text nodes inside #kol_content. Jsoup cannot remove
	 * those with style/script selectors because they are not HTML elements, so
	 * discard the injected tail before parsing and sanitising the fragment.
	 */
	internal fun sanitizeChapterElement(
		sourceElement: Element,
		hiddenClasses: Set<String> = emptySet(),
	): Element {
		val rawHtml = sourceElement.html()
		val markerIndex = SHOLA_TAIL_MARKERS.map(rawHtml::indexOf)
			.filter { it >= 0 }
			.minOrNull()
		val chapterHtml = markerIndex?.let { rawHtml.substring(0, it) } ?: rawHtml
		return Jsoup.parseBodyFragment(chapterHtml).body().also { content ->
			// KolNovel sends the real chapter and shuffled decoy paragraphs together.
			// Random class names declared in the page's inline CSS identify the decoys.
			hiddenClasses.forEach { className ->
				content.getElementsByClass(className).remove()
			}
			content.select(
				"script, style, noscript, form, iframe, ins, .code-block, " +
					"[id^=pf-], [id^=shola-], [class*=shola-], [hidden], [aria-hidden=true]",
			).remove()
		}
	}

	internal fun findHiddenChapterClasses(document: Document, sourceElement: Element): Set<String> {
		val chapterClasses = sourceElement.select("[class]")
			.flatMapTo(HashSet()) { it.classNames() }
		if (chapterClasses.isEmpty()) return emptySet()

		return buildSet {
			document.select("style").forEach { style ->
				CSS_RULE.findAll(style.data() + style.html()).forEach { rule ->
					val declarations = rule.groupValues[2]
					if (!isHiddenDecoyRule(declarations)) return@forEach
					CLASS_SELECTOR.findAll(rule.groupValues[1]).forEach { selector ->
						selector.groupValues[1].takeIf(chapterClasses::contains)?.let(::add)
					}
				}
			}
		}
	}

	internal companion object {
		private const val MAX_CHAPTER_PAGES = 100
		private val ONLY_NUMBER = Regex("^\\d+$")
		// Android's ICU regex engine does not accept an unmatched literal closing
		// brace here, although the desktop JVM does. Escape both CSS braces so class
		// initialization works consistently across Android versions.
		private val CSS_RULE = Regex("""([^{}]+)\{([^{}]+)\}""")
		private val CLASS_SELECTOR = Regex("""\.([A-Za-z][\w-]*)""")
		private val ZERO_OPACITY = Regex("""(?:^|;)\s*opacity\s*:\s*0(?:\.0+)?\s*(?:!important)?\s*(?:;|$)""", RegexOption.IGNORE_CASE)
		private val HIDDEN_POSITION = Regex(
			"""(?:position\s*:\s*fixed|text-indent\s*:\s*-\d{3,}|bottom\s*:\s*-\d{2,}px|height\s*:\s*0(?:\.\d+)?px)""",
			RegexOption.IGNORE_CASE,
		)
		private val SHOLA_TAIL_MARKERS = listOf(
			".shola-widget",
			".shola-lb-wrap",
			".shola-progress-wrap",
			"function sholaTab(",
			"function shola",
		)

		private fun isHiddenDecoyRule(declarations: String): Boolean =
			ZERO_OPACITY.containsMatchIn(declarations) && HIDDEN_POSITION.containsMatchIn(declarations)
	}
}
