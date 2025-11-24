package org.koitharu.kotatsu.parsers.site.madara.ar

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.selectLast
import org.koitharu.kotatsu.parsers.util.selectOrThrow
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat

@MangaSourceParser("CROWSCANS", "Hadess", "ar")
internal class CrowScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.CROWSCANS, "www.hadess.xyz") {
	override val datePattern = "dd MMMM، yyyy"

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		return parseChapters(document)
	}

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		return parseChapters(doc)
	}

	private fun parseChapters(doc: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val chapters = doc.select("a.chapter-link")

		return chapters.mapChapters(reversed = true) { i, a ->
			val href = a.attrAsRelativeUrl("href")
			val title = a.selectFirst(".chapter-title")?.text()?.trim() ?: a.ownText()
			val dateText = a.selectFirst(".meta-item[time]")?.attr("time")
				?: a.selectFirst(".meta-item")?.text()

			// Extract chapter number from title or URL
			val chapterNumber = title.toFloatOrNull()
				?: href.substringBeforeLast("/").substringAfterLast("/").toFloatOrNull()
				?: (i + 1f)

			MangaChapter(
				id = generateUid(href),
				title = "Chapter $title",
				number = chapterNumber,
				volume = 0,
				url = href,
				uploadDate = parseChapterDate(dateFormat, dateText),
				source = source,
				scanlator = null,
				branch = null
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = captureDocument(fullUrl)

		// Custom description selector for Hadess
		val desc = doc.selectFirst("div.description div.description-content")?.html()
			?: doc.select(selectDesc).html()

		val stateDiv = doc.selectFirst(selectState)?.selectLast("div.summary-content")
		val state = stateDiv?.let {
			when (it.text().lowercase()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				in abandoned -> MangaState.ABANDONED
				in paused -> MangaState.PAUSED
				else -> null
			}
		}
		val alt = doc.body().select(selectAlt).firstOrNull()?.tableValue()?.textOrNull()
		val genres = doc.body().select(selectGenre).mapNotNullToSet { a -> createMangaTag(a) }

		return manga.copy(
			title = doc.selectFirst("h1")?.textOrNull() ?: manga.title,
			url = fullUrl.toRelativeUrl(domain),
			publicUrl = fullUrl,
			tags = genres,
			description = desc,
			altTitles = setOfNotNull(alt),
			state = state,
			chapters = parseChapters(doc),
			contentRating = if (doc.selectFirst(".adult-confirm") != null || isNsfwSource) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)

		// Use captureDocument since the site has browser checks
		val doc = captureDocument(fullUrl)

		// Try Hadess custom structure first
		val customImages = doc.select("img.preload-image[data-src]")
		if (customImages.isNotEmpty()) {
			return customImages.map { img ->
				val imageUrl = img.attr("data-src").ifEmpty { img.attr("src") }
				val relativeUrl = imageUrl.toRelativeUrl(domain)
				MangaPage(
					id = generateUid(relativeUrl),
					url = relativeUrl,
					preview = null,
					source = source,
				)
			}
		}

		// Fallback to standard Madara selectors
		val root = doc.body().selectFirst(selectBodyPage) ?: throw ParseException(
			"No image found",
			fullUrl,
		)

		return root.select(selectPage).flatMap { div ->
			div.selectOrThrow("img").map { img ->
				val url = img.requireSrc().toRelativeUrl(domain)
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}
	}

	private suspend fun captureDocument(url: String): Document {
		val script = """
			(() => {
				// Check for different types of content
				const hasReadingContent = document.querySelector('div.reading-content') !== null ||
										   document.querySelector('div.page-break') !== null ||
										   document.querySelectorAll('img.preload-image[data-src]').length > 0 ||
										   document.querySelector('img[data-src]') !== null;

				const hasMangaList = document.querySelector('div.page-listing-item') !== null ||
									 document.querySelector('div.page-item-detail') !== null ||
									 document.querySelector('.wp-manga-item') !== null;

				// For manga details, specifically wait for chapters to load
				const hasChapters = document.querySelectorAll('a.chapter-link').length > 0;
				const hasBasicDetails = document.querySelector('div.description') !== null ||
										document.querySelector('.post-title') !== null;

				// If this is a manga details page, wait for chapters to load
				if (document.querySelector('#manga-chapters-holder') !== null) {
					if (hasChapters && hasBasicDetails) {
						window.stop();
						const elementsToRemove = document.querySelectorAll('script, iframe, object, embed, style');
						elementsToRemove.forEach(el => el.remove());
						return document.documentElement.outerHTML;
					}
					return null; // Keep waiting for chapters to load
				}

				// For other content types, return immediately
				if (hasReadingContent || hasMangaList || hasBasicDetails) {
					window.stop();
					const elementsToRemove = document.querySelectorAll('script, iframe, object, embed, style');
					elementsToRemove.forEach(el => el.remove());
					return document.documentElement.outerHTML;
				}
				return null;
			})();
		""".trimIndent()

		val rawHtml = context.evaluateJs(url, script, 30000L) ?: throw ParseException("Failed to load page", url)

		val html = if (rawHtml.startsWith("\"") && rawHtml.endsWith("\"")) {
			rawHtml.substring(1, rawHtml.length - 1)
				.replace("\\\"", "\"")
				.replace("\\n", "\n")
				.replace("\\r", "\r")
				.replace("\\t", "\t")
				.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
					val hexValue = match.groupValues[1]
					hexValue.toInt(16).toChar().toString()
				}
		} else rawHtml

		return Jsoup.parse(html, url)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
