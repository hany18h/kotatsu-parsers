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
import org.jsoup.Jsoup
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
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("GALAXYNOVELS", "مجرة الروايات", "ar", ContentType.NOVEL)
internal class GalaxyNovels(private val loaderContext: MangaLoaderContext) : PagedMangaParser(
	context = loaderContext,
	source = MangaParserSource.GALAXYNOVELS,
	pageSize = PAGE_SIZE,
) {

	override val configKeyDomain = ConfigKey.Domain("galaxynovels.com")

	// Use the same configured identity for HTTP and browser fallback requests.
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

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
		// Verification must use the browser's native network stack, not HTTP replay.
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = false))
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
		val url = getListPageUrl(page, order, filter)
		val document = loadPublicDocument(url, "https://$domain/", "$CATALOG_CARD_SELECTOR, .wor-library-empty")
		return parseNovelList(document)
	}

	internal fun getListPageUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
		val query = filter.query?.trim().orEmpty()
		val url = if (query.isEmpty() && filter.tags.isEmpty() && filter.states.isEmpty()) {
			when (order) {
				SortOrder.UPDATED -> "https://$domain/recent/?recent_page=$page"
				SortOrder.ALPHABETICAL -> "https://$domain/library/?sort=name&library_page=$page"
				else -> "https://$domain/novels/${if (page > 1) "page/$page/" else ""}?sort=popular&period=all"
			}
		} else {
			buildString {
				append("https://")
				append(domain)
				append("/library/?library_page=")
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
		return url
	}

	internal fun parseNovelList(document: Document): List<Manga> =
		document.select(CATALOG_CARD_SELECTOR).mapNotNull { card ->
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
		val document = loadPublicDocument(mangaUrl, "https://$domain/", DETAILS_READY_SELECTOR)
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
		// The page only renders the first 30 chapters. Its public manifest points
		// to a versioned pack containing the complete list (often thousands of
		// chapters). Follow the URL supplied by the site rather than guessing it.
		val manifestUrl = document.selectFirst("[data-wor-chapters-container][data-manifest-url]")
			?.attr("data-manifest-url")
			?.trim()
			?.takeIf(String::isNotEmpty)
			?.toAbsoluteUrl(domain)
		val packedChapters = manifestUrl?.let { url ->
			// Do not turn a denied manifest/pack into a successful but truncated library.
			val manifest = loadPublicJson(url, mangaUrl)
			val packUrl = checkNotNull(parseManifestPackUrl(manifest)) { "Galaxy chapter manifest is incomplete" }
			val metadata = JSONObject(manifest)
			val packed = parseCachedChapters(loadPublicJson(packUrl.toAbsoluteUrl(domain), mangaUrl))
			val recent = metadata.optJSONArray("live_tail")?.let {
				parseCachedChapters(JSONObject().put("chapters", it).toString())
			}.orEmpty()
			(packed + recent).distinctBy(MangaChapter::id).also {
				check(it.isNotEmpty() && it.size >= metadata.optInt("total", 0)) {
					"Galaxy returned an incomplete chapter pack"
				}
			}
		}.orEmpty()
		val chapters = packedChapters.ifEmpty { parseHtmlChapters(document) }
		return chapters
			.distinctBy(MangaChapter::id)
			.sortedBy(MangaChapter::number)
	}

	private suspend fun loadPublicJson(url: String, referer: String): String {
		val headers = siteHeaders(referer).newBuilder().set("Accept", "application/json").build()
		val direct = runCatchingCancellable { webClient.httpGet(url, headers).parseRaw() }
		rethrowPublicPageNetworkError(direct.exceptionOrNull())
		direct.getOrNull()?.takeIf(::isJsonObject)?.let { return it }
		// Public chapter packs can be challenged independently of the novel page.
		// Keep the browser session for these navigations too; do not return a
		// truncated 30-chapter list when the HTTP stack is rejected.
		val browser = runCatchingCancellable {
			loaderContext.evaluateJs(url, """
				(function() {
				  var text = document.body ? document.body.innerText.trim() : '';
				  try { var value = JSON.parse(text); return value && typeof value === 'object' ? text : null; }
				  catch (e) { return null; }
				})()
			""".trimIndent(), headers)?.let(::decodeWebViewString)
		}
		browser.getOrNull()?.takeIf(::isJsonObject)?.let { return it }
		loaderContext.requestBrowserAction(this, referer)
	}

	private fun isJsonObject(raw: String): Boolean = runCatching { JSONObject(raw) }.isSuccess

	internal fun parseManifestPackUrl(raw: String): String? = runCatching {
		JSONObject(raw).optString("pack_url").trim().takeIf(String::isNotEmpty)
	}.getOrNull()

	internal fun parseCachedChapters(raw: String): List<MangaChapter> {
		val array = runCatching { JSONObject(raw).optJSONArray("chapters") }.getOrNull() ?: return emptyList()
		return buildList {
			for (index in 0 until array.length()) {
				val item = array.optJSONObject(index) ?: continue
				val publicUrl = item.optString("url").trim().takeIf(String::isNotEmpty) ?: continue
				// content_api is protected and may return "server blocked you" while
				// the same chapter's normal public reader URL works normally.
				val url = publicUrl
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

	private suspend fun loadPublicDocument(url: String, referer: String, readySelector: String): Document {
		// Public catalogue and novel pages normally work over HTTP and arrive with
		// their useful markup already present. Prefer that fast path. The previous
		// WebView-first implementation could return at DOM interactive state before
		// Galaxy's scripts inserted any cards, resulting in a successful empty list.
		val directResult = runCatchingCancellable {
			webClient.httpGet(url, siteHeaders(referer)).parseHtml()
		}
		rethrowPublicPageNetworkError(directResult.exceptionOrNull())
		directResult.getOrNull()?.takeIf { isReadableDocument(it, readySelector) }?.let { return it }

		// Some networks are still challenged at the HTTP edge. In that case use a
		// real WebView, but keep polling until the page's useful content exists.
		val webViewResult = runCatchingCancellable {
			loadPageDocumentInWebView(url, readySelector)
		}
		webViewResult.getOrNull()?.takeIf { isReadableDocument(it, readySelector) }?.let { return it }

		loaderContext.requestBrowserAction(this, url)
	}

	internal fun isReadableDocument(document: Document, readySelector: String): Boolean =
		!isBlockedDocument(document) && document.selectFirst(readySelector) != null

	internal fun isBlockedDocument(document: Document): Boolean {
		if (document.selectFirst("#challenge-form, #challenge-running, script[src*='/cdn-cgi/challenge-platform/']") != null) {
			return true
		}
		val text = document.text().lowercase(Locale.ROOT)
		return BLOCK_PAGE_MARKERS.any(text::contains) ||
			document.select("script").any { "window._cf_chl_opt" in it.data() }
	}

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val requestUrl = findLegacyChapterPostId(chapter.url)?.let { postId ->
			// Older versions stored wor-reader-app REST URLs. That endpoint is now
			// rejected with 403, even while the public chapter remains available.
			// WordPress' canonical post resolver redirects the same chapter id to
			// its normal reader URL, so existing libraries recover automatically.
			"https://$domain/?p=$postId"
		} ?: chapterUrl

		// The public reader is normally available as complete HTML. Avoid starting
		// WebView for every chapter; it is slower and can return before client-side
		// markup settles. WebView remains the challenge fallback below.
		val directResult = runCatchingCancellable {
			val response = webClient.httpGet(
				requestUrl,
				siteHeaders("https://$domain/", isChapterRequest = true),
			)
			val resolvedChapterUrl = response.request.url.toString()
			response.parseHtml() to resolvedChapterUrl
		}
		rethrowPublicPageNetworkError(directResult.exceptionOrNull())
		directResult.getOrNull()?.let { (document, resolvedChapterUrl) ->
			extractChapterContent(document)?.let { content ->
				return sanitizeChapterContent(content, resolvedChapterUrl)
			}
		}

		val webViewResult = runCatchingCancellable {
			loadChapterDocumentInWebView(requestUrl)
		}
		webViewResult.getOrNull()?.let { document ->
			extractChapterContent(document)?.let { content ->
				return sanitizeChapterContent(content, requestUrl)
			}
		}
		loaderContext.requestBrowserAction(this, requestUrl)
	}

	private suspend fun loadChapterDocumentInWebView(chapterUrl: String): Document? {
		val rawResult = loaderContext.evaluateJs(
			chapterUrl,
			"""
			(function() {
			  var content = document.querySelector('$CHAPTER_CONTENT_SELECTOR');
			  if (document.querySelector('#challenge-form, #challenge-running') || window._cf_chl_opt) return null;
			  if (document.querySelector('#cf-error-details')) return document.documentElement.outerHTML;
			  return content && (content.textContent.trim() || content.querySelector('img')) ? content.outerHTML : null;
			})()
			""".trimIndent(),
			headers = siteHeaders("https://$domain/"),
		) ?: return null
		val html = decodeWebViewString(rawResult) ?: return null
		return Jsoup.parse(html, chapterUrl)
	}

	private suspend fun loadPageDocumentInWebView(pageUrl: String, readySelector: String): Document? {
		val rawResult = loaderContext.evaluateJs(
			pageUrl,
			"""
			(function() {
			  if (document.readyState === 'loading') return null;
			  if (document.querySelector('#challenge-form, #challenge-running') || window._cf_chl_opt) return null;
			  if (!document.querySelector('$readySelector, #cf-error-details')) return null;
			  var root = document.documentElement;
			  return root ? root.outerHTML : null;
			})()
			""".trimIndent(),
			headers = siteHeaders("https://$domain/"),
		) ?: return null
		val html = decodeWebViewString(rawResult) ?: return null
		return Jsoup.parse(html, pageUrl)
	}

	internal fun decodeWebViewString(rawResult: String): String? = runCatching {
		JSONObject("{\"value\":$rawResult}").optString("value").trim().takeIf(String::isNotEmpty)
	}.getOrNull()

	internal fun parseReaderApiContent(response: JSONObject): NovelChapterContent? {
		val data = response.optJSONObject("data") ?: response
		val html = data.optString("content_html").trim().takeIf(String::isNotEmpty) ?: return null
		val chapterUrl = data.optString("url").trim().takeIf(String::isNotEmpty)
			?.toAbsoluteUrl(domain)
			?: "https://$domain/"
		val content = org.jsoup.Jsoup.parseBodyFragment(html).body()
		return sanitizeChapterContent(content, chapterUrl)
	}

	private fun sanitizeChapterContent(content: Element, chapterUrl: String): NovelChapterContent? {
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

	internal fun extractChapterContent(document: Document): Element? =
		if (isBlockedDocument(document)) null else document.selectFirst(CHAPTER_CONTENT_SELECTOR)

	internal fun siteHeaders(referer: String, isChapterRequest: Boolean = false): Headers {
		return Headers.Builder()
		.add("Referer", referer)
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.add("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
		.add("Sec-Fetch-Dest", "document")
		.add("Sec-Fetch-Mode", "navigate")
		.add("Sec-Fetch-Site", "same-origin")
		.add("Upgrade-Insecure-Requests", "1")
		.add("User-Agent", config[userAgentKey])
		.apply {
			if (isChapterRequest) {
				add("Cookie", "wor_reader_js=1")
				add("X-Wor-Continuous", "1")
			}
		}
		.build()
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
		internal const val CATALOG_CARD_SELECTOR = "article.wor-novel-card, article.wor-library-card"
		private const val DETAILS_READY_SELECTOR =
			"[data-wor-chapters-container][data-manifest-url], article.wor-novel-chapter-item"
		private val BLOCK_PAGE_MARKERS = listOf(
			"تم حظرك من قبل الخادم",
			"حاول استخدام شبكة اتصال مختلفة",
			"you have been blocked",
			"attention required! | cloudflare",
			"sorry, you have been blocked",
		)
		internal const val CHAPTER_CONTENT_SELECTOR =
			".wor-reader-text-surface, .wor-reading-page__content, .wor-chapter-content, " +
				".entry-content, .chapter-content, .post-content, article .content"
		private val READER_API_CHAPTER_ID = Regex("""/wp-json/wor-reader-app/v1/chapters/(\d+)""")

		internal fun findLegacyChapterPostId(url: String): String? =
			READER_API_CHAPTER_ID.find(url)?.groupValues?.getOrNull(1)

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
