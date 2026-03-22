package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("PROCHAN", "ProChan", "ar")
internal class ProChan(context: MangaLoaderContext) : PagedMangaParser(
	context,
	source = MangaParserSource.PROCHAN,
	pageSize = 18,
), Interceptor {

	override val configKeyDomain = ConfigKey.Domain("procomic.pro")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = false,
		)

	private val dateFormat by lazy {
		SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val newRequest = request.newBuilder()
			.header("Referer", "https://procomic.pro/")
			.header("Origin", "https://procomic.pro")
			.header("Accept", "*/*")
			.header("Accept-Language", "en-US,en;q=0.9")
			.header(
				"User-Agent",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
					"(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
			)
			.build()

		val response = chain.proceed(newRequest)
		val host = request.url.host

		// إصلاح Content-Type للصور التي يُعيدها CDN بدون نوع صحيح
		if (host.contains("procomic") || host.contains("prochan")) {
			val contentType = response.header("Content-Type") ?: ""
			if (contentType.contains("octet-stream") || contentType.isEmpty()) {
				val path = request.url.encodedPath.lowercase()
				val fixedType = when {
					path.endsWith(".avif") -> "image/avif"
					path.endsWith(".webp") -> "image/webp"
					path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
					path.endsWith(".png") -> "image/png"
					path.endsWith(".gif") -> "image/gif"
					else -> "image/jpeg"
				}
				return response.newBuilder()
					.header("Content-Type", fixedType)
					.build()
			}
		}
		return response
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val endpoint = when (order) {
			SortOrder.UPDATED      -> "latest-updates"
			SortOrder.POPULARITY   -> "popular"
			SortOrder.ALPHABETICAL -> "az"
			else                   -> "latest-updates"
		}

		val url = "https://$domain/api/public/content/$endpoint" +
			"?limit=$pageSize&category=comics&page=$page"

		val json = webClient.httpGet(url).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()

		val seen = HashSet<Int>()
		return data.mapJSONNotNull { item ->
			val mangaId = item.optInt("mangaId").takeIf { it > 0 }
				?: item.optInt("id").takeIf { it > 0 }
				?: return@mapJSONNotNull null
			if (!seen.add(mangaId)) return@mapJSONNotNull null
			parseMangaFromList(item)
		}
	}

	private fun parseMangaFromList(item: JSONObject): Manga? {
		val id = item.optInt("mangaId").takeIf { it > 0 }
			?: item.optInt("id").takeIf { it > 0 }
			?: return null
		val slug = item.optString("mangaSlug").takeIf { it.isNotEmpty() }
			?: item.optString("slug").takeIf { it.isNotEmpty() }
			?: return null
		val title = item.optString("mangaTitle").takeIf { it.isNotEmpty() }
			?: item.optString("title").takeIf { it.isNotEmpty() }
			?: return null
		val type = item.optString("type", "manhua")
		if (type == "novel") return null

		val coverUrl = getBestCover(item)
		val status = item.optString("status", "")
		val mangaUrl = "/series/$type/$id/$slug"

		return Manga(
			id = generateUid(mangaUrl),
			title = title,
			altTitles = emptySet(),
			url = mangaUrl,
			publicUrl = "https://$domain$mangaUrl",
			rating = RATING_UNKNOWN,
			contentRating = if (item.optBoolean("isSensitiveImage")) ContentRating.ADULT else null,
			coverUrl = coverUrl,
			tags = emptySet(),
			state = parseState(status),
			authors = emptySet(),
			description = null,
			chapters = null,
			source = source,
		)
	}

	private fun getBestCover(item: JSONObject): String {
		val appCover = item.optJSONObject("coverImageApp")
		if (appCover != null) {
			val card = appCover.optJSONObject("card")
			val mobile = card?.optString("mobile")?.takeIf { it.isNotEmpty() }
			if (mobile != null) return mobile
			val desktop = appCover.optString("desktop").takeIf { it.isNotEmpty() }
			if (desktop != null) return desktop
		}
		return item.optString("coverImage", "")
	}

	private fun parseState(status: String): MangaState? = when {
		status.contains("مستمر", ignoreCase = true)    -> MangaState.ONGOING
		status.contains("مكتمل", ignoreCase = true)    -> MangaState.FINISHED
		status.contains("متوقف", ignoreCase = true)    -> MangaState.ABANDONED
		status.contains("ongoing", ignoreCase = true)   -> MangaState.ONGOING
		status.contains("completed", ignoreCase = true) -> MangaState.FINISHED
		else -> null
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val parts = manga.url.split("/").filter { it.isNotEmpty() }
		if (parts.size < 4) return manga
		val id = parts[2]

		val allChapters = JSONArray()
		var page = 1
		while (true) {
			val chaptersUrl = "https://$domain/api/public/chapters" +
				"?contentId=$id&page=$page&limit=500&order=asc"

			val chaptersJson = runCatching {
				webClient.httpGet(chaptersUrl).parseJson()
			}.getOrElse { break }

			val chaptersData = chaptersJson.optJSONArray("chapters") ?: break
			if (chaptersData.length() == 0) break
			for (i in 0 until chaptersData.length()) {
				allChapters.put(chaptersData.opt(i))
			}
			if (!chaptersJson.optBoolean("hasMore", false)) break
			page++
		}

		return manga.copy(
			chapters = parseChapters(allChapters, manga.url),
		)
	}

	private fun parseChapters(data: JSONArray, mangaUrl: String): List<MangaChapter> {
		val chapters = mutableListOf<MangaChapter>()
		for (i in 0 until data.length()) {
			val item = data.optJSONObject(i) ?: continue

			val coinsRequired = item.optInt("coins_required", 0)
			val lockedForever = item.optBoolean("lockedForever", false) ||
				item.optJSONObject("metadata")?.optBoolean("lockForever", false) == true
			if (coinsRequired > 0 || lockedForever) continue

			val chapterId = item.optInt("id").takeIf { it > 0 } ?: continue

			val chapterNumberStr = item.optString("chapter_number")
				.takeIf { it.isNotEmpty() && it != "null" }
			val title = item.optString("title")
				.takeIf { it.isNotEmpty() && it != "null" }

			val chapterNum = chapterNumberStr?.toFloatOrNull()
				?: title?.toFloatOrNull()
				?: (i + 1).toFloat()

			val displayTitle = title?.takeIf { it != chapterNumberStr }

			val publishedAt = item.optString("published_at", "")
				.takeIf { it.isNotEmpty() }
				?: item.optString("publishedAt", "")

			val uploadDate = runCatching {
				dateFormat.parse(publishedAt)?.time ?: 0L
			}.getOrDefault(0L)

			chapters.add(
				MangaChapter(
					id = generateUid("$mangaUrl/$chapterId"),
					title = displayTitle,
					number = chapterNum,
					volume = 0,
					url = "$mangaUrl/$chapterId",
					scanlator = null,
					uploadDate = uploadDate,
					branch = null,
					source = source,
				),
			)
		}
		chapters.sortBy { it.number }
		return chapters
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.split("/").filter { it.isNotEmpty() }
		val chapterId = parts.lastOrNull() ?: return emptyList()

		val url = "https://$domain/api/public/chapters/$chapterId"
		val json = webClient.httpGet(url).parseJson()

		// ——— الأولوية: appImages (الصور الكاملة غير المقطعة) ———
		// الموقع يقسم كل صفحة لـ mobile (نصف يسار) + desktop (نصف يمين أو كاملة)
		// نستخدم desktop لأنها الصورة الكاملة
		val appImages = json.optJSONArray("appImages")
		if (appImages != null && appImages.length() > 0) {
			val pages = mutableListOf<MangaPage>()
			for (i in 0 until appImages.length()) {
				val imgObj = appImages.optJSONObject(i) ?: continue
				// desktop = الصورة الكاملة للصفحة
				val desktopUrl = imgObj.optString("desktop").takeIf { it.isNotEmpty() }
				// fallback لـ mobile إذا desktop غير موجود
				val imageUrl = desktopUrl
					?: imgObj.optString("mobile").takeIf { it.isNotEmpty() }
					?: continue
				pages.add(
					MangaPage(
						id = generateUid("${chapter.id}-$i"),
						url = imageUrl,
						preview = null,
						source = source,
					),
				)
			}
			if (pages.isNotEmpty()) return pages
		}

		// ——— Fallback: images array من CDN ———
		val cdnPath = json.optString("cdn_path").takeIf { it.isNotEmpty() }
		val cdnBase = when {
			cdnPath != null && cdnPath.startsWith("http") -> cdnPath.trimEnd('/')
			cdnPath != null -> "https://$cdnPath.procomic.pro"
			else -> "https://app.procomic.pro"
		}

		val images = json.optJSONObject("metadata")?.optJSONArray("images")
			?: json.optJSONArray("images")
			?: json.optJSONObject("chapter")?.optJSONObject("metadata")?.optJSONArray("images")
			?: json.optJSONObject("chapter")?.optJSONArray("images")
			?: return emptyList()

		val result = mutableListOf<MangaPage>()
		for (i in 0 until images.length()) {
			val imagePath = extractImagePath(images, i) ?: continue
			val finalUrl = if (imagePath.startsWith("http")) imagePath else "$cdnBase$imagePath"
			result.add(
				MangaPage(
					id = generateUid("${chapter.id}-$i"),
					url = finalUrl,
					preview = null,
					source = source,
				),
			)
		}
		return result
	}

	private fun extractImagePath(images: JSONArray, index: Int): String? {
		val item = images.opt(index) ?: return null
		if (item is String) return item.takeIf { it.isNotEmpty() }
		if (item is JSONObject) {
			return item.optString("url").takeIf { it.isNotEmpty() }
				?: item.optString("path").takeIf { it.isNotEmpty() }
				?: item.optString("src").takeIf { it.isNotEmpty() }
				?: item.optString("image").takeIf { it.isNotEmpty() }
		}
		return images.optString(index).takeIf { it.isNotEmpty() }
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url
}
