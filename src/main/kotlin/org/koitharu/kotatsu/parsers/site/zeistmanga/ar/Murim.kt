package org.koitharu.kotatsu.parsers.site.zeistmanga.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.EnumSet
import java.util.Locale

/*
 * الموقع بقى SPA بالكامل وبيجيب بياناته من JSON endpoints مباشرة:
 *   - /index.json                              -> قائمة كل الأعمال (رئيسية + بحث)
 *   - /manga/{slug}/details.json                -> تفاصيل العمل + قائمة الفصول
 *   - /manga/{slug}/chapters/{chapterNum}.json  -> صور الفصل
 *
 * القالب القديم (ZeistManga/Blogger) اللي كان الـ parser القديم مبني عليه مبقاش
 * موجود، فالكلاس ده بقى مستقل ومبني بالكامل على استهلاك الـ JSON.
 *
 * ملحوظة: نظام القفل بالعملات/الروابط المختصرة شغال Client-side بس؛
 * الـ JSON الخاص بالفصل بيرجع الصور كاملة بغض النظر عن حالة القفل،
 * فمفيش داعي نتعامل مع أي منطق فتح/دفع هنا.
 */
@MangaSourceParser("MURIM", "Murim", "ar")
internal class Murim(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MURIM, pageSize = 12) {

	override val configKeyDomain = ConfigKey.Domain("www.murim.site")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	// كاش لملف index.json عشان مانعملش fetch جديد مع كل صفحة/بحث
	private val indexCache = suspendLazy(initializer = ::fetchIndex)

	private suspend fun fetchIndex(): List<org.json.JSONObject> {
		val json = webClient.httpGet("https://$domain/index.json").parseJson()
		val arr = json.optJSONArray("latest") ?: return emptyList()
		return arr.mapJSON { it }
	}

	private fun extractSlug(link: String?): String? {
		if (link.isNullOrEmpty()) return null
		val afterId = link.substringAfter("id=", "")
		if (afterId.isEmpty()) return null
		return afterId.substringBefore('&').substringBefore('#')
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val all = indexCache.get()

		val query = filter.query?.trim()?.lowercase(Locale.getDefault())
		val filtered = if (!query.isNullOrEmpty()) {
			all.filter { it.getStringOrNull("title")?.lowercase(Locale.getDefault())?.contains(query) == true }
		} else {
			all
		}

		val start = (page - 1) * pageSize
		if (start >= filtered.size) return emptyList()
		val end = minOf(start + pageSize, filtered.size)

		return filtered.subList(start, end).mapNotNull { item ->
			val slug = extractSlug(item.getStringOrNull("link")) ?: return@mapNotNull null
			Manga(
				id = generateUid(slug),
				title = item.getStringOrNull("title") ?: return@mapNotNull null,
				altTitles = emptySet(),
				url = slug,
				publicUrl = "https://$domain/details?id=$slug",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = item.getStringOrNull("image")?.toAbsoluteUrl(domain),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url
		val json = webClient.httpGet("https://$domain/manga/$slug/details.json").parseJson()

		val stateStr = (json.getStringOrNull("status") ?: "").lowercase(Locale.getDefault())
		val state = when {
			stateStr.contains("مستمر") || stateStr.contains("ongoing") -> MangaState.ONGOING
			stateStr.contains("مكتمل") || stateStr.contains("completed") -> MangaState.FINISHED
			stateStr.contains("متروك") || stateStr.contains("dropped") -> MangaState.ABANDONED
			stateStr.contains("متوقف") || stateStr.contains("paused") || stateStr.contains("hiatus") -> MangaState.PAUSED
			else -> null
		}

		val tags = json.optJSONArray("tags")?.mapJSONToSet { /* not used, placeholder guard */ it } ?: emptySet()
		// tags في details.json عبارة عن مصفوفة نصوص وليست كائنات JSON، فبنبنيها يدوي:
		val tagArr = json.optJSONArray("tags")
		val tagSet: Set<MangaTag> = if (tagArr != null) {
			(0 until tagArr.length()).mapNotNullToSet { i ->
				val name = tagArr.optString(i, "")
				if (name.isEmpty()) null else MangaTag(key = name, title = name.toTitleCase(), source = source)
			}
		} else {
			emptySet()
		}

		val chaptersArr = json.optJSONArray("chapters_list")
		val chapters = chaptersArr?.mapJSONNotNull { ch ->
			if (ch.optBoolean("is_draft", false)) return@mapJSONNotNull null
			val chapterNum = ch.getStringOrNull("chapter")?.toFloatOrNull() ?: return@mapJSONNotNull null
			MangaChapter(
				id = generateUid("$slug#$chapterNum"),
				url = "$slug#$chapterNum",
				title = ch.getStringOrNull("title"),
				number = chapterNum,
				volume = 0,
				branch = null,
				uploadDate = parseChapterDate(ch.getStringOrNull("date")),
				scanlator = null,
				source = source,
			)
		}?.sortedBy { it.number } ?: emptyList()

		return manga.copy(
			title = json.getStringOrNull("title") ?: manga.title,
			coverUrl = json.getStringOrNull("cover")?.toAbsoluteUrl(domain) ?: manga.coverUrl,
			description = json.getStringOrNull("description"),
			authors = json.getStringOrNull("author")?.let { setOf(it) } ?: emptySet(),
			tags = tagSet,
			state = state,
			chapters = chapters,
		)
	}

	private fun parseChapterDate(dateStr: String?): Long {
		if (dateStr.isNullOrEmpty()) return 0L
		dateStr.toLongOrNull()?.let { return it }
		return dateStr.parseSafe("yyyy-MM-dd") // extension متوفرة في util package بالمكتبة
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val (slug, chapterNum) = chapter.url.split('#', limit = 2)
		val json = webClient.httpGet("https://$domain/manga/$slug/chapters/$chapterNum.json").parseJson()
		val imagesArr = json.optJSONArray("images") ?: return emptyList()

		return (0 until imagesArr.length()).map { i ->
			val url = imagesArr.getString(i).toAbsoluteUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
