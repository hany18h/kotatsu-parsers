package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("TEAMX", "TeamX", "ar")
internal class TeamX(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TEAMX, 10) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override val configKeyDomain = ConfigKey.Domain("olympustaff.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/?search=")
					append(filter.query.urlEncoded())
					append("&page=")
					append(page)
				}

				else -> {

					if (order == SortOrder.UPDATED) {
						if (filter.tags.isNotEmpty() || filter.demographics.isNotEmpty()) {
							throw IllegalArgumentException("Updated sorting does not support other sorting filters")
						}
						append("/?page=")
						append(page.toString())
					} else {
						append("/series?page=")
						append(page.toString())

						filter.tags.oneOrThrowIfMany()?.let {
							append("&genre=")
							append(it.key)
						}

						filter.types.forEach {
							append("&type=")
							append(
								when (it) {
									ContentType.MANGA -> "مانجا ياباني"
									ContentType.MANHWA -> "مانهوا كورية"
									ContentType.MANHUA -> "مانها صيني"
									else -> ""
								},
							)
						}

						filter.states.oneOrThrowIfMany()?.let {
							append("status=")
							append(
								when (it) {
									MangaState.ONGOING -> "مستمرة"
									MangaState.FINISHED -> "مكتمل"
									MangaState.ABANDONED -> "متوقف"
									else -> "مستمرة"
								},
							)
						}
					}
				}
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div.listupd .bs .bsx").ifEmpty {
			doc.select("div.post-body .box")
		}.map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				title = div.select(".tt, h3").text(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = div.selectFirstOrThrow("img").src()?.replace("thumbnail_", ""),
				tags = emptySet(),
				state = when (div.selectFirst(".status")?.text()) {
					"مستمرة" -> MangaState.ONGOING
					"مكتمل" -> MangaState.FINISHED
					"متوقف" -> MangaState.ABANDONED
					else -> null
				},
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/series").parseHtml()
		return doc.requireElementById("select_genre").select("option").mapToSet {
			MangaTag(
				key = it.attr("value"),
				title = it.text().toTitleCase(sourceLocale),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		
		// تحسين البحث عن عدد صفحات الفصول
		val paginationLinks = doc.select(".pagination a, .hpage a, a.page-link")
		var maxPageChapter = 1
		
		paginationLinks.forEach { link ->
			val href = link.attr("href")
			// البحث عن رقم الصفحة في الرابط
			val pageMatch = Regex("""[?&]page=(\d+)""").find(href)
			pageMatch?.groupValues?.get(1)?.toIntOrNull()?.let { pageNum ->
				if (pageNum > maxPageChapter) {
					maxPageChapter = pageNum
				}
			}
		}
		
		return manga.copy(
			state = doc.selectFirst(".full-list-info:contains(الحالة:) a")?.text()?.let { status ->
				when (status) {
					"مستمرة" -> MangaState.ONGOING
					"مكتمل" -> MangaState.FINISHED
					"متوقف" -> MangaState.ABANDONED
					else -> null
				}
			},
			tags = doc.select(".review-author-info a, .genre-info a").mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast("="),
					title = a.text(),
					source = source,
				)
			},
			description = doc.selectFirst(".review-content, .summary__content, .entry-content")?.html(),
			chapters = run {
				if (maxPageChapter == 1) {
					parseChapters(doc)
				} else {
					coroutineScope {
						val allChapters = ArrayList(parseChapters(doc))
						allChapters.ensureCapacity(allChapters.size * maxPageChapter)
						
						val additionalChapters = (2..maxPageChapter).map { page ->
							async {
								loadChapters(mangaUrl, page)
							}
						}.awaitAll()
						
						additionalChapters.forEach { chapters ->
							allChapters.addAll(chapters)
						}
						
						allChapters
					}
				}
			}.reversed(),
		)
	}

	private suspend fun loadChapters(baseUrl: String, page: Int): List<MangaChapter> {
		val doc = webClient.httpGet("$baseUrl?page=$page").parseHtml()
		return parseChapters(doc)
	}

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", sourceLocale)

	private fun parseChapters(root: Element): List<MangaChapter> {
		// البحث عن الفصول في enhanced-chapters-grid أولاً
		val chapterCards = root.select(".enhanced-chapters-grid .chapter-card")
		
		if (chapterCards.isNotEmpty()) {
			return chapterCards.mapNotNull { card ->
				val link = card.selectFirst(".chapter-link") ?: return@mapNotNull null
				val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
				
				// تجاهل الروابط التي تفتح modal
				if (link.hasAttr("data-bs-toggle")) return@mapNotNull null
				
				val number = card.attr("data-number").toFloatOrNull() ?: 0f
				val title = card.selectFirst(".chapter-title")?.text() 
					?: card.selectFirst(".chapter-number")?.text()
					?: "Chapter $number"
				
				val dateStr = card.attr("data-date")
				val uploadDate = if (dateStr.isNotEmpty()) {
					try {
						dateStr.toLong() * 1000 // Convert to milliseconds
					} catch (e: Exception) {
						0
					}
				} else {
					0
				}
				
				MangaChapter(
					id = generateUid(url),
					title = title,
					number = number,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = uploadDate,
					branch = null,
					source = source,
				)
			}
		}
		
		// محاولة البحث في القوائم التقليدية
		val chapterElements = root.select("#chapter-contact li, .eplister li, ul.clstyle li, .chapter-list li")
		
		if (chapterElements.isNotEmpty()) {
			return chapterElements.mapNotNull { li ->
				val link = li.selectFirst("a") ?: return@mapNotNull null
				val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
				
				val title = li.selectFirst(".epl-title, .chapternum, .chapter-title")?.text() 
					?: link.text()
					?: "Chapter ${url.substringAfterLast('/')}"
				
				val dateElement = li.selectFirst(".epl-date, .chapterdate, .chapter-date")
				
				MangaChapter(
					id = generateUid(url),
					title = title,
					number = url.substringAfterLast('/').filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = dateElement?.text()?.let { dateFormat.parseSafe(it) } ?: 0,
					branch = null,
					source = source,
				)
			}
		}
		
		// آخر محاولة: البحث عن أي روابط للفصول
		return root.select("a[href*=/series/][href*=/]").mapNotNull { link ->
			val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val urlPath = url.substringAfter("/series/")
			if (!urlPath.contains("/")) return@mapNotNull null
			
			val number = urlPath.substringAfterLast('/').toFloatOrNull() ?: return@mapNotNull null
			
			MangaChapter(
				id = generateUid(url),
				title = link.text().ifEmpty { "Chapter $number" },
				number = number,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = 0,
				branch = null,
				source = source,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select(".image_list img, .image_list canvas, #readerarea img, .chapter-content img").map { img ->
			val url = when {
				img.hasAttr("src") -> img.requireSrc().toRelativeUrl(domain)
				img.hasAttr("data-src") -> img.attrAsRelativeUrl("data-src")
				else -> img.attrAsRelativeUrl("data-lazy-src")
			}
			
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
