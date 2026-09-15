package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("CENELE", "فضاء الروايات", "ar", ContentType.NOVEL)
internal class Cenele(private val loaderContext: MangaLoaderContext) :
	PagedMangaParser(loaderContext, MangaParserSource.CENELE, pageSize = HTML_LIBRARY_PAGE_SIZE) {

	init {
		setFirstPage(firstPage = 1, firstPageForSearch = 1)
	}

	override val configKeyDomain = ConfigKey.Domain("cenele.com")
	override val userAgentKey = ConfigKey.UserAgent(CENELE_MOBILE_USER_AGENT)

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
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = false))
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = CENELE_GENRES.mapTo(LinkedHashSet()) { title ->
			MangaTag(key = title, title = title, source = source)
		},
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
			val root = if (tag == null) "/cont/" else "/cont-genre/${tag.urlEncoded()}/"
			val path = if (page <= 1) root else "${root}page/$page/"
			"https://$domain$path?m_orderby=$sort"
		}
		val doc = loadPublicDocument(url, "https://$domain/")

		return doc.select("article.nhv-library-card, div.row.c-tabs-item__content").mapNotNull { card ->
			val link = card.selectFirst(".nhv-library-card__title a, .post-title a, h3 a, h4 a")
				?: card.selectFirst("a[href*=/cont/]")
				?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href")
			val title = card.selectFirst(".nhv-library-card__title, .post-title, h3, h4, .manga-name")
				?.text()?.trim().orEmpty()
				.ifEmpty { link.attr("title") }
			if (title.isEmpty()) return@mapNotNull null
			val status = parseState(
				card.selectFirst(".nhv-library-card__status, .summary-content")?.text().orEmpty(),
			)
			if (filter.states.isNotEmpty() && status !in filter.states) return@mapNotNull null
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = card.selectFirst(".nhv-library-card__cover img, .tab-thumb img, img")?.src(),
				tags = emptySet(),
				state = status,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val pageUrl = manga.url.substringBefore('#').toAbsoluteUrl(domain)
		val doc = loadPublicDocument(pageUrl, "https://$domain/cont/", noCache = true)
		val state = parseState(
			doc.selectFirst(".nhv-novel-status, .post-status .summary-content")?.text().orEmpty(),
		)
		val authors = doc.select(".nhv-novel-integrations a[href*=/cont-artist/], .author-content a")
			.mapNotNullToSet { it.text().trim().ifEmpty { null } }
		val tags = doc.select(".nhv-novel-genres a, .genres-content a").mapToSet { link ->
			MangaTag(
				key = link.attr("href").removeSuffix("/").substringAfterLast("/"),
				title = link.text().trim(),
				source = source,
			)
		}
		val cover = doc.selectFirst(".nhv-novel-cover img, .summary_image img")?.src() ?: manga.coverUrl
		val altTitle = doc.selectFirst(".manga-name-or, .post-content_item:contains(Alt) .summary-content")
			?.text()?.trim()

		return manga.copy(
			title = doc.selectFirst("h1.nhv-novel-title, div.post-title h1")?.text()?.trim() ?: manga.title,
			altTitles = setOfNotNull(altTitle?.ifEmpty { null }),
			url = manga.url.substringBefore('#'),
			publicUrl = pageUrl,
			description = loadDescription(doc),
			coverUrl = cover,
			largeCoverUrl = cover,
			state = state ?: manga.state,
			authors = authors,
			tags = tags,
			chapters = loadChapters(pageUrl, doc),
		)
	}

	private fun loadDescription(doc: Document): String? =
		doc.selectFirst(".nhv-novel-synopsis, div.summary__content, .manga-excerpt .excerpt-content")
			?.html()?.trim()?.takeIf(String::isNotEmpty)

	private suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
		val mangaId = doc.selectFirst("[data-manga-id], [data-post]")?.let {
			it.attr("data-manga-id").ifEmpty { it.attr("data-post") }
		}?.takeIf(String::isNotBlank)
		// Older public pages contain the entire list. Modern pages expose a
		// volumes accordion. Let the site's own buttons load it in the browser;
		// never call Cenele's REST/AJAX endpoints from the parser.
		val inline = doc.select("ul.main li.wp-manga-chapter")
		if (inline.isNotEmpty()) return parseChapterElements(inline, mangaId)
		val raw = loaderContext.evaluateJs(
			mangaUrl,
			CenelePublicChapters.SCRIPT,
			siteHeaders(mangaUrl, noCache = true),
			timeoutMillis = 180_000L,
		)?.let(::decodeWebViewString)
		val result = raw?.let(::JSONObject)
		if (result?.optBoolean("complete") != true) {
			loaderContext.requestBrowserAction(this, mangaUrl)
		}
		val chaptersDoc = Jsoup.parse(result.getString("html"), mangaUrl)
		return parseChapterElements(chaptersDoc.select("li.wp-manga-chapter"), mangaId)
			.distinctBy(MangaChapter::id).sortedBy(MangaChapter::number)
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
		val locator = parseCeneleChapterLocator(chapter.url)

		// Use only the public reader page. Its markup is randomized on every
		// response, so bypass cached/304 bodies and locate the stable chapter marker.
		val doc = loadPublicDocument(cleanUrl, "https://$domain/", noCache = true)
		val content = findDirectChapterContent(doc, locator)
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

	private suspend fun loadPublicDocument(
		url: String,
		referer: String,
		noCache: Boolean = false,
	): Document {
		// The useful public markup is normally present in the initial response.
		// Prefer that fast path and reserve WebView for networks that are actually
		// challenged, otherwise every catalog/chapter open pays a browser startup.
		val directResult = runCatchingCancellable {
			webClient.httpGet(url, siteHeaders(referer, noCache)).parseHtml()
		}
		rethrowPublicPageNetworkError(directResult.exceptionOrNull())
		directResult.getOrNull()?.takeUnless(::isBlockedDocument)?.let { return it }

		val webViewResult = runCatchingCancellable {
			val rawResult = loaderContext.evaluateJs(
				url,
				"""
				(function() {
				  if (document.readyState === 'loading') return null;
				  if (document.querySelector('#challenge-form, #challenge-running') || window._cf_chl_opt) return null;
				  if (!document.querySelector('article.nhv-library-card, .c-tabs-item__content, h1.nhv-novel-title, .post-title h1, .reading-content, .nhv-library-empty, .no-results, #cf-error-details')) return null;
				  return document.documentElement.outerHTML;
				})()
				""".trimIndent(),
				headers = siteHeaders(referer, noCache),
			) ?: return@runCatchingCancellable null
			decodeWebViewString(rawResult)?.let { Jsoup.parse(it, url) }
		}
		webViewResult.getOrNull()?.takeUnless(::isBlockedDocument)?.let { return it }

		loaderContext.requestBrowserAction(this, url)
	}

	private fun siteHeaders(referer: String, noCache: Boolean = false): Headers = Headers.Builder()
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.add("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
		.add("Referer", referer)
		.add("Upgrade-Insecure-Requests", "1")
		.add("User-Agent", config[userAgentKey])
		.apply {
			if (noCache) {
				add("Cache-Control", "no-cache, no-store")
				add("Pragma", "no-cache")
			}
		}
		.build()

	internal companion object {
		internal const val CENELE_MOBILE_USER_AGENT =
			"Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
				"Chrome/131.0.0.0 Mobile Safari/537.36"

		internal fun decodeWebViewString(rawResult: String): String? = runCatching {
			JSONObject("{\"value\":$rawResult}").optString("value").trim().takeIf(String::isNotEmpty)
		}.getOrNull()

		internal fun isBlockedDocument(document: Document): Boolean {
			val text = (document.title() + " " + document.text()).lowercase(Locale.ROOT)
			if (document.selectFirst("#challenge-form, #challenge-running") != null ||
				document.select("script").any { "window._cf_chl_opt" in it.data() } ||
				BLOCK_PAGE_MARKERS.any(text::contains)
			) {
				return true
			}
			// Cloudflare may append its lightweight JSD script to a complete public
			// page. Treat it as a challenge only when none of Cenele's useful public
			// markup is present, otherwise a valid chapter is sent to browser action.
			val hasPublicMarkup = document.selectFirst(
				"article.nhv-library-card, .c-tabs-item__content, h1.nhv-novel-title, " +
					".post-title h1, .reading-content, .nhv-library-empty, .no-results",
			) != null
			return !hasPublicMarkup &&
				document.selectFirst("script[src*='/cdn-cgi/challenge-platform/']") != null
		}

		private val ZERO_WIDTH_MARKS = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
		private val ARABIC_DECORATION = Regex("[\\u0640\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
		private val HIDDEN_CSS_CLASS = Regex(
			"""\.([A-Za-z][\w-]*)\s*\{[^{}]*display\s*:\s*none(?:\s*!important)?[^{}]*\}""",
			RegexOption.IGNORE_CASE,
		)
		private const val HTML_LIBRARY_PAGE_SIZE = 10
		private val CENELE_GENRES = listOf(
			"أكشن", "استراتجي", "انتقام", "بالغ", "بطل شرير", "بناء مملكة", "بوليسي",
			"تاريخي", "تشويق", "حريم", "حياة يومية", "خيال", "خيال علمي", "دراما", "رعب",
			"رومانسية", "زراعة", "سحر", "شونين", "عسكري", "غموض", "فانتازيا", "فنون قتالية",
			"قوى خارقة", "كوميديا", "للكبار", "لعبة", "مأساة", "مدرسي", "مغامرة", "نظام", "نفسي",
		)
		private val BLOCK_PAGE_MARKERS = listOf(
			"تم حظرك من قبل الخادم",
			"حاول استخدام شبكة اتصال مختلفة",
			"you have been blocked",
			"attention required! | cloudflare",
			"sorry, you have been blocked",
		)

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
			// Public reader bait is now positioned offscreen, not display:none.
			// Both attributes identify its wrapper even when tags, classes and text change.
			content.select("[inert][data-nosnippet]").remove()
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

			// Work from the innermost span outwards so formatted inline bait is
			// removed before checking the real paragraph that contains it.
			content.select("p, span").asReversed().forEach { element ->
				if (isAntiCopyText(element.text())) {
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
			// Normalize Arabic presentation forms, tatweel, diacritics and invisible
			// separators only for matching; never rewrite the actual chapter text.
			val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
				.let { ZERO_WIDTH_MARKS.replace(it, "") }
				.let { ARABIC_DECORATION.replace(it, "") }
				.replace(Regex("\\s+"), " ")
				.trim()
				.lowercase(Locale.ROOT)
			if ("فضاء الروايات" !in normalized && "cenele.com" !in normalized) return false
			return "نص تمويهي" in normalized ||
				"هذا تنبيه" in normalized ||
				"تطبيق سارق" in normalized ||
				"هذا التطبيق يسرق" in normalized ||
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
