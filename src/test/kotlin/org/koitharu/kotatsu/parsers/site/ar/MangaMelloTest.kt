package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class MangaMelloTest {

	@Test
	fun `extracts old and current chapter image fields`() {
		val json = JSONObject(
			"""
			{
			  "data": {
			    "chapterImages": [
			      {
			        "src": "https:\/\/cdn.example.org\/1.webp",
			        "original_src": "https:\/\/cdn.example.org\/1-original.webp"
			      },
			      {"original_src": "//img.example.org/2.jpg"},
			      "/storage/3.png"
			    ]
			  }
			}
			""".trimIndent(),
		)

		assertEquals(
			listOf(
				"https://cdn.example.org/1.webp",
				"https://img.example.org/2.jpg",
				"https://api.mangamello.com/storage/3.png",
			),
			MangaMelloParser.extractImageUrls(json, "https://api.mangamello.com/v1/"),
		)
	}

	@Test
	fun `normalizes escaped and protocol relative image urls`() {
		assertEquals(
			"https://cdn.example.org/page.webp?x=1&y=2",
			MangaMelloParser.normalizeImageUrl(
				"https:\\/\\/cdn.example.org\\/page.webp?x=1\\u0026y=2",
				"https://api.mangamello.com/v1/",
			),
		)
		assertEquals(
			"https://cdn.example.org/page.jpg",
			MangaMelloParser.normalizeImageUrl("//cdn.example.org/page.jpg", "https://api.mangamello.com/v1/"),
		)
	}
}
