package org.koitharu.kotatsu.parsers.site.ar

/*
 * Site selectors and the reader-cache discovery were informed by GalaxyNovels.kt
 * from NovelSourcery/extensions-source (Apache-2.0):
 * https://github.com/NovelSourcery/extensions-source/tree/main/src/ar/galaxynovels
 * Reimplemented here for Kotatsu's parser API, including working search pagination,
 * chapter ordering, HTML cleanup, and inline-image support.
 */

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.NovelChapterContent
import org.koitharu.kotatsu.parsers.model.NovelImage
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("GALAXYNOVELS", "مجرة الروايات", "ar", ContentType.NOVEL)
internal class GalaxyNovels(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.GALAXYNOVELS,
	pageSize = PAGE_SIZE,
) {

	override val configKeyDomain = ConfigKey.Domain("galaxynovels.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = GENRES.mapTo(LinkedHashSet()) {
			MangaTag(title = it, key = it, source = source)
		},
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
	)

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val url = if (query.isEmpty() && filter.tags.isEmpty() && filter.states.isEmpty()) {
			when (order) {
				SortOrder.UPDATED -> "https://$domain/recent/?page=$page"
				SortOrder.ALPHABETICAL -> "https://$domain/library/?sort=name&page=$page"
				else -> "https://$domain/novels/?sort=popular&period=all&page=$page"
			}
		} else {
			buildString {
				append("https://")
				append(domain)
				append("/library/?page=")
				append(page)
				if (query.isNotEmpty()) {
					append("&q=")
					append(query.urlEncoded())
				}
				filter.tags.forEach { tag ->
					append("&genre%5B%5D=")
					append(tag.key.urlEncoded())
				}
				filter.states.forEach { state ->
					append("&status%5B%5D=")
					append(state.toGalaxyStatus())
				}
				append("&sort=")
				append(if (order == SortOrder.ALPHABETICAL) "name" else "")
			}
		}
		val document = webClient.httpGet(url, siteHeaders("https://$domain/")).parseHtml()
		return parseNovelList(document)
	}

	private fun parseNovelList(document: Document): List<Manga> =
		document.select("article.wor-novel-card, article.wor-library-card").mapNotNull { card ->
			val link = card.selectFirst(
				"h2.wor-library-card__title a[href], h3 a[href], " +
					"a.wor-novel-card__cover[href], a.wor-library-card__cover[href]",
			) ?: return@mapNotNull null
			val relativeUrl = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = card.selectFirst("h2.wor-library-card__title a, h3 a")?.text()?.trim()
				?.takeIf(String::isNotEmpty)
				?: link.attr("aria-label").trim().takeIf(String::isNotEmpty)
				?: return@mapNotNull null
			val image = card.selectFirst("img.wor-cover-img, img")
			val cover = image?.attr("data-src")?.trim()?.takeIf(String::isNotEmpty)
				?: image?.src()
			Manga(
				id = generateUid(relativeUrl),
				title = title,
				altTitles = emptySet(),
				url = relativeUrl,
				publicUrl = relativeUrl.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.SAFE,
				coverUrl = cover?.toAbsoluteUrl(domain),
				tags = emptySet(),
				state = parseState(card.selectFirst(".wor-cover-status")?.text()),
				authors = emptySet(),
				source = source,
			)
		}.distinctBy(Manga::id)

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(mangaUrl, siteHeaders("https://$domain/")).parseHtml()
		val coverElement = document.selectFirst("img.wor-cover-img")
		val cover = coverElement?.attr("data-src")?.trim()?.takeIf(String::isNotEmpty)
			?: coverElement?.src()
			?: manga.coverUrl
		val tags = document.select(".wor-tag-pill").mapNotNullToSet { element ->
			val title = element.text().trim().takeIf(String::isNotEmpty) ?: return@mapNotNullToSet null
			MangaTag(title = title, key = title, source = source)
		}
		val author = document.selectFirst(".wor-single-hero__meta-text span")?.text()?.trim()
			?.takeIf(String::isNotEmpty)
		val summary = document.selectFirst(".wor-single-summary__text")?.also {
			it.select("script, style, button").remove()
		}?.html()

		return manga.copy(
			title = document.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotEmpty) ?: manga.title,
			publicUrl = mangaUrl,
			coverUrl = cover?.toAbsoluteUrl(domain),
			largeCoverUrl = cover?.toAbsoluteUrl(domain),
			description = summary ?: manga.description,
			tags = tags.ifEmpty { manga.tags },
			state = parseState(document.selectFirst(".wor-cover-status")?.text()) ?: manga.state,
			authors = setOfNotNull(author).ifEmpty { manga.authors },
			chapters = loadChapters(mangaUrl, document),
		)
	}

	private suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val novelId = document.selectFirst("[data-novel-id]")?.attr("data-novel-id")
			?.takeIf(String::isNotBlank)
		val cached = novelId?.let { id ->
			runCatching {
				webClient.httpGet(
					"https://$domain/wp-content/uploads/wor-reader-cache/chapters/novel-$id.json",
					siteHeaders(mangaUrl),
				).parseRaw()
			}.getOrNull()
		}
		val chapters = cached?.let(::parseCachedChapters).orEmpty()
		return (if (chapters.isNotEmpty()) chapters else parseHtmlChapters(document))
			.distinctBy(MangaChapter::id)
			.sortedBy(MangaChapter::number)
	}

	internal fun parseCachedChapters(raw: String): List<MangaChapter> {
		val array = runCatching { JSONObject(raw).optJSONArray("chapters") }.getOrNull() ?: return emptyList()
		return buildList {
			for (index in 0 until array.length()) {
				val item = array.optJSONObject(index) ?: continue
				val url = item.optString("url").trim().takeIf(String::isNotEmpty) ?: continue
				val number = item.optString("number").toFloatOrNull()
					?: item.optInt("position", index + 1).toFloat()
				val label = item.optString("label").trim().ifEmpty { "الفصل ${formatNumber(number)}" }
				val extraTitle = item.optString("title").trim()
				add(createChapter(url, number, listOf(label, extraTitle).filter(String::isNotEmpty).joinToString(" — "), item.optString("date_iso")))
			}
		}
	}

	private fun parseHtmlChapters(document: Document): List<MangaChapter> =
		document.select("article.wor-novel-chapter-item").mapIndexedNotNull { index, item ->
			val link = item.selectFirst("a[href]") ?: return@mapIndexedNotNull null
			val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapIndexedNotNull null
			val number = item.selectFirst(".wor-novel-chapter-item__num")?.text()?.trim()?.toFloatOrNull()
				?: NUMBER.find(item.text())?.value?.toFloatOrNull()
				?: (index + 1).toFloat()
			val title = item.selectFirst("h3 a")?.text()?.trim().orEmpty().ifEmpty {
				"الفصل ${formatNumber(number)}"
			}
			createChapter(url, number, title, item.selectFirst("time")?.attr("datetime"))
		}

	private fun createChapter(url: String, number: Float, title: String, date: String?): MangaChapter = MangaChapter(
		id = generateUid(url),
		title = title,
		number = number,
		volume = 0,
		url = url,
		scanlator = "مجرة الروايات",
		uploadDate = parseDate(date),
		branch = null,
		source = source,
	)

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(chapterUrl, siteHeaders("https://$domain/")).parseHtml()
		val content = document.selectFirst(
			".wor-chapter-content, .entry-content, .chapter-content, .post-content, article .content",
		) ?: return null
		content.select(
			"script, style, iframe, noscript, form, button, nav, .ads, .ad-unit, " +
				"[data-ad-position], [hidden], [aria-hidden=true]",
		).remove()
		content.select("img").forEach(::promoteLazyImage)
		content.select("*").forEach { element ->
			element.removeAttr("class")
			element.removeAttr("id")
			element.removeAttr("style")
		}
		val images = content.select("img").mapNotNull { image ->
			image.src()?.let { imageUrl ->
				NovelImage(
					url = imageUrl,
					headers = mapOf(
						"Referer" to chapterUrl,
						"User-Agent" to config[userAgentKey],
					),
				)
			}
		}.distinctBy(NovelImage::url)
		if (content.text().isBlank() && images.isEmpty()) return null
		return NovelChapterContent(html = content.html(), images = images)
	}

	private fun siteHeaders(referer: String): Headers = Headers.Builder()
		.add("Cookie", "wor_reader_js=1")
		.add("Referer", referer)
		.add("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
		.add("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
		.add("User-Agent", config[userAgentKey])
		.build()

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

	private fun parseState(value: String?): MangaState? = when {
		value.isNullOrBlank() -> null
		value.contains("مكتملة") || value.contains("مكتمل") -> MangaState.FINISHED
		value.contains("متوقفة") || value.contains("متوقف") -> MangaState.PAUSED
		value.contains("مستمرة") || value.contains("مستمر") -> MangaState.ONGOING
		else -> null
	}

	private fun MangaState.toGalaxyStatus(): String = when (this) {
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		else -> "ongoing"
	}

	private fun parseDate(value: String?): Long {
		if (value.isNullOrBlank()) return 0L
		for (pattern in DATE_PATTERNS) {
			val result = synchronized(pattern) { runCatching { pattern.parse(value)?.time }.getOrNull() }
			if (result != null) return result
		}
		return 0L
	}

	private fun formatNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

	internal companion object {
		private const val PAGE_SIZE = 20
		private val NUMBER = Regex("""\d+(?:\.\d+)?""")
		private val DATE_PATTERNS = listOf(
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH),
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH),
			SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
		)
		private val GENRES = listOf(
			"أكشن", "البطل ذكر", "البطل انثى", "الزراعة", "الهجرة", "بناء القواعد",
			"تاريخي", "تشويق", "خيال", "خيال علمي", "دراما", "رعب", "رعب بالغ",
			"سحر", "شونين", "عسكري", "غموض", "فانتازيا", "فنون قتالية", "قتال",
			"قوى خارقة", "كوميديا", "لعبة", "محاكي", "مغامرة", "مهارات القتال", "نظام", "نفسي",
		)
	}
}
