package org.koitharu.kotatsu.parsers.site.ar

/*
 * Site structure was verified against NovelSourcery's Apache-2.0
 * LightNovelWPNovel implementation and reimplemented for Kotatsu's parser API:
 * https://github.com/NovelSourcery/extensions-source/tree/main/src/ar/novelsparadise
 */

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("NOVELSPARADISE", "جنة الروايات", "ar", ContentType.NOVEL)
internal class NovelsParadise(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.NOVELSPARADISE,
	pageSize = 20,
) {

	override val configKeyDomain = ConfigKey.Domain("novelsparadise.site")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val document = webClient.httpGet("https://$domain/series/").parseHtml()
		return MangaListFilterOptions(
			availableTags = document.select(".quickfilter input[name^=genre][value]").mapNotNullToSet { input ->
				val key = input.attr("value").trim().takeIf(String::isNotEmpty) ?: return@mapNotNullToSet null
				val title = input.nextElementSibling()?.takeIf { it.tagName() == "label" }?.text()?.trim()
					?.takeIf(String::isNotEmpty)
					?: key
				MangaTag(key = key, title = title, source = source)
			},
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		)
	}

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val url = if (query.isNotEmpty()) {
			buildString {
				append("https://")
				append(domain)
				if (page > 1) append("/page/$page")
				append("/?s=")
				append(query.urlEncoded())
			}
		} else {
			buildString {
				append("https://")
				append(domain)
				append("/series/?page=")
				append(page)
				append("&order=")
				append(order.toParadiseOrder())
				filter.tags.oneOrThrowIfMany()?.let { tag ->
					append("&genre%5B%5D=")
					append(tag.key.urlEncoded())
				}
				filter.states.oneOrThrowIfMany()?.let { state ->
					append("&status=")
					append(state.toParadiseStatus())
				}
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	internal fun parseMangaList(document: Document): List<Manga> =
		document.select(".listupd article.maindet").mapNotNull { article ->
			val anchor = article.selectFirst(".mdthumb a[href], .mdinfo h2 a[href]")
				?: return@mapNotNull null
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = anchor.attr("title").trim().takeIf(String::isNotEmpty)
				?: article.selectFirst(".mdinfo h2")?.text()?.trim()?.takeIf(String::isNotEmpty)
				?: return@mapNotNull null
			val image = article.selectFirst(".mdthumb img")
			val rating = article.selectFirst(".mdminf")?.ownText()?.trim()?.toFloatOrNull()
				?.div(10f)
				?.coerceIn(0f, 1f)
				?: RATING_UNKNOWN
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = rating,
				contentRating = null,
				coverUrl = image?.src(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}.distinctBy(Manga::id)

	override suspend fun getDetails(manga: Manga): Manga {
		val document = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val cover = document.selectFirst(".sertothumb img")?.src() ?: manga.coverUrl
		val status = document.selectFirst(".sertostat")?.let(::parseState) ?: manga.state
		val authors = document.select(".sertoauth .serl").mapNotNullToSet { row ->
			val label = row.selectFirst(".sername")?.text().orEmpty()
			if (label.contains("المؤلف") || label.contains("Author", ignoreCase = true)) {
				row.selectFirst(".serval")?.text()?.trim()?.takeIf(String::isNotEmpty)
			} else {
				null
			}
		}
		val tags = document.select(".sertogenre a[href]").mapNotNullToSet { anchor ->
			val title = anchor.text().trim().takeIf(String::isNotEmpty) ?: return@mapNotNullToSet null
			val key = anchor.attr("href").removeSuffix("/").substringAfterLast("/")
			MangaTag(key = key, title = title, source = source)
		}
		val rating = document.selectFirst("meta[itemprop=ratingValue]")?.attr("content")
			?.toFloatOrNull()?.div(10f)?.coerceIn(0f, 1f) ?: manga.rating

		return manga.copy(
			title = document.selectFirst("h1.entry-title")?.text()?.trim()?.takeIf(String::isNotEmpty)
				?: manga.title,
			altTitles = setOfNotNull(
				document.selectFirst(".sertoinfo .alter")?.text()?.trim()?.takeIf(String::isNotEmpty),
			),
			description = document.selectFirst(".sersys.entry-content")?.also(::sanitizeDescription)?.html(),
			coverUrl = cover,
			largeCoverUrl = cover,
			rating = rating,
			state = status,
			authors = authors,
			tags = tags,
			chapters = parseChapters(document),
		)
	}

	internal fun parseChapters(document: Document): List<MangaChapter> =
		document.select(".eplister ul li").mapNotNull { item ->
			if (item.selectFirst(".fa-lock, i[class*=lock]") != null) return@mapNotNull null
			val anchor = item.selectFirst("a[href]") ?: return@mapNotNull null
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val numberText = item.selectFirst(".epl-num")?.text().orEmpty()
			val number = NUMBER.find(numberText)?.value?.toFloatOrNull()
				?: NUMBER.find(href.removeSuffix("/").substringAfterLast('-'))?.value?.toFloatOrNull()
				?: return@mapNotNull null
			val title = item.selectFirst(".epl-title")?.text()?.trim().orEmpty()
			val date = item.selectFirst(".epl-date")?.text()?.trim().orEmpty()
			MangaChapter(
				id = generateUid(href),
				title = title.ifEmpty { "الفصل $number" },
				number = number,
				volume = 0,
				url = href,
				scanlator = "جنة الروايات",
				uploadDate = parseDate(date),
				branch = null,
				source = source,
			)
		}.distinctBy(MangaChapter::id).sortedBy(MangaChapter::number)

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(chapterUrl).parseHtml()
		val content = selectChapterContent(document) ?: return null
		sanitizeChapterContent(content)
		if (content.text().isBlank() && content.selectFirst("img") == null) return null

		val title = document.selectFirst("h1.entry-title")?.text()?.trim()
			?.takeIf(String::isNotEmpty)
			?: chapter.title.orEmpty()
		if (title.isNotEmpty()) content.prependChild(Element("h1").text(title))
		return NovelChapterContent(
			html = content.html(),
			images = content.select("img").mapNotNull { image ->
				image.src()?.toAbsoluteUrl(domain)?.let { imageUrl ->
					NovelImage(
						url = imageUrl,
						headers = mapOf(
							"Referer" to chapterUrl,
							"User-Agent" to config[userAgentKey],
						),
					)
				}
			}.distinctBy(NovelImage::url),
		)
	}

	internal fun selectChapterContent(document: Document): Element? = document.select(
		".epcontent.entry-content, .epcontent, #chapter-content, .reading-content, " +
			".chapter-content, article .entry-content",
	).filter { it.select("p").isNotEmpty() }
		.maxByOrNull { candidate -> candidate.select("p").sumOf { it.text().length } }

	private fun sanitizeChapterContent(content: Element) {
		content.select(
			"script, style, iframe, noscript, form, input, button, ins, .ads, .ad-unit, " +
				".adsbygoogle, .code-block, .unlock-buttons, [id*=google], [class*=google], " +
				"[hidden], [aria-hidden=true]",
		).remove()
		content.select("img").forEach(::promoteLazyImage)
		content.select("p, div, span").forEach { element ->
			if (element.text().trim().isEmpty() && element.selectFirst("img") == null) element.remove()
		}
		content.select("*").forEach { element ->
			element.removeAttr("class")
			element.removeAttr("id")
			element.removeAttr("style")
			element.removeAttr("width")
			element.removeAttr("height")
		}
	}

	private fun sanitizeDescription(content: Element) {
		content.select("script, style, iframe, ins, [class*=advert], [id*=advert]").remove()
		content.select("*").forEach { it.removeAttr("style") }
	}

	private fun promoteLazyImage(image: Element) {
		val current = image.attr("src").trim()
		if (current.isNotEmpty() && !current.startsWith("data:", true) && current != "#") return
		for (attribute in arrayOf("data-src", "data-lazy-src", "data-original", "data-url")) {
			val value = image.attr(attribute).trim()
			if (value.isNotEmpty() && !value.startsWith("data:", true)) {
				image.attr("src", value)
				return
			}
		}
	}

	private fun parseState(element: Element): MangaState? {
		val value = (element.className() + " " + element.text()).lowercase(Locale.ROOT)
		return when {
			"completed" in value || "مكتمل" in value -> MangaState.FINISHED
			"hiatus" in value || "متوقف" in value -> MangaState.PAUSED
			"ongoing" in value || "مستمر" in value -> MangaState.ONGOING
			else -> null
		}
	}

	private fun SortOrder.toParadiseOrder(): String = when (this) {
		SortOrder.POPULARITY -> "popular"
		SortOrder.ALPHABETICAL -> "title"
		SortOrder.NEWEST -> "latest"
		SortOrder.RATING -> "rating"
		else -> "update"
	}

	private fun MangaState.toParadiseStatus(): String = when (this) {
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		else -> "ongoing"
	}

	private fun parseDate(value: String): Long {
		if (value.isBlank()) return 0L
		for (format in DATE_FORMATS) {
			val parsed = synchronized(format) { runCatching { format.parse(value)?.time }.getOrNull() }
			if (parsed != null) return parsed
		}
		return 0L
	}

	internal companion object {
		private val NUMBER = Regex("""\d+(?:\.\d+)?""")
		private val DATE_FORMATS = listOf(
			SimpleDateFormat("MMMM dd, yyyy", Locale("ar")),
			SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH),
			SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
		)
	}
}
