package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("CENELE", "فضاء الروايات", "ar", ContentType.NOVEL)
internal class Cenele(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CENELE, pageSize = API_LIBRARY_PAGE_SIZE) {

	private val novelMetaBySlug = ConcurrentHashMap<String, CeneleNovelMeta>()

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
		availableTags = CENELE_GENRES.mapTo(LinkedHashSet()) { title ->
			MangaTag(key = title, title = title, source = source)
		},
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val orderBy = when (order) {
			SortOrder.POPULARITY -> "views"
			SortOrder.ALPHABETICAL -> "alphabet"
			else -> "latest"
		}
		val url = buildString {
			append("https://")
			append(domain)
			append("/wp-json/nhv/v1/library?orderby=")
			append(orderBy)
			append("&order=desc&page=")
			append(page)
			append("&per_page=")
			append(API_LIBRARY_PAGE_SIZE)
			if (query.isNotEmpty()) {
				append("&search=")
				append(query.urlEncoded())
			}
			filter.tags.oneOrThrowIfMany()?.let { tag ->
				append("&genre=")
				append(tag.key.urlEncoded())
			}
		}
		val items = apiGet(url).optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
		return buildList {
			for (index in 0 until items.length()) {
				val item = items.optJSONObject(index) ?: continue
				val parsed = parseApiManga(item) ?: continue
				if (filter.states.isEmpty() || parsed.state in filter.states) add(parsed)
			}
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
		val novelId = resolveNovelId(manga) ?: return manga
		val response = apiGet("https://$domain/wp-json/nhv/v1/novels/$novelId")
		val data = response.optJSONObject("data") ?: return manga
		val permalink = data.optString("permalink").trim().takeIf(String::isNotEmpty)
			?: manga.publicUrl
		val relativeUrl = permalink.toRelativeUrl(domain)
		val version = data.optInt("version", 0)
		val slug = extractCeneleNovelSlug(relativeUrl)
		if (slug != null) novelMetaBySlug[slug] = CeneleNovelMeta(novelId, version)
		val cover = data.optString("cover").trim().takeIf(String::isNotEmpty) ?: manga.coverUrl
		val rating = data.optJSONObject("rating")?.optDouble("avg", Double.NaN)
			?.takeUnless(Double::isNaN)?.div(10.0)?.toFloat()?.coerceIn(0f, 1f)
			?: manga.rating

		return manga.copy(
			title = data.optString("title").trim().takeIf(String::isNotEmpty) ?: manga.title,
			altTitles = setOfNotNull(data.optString("alternative").trim().takeIf(String::isNotEmpty)),
			url = attachCeneleNovelLocator(relativeUrl, novelId),
			publicUrl = permalink,
			rating = rating,
			description = data.optString("summary").trim().takeIf(String::isNotEmpty) ?: manga.description,
			coverUrl = cover,
			largeCoverUrl = cover,
			state = parseState(data.optString("status")) ?: manga.state,
			authors = parseApiNames(data, "authors").ifEmpty { manga.authors },
			tags = parseApiTags(data).ifEmpty { manga.tags },
			chapters = loadApiChapters(novelId, permalink),
		)
	}

	private fun parseApiManga(item: JSONObject): Manga? {
		val novelId = item.optLong("id", 0L).takeIf { it > 0L } ?: return null
		val publicUrl = item.optString("permalink").trim().takeIf(String::isNotEmpty) ?: return null
		val relativeUrl = publicUrl.toRelativeUrl(domain)
		val slug = item.optString("slug").trim().takeIf(String::isNotEmpty)
			?: extractCeneleNovelSlug(relativeUrl)
		if (slug != null) novelMetaBySlug[slug] = CeneleNovelMeta(novelId.toString(), 0)
		val rating = item.optDouble("rating", Double.NaN).takeUnless(Double::isNaN)
			?.div(10.0)?.toFloat()?.coerceIn(0f, 1f) ?: RATING_UNKNOWN
		return Manga(
			id = generateUid(relativeUrl),
			title = item.optString("title").trim().takeIf(String::isNotEmpty) ?: return null,
			altTitles = emptySet(),
			url = attachCeneleNovelLocator(relativeUrl, novelId.toString()),
			publicUrl = publicUrl,
			rating = rating,
			contentRating = null,
			coverUrl = item.optString("cover").trim().takeIf(String::isNotEmpty),
			tags = parseApiTags(item),
			state = parseState(item.optString("status")),
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseApiTags(data: JSONObject): Set<MangaTag> {
		val values = data.optJSONArray("genres") ?: return emptySet()
		return buildSet {
			for (index in 0 until values.length()) {
				val item = values.optJSONObject(index) ?: continue
				val title = item.optString("name").trim().takeIf(String::isNotEmpty) ?: continue
				add(MangaTag(key = title, title = title, source = source))
			}
		}
	}

	private fun parseApiNames(data: JSONObject, key: String): Set<String> {
		val values = data.optJSONArray(key) ?: return emptySet()
		return buildSet {
			for (index in 0 until values.length()) {
				values.optJSONObject(index)?.optString("name")?.trim()
					?.takeIf(String::isNotEmpty)?.let(::add)
			}
		}
	}

	private suspend fun loadApiChapters(novelId: String, mangaUrl: String): List<MangaChapter> {
		val result = ArrayList<MangaChapter>()
		var page = 1
		var totalPages: Int
		do {
			val response = apiGet(
				"https://$domain/wp-json/nhv/v1/novels/$novelId/chapters" +
					"?order=asc&per_page=$API_CHAPTERS_PAGE_SIZE&page=$page&fields=min",
			)
			val data = response.optJSONObject("data") ?: break
			val chapters = data.optJSONArray("chapters") ?: break
			for (index in 0 until chapters.length()) {
				parseApiChapter(chapters.optJSONObject(index) ?: continue, novelId, mangaUrl, result.size)
					?.let(result::add)
			}
			totalPages = data.optInt("total_pages", page)
			page++
		} while (page <= totalPages && page <= MAX_API_CHAPTER_PAGES)
		return result.distinctBy(MangaChapter::id).sortedBy(MangaChapter::number)
	}

	private fun parseApiChapter(
		item: JSONObject,
		novelId: String,
		mangaUrl: String,
		fallbackIndex: Int,
	): MangaChapter? {
		val chapterId = item.optLong("chapter_id", 0L).takeIf { it > 0L } ?: return null
		val title = listOf(
			item.optString("chapter_name").trim(),
			item.optString("chapter_name_extend").trim(),
		).filter(String::isNotEmpty).joinToString(" — ").ifEmpty { "الفصل ${fallbackIndex + 1}" }
		val publicUrl = item.optString("url").trim().takeIf(String::isNotEmpty) ?: buildString {
			append(mangaUrl.trimEnd('/'))
			append('/')
			item.optString("volume_slug").trim().takeIf(String::isNotEmpty)?.let { volume ->
				append(volume.trim('/'))
				append('/')
			}
			append(item.optString("chapter_slug").trim().trim('/'))
			append('/')
		}
		val relativeUrl = publicUrl.toRelativeUrl(domain)
		val number = parseChapterNumber(title) ?: (fallbackIndex + 1).toFloat()
		return MangaChapter(
			id = generateUid(relativeUrl),
			title = title,
			number = number,
			volume = 0,
			url = attachCeneleChapterLocator(relativeUrl, novelId, chapterId.toString()),
			scanlator = null,
			uploadDate = parseApiDate(item.optString("date")),
			branch = null,
			source = source,
		)
	}

	private suspend fun apiGet(url: String): JSONObject = webClient.httpGet(
		url,
		Headers.Builder()
			.add("Accept", "application/json")
			.add("Referer", "https://$domain/")
			.add("User-Agent", config[userAgentKey])
			.build(),
	).parseJson()

	private suspend fun resolveNovelId(manga: Manga): String? {
		parseCeneleNovelLocator(manga.url)?.let { return it }
		val slug = extractCeneleNovelSlug(manga.url) ?: return null
		novelMetaBySlug[slug]?.let { return it.id }
		val query = manga.title.trim().takeIf(String::isNotEmpty)
		if (query != null) {
			val items = runCatching {
				apiGet(
					"https://$domain/wp-json/nhv/v1/library?per_page=$API_CATALOG_PAGE_SIZE&page=1" +
						"&search=${query.urlEncoded()}",
				).optJSONObject("data")?.optJSONArray("items")
			}.getOrNull()
			if (items != null) {
				cacheCatalogItems(items)
				novelMetaBySlug[slug]?.let { return it.id }
			}
		}
		return resolveNovelMetaBySlug(slug)?.id
	}

	private suspend fun resolveNovelMetaBySlug(slug: String): CeneleNovelMeta? {
		novelMetaBySlug[slug]?.let { return it }
		var page = 1
		var totalPages: Int
		do {
			val response = apiGet(
				"https://$domain/wp-json/nhv/v1/library?per_page=$API_CATALOG_PAGE_SIZE&page=$page",
			)
			val data = response.optJSONObject("data") ?: break
			cacheCatalogItems(data.optJSONArray("items"))
			novelMetaBySlug[slug]?.let { return it }
			totalPages = data.optInt("total_pages", page)
			page++
		} while (page <= totalPages && page <= MAX_API_CATALOG_PAGES)
		return novelMetaBySlug[slug]
	}

	private fun cacheCatalogItems(items: org.json.JSONArray?) {
		if (items == null) return
		for (index in 0 until items.length()) {
			val item = items.optJSONObject(index) ?: continue
			val slug = item.optString("slug").trim().takeIf(String::isNotEmpty) ?: continue
			val id = item.optLong("id", 0L).takeIf { it > 0L } ?: continue
			novelMetaBySlug.putIfAbsent(slug, CeneleNovelMeta(id.toString(), 0))
		}
	}

	private suspend fun resolveLegacyChapterLocator(chapter: MangaChapter): CeneleChapterLocator? {
		val slug = extractCeneleNovelSlug(chapter.url) ?: return null
		var meta = resolveNovelMetaBySlug(slug) ?: return null
		if (meta.version <= 0) {
			val manifest = apiGet(
				"https://$domain/wp-json/cenele-app/v1/novels/${meta.id}/chapters/manifest" +
					"?per_page=$API_CHAPTERS_PAGE_SIZE",
			).optJSONObject("data") ?: return null
			val version = manifest.optInt("version", 0).takeIf { it > 0 } ?: return null
			meta = meta.copy(version = version)
			novelMetaBySlug[slug] = meta
		}
		val number = parseChapterNumber(chapter.title.orEmpty())
			?: parseChapterNumber(chapter.url)
			?: return null
		val volume = extractCeneleVolumeSlug(chapter.url)
		val locateUrl = buildString {
			append("https://")
			append(domain)
			append("/wp-json/cenele-app/v1/novels/")
			append(meta.id)
			append("/chapters/locate?v=")
			append(meta.version)
			append("&number=")
			append(formatChapterNumber(number).urlEncoded())
			append("&order=asc&per_page=")
			append(API_CHAPTERS_PAGE_SIZE)
			if (volume != null) {
				append("&volume=")
				append(volume.urlEncoded())
			}
		}
		val chapterId = apiGet(locateUrl).optJSONObject("data")
			?.optJSONObject("chapter")
			?.optLong("chapter_id", 0L)
			?.takeIf { it > 0L }
			?: return null
		return CeneleChapterLocator(meta.id, chapterId.toString())
	}

	private fun parseApiDate(value: String): Long = synchronized(API_DATE_FORMAT) {
		runCatching { API_DATE_FORMAT.parse(value)?.time }.getOrNull() ?: 0L
	}

	private fun parseChapterNumber(value: String): Float? =
		CHAPTER_NUMBER.find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
			?: NUMBER.find(value)?.value?.toFloatOrNull()

	private fun formatChapterNumber(value: Float): String =
		if (value % 1f == 0f) value.toInt().toString() else value.toString()

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
		val locator = parseCeneleChapterLocator(chapter.url)
			?: runCatching { resolveLegacyChapterLocator(chapter) }.getOrNull()

		// Cenele exposes the same clean chapter payload used by its official app.
		// Prefer that public endpoint: the normal HTML page is protected by
		// network-dependent Cloudflare/anti-copy layers and also injects fake
		// paragraphs. Keep the HTML reader below for old saved chapters that do
		// not contain a chapter locator and as a compatibility fallback.
		if (locator != null) {
			val candidates = listOf(
				"https://$domain/wp-json/cenele-app/v1/chapters/${locator.chapterId}",
				"https://$domain/?rest_route=/cenele-app/v1/chapters/${locator.chapterId}",
				"https://$domain/index.php?rest_route=/cenele-app/v1/chapters/${locator.chapterId}",
			)
			for (candidate in candidates) {
				val appContent = runCatching {
					webClient.httpGet(
						candidate,
						Headers.Builder()
							.add("Accept", "application/json")
							.add("Referer", cleanUrl)
							.add("User-Agent", config[userAgentKey])
							.build(),
					).parseJson()
				}.getOrNull()?.let { response ->
					parseAppChapterContent(response, cleanUrl, chapter.title.orEmpty())
				}
				if (appContent != null) return appContent
			}
		}

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

	internal fun parseAppChapterContent(
		response: JSONObject,
		chapterUrl: String,
		fallbackTitle: String,
	): NovelChapterContent? {
		if (!response.optBoolean("success")) return null
		val data = response.optJSONObject("data") ?: return null
		val html = data.optString("content").trim().takeIf(String::isNotEmpty) ?: return null
		val content = Jsoup.parseBodyFragment(html, chapterUrl).body()
		sanitizeChapterContent(content)
		val title = data.optJSONObject("chapter")
			?.optString("chapter_name")
			?.trim()
			?.takeIf(String::isNotEmpty)
			?: fallbackTitle
		val images = content.select("img").mapNotNull { image ->
			image.src()?.let { url ->
				NovelImage(
					url = url,
					headers = mapOf(
						"Referer" to chapterUrl,
						"User-Agent" to config[userAgentKey],
					),
				)
			}
		}.distinctBy(NovelImage::url)
		if (content.text().isBlank() && images.isEmpty()) return null
		return NovelChapterContent(
			html = buildString {
				if (title.isNotBlank()) append(Element("h1").text(title).outerHtml())
				append(content.html())
			},
			images = images,
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
		private val CHAPTER_NUMBER = Regex("""(?:الفصل|chapter)\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
		private val NUMBER = Regex("""[0-9]+(?:\.[0-9]+)?""")
		private val API_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
		private val LOAD_NONCE = Regex("""["']load_nonce["']\s*:\s*["']([^"']+)""")
		private val CHAPTERS_POST_ID = Regex("""["']postId["']\s*:\s*["']?(\d+)""")
		private val CHAPTERS_NONCE = Regex("""["']chaptersNonce["']\s*:\s*["']([^"']+)""")
		private val HIDDEN_CSS_CLASS = Regex(
			"""\.([A-Za-z][\w-]*)\s*\{[^{}]*display\s*:\s*none(?:\s*!important)?[^{}]*\}""",
			RegexOption.IGNORE_CASE,
		)
		private const val CHAPTERS_PER_PAGE = 100
		private const val MAX_CHAPTER_PAGES = 50
		private const val API_LIBRARY_PAGE_SIZE = 24
		private const val API_CATALOG_PAGE_SIZE = 60
		private const val API_CHAPTERS_PAGE_SIZE = 300
		private const val MAX_API_CATALOG_PAGES = 20
		private const val MAX_API_CHAPTER_PAGES = 100
		private val CENELE_GENRES = listOf(
			"أكشن", "استراتجي", "انتقام", "بالغ", "بطل شرير", "بناء مملكة", "بوليسي",
			"تاريخي", "تشويق", "حريم", "حياة يومية", "خيال", "خيال علمي", "دراما", "رعب",
			"رومانسية", "زراعة", "سحر", "شونين", "عسكري", "غموض", "فانتازيا", "فنون قتالية",
			"قوى خارقة", "كوميديا", "للكبار", "لعبة", "مأساة", "مدرسي", "مغامرة", "نظام", "نفسي",
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

internal data class CeneleNovelMeta(
	val id: String,
	val version: Int,
)

internal fun attachCeneleNovelLocator(url: String, novelId: String): String =
	"${url.substringBefore('#')}#cenele-novel=$novelId"

internal fun parseCeneleNovelLocator(url: String): String? =
	url.substringAfter("#cenele-novel=", missingDelimiterValue = "").takeIf(String::isNotBlank)

internal fun extractCeneleNovelSlug(url: String): String? = url
	.substringBefore('#')
	.substringAfter("/cont/", missingDelimiterValue = "")
	.trim('/')
	.substringBefore('/')
	.takeIf(String::isNotBlank)

internal fun extractCeneleVolumeSlug(url: String): String? {
	val parts = url.substringBefore('#')
		.substringAfter("/cont/", missingDelimiterValue = "")
		.trim('/')
		.split('/')
	return parts.getOrNull(1)?.takeIf { parts.size >= 3 && it.isNotBlank() }
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
