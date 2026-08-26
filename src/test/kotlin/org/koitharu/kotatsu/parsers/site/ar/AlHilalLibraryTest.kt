package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import java.util.Base64

internal class AlHilalLibraryTest {

	private val parser = AlHilalLibrary(MangaLoaderContextMock)

	@Test
	fun decodesDecoratedApiResponse() {
		val json = """{"book":{"id":6617,"title":"الياقوت"}}"""
		val inner = "A".repeat(17) + "04" + "B".repeat(4) + json
		val encoded = Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.UTF_8))
		val decorated = "C".repeat(23) + encoded.take(12) + "é-�" + encoded.drop(12)

		assertEquals(json, parser.decodeResponse(decorated))
	}

	@Test
	fun parsesBooksAndCreatesPdfPages() {
		val books = parser.parseBookList(
			JSONArray(
				"""
				[
				  {
				    "id": 6617,
				    "title": "الياقوت",
				    "author": "شهد قربان",
				    "category": "روايات عربية",
				    "image": "https://alkawn-lib.site/Books/Images/Book6617.jpg",
				    "rating": 4.85
				  }
				]
				""".trimIndent(),
			),
		)
		val book = books.single()

		assertEquals("الياقوت", book.title)
		assertEquals(setOf("شهد قربان"), book.authors)
		assertEquals("روايات عربية", book.tags.single().title)
		assertTrue(book.rating > 0.9f)
	}
}
