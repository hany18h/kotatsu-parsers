package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.Response
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
), Interceptor {

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

	override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val host = request.url.host
    return if (host.contains("prochan")) {
        val response = chain.proceed(
            request.newBuilder()
                .header("Referer", "https://prochan.pro/")
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .build(),
        )
        val contentType = response.header("Content-Type") ?: ""
        if (contentType.contains("octet-stream") || contentType.isEmpty()) {
            val path = request.url.encodedPath.lowercase()
            val fixedType = when {
                path.endsWith(".avif") -> "image/avif"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".gif") -> "image/gif"
                else -> {
                    // قراءة أول بايتات بدون استهلاك البيانات
                    val peek = response.peekBody(16)
                    val bytes = peek.bytes()
                    detectImageType(bytes)
                }
            }
            response.newBuilder()
                .header("Content-Type", fixedType)
                .build()
        } else {
            response
        }
    } else {
        chain.proceed(request)
    }
}

private fun detectImageType(bytes: ByteArray): String {
    if (bytes.size < 4) return "image/jpeg"
    return when {
        // JPEG: FF D8 FF
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() 
            -> "image/jpeg"
        // PNG: 89 50 4E 47
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
            && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() 
            -> "image/png"
        // GIF: 47 49 46
        bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte()
            && bytes[2] == 0x46.toByte() 
            -> "image/gif"
        // WEBP: RIFF....WEBP
        bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte()
            && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()
            && bytes.size >= 12
            && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte()
            && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() 
            -> "image/webp"
        // AVIF/HEIF: ....ftyp
        bytes.size >= 8
            && bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte()
            && bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte() 
            -> "image/avif"
        else -> "image/jpeg"
    }
}

private fun detectImageType(bytes: ByteArray): String {
    if (bytes.size < 4) return "image/jpeg"
    return when {
        // JPEG: FF D8 FF
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        // PNG: 89 50 4E 47
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() 
            && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
        // GIF: 47 49 46
        bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() 
            && bytes[2] == 0x46.toByte() -> "image/gif"
        // WEBP: RIFF....WEBP
        bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() 
            && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()
            && bytes.size >= 12 && bytes[8] == 0x57.toByte() 
            && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() 
            && bytes[11] == 0x50.toByte() -> "image/webp"
        // AVIF: ....ftypavif أو ftypavis
        bytes.size >= 12 && bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte()
            && bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte() -> "image/avif"
        else -> "image/jpeg"
    }
}

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/api/public/series/search?status=approved&limit=18&page=")
			append(page)
			append("&sort=")
			append(
				when (order) {
					SortOrder.UPDATED      -> "latest_chapter"
					SortOrder.POPULARITY   -> "popular"
					SortOrder.ALPHABETICAL -> "az"
					else                   -> "latest_chapter"
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

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc              = webClient.httpGet(manga.publicUrl).parseHtml()
		val chaptersDeferred = async { fetchChapters(manga.url) }
		val scriptData       = collectNextScripts(doc)

		manga.copy(
			description = parseStringField(scriptData, "description"),
			altTitles   = parseAltTitles(scriptData),
			tags        = parseTags(scriptData),
			authors     = parseAuthors(scriptData),
			chapters    = chaptersDeferred.await(),
		)
	}

	private suspend fun fetchChapters(mangaUrl: String): List<MangaChapter> {
		val parts = mangaUrl.split("/").filter { it.isNotEmpty() }
		if (parts.size < 3) return emptyList()

		val type = parts[1]
		val id   = parts[2]

		val json = webClient.httpGet(
			"https://$domain/api/public/$type/$id/chapters?page=1&limit=2000&order=asc",
		).parseJson()
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
				id         = generateUid(chapterUrl),
				title      = chapterTitle,
				number     = chapterNum,
				volume     = 0,
				url        = chapterUrl,
				scanlator  = null,
				uploadDate = uploadDate,
				branch     = null,
				source     = source,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts     = chapter.url.split("/").filter { it.isNotEmpty() }
		val chapterId = parts.getOrNull(4) ?: return emptyList()

		val json    = webClient.httpGet("https://$domain/api/public/chapters/$chapterId").parseJson()
		val cdnPath = json.optString("cdn_path").takeIf { it.isNotEmpty() } ?: "cdn2"
		val images  = json.optJSONArray("images")
			?: json.optJSONObject("metadata")?.optJSONArray("images")
			?: return emptyList()
		val meta = json.optJSONObject("metadata") ?: JSONObject()
		val maps = meta.optJSONArray("maps") ?: JSONArray()

		val result = mutableListOf<MangaPage>()
		for (i in 0 until images.length()) {
			val imagePath = images.optString(i).takeIf { it.isNotEmpty() } ?: continue
			val token     = maps.optJSONObject(i)?.optString("token")

			val finalUrl = buildString {
				append("https://")
				append(cdnPath)
				append(".prochan.pro")
				append(imagePath)
				if (!token.isNullOrEmpty()) {
					append("?token=")
					append(token)
				}
			}

			result.add(
				MangaPage(
					id      = generateUid("${chapter.id}-$i"),
					url     = finalUrl,
					preview = null,
					source  = source,
				),
			)
		}
		return result
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url

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
		val match = Regex("\"altTitles\"\\s*:\\s*(\\[[^\\]]*\\])").find(data)
			?: return emptySet()
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
			.mapNotNull {
				it.groupValues[1].trim().takeIf { v -> v.isNotEmpty() && v != "null" }
			}
			.toSet()
}
