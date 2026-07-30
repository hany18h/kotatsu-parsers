package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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
	PagedMangaParser(context, MangaParserSource.CENELE, pageSize = 12) {

	init {
		// Madara's load-more endpoint is zero based, while the regular search is one based.
		setFirstPage(firstPage = 0, firstPageForSearch = 1)
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
				else -> {
					// استخدام AJAX مثل MadaraParser
				}
			}
		}

		// للبحث استخدم GET، لغيره استخدم AJAX POST
		val doc = if (!filter.query.isNullOrEmpty()) {
			webClient.httpGet(url).parseHtml()
		} else {
			val payload = buildString {
				append("action=madara_load_more")
				append("&page=").append(page)
				append("&template=madara-core%2Fcontent%2Fcontent-search")
				append("&vars%5Bs%5D=")
				append("&vars%5Bpaged%5D=1")
				append("&vars%5Btemplate%5D=search")
				append("&vars%5Bmeta_query%5D%5B0%5D%5Brelation%5D=AND")
				append("&vars%5Bmeta_query%5D%5Brelation%5D=AND")
				append("&vars%5Bpost_type%5D=wp-manga")
				append("&vars%5Bpost_status%5D=publish")
				append("&vars%5Bmanga_archives_item_layout%5D=default")

				// ترتيب
				when (order) {
					SortOrder.UPDATED -> {
						append("&vars%5Bmeta_key%5D=_latest_update")
						append("&vars%5Borderby%5D=meta_value_num")
						append("&vars%5Border%5D=desc")
					}
					SortOrder.POPULARITY -> {
						append("&vars%5Bmeta_key%5D=_wp_manga_views")
						append("&vars%5Borderby%5D=meta_value_num")
						append("&vars%5Border%5D=desc")
					}
					SortOrder.NEWEST -> {
						append("&vars%5Borderby%5D=date")
						append("&vars%5Border%5D=desc")
					}
					SortOrder.ALPHABETICAL -> {
						append("&vars%5Borderby%5D=post_title")
						append("&vars%5Border%5D=asc")
					}
					else -> {
						append("&vars%5Bmeta_key%5D=_latest_update")
						append("&vars%5Borderby%5D=meta_value_num")
						append("&vars%5Border%5D=desc")
					}
				}

				// تصفية حسب التصنيف
				filter.tags.oneOrThrowIfMany()?.let {
					append("&vars%5Btax_query%5D%5B0%5D%5Btaxonomy%5D=wp-manga-genre")
					append("&vars%5Btax_query%5D%5B0%5D%5Bfield%5D=slug")
					append("&vars%5Btax_query%5D%5B0%5D%5Bterms%5D%5B0%5D=").append(it.key)
					append("&vars%5Btax_query%5D%5B0%5D%5Boperator%5D=IN")
				}

				// تصفية حسب الحالة
				filter.states.oneOrThrowIfMany()?.let {
					append("&vars%5Bmeta_query%5D%5B0%5D%5B0%5D%5Bkey%5D=_wp_manga_status")
					append("&vars%5Bmeta_query%5D%5B0%5D%5B0%5D%5Bcompare%5D=IN")
					append("&vars%5Bmeta_query%5D%5B0%5D%5B0%5D%5Bvalue%5D%5B%5D=")
					append(if (it == MangaState.FINISHED) "end" else "on-going")
				}
			}
			webClient.httpPost(
				"https://$domain/wp-admin/admin-ajax.php",
				payload,
			).parseHtml()
		}

		return doc.select("div.page-item-detail, div.row.c-tabs-item__content").map { div ->
			val a = div.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val img = div.selectFirst("img")
			val title = div.selectFirst("h3, h4, .manga-name")?.text()?.trim().orEmpty()
				.ifEmpty { a.attr("title") }
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
				state = null,
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

		val statusText = doc.selectFirst(".post-status .summary-content")
			?.text()?.trim().orEmpty().lowercase()
		val state = when {
			statusText.contains("مستمر") || statusText.contains("ongoing") -> MangaState.ONGOING
			statusText.contains("مكتمل") || statusText.contains("completed") || statusText.contains("end") -> MangaState.FINISHED
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

		val coverUrl = doc.selectFirst(".summary_image img")?.src() ?: manga.coverUrl
		val altTitle = doc.selectFirst(".manga-name-or, .post-content_item:contains(Alt) .summary-content")
			?.text()?.trim()

		// تحميل الفصول
		val chapters = loadChapters(manga.url.toAbsoluteUrl(domain), doc)

		return manga.copy(
			title = doc.selectFirst("div.post-title h1")?.text()?.trim() ?: manga.title,
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
		val fallback = doc.selectFirst("div.summary__content, .manga-excerpt .excerpt-content")
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

		val doc = webClient.httpGet(cleanUrl).parseHtml()
		val locator = parseCeneleChapterLocator(chapter.url)
		val content = locator?.let {
			loadChapterViaAjax(doc, cleanUrl, it)
		} ?: findDirectChapterContent(doc, locator)
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
			).parseHtml().selectFirst("div.text-left")
		}.getOrNull()
	}

	internal companion object {

		private val ZERO_WIDTH_MARKS = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
		private val LOAD_NONCE = Regex("""["']load_nonce["']\s*:\s*["']([^"']+)""")

		internal fun findDirectChapterContent(
			doc: Document,
			locator: CeneleChapterLocator?,
		): Element? {
			if (locator != null) {
				return doc.selectFirst("#chapter-${locator.chapterId} div.text-left")
			}
			return doc.selectFirst("div.reading-content.current div.text-left")
		}

		internal fun sanitizeChapterContent(content: Element): Element {
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
