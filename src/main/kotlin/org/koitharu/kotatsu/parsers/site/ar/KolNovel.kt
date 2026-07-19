package org.koitharu.kotatsu.parsers.site.ar

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

	override val configKeyDomain = ConfigKey.Domain("free.kolnovel.com")

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
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/?s=")
					append(filter.query.urlEncoded())
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
						append("&genre=")
						append(it.key)
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
		return doc.select(".listupd article.maindet").map { article ->
			val a = article.selectFirstOrThrow(".mdthumb a")
			val href = a.attrAsRelativeUrl("href")
			val title = a.attr("title").ifEmpty {
				article.selectFirst(".mdinfo h2")?.text().orEmpty()
			}
			val img = article.selectFirst(".mdthumb img")
			val ratingText = article.selectFirst(".mdminf")?.ownText()?.trim()
			val rating = ratingText?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN
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
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/series/").parseHtml()
		return doc.select(".quickfilter .genrez li label").mapNotNullToSet { label ->
			val input = label.selectFirst("input") ?: return@mapNotNullToSet null
			val key = input.attr("value")
			if (key.isEmpty()) return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = label.text().trim(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val statusText = doc.selectFirst(".sertostat span")?.text()?.trim().orEmpty()
		val state = when {
			statusText.contains("Ongoing", ignoreCase = true) || statusText.contains("مستمرة") -> MangaState.ONGOING
			statusText.contains("Completed", ignoreCase = true) || statusText.contains("مكتمل") -> MangaState.FINISHED
			else -> manga.state
		}

		val authors = doc.select(".sertoauth .serl").mapNotNull { serl ->
			val label = serl.selectFirst(".sername")?.text().orEmpty()
			if (label.contains("الكاتب") || label.contains("المؤلف")) {
				serl.selectFirst(".serval")?.text()
			} else null
		}.toSet()

		val tags = doc.select(".sertogenre a").mapToSet { a ->
			MangaTag(
				key = a.attr("href").removeSuffix("/").substringAfterLast("/"),
				title = a.text().trim(),
				source = source,
			)
		}

		val ratingValue = doc.selectFirst("meta[itemprop=ratingValue]")
			?.attr("content")?.toFloatOrNull()?.div(10f) ?: manga.rating

		val coverUrl = doc.selectFirst(".sertothumb img")?.src() ?: manga.coverUrl
		val altTitle = doc.selectFirst(".sertoinfo .alter")?.text()

		return manga.copy(
			title = doc.selectFirst("h1.entry-title")?.text() ?: manga.title,
			altTitles = setOfNotNull(altTitle),
			description = doc.selectFirst(".sersys.entry-content")?.html(),
			coverUrl = coverUrl,
			largeCoverUrl = coverUrl,
			rating = ratingValue,
			state = state,
			authors = authors,
			tags = tags,
			chapters = parseChapters(doc),
		)
	}

	private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("ar"))
	private val dateFormatEn = SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH)

	private fun parseChapters(doc: Document): List<MangaChapter> {
		return doc.select(".eplister ul li").mapNotNull { li ->
			val a = li.selectFirst("a") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val numText = li.selectFirst(".epl-num")?.text().orEmpty()
			val title = li.selectFirst(".epl-title")?.text().orEmpty()
			val dateText = li.selectFirst(".epl-date")?.text().orEmpty()

			val number = numText.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f

			val uploadDate = parseChapterDate(dateText)

			MangaChapter(
				id = generateUid(href),
				title = title.ifEmpty { numText },
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

	private fun parseChapterDate(dateText: String): Long {
		if (dateText.isBlank()) return 0L
		return runCatching { dateFormat.parse(dateText)?.time }.getOrNull()
			?: runCatching { dateFormatEn.parse(dateText)?.time }.getOrNull()
			?: 0L
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val contentElement = doc.selectFirst("#kol_content")
			?: doc.selectFirst(".epcontent.entry-content")
			?: return null

		// Remove ads and scripts
		contentElement.select("script, .code-block, ins, iframe, [id^=pf-]").remove()

		// Clean up: remove empty paragraphs and ad text
		contentElement.select("p").forEach { p ->
			val text = p.text().trim()
			if (text.isEmpty() || text == "&nbsp;" || text.matches(Regex("^\\d+$"))) {
				p.remove()
			}
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
}
