package org.koitharu.kotatsu.parsers.site.ar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock

internal class GalaxyNovelsTest {

	@Test
	fun parsesAndOrdersCachedChapterMetadata() {
		val parser = GalaxyNovels(MangaLoaderContextMock)
		val chapters = parser.parseCachedChapters(
			"""
			{
			  "chapters": [
			    {"position":12,"number":"12.5","label":"الفصل 12.5","title":"العودة","url":"/novel/a/chapter-12/","date_iso":"2026-08-10"},
			    {"position":2,"number":"2","label":"الفصل 2","title":"","url":"/novel/a/chapter-2/","date_iso":"2026-08-01"}
			  ]
			}
			""".trimIndent(),
		)

		assertEquals(listOf(12.5f, 2f), chapters.map { it.number })
		assertEquals("الفصل 12.5 — العودة", chapters.first().title)
	}
}
