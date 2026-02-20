package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("PROCHAN", "ProChan", "ar")
internal class ProChan(context: MangaLoaderContext) : PagedMangaParser(
	context,
	source = MangaParserSource.PROCHAN,
	pageSize = 18,
) {
	override val configKeyDomain = ConfigKey.Domain("prochan.pro")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	private val dateFormat by lazy {
		SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	// ─────────────────────────────────────────────
	// LIST
	// ─────────────────────────────────────────────

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/api/public/series/search?status=approved&limit=18&page=")
			append(page)
			append("&sort=")
			append(
				when (order) {
					SortOrder.UPDATED    -> "latest_chapter"
					SortOrder.POPULARITY -> "popular"
					SortOrder.ALPHABETICAL -> "az"
					else                 -> "latest_chapter"
				},
			)
			if (!filter.query.isNullOrEmpty()) {
				append("&search=")
				append(filter.query.urlEncoded())
			}
		}

		val json = webClient.httpGet(url).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()

		return data.mapJSONNotNull { item ->
			// Skip text-only novels
			if (item.optString("type") == "novel") return@mapJSONNotNull null

			val id       = item.optInt("id")
			val slug     = item.optString("slug")
			val type     = item.optString("type")
			val title    = item.optString("title")
			val cover    = item.optString("coverImage")
			val meta     = item.optJSONObject("metadata") ?: JSONObject()
			val progress = meta.optString("progress", "")

			val state = when {
				progress.contains("مستمر",  ignoreCase = true) -> MangaState.ONGOING
				progress.contains("مكتمل", ignoreCase = true) -> MangaState.FINISHED
				else -> null
			}

			val mangaUrl = "/series/$type/$id/$slug"

			Manga(
				id            = generateUid(mangaUrl),
				title         = title,
				altTitles     = emptySet(),
				url           = mangaUrl,
				publicUrl     = mangaUrl.toAbsoluteUrl(domain),
				rating        = RATING_UNKNOWN,
				contentRating = null,
				coverUrl      = cover,
				tags          = emptySet(),
				state         = state,
				authors       = emptySet(),
				description   = null,
				chapters      = null,
				source        = source,
			)
		}
	}

	// ─────────────────────────────────────────────
	// DETAILS
	// ─────────────────────────────────────────────

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc             = webClient.httpGet(manga.publicUrl).parseHtml()
		val chaptersDeferred = async { fetchChapters(manga.url) }
		val scriptData      = collectNextScripts(doc)

		val description = parseStringField(scriptData, "description")
		val altTitles   = parseAltTitles(scriptData)
		val tags        = parseTags(scriptData)
		val authors     = parseAuthors(scriptData)

		manga.copy(
			description = description,
			altTitles   = altTitles,
			tags        = tags,
			authors     = authors,
			chapters    = chaptersDeferred.await(),
		)
	}

	// ─────────────────────────────────────────────
	// CHAPTERS
	// ─────────────────────────────────────────────

	private suspend fun fetchChapters(mangaUrl: String): List<MangaChapter> {
		// mangaUrl = /series/{type}/{id}/{slug}
		val parts = mangaUrl.split("/").filter { it.isNotEmpty() }
		if (parts.size < 3) return emptyList()

		val type = parts[1]   // manhwa / manga / manhua
		val id   = parts[2]   // numeric id

		// API works on both prochan.pro and prochan.net — use same domain
		val url = "https://$domain/api/public/$type/$id/chapters?page=1&limit=2000&order=asc"
		val json = webClient.httpGet(url).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()

		return data.mapJSONNotNull { item ->
			val chapterId     = item.optInt("id")
			val chapterNumber = item.optString("chapter_number")
			val chapterNum    = chapterNumber.toFloatOrNull() ?: return@mapJSONNotNull null
			val rawTitle      = item.optString("title")
			val chapterTitle  = rawTitle.takeIf { it.isNotBlank() && it != "null" }
			val createdAt     = item.optString("created_at", "")

			val uploadDate = runCatching {
				dateFormat.parse(createdAt)?.time ?: 0L
			}.getOrDefault(0L)

			val chapterUrl = "$mangaUrl/$chapterId/$chapterNumber"

			MangaChapter(
				id          = generateUid(chapterUrl),
				title       = chapterTitle,
				number      = chapterNum,
				volume      = 0,
				url         = chapterUrl,
				scanlator   = null,
				uploadDate  = uploadDate,
				branch      = null,
				source      = source,
			)
		}
	}

	// ─────────────────────────────────────────────
	// PAGES
	// ─────────────────────────────────────────────

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc        = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val scriptData = collectNextScripts(doc)

		// Primary: parse the appImages JSON array embedded by Next.js
		val pages = parseAppImages(scriptData, chapter)
		if (pages.isNotEmpty()) return pages

		// Fallback: grab the few full <img alt="page N"> elements
		return doc.select("img[alt^='page']")
			.filterNot { it.attr("src").contains("-mobile.") }
			.mapIndexed { i, img ->
				MangaPage(
					id      = generateUid("${chapter.id}-$i"),
					url     = img.attr("src"),
					preview = null,
					source  = source,
				)
			}
	}

	/**
	 * Parse the `appImages` array that Next.js embeds in streaming script chunks.
	 * Each entry is {"desktop": "https://...", "mobile": "https://..."}.
	 * The desktop URL is the full-resolution image (no stripe splitting).
	 */
	private fun parseAppImages(scriptData: String, chapter: MangaChapter): List<MangaPage> {
		// The array may appear with or without escaped quotes inside JS string literals
		val patterns = listOf(
			Regex(""""appImages"\s*:\s*(\[\s*\{.+?\}\s*\])""", RegexOption.DOT_MATCHES_ALL),
			Regex("""\\\"appImages\\\"\s*:\s*(\[.+?\])""",     RegexOption.DOT_MATCHES_ALL),
		)

		for (pattern in patterns) {
			val match = pattern.find(scriptData) ?: continue
			val rawJson = match.groupValues[1]
				.replace("\\\"", "\"")
				.replace("\\/", "/")

			return try {
				val arr = JSONArray(rawJson)
				arr.mapJSONNotNull { item ->
					if (item !is JSONObject) return@mapJSONNotNull null
					// Prefer desktop, fall back to mobile
					val url = item.optString("desktop").takeIf { it.isNotEmpty() }
						?: item.optString("mobile").takeIf { it.isNotEmpty() }
						?: return@mapJSONNotNull null
					MangaPage(
						id      = generateUid("${chapter.id}-$url"),
						url     = url,
						preview = null,
						source  = source,
					)
				}
			} catch (e: Exception) {
				continue
			}
		}

		return emptyList()
	}

	// ─────────────────────────────────────────────
	// HELPERS — Next.js data extraction
	// ─────────────────────────────────────────────

	/** Concatenate all Next.js script chunks into one searchable string. */
	private fun collectNextScripts(doc: Document): String = buildString {
		doc.selectFirst("script#__NEXT_DATA__")?.let { append(it.data()) }
		doc.select("script:containsData(__next_f.push)").forEach { append(it.data()) }
	}

	private fun parseStringField(data: String, field: String): String? =
		Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
			.find(data)
			?.groupValues?.get(1)
			?.replace("\\n", "\n")
			?.replace("\\\"", "\"")
			?.replace("\\\\", "\\")

	private fun parseAltTitles(data: String): Set<String> {
		val match = Regex("\"altTitles\"\\s*:\\s*(\\[[^\\]]*\\])").find(data) ?: return emptySet()
		return try {
			JSONArray(match.groupValues[1]).mapJSONToSet { it as String }
		} catch (e: Exception) {
			emptySet()
		}
	}

	private fun parseTags(data: String): Set<MangaTag> {
		val match = Regex(
			"\"(?:genres|categories|tags)\"\\s*:\\s*(\\[(?:[^\\[\\]]|\\[[^\\[\\]]*\\])*\\])",
		).find(data) ?: return emptySet()
		return try {
			val arr  = JSONArray(match.groupValues[1])
			val tags = mutableSetOf<MangaTag>()
			for (i in 0 until arr.length()) {
				val obj  = arr.optJSONObject(i) ?: continue
				val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: continue
				val key  = obj.optString("slug").takeIf { it.isNotEmpty() } ?: name
				tags += MangaTag(key = key, title = name, source = source)
			}
			tags
		} catch (e: Exception) {
			emptySet()
		}
	}

	private fun parseAuthors(data: String): Set<String> =
		Regex("\"(?:author|artist)\"\\s*:\\s*\"([^\"]+)\"")
			.findAll(data)
			.mapNotNull { it.groupValues[1].trim().takeIf { v -> v.isNotEmpty() && v != "null" } }
			.toSet()
}
