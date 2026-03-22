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
	PagedMangaParser(context, MangaParserSource.CENELE, pageSize = 12) {

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
		return doc.select("li a[href*=novel-genre]").mapNotNullToSet { a ->
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
			description = doc.selectFirst("div.summary__content")?.html(),
			coverUrl = coverUrl,
			largeCoverUrl = coverUrl,
			state = state,
			authors = authors,
			tags = tags,
			chapters = chapters,
		)
	}

	private suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
		// أولاً جرب inline chapters
		val inline = doc.select("ul.main li.wp-manga-chapter")
		if (inline.isNotEmpty()) return parseChapterElements(inline)

		// ثم جرب ajax/chapters/ (الطريقة الأحدث في Madara)
		val ajaxDoc = runCatching {
			val ajaxUrl = mangaUrl.trimEnd('/') + "/ajax/chapters/"
			webClient.httpPost(ajaxUrl, emptyMap()).parseHtml()
		}.getOrNull()

		if (ajaxDoc != null) {
			val items = ajaxDoc.select("ul.main li.wp-manga-chapter")
			if (items.isNotEmpty()) return parseChapterElements(items)
		}

		// أخيراً جرب admin-ajax.php
		val mangaId = doc.selectFirst("#manga-chapters-holder")?.attr("data-id") ?: return emptyList()
		val adminDoc = webClient.httpPost(
			"https://$domain/wp-admin/admin-ajax.php",
			mapOf("action" to "manga_get_chapters", "manga" to mangaId),
		).parseHtml()
		return parseChapterElements(adminDoc.select("ul.main li.wp-manga-chapter"))
	}

	private fun parseChapterElements(elements: org.jsoup.select.Elements): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("ar"))
		val dateFormatEn = SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH)
		val dateFormatShort = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)

		return elements.mapIndexedNotNull { index, li ->
			val a = li.selectFirst("a") ?: return@mapIndexedNotNull null
			// نأخذ href النظيف بدون ?style=list
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
				url = href, // بدون ?style=list
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
			.replace("?style=list", "")
			.replace("&style=list", "")
			.toAbsoluteUrl(domain)

		val doc = webClient.httpGet(cleanUrl).parseHtml()

		// div.text-left هو container النص في cenele
		val content = doc.selectFirst("div.text-left") ?: return null

		// إزالة كل العناصر المخفية (حماية الموقع من السرقة بـ spans/paragraphs مع aria-hidden)
		content.select(
			"span[aria-hidden=true], " +
				"p[aria-hidden=true], " +
				"span[role=presentation], " +
				"p[role=presentation], " +
				"input[type=hidden], " +
				"script, style, ins, iframe, noscript, " +
				".adsbygoogle, .google-auto-placed, " +
				"[id^=ezoic], [id^=pf-], [id^=bg-ssp]",
		).remove()

		// إزالة الفقرات الفارغة
		content.select("p").forEach { p ->
			if (p.text().trim().isEmpty()) p.remove()
		}

		// عنوان الفصل
		val title = doc.selectFirst("h3.chapter-name")?.text()?.trim()
			?: chapter.title
			?: ""

		return NovelChapterContent(
			html = buildString {
				if (title.isNotBlank()) append("<h1>$title</h1>")
				append(content.html())
			},
			images = emptyList(),
		)
	}
}
