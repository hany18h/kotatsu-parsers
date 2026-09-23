package org.koitharu.kotatsu.parsers.site.anime.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class AnimePhoenixTest {

	@Test
	fun recognizesCloudflareAndAccessDeniedPages() {
		assertTrue(AnimePhoenix.isBlockedPage(Jsoup.parse("<title>Just a moment...</title><div id='challenge-form'>Verify you are human</div>")))
		assertTrue(AnimePhoenix.isBlockedPage(Jsoup.parse("<h1>Access Denied</h1>")))
		assertFalse(AnimePhoenix.isBlockedPage(Jsoup.parse("<h1>قائمة الأنمي</h1>")))
	}

	@Test
	fun staticIndexProvidesCatalogFallback() {
		val parser = AnimePhoenix(MangaLoaderContextMock)
		val document = Jsoup.parse(
			"""
			<a href="/animes/one"><img alt="الأنمي الأول" src="/one.webp"></a>
			<a href="https://anime-phoenix.com/animes/two"><h3>الأنمي الثاني</h3></a>
			""".trimIndent(),
		)

		val result = parser.parseIndexPage(document, 1, "الثاني", SortOrder.UPDATED)

		assertEquals(1, result.size)
		assertEquals("الأنمي الثاني", result.single().title)
		assertEquals("/animes/two", result.single().url)
	}
}
