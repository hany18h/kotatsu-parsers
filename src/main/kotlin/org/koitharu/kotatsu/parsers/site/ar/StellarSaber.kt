package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("STELLARSABER", "StellarSaber", "ar")
internal class StellarSaber(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.STELLARSABER, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("stellarsaber.pro")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	// ---------------------------------------------------------------------
	// List / search
	// ---------------------------------------------------------------------

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			if (!filter.query.isNullOrEmpty()) {
				// TODO: تأكد من الـ endpoint الحقيقي لمودال البحث (AJAX) -
				// دي محاولة بطريقة ووردبريس القياسية.
				append("/?s=")
				append(filter.query!!.urlEncoded())
				if (page > 1) {
					append("&paged=")
					append(page)
				}
			} else {
				append("/manga/")
				if (page > 1) {
					append("page/")
					append(page)
					append('/')
				}
				val params = mutableListOf<String>()
				params += "sort=" + when (order) {
					SortOrder.ALPHABETICAL -> "az"
					SortOrder.RATING -> "rating"
					SortOrder.POPULARITY -> "popular"
					SortOrder.NEWEST -> "updated"
					else -> "latest"
				}
				filter.tags.forEach { tag ->
					params += "genre%5B0%5D=${tag.key.urlEncoded()}"
				}
				if (params.isNotEmpty()) {
					append('?')
					append(params.joinToString("&"))
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div.card-grid > a.card").mapNotNull { parseMangaItem(it) }
	}

	private fun parseMangaItem(a: Element): Manga? {
		val absUrl = a.attrAsAbsoluteUrlOrNull("href") ?: return null
		val relUrl = absUrl.toRelativeUrl(domain)
		val title = a.selectFirst(".card__title")?.text()?.trim() ?: return null
		val cover = a.selectFirst(".card__image-wrap img")?.attr("src")?.takeIf { it.isNotBlank() }
		val ratingText = a.selectFirst(".card__rating")
			?.ownText()
			?.trim()
		val rating = ratingText?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN
		val author = a.selectFirst(".card__meta")?.text()?.trim().orEmpty()

		return Manga(
			id = generateUid(relUrl),
			title = title,
			altTitles = emptySet(),
			url = relUrl,
			publicUrl = absUrl,
			rating = rating,
			contentRating = null,
			coverUrl = cover,
			tags = emptySet(),
			state = null,
			authors = if (author.isNotEmpty()) setOf(author) else emptySet(),
			source = source,
		)
	}

	private fun fetchAvailableTags(): Set<MangaTag> {
		// من قايمة التصنيفات في الشريط الجانبي لصفحة /manga/
		val genres = mapOf(
			"action" to "أكشن",
			"thriller" to "إثارة",
			"isekai" to "إيسيكاي",
			"historical" to "تاريخي",
			"josei" to "جوسيه",
			"harem" to "حريم",
			"school-life" to "حياة مدرسية",
			"supernatural" to "خارق للطبيعة",
			"sci-fi" to "خيال علمي",
			"drama" to "دراما",
			"horror" to "رعب",
			"romance" to "رومانسية",
			"sports" to "رياضة",
			"seinen" to "سينين",
			"slice-of-life" to "شريحة من الحياة",
			"shoujo" to "شوجو",
			"shounen" to "شونين",
			"cooking" to "طبخ",
			"mystery" to "غموض",
			"fantasy" to "فانتازيا",
			"martial-arts" to "فنون قتالية",
			"comedy" to "كوميديا",
			"adult" to "للبالغين",
			"tragedy" to "مأساة",
			"manhua" to "مانها",
			"adventure" to "مغامرة",
			"mecha" to "ميكا",
			"mature" to "ناضج",
			"psychological" to "نفسي",
		)
		return genres.mapTo(mutableSetOf()) { (key, name) ->
			MangaTag(title = name, key = key, source = source)
		}
	}

	// ---------------------------------------------------------------------
	// Details
	// ---------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val altTitles = doc.selectFirst(".detail-info__alt-title")
			?.text()
			?.split(",")
			?.map { it.trim() }
			?.filter { it.isNotEmpty() }
			?.toSet()
			.orEmpty()

		val rating = doc.selectFirst(".detail-rating__score")
			?.text()
			?.trim()
			?.toFloatOrNull()
			?.div(10f) ?: RATING_UNKNOWN

		val cover = doc.selectFirst(".detail-poster img")?.attr("src")?.takeIf { it.isNotBlank() }
			?: manga.coverUrl

		val metaMap = mutableMapOf<String, String>()
		val labels = doc.select(".detail-meta__label")
		for (label in labels) {
			val key = label.text().trim()
			val value = label.nextElementSibling()?.text()?.trim() ?: continue
			metaMap[key] = value
		}
		val author = metaMap["المؤلف"]

		val statusText = metaMap["الحالة"].orEmpty()
		val state = when {
			statusText.contains("مستمر") -> MangaState.ONGOING
			statusText.contains("مكتمل") -> MangaState.FINISHED
			statusText.contains("متوقف") -> MangaState.PAUSED
			statusText.contains("ملغا") -> MangaState.ABANDONED
			else -> null
		}

		val description = doc.selectFirst(".detail-desc")?.text()?.trim()

		// أول .detail-genres بس (اللي فيها التصنيفات الحقيقية)
		// - في .detail-genres تانية تحت لفريق الترجمة، مستبعدة لأنها مش direct child.
		val tags = doc.select(".detail-info > .detail-genres > a.detail-genre").mapNotNullToSet { el ->
			val name = el.text().trim()
			if (name.isEmpty()) return@mapNotNullToSet null
			val slug = el.attr("href").substringAfterLast("/genre/").trim('/')
			MangaTag(title = name, key = slug.ifEmpty { name }, source = source)
		}

		val chapters = parseChapters(doc)

		return manga.copy(
			altTitles = altTitles,
			coverUrl = cover,
			rating = rating,
			state = state,
			tags = tags.ifEmpty { manga.tags },
			authors = if (!author.isNullOrBlank()) setOf(author) else manga.authors,
			description = description,
			chapters = chapters,
		)
	}

	private fun parseChapters(doc: Document): List<MangaChapter> {
		val result = ArrayList<MangaChapter>()
		val volumeGroups = doc.select(".chapter-list .volume-group")

		for (group in volumeGroups) {
			val volumeLabel = group.selectFirst(".volume-group__label")?.text()?.trim().orEmpty()
			val volumeNumber = Regex("""\d+""").find(volumeLabel)?.value?.toIntOrNull() ?: 0

			for (item in group.select(".chapter-item")) {
				val href = item.attrAsAbsoluteUrlOrNull("href") ?: continue
				val relUrl = href.toRelativeUrl(domain)
				val numberText = item.selectFirst(".chapter-item__number")?.text().orEmpty()
				val number = Regex("""[\d.]+""").find(numberText)?.value?.toFloatOrNull() ?: 0f
				val title = item.selectFirst(".chapter-item__title")?.text()?.trim()
				val scanlator = item.selectFirst(".chapter-item__team")?.text()?.trim()

				result += MangaChapter(
					id = generateUid(relUrl),
					title = title,
					number = number,
					volume = volumeNumber,
					url = relUrl,
					scanlator = scanlator,
					uploadDate = 0L, // التاريخ نصي نسبي ("منذ أسبوعين") مش قابل للتحويل بدقة
					branch = null,
					source = source,
				)
			}
		}

		return result.sortedWith(compareBy({ it.volume }, { it.number }))
	}

	// ---------------------------------------------------------------------
	// Pages
	// ---------------------------------------------------------------------

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		return doc.select("#reader-images .reader__page-wrap img.reader__page").mapNotNull { img ->
			// الصورة الحقيقية في data-cdn-url مش src (اللي هو placeholder gif فاضي)
			val url = img.attr("data-cdn-url").takeIf { it.isNotBlank() }
				?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
				?: return@mapNotNull null

			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
