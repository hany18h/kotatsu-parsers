package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("PROCHAN", "ProChan", "ar")
class ProChan(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.PROCHAN, 18), Interceptor {

	override val configKeyDomain = ConfigKey.Domain("procomic.pro")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities()

	private val dateFormat by lazy {
		SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val newRequest = request.newBuilder()
			.header("Referer", "https://${domain}/")
			.header("Origin", "https://${domain}")
			.header("Accept", "*/*")
			.header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
			.header(
				"User-Agent",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
			)
			.build()

		val response = chain.proceed(newRequest)

		// Fix content type for images from CDN
		val host = request.url.host
		if (host.contains("procomic") || host.contains("prochan")) {
			val contentType = response.header("Content-Type") ?: ""
			if (contentType.contains("octet-stream") || contentType.isEmpty()) {
				val path = request.url.encodedPath.lowercase(Locale.ROOT)
				val fixedType = when {
					path.endsWith(".avif") -> "image/avif"
					path.endsWith(".webp") -> "image/webp"
					path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
					path.endsWith(".png") -> "image/png"
					else -> null
				}
				if (fixedType != null) {
					return response.newBuilder()
						.header("Content-Type", fixedType)
						.build()
				}
			}
		}
		return response
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val domain = domain
		val sortParam = when (order) {
			SortOrder.UPDATED -> "updates"
			SortOrder.POPULARITY -> "series"
			SortOrder.ALPHABETICAL -> "series"
			else -> "series"
		}

		val url = "https://$domain/$sortParam"
		val doc = webClient.httpGet(url).parseHtml()

		// Parse the Next.js embedded data from the page
		val scripts = doc.select("script")
		val mangaList = mutableListOf<Manga>()

		// Try parsing from HTML elements first
		val seriesLinks = doc.select("a[href*=/series/]")
		val seen = mutableSetOf<String>()

		for (link in seriesLinks) {
			val href = link.attr("href")
			if (href.isBlank() || !href.contains("/series/")) continue

			// Extract manga info from URL: /series/{type}/{id}/{slug}
			val parts = href.trimEnd('/').split("/")
			if (parts.size < 4) continue

			val idStr = parts.getOrNull(parts.indexOf("series") + 2) ?: continue
			val id = idStr.toLongOrNull() ?: continue
			val slug = parts.getOrNull(parts.indexOf("series") + 3) ?: continue

			if (!seen.add("$id")) continue

			val title = link.selectFirst("h3, h2, [class*=title]")?.text()?.trim()
				?: link.attr("title").ifBlank { slug.replace("-", " ").trim() }

			val coverImg = link.selectFirst("img")
			val coverUrl = coverImg?.let {
				it.attr("src").ifBlank { it.attr("data-src") }
			} ?: ""

			mangaList.add(
				Manga(
					id = generateUid(id),
					url = href,
					publicUrl = "https://$domain$href",
					title = title,
					coverUrl = coverUrl,
					source = source,
					altTitles = emptySet(),
					rating = RATING_UNKNOWN,
					contentRating = null,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					description = null,
				),
			)
		}

		return mangaList
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val domain = domain
		val url = manga.publicUrl.ifBlank { "https://$domain${manga.url}" }
		val doc = webClient.httpGet(url).parseHtml()

		// Extract manga details
		val title = doc.selectFirst("h1, [class*=series-title]")?.text()?.trim() ?: manga.title
		val description = doc.selectFirst("[class*=description], [class*=synopsis], meta[name=description]")
			?.let { it.text().ifBlank { it.attr("content") } }
		val coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
			?: manga.coverUrl

		val author = doc.selectFirst("[class*=author]")?.text()?.trim()

		// Parse status
		val statusText = doc.body().text()
		val state = when {
			statusText.contains("مستمر") || statusText.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
			statusText.contains("مكتمل") || statusText.contains("completed", ignoreCase = true) -> MangaState.FINISHED
			else -> null
		}

		// Parse chapters from the table
		val chapters = mutableListOf<MangaChapter>()
		val chapterLinks = doc.select("a[href*=${manga.url}]").filter {
			val href = it.attr("href")
			// Chapter URLs have additional path segments after the manga URL
			href.split("/").size > manga.url.trimEnd('/').split("/").size
		}

		for ((index, link) in chapterLinks.withIndex()) {
			val href = link.attr("href")
			val parts = href.trimEnd('/').split("/")

			// Chapter URL: /series/{type}/{id}/{slug}/{chapterId}/{chapterNum}
			val chapterId = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: continue
			val chapterNum = parts.lastOrNull()?.toFloatOrNull() ?: continue

			val chapterTitle = link.text().trim().ifBlank { "Chapter ${chapterNum.toInt()}" }

			chapters.add(
				MangaChapter(
					id = generateUid(chapterId),
					url = href,
					title = chapterTitle,
					number = chapterNum,
					volume = 0,
					branch = null,
					uploadDate = 0L,
					source = source,
					scanlator = null,
				),
			)
		}

		// Sort chapters by number (ascending)
		val sortedChapters = chapters
			.distinctBy { it.id }
			.sortedBy { it.number }

		val authors = author?.let { setOf(it) } ?: emptySet()

		return manga.copy(
			title = title,
			description = description,
			coverUrl = coverUrl,
			largeCoverUrl = coverUrl,
			authors = authors,
			state = state,
			chapters = sortedChapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val domain = domain
		val url = if (chapter.url.startsWith("http")) chapter.url else "https://$domain${chapter.url}"
		val doc = webClient.httpGet(url).parseHtml()

		val pages = mutableListOf<MangaPage>()

		// Try to find images in the reader
		val images = doc.select("img[src*=prochan], img[src*=procomic], img[data-src*=prochan], img[data-src*=procomic]")

		for ((index, img) in images.withIndex()) {
			val imgUrl = img.attr("src").ifBlank { img.attr("data-src") }
			if (imgUrl.isBlank()) continue

			pages.add(
				MangaPage(
					id = generateUid(imgUrl),
					url = imgUrl,
					preview = null,
					source = source,
				),
			)
		}

		// If no images found via direct parsing, try extracting from Next.js data
		if (pages.isEmpty()) {
			val scripts = doc.select("script").map { it.data() }
			for (script in scripts) {
				// Look for image URLs in script data
				val regex = Regex("""https?://[^"'\s]+\.(avif|webp|jpg|jpeg|png)""")
				val matches = regex.findAll(script)
					.map { it.value }
					.filter { it.contains("prochan") || it.contains("procomic") }
					.distinct()
					.toList()

				for ((index, imgUrl) in matches.withIndex()) {
					pages.add(
						MangaPage(
							id = generateUid(imgUrl),
							url = imgUrl,
							preview = null,
							source = source,
						),
					)
				}
				if (pages.isNotEmpty()) break
			}
		}

		return pages
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions()
	}
}
