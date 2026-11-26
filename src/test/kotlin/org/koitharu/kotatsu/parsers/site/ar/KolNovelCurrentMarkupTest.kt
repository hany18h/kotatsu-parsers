package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock

internal class KolNovelCurrentMarkupTest {

	@Test
	fun parsesCurrentChapterMarkup() {
		val parser = KolNovel(MangaLoaderContextMock)
		val document = Jsoup.parse(
			"""
			<div class="kol-series-chapter">
			  <a href="https://kolnovel.com/series/example/chapter-12/" data-kol-chapter-transition-link>
			    <span data-kol-chapter-number="12">12</span>
			    <span data-kol-chapter-title>الفصل الثاني عشر</span>
			  </a>
			  <time data-kol-chapter-date datetime="2026-11-26T10:00:00+00:00"></time>
			</div>
			""".trimIndent(),
			"https://kolnovel.com/series/example/",
		)

		val chapters = parser.parseChapters(document)

		assertEquals(1, chapters.size)
		assertEquals("/series/example/chapter-12/", chapters.single().url)
		assertEquals(12f, chapters.single().number)
		assertEquals("الفصل الثاني عشر", chapters.single().title)
	}
}
