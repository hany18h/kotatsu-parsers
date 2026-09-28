package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONArray
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN

internal class AzoramoonTest {
	private val parser = Azoramoon(MangaLoaderContextMock)
	private val manga = Manga(
		id = 1,
		title = "Four choices",
		altTitles = emptySet(),
		url = "/series/four-choices",
		publicUrl = "https://azorafly.com/series/four-choices",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = MangaParserSource.AZORAMOON,
	)

	@Test
	fun parsesCurrentApiChapterShapeAndKeepsStableChapterUrls() {
		val chapters = parser.parseApiChapters(manga, JSONArray(
			"""[{"slug":"chapter-42.5","number":"42.5","title":"Review","createdAt":"2026-09-28T21:43:24.000Z"}]""",
		))

		assertEquals(1, chapters.size)
		assertEquals("/series/four-choices/chapter-42.5", chapters.single().url)
		assertEquals(42.5f, chapters.single().number)
	}

	@Test
	fun parsesNewSiteLinksWhenAstroPropertiesAreAbsent() {
		val doc = Jsoup.parse(
			"""
			<a href="/series/four-choices/chapter-1">Start reading</a>
			<a href="/series/four-choices/chapter-42.5">Latest chapter</a>
			<a href="/series/four-choices/chapter-1">Chapter one</a>
			""".trimIndent(),
		)
		val chapters = parser.parseChaptersFromHtml(manga, doc)

		assertEquals(listOf(1f, 42.5f), chapters.map { it.number })
	}
}
