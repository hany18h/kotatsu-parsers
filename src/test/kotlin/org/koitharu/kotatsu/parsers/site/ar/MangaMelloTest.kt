package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.SortOrder

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
			MangaMelloPlus.extractImageUrls(json, "https://api.mangamello.com/v1/"),
		)
	}

	@Test
	fun `normalizes escaped and protocol relative image urls`() {
		assertEquals(
			"https://cdn.example.org/page.webp?x=1&y=2",
			MangaMelloPlus.normalizeImageUrl(
				"https:\\/\\/cdn.example.org\\/page.webp?x=1\\u0026y=2",
				"https://api.mangamello.com/v1/",
			),
		)
		assertEquals(
			"https://cdn.example.org/page.jpg",
			MangaMelloPlus.normalizeImageUrl("//cdn.example.org/page.jpg", "https://api.mangamello.com/v1/"),
		)
	}

	@Test
	fun `rewrites retired lekmanga image hosts`() {
		assertEquals(
			"https://s2storm.lekmanga.site/manga/arb12/data/chapter/image-01.jpg",
			MangaMelloPlus.normalizeImageUrl(
				"https://s2lekmangas.lekmanga.site/manga/arb12/data/chapter/image-01.jpg",
				"https://plus.mangamello.com/api/v1/",
			),
		)
		assertEquals(
			"https://s17storm.lekmanga.site/manga/arb1/data/chapter/page.webp?width=1200",
			MangaMelloPlus.rewriteLegacyImageUrl(
				"https://s17lekmangas.lekmanga.site/manga/arb1/data/chapter/page.webp?width=1200",
			),
		)
	}

	@Test
	fun `keeps current image hosts unchanged`() {
		val currentLekMangaUrl =
			"https://s3storm.lekmanga.site/manga/arb13/data/chapter/image-1.jpg"
		val olympusUrl =
			"https://olympustaff.com/uploads/manga_7d771/0/page.webp"

		assertEquals(currentLekMangaUrl, MangaMelloPlus.rewriteLegacyImageUrl(currentLekMangaUrl))
		assertEquals(olympusUrl, MangaMelloPlus.rewriteLegacyImageUrl(olympusUrl))
	}

	@Test
	fun `search path omits sort parameters rejected by plus api`() {
		val path = MangaMelloPlus.buildListPath(
			page = 1,
			order = SortOrder.UPDATED,
			query = "the",
		)

		assertTrue(path.startsWith("mangas/search?"))
		assertTrue(path.contains("title=the"))
		assertFalse(path.contains("sort_by"))
		assertFalse(path.contains("dir="))
	}
}
