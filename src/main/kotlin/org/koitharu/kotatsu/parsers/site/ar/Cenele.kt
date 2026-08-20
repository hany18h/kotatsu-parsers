package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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

@MangaSourceParser("CENELE", "فضاء الروايات", "ar", ContentType.NOVEL)
internal class Cenele(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CENELE, pageSize = 10) {

	init {
		setFirstPage(firstPage = 1, firstPageForSearch = 1)
	}

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

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val sort = when (order) {
			SortOrder.POPULARITY -> "views"
			SortOrder.ALPHABETICAL -> "alphabet"
			else -> "latest"
		}
		val url = if (query.isNotEmpty()) {
			"https://$domain/?s=${query.urlEncoded()}&post_type=wp-manga&page=$page"
		} else {
			val tag = filter.tags.oneOrThrowIfMany()?.key
			val root = if (tag == null) "/novel/" else "/cont-genre/${tag.urlEncoded()}/"
			val path = if (page <= 1) root else "${root}page/$page/"
			"https://$domain$path?m_orderby=$sort"
		}
		val doc = webClient.httpGet(url).parseHtml()

		return doc.select("article.nhv-library-card, div.row.c-tabs-item__content").mapNotNull { div ->
			val a = div.selectFirst(".nhv-library-card__title a, .post-title a, h3 a, h4 a")
				?: div.selectFirst("a[href*=/cont/]")
				?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			val img = div.selectFirst(".nhv-library-card__cover img, .tab-thumb img, img")
			val title = div.selectFirst(".nhv-library-card__title, .post-title, h3, h4, .manga-name")
				?.text()?.trim().orEmpty()
				.ifEmpty { a.attr("title") }
			val status = div.selectFirst(".nhv-library-card__status, .summary-content")?.text().orEmpty()
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = img?.src(),
				tags = emptySet(),
				state = parseState(status),
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/novel/").parseHtml()
		return doc.select("a[href*=/cont-genre/], a[href*=/novel-genre/]").mapNotNullToSet { a ->
			val key = a.attr("href").removeSuffix("/").substringAfterLast("/")
			if (key.isEmpty()) return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = a.text().trim().ifEmpty { return@mapNotNullToSet null },
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val state = parseState(
			doc.selectFirst(".nhv-novel-status, .post-status .summary-content")?.text().orEmpty(),
		)

		val authors = doc.select(".nhv-novel-integrations a[href*=/cont-artist/], .author-content a")
			.mapNotNullToSet { it.text().trim().ifEmpty { null } }

		val tags = doc.select(".nhv-novel-genres a, .genres-content a").mapToSet { a ->
			MangaTag(
				key = a.attr("href").removeSuffix("/").substringAfterLast("/"),
				title = a.text().trim(),
				source = source,
			)
		}

		val coverUrl = doc.selectFirst(".nhv-novel-cover img, .summary_image img")?.src() ?: manga.coverUrl
		val altTitle = doc.selectFirst(".manga-name-or, .post-content_item:contains(Alt) .summary-content")
			?.text()?.trim()

		// تحميل الفصول
		val chapters = loadChapters(manga.url.toAbsoluteUrl(domain), doc)

		return manga.copy(
			title = doc.selectFirst("h1.nhv-novel-title, div.post-title h1")?.text()?.trim() ?: manga.title,
			altTitles = setOfNotNull(altTitle?.ifEmpty { null }),
			description = loadDescription(doc),
			coverUrl = coverUrl,
			largeCoverUrl = coverUrl,
			state = state,
			authors = authors,
			tags = tags,
			chapters = chapters,
		)
	}

	private suspend fun loadDescription(doc: Document): String? {
		val fallback = doc.selectFirst(".nhv-novel-synopsis, div.summary__content, .manga-excerpt .excerpt-content")
			?.html()?.trim()?.takeIf(String::isNotEmpty)
		val readMore = doc.selectFirst("#nhv-synopsis-readmore") ?: return fallback
		val postId = readMore.attr("data-post-id").trim().takeIf(String::isNotEmpty) ?: return fallback
		val nonce = readMore.attr("data-nonce").trim().takeIf(String::isNotEmpty) ?: return fallback

		return runCatching {
			webClient.httpPost(
				"https://$domain/wp-admin/admin-ajax.php",
				mapOf(
					"action" to "nhv_get_manga_synopsis",
					"nonce" to nonce,
					"post_id" to postId,
				),
			).parseJson()
				.optJSONObject("data")
				?.optString("html")
				?.trim()
				?.takeIf(String::isNotEmpty)
		}.getOrNull() ?: fallback
	}

	private suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
		val scripts = doc.select("script").joinToString("\n") { it.data() + it.html() }
		val mangaId = CHAPTERS_POST_ID.find(scripts)?.groupValues?.getOrNull(1)
			?.takeIf(String::isNotBlank)
			?: doc.selectFirst("[data-manga-id]")?.attr("data-manga-id")?.takeIf(String::isNotBlank)
			?: doc.selectFirst("#manga-chapters-holder")?.attr("data-id")?.takeIf(String::isNotBlank)
		val nonce = CHAPTERS_NONCE.find(scripts)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
		if (mangaId == null || nonce == null) return loadChaptersLegacy(mangaUrl, doc)

		val items = org.jsoup.select.Elements()
		var page = 1
		do {
			val response = webClient.httpPost(
				"https://$domain/wp-admin/admin-ajax.php".toHttpUrl(),
				mapOf(
					"action" to "nhv_manga_single_chapters_page",
					"nonce" to nonce,
					"manga_id" to mangaId,
					"volume" to "-1",
					"page" to page.toString(),
					"per_page" to CHAPTERS_PER_PAGE.toString(),
					"order" to "desc",
				),
				Headers.Builder()
					.add("Accept", "application/json")
					.add("Referer", mangaUrl)
					.add("X-Requested-With", "XMLHttpRequest")
					.add("User-Agent", config[userAgentKey])
					.build(),
			).parseJson()
			if (!response.optBoolean("success")) break
			val fragment = Jsoup.parseBodyFragment(response.optString("html"), mangaUrl)
			val pageItems = fragment.select("li.wp-manga-chapter[data-chapter-id]")
			if (pageItems.isEmpty()) break
			items.addAll(pageItems)
			page++
			val hasMore = response.optBoolean("has_more")
		} while (hasMore && page <= MAX_CHAPTER_PAGES)

		return if (items.isEmpty()) loadChaptersLegacy(mangaUrl, doc) else parseChapterElements(items, mangaId)
	}

	private suspend fun loadChaptersLegacy(mangaUrl: String, doc: Document): List<MangaChapter> {
		val mangaId = doc.selectFirst("[data-manga-id]")?.attr("data-manga-id")
			?.takeIf(String::isNotBlank)
			?: doc.selectFirst("#manga-chapters-holder")?.attr("data-id")?.takeIf(String::isNotBlank)

		// أولاً جرب inline chapters
		val inline = doc.select("ul.main li.wp-manga-chapter")
		if (inline.isNotEmpty()) return parseChapterElements(inline, mangaId)

		// ثم جرب ajax/chapters/ (الطريقة الأحدث في Madara)
		val ajaxDoc = runCatching {
			val ajaxUrl = mangaUrl.trimEnd('/') + "/ajax/chapters/"
			webClient.httpPost(ajaxUrl, emptyMap()).parseHtml()
		}.getOrNull()

		if (ajaxDoc != null) {
			val items = ajaxDoc.select("ul.main li.wp-manga-chapter")
			if (items.isNotEmpty()) {
				val ajaxMangaId = ajaxDoc.selectFirst("[data-manga-id]")?.attr("data-manga-id")
					?.takeIf(String::isNotBlank)
					?: mangaId
				return parseChapterElements(items, ajaxMangaId)
			}
		}

		// أخيراً جرب admin-ajax.php
		if (mangaId == null) return emptyList()
		val adminDoc = webClient.httpPost(
			"https://$domain/wp-admin/admin-ajax.php",
			mapOf("action" to "manga_get_chapters", "manga" to mangaId),
		).parseHtml()
		return parseChapterElements(adminDoc.select("ul.main li.wp-manga-chapter"), mangaId)
	}

	private fun parseChapterElements(
		elements: org.jsoup.select.Elements,
		mangaId: String?,
	): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("ar"))
		val dateFormatEn = SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH)
		val dateFormatShort = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)

		return elements.mapIndexedNotNull { index, li ->
			val a = li.selectFirst("a") ?: return@mapIndexedNotNull null
			// نأخذ href النظيف بدون ?style=list
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapIndexedNotNull null
			val chapterId = li.attr("data-chapter-id").takeIf(String::isNotBlank)
			val parserUrl = attachCeneleChapterLocator(href, mangaId, chapterId)
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
				url = parserUrl,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}.reversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		// نضمن إزالة ?style=list من أي URL قديم مخزن
		val cleanUrl = chapter.url
			.substringBefore('#')
			.replace("?style=list", "")
			.replace("&style=list", "")
			.toAbsoluteUrl(domain)

		// Chapter markup is randomized on every response. Reusing an older cached
		// document (or an empty 304 body) can therefore make an otherwise valid
		// chapter look unsupported, especially during continuous prefetching.
		val doc = webClient.httpGet(
			cleanUrl,
			Headers.Builder()
				.add("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
				.add("Cache-Control", "no-cache, no-store")
				.add("Pragma", "no-cache")
				.add("Referer", "https://$domain/")
				.add("User-Agent", config[userAgentKey])
				.build(),
		).parseHtml()
		val locator = parseCeneleChapterLocator(chapter.url)
		val content = findDirectChapterContent(doc, locator)
			?: locator?.let { loadChapterViaAjax(doc, cleanUrl, it) }
		?: return null

		sanitizeChapterContent(content)

		// عنوان الفصل
		val title = doc.selectFirst("h3.chapter-name")?.text()?.trim()
			?: chapter.title
			?: ""

		return NovelChapterContent(
			html = buildString {
				if (title.isNotBlank()) append(Element("h1").text(title).outerHtml())
				append(content.html())
			},
			images = content.select("img").mapNotNull { image ->
				image.src()?.let { url ->
					NovelImage(
						url = url,
						headers = mapOf(
							"Referer" to cleanUrl,
							"User-Agent" to config[userAgentKey],
						),
					)
				}
			}.distinctBy(NovelImage::url),
		)
	}

	private suspend fun loadChapterViaAjax(
		doc: Document,
		referer: String,
		locator: CeneleChapterLocator,
	): Element? {
		val scripts = doc.select("script").joinToString("\n") { it.data() }
		val nonce = LOAD_NONCE.find(scripts)?.groupValues?.getOrNull(1)
			?.takeIf(String::isNotBlank)
			?: return null
		return runCatching {
			webClient.httpPost(
				"https://$domain/wp-admin/admin-ajax.php".toHttpUrl(),
				mapOf(
					"action" to "load_chapter",
					"manga_id" to locator.mangaId,
					"chapter_id" to locator.chapterId,
					"nonce" to nonce,
				),
				Headers.Builder()
					.add("Accept", "text/html, */*;q=0.8")
					.add("Referer", referer)
					.add("X-Requested-With", "XMLHttpRequest")
					.add("User-Agent", config[userAgentKey])
					.build(),
			).parseHtml().selectFirst(".text-left")
		}.getOrNull()
	}

	internal companion object {

		private val ZERO_WIDTH_MARKS = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
		private val LOAD_NONCE = Regex("""["']load_nonce["']\s*:\s*["']([^"']+)""")
		private val CHAPTERS_POST_ID = Regex("""["']postId["']\s*:\s*["']?(\d+)""")
		private val CHAPTERS_NONCE = Regex("""["']chaptersNonce["']\s*:\s*["']([^"']+)""")
		private val HIDDEN_CSS_CLASS = Regex(
			"""\.([A-Za-z][\w-]*)\s*\{[^{}]*display\s*:\s*none(?:\s*!important)?[^{}]*\}""",
			RegexOption.IGNORE_CASE,
		)
		private const val CHAPTERS_PER_PAGE = 100
		private const val MAX_CHAPTER_PAGES = 50

		internal fun findDirectChapterContent(
			doc: Document,
			locator: CeneleChapterLocator?,
		): Element? {
			val chapterRoot = if (locator != null) {
				doc.selectFirst(
					"#chapter-${locator.chapterId}, " +
						".reading-content[data-block-chapter-id=${locator.chapterId}]",
				)
			} else {
				doc.selectFirst(".reading-content.current, .reading-content[data-block-chapter-id]")
			}
			if (chapterRoot == null) return null
			// Cenele now randomizes the wrapper tag/class and adds `text-left` only
			// after JavaScript runs. The hidden chapter URL is the stable server-side
			// marker, so prefer its direct parent over generic article elements (which
			// can be donation/promo cards inside the same chapter container).
			val exactMarker = locator?.let { value ->
				chapterRoot.selectFirst("input#chapter-url-${value.chapterId}")
			} ?: chapterRoot.selectFirst("input[id^=chapter-url-]")
			return exactMarker?.parent()
				?: chapterRoot.selectFirst(".text-left, .text-content, .text-chapter-content, article")
		}

		internal fun sanitizeChapterContent(content: Element): Element {
			val hiddenClasses = content.select("style").flatMap { style ->
				HIDDEN_CSS_CLASS.findAll(style.data() + style.html()).map { it.groupValues[1] }.toList()
			}.distinct()
			hiddenClasses.forEach { className -> content.getElementsByClass(className).remove() }

			// Cenele injects the anti-copy span inside the same <p> as the real text.
			// Remove the marker's following bait paragraph before deleting generic
			// templates. This keeps the relationship intact even if the warning text
			// changes while still preserving the surrounding real paragraphs.
			content.select("template[data-nhv-rb]").forEach { marker ->
				val bait = marker.nextElementSibling()
					?.takeIf { it.tagName() == "p" && isAntiCopyText(it.text()) }
				bait?.remove()
				marker.remove()
			}

			// Remove hidden descendants before examining visible paragraphs; checking
			// a parent's full text first would include the injected hidden watermark
			// and could classify a complete real paragraph as bait.
			content.select(
				"span[aria-hidden=true], " +
					"p[aria-hidden=true], " +
					"span[role=presentation], " +
					"p[role=presentation], " +
					"input[type=hidden], [hidden], .d-none, " +
					"[style*=\"display:none\"], [style*=\"display: none\"], " +
					"[style*=\"visibility:hidden\"], [style*=\"visibility: hidden\"], " +
					"script, style, ins, iframe, noscript, template, " +
					".adsbygoogle, .google-auto-placed, " +
					"[id^=ezoic], [id^=pf-], [id^=bg-ssp]",
			).remove()

			// Use ownText first so a future unrecognised hidden child cannot cause a
			// real paragraph to be deleted together with the watermark.
			content.select("p, span").forEach { element ->
				if (
					isAntiCopyText(element.ownText()) ||
					(element.children().isEmpty() && isAntiCopyText(element.text()))
				) {
					element.remove()
				}
			}
			content.select("img").forEach(::promoteLazyImageSource)
			content.select("p").forEach { paragraph ->
				if (paragraph.text().trim().isEmpty() && paragraph.selectFirst("img") == null) {
					paragraph.remove()
				}
			}
			return content
		}

		internal fun isAntiCopyText(value: String): Boolean {
			val normalized = ZERO_WIDTH_MARKS.replace(value, "")
				.replace(Regex("\\s+"), " ")
				.trim()
				.lowercase(Locale.ROOT)
			if ("فضاء الروايات" !in normalized && "cenele.com" !in normalized) return false
			return "نص تمويهي" in normalized ||
				"هذا تنبيه" in normalized ||
				"تطبيق سارق" in normalized ||
				"المصدر مسروق" in normalized
		}

		private fun promoteLazyImageSource(image: Element) {
			val current = image.attr("src").trim()
			if (current.isNotEmpty() && !current.startsWith("data:", true) && current != "#") return
			for (attribute in arrayOf("data-src", "data-lazy-src", "data-original", "data-url")) {
				val value = image.attr(attribute).trim()
				.takeIf { it.isNotEmpty() && !it.startsWith("data:", true) }
				?: continue
				image.attr("src", value)
					.removeAttr("srcset")
					.removeAttr("data-src")
					.removeAttr("data-lazy-src")
					.removeAttr("data-original")
					.removeAttr("data-url")
				return
			}
		}
	}

	private fun parseState(value: String): MangaState? {
		val status = value.trim().lowercase(Locale.ROOT)
		return when {
			status.contains("مستمر") || status.contains("ongoing") || status.contains("on-going") -> MangaState.ONGOING
			status.contains("مكتمل") || status.contains("completed") || status == "end" -> MangaState.FINISHED
			else -> null
		}
	}
}

internal data class CeneleChapterLocator(
	val mangaId: String,
	val chapterId: String,
)

internal fun attachCeneleChapterLocator(
	url: String,
	mangaId: String?,
	chapterId: String?,
): String {
	if (mangaId.isNullOrBlank() || chapterId.isNullOrBlank()) return url
	return "${url.substringBefore('#')}#cenele=$mangaId:$chapterId"
}

internal fun parseCeneleChapterLocator(url: String): CeneleChapterLocator? {
	val value = url.substringAfter("#cenele=", missingDelimiterValue = "")
	if (value.isEmpty()) return null
	val mangaId = value.substringBefore(':').takeIf(String::isNotBlank) ?: return null
	val chapterId = value.substringAfter(':', missingDelimiterValue = "").takeIf(String::isNotBlank) ?: return null
	return CeneleChapterLocator(mangaId, chapterId)
}
