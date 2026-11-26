package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock

internal class MangaDarTest {

	@Test
	fun parsesChapterRowsFromTheCurrentDynamicJsonScriptId() {
		val parser = MangaDar(MangaLoaderContextMock)
		val document = Jsoup.parse(
			"""
			<script id="mv-chapters-222350" type="application/json">
			[{"id":222350,"number":"41","url":"https://mangadar.com/manga/example/41/","date":1795636457}]
			</script>
			""".trimIndent(),
		)

		val chapters = parser.parseChapters(document)

		assertEquals(1, chapters.size)
		assertEquals("/manga/example/41/", chapters.single().url)
		assertEquals(41f, chapters.single().number)
		assertEquals("الفصل 41", chapters.single().title)
	}

	@Test
	fun readsPagesFromTheCurrentDynamicJsonScriptId() {
		val parser = MangaDar(MangaLoaderContextMock)
		val document = Jsoup.parse(
			"""
			<script id="mv-pages-222350" type="application/json">
			["https://cdn.mangadar.com/001.webp","https://cdn.mangadar.com/002.webp"]
			</script>
			""".trimIndent(),
		)

		assertEquals(
			listOf("https://cdn.mangadar.com/001.webp", "https://cdn.mangadar.com/002.webp"),
			parser.parsePageUrls(document),
		)
	}
}
