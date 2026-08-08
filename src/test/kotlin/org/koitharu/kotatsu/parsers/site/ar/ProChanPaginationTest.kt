package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ProChanPaginationTest {

	@Test
	fun mergesEveryChapterPageWithoutDuplicatingOverlappingItems() {
		val target = JSONArray()
		val seen = HashSet<Int>()
		val firstPage = JSONArray(
			(5 downTo 1).map { number ->
				JSONObject()
					.put("id", number)
					.put("chapter_number", number.toString())
					.put("language", "AR")
			},
		)
		val secondPage = JSONArray(
			listOf(
				JSONObject().put("id", 1).put("chapter_number", "1").put("language", "AR"),
				JSONObject().put("id", 6).put("chapter_number", "6").put("language", "AR"),
			),
		)

		assertEquals(5, appendUniqueProChanChapters(target, firstPage, seen))
		assertEquals(1, appendUniqueProChanChapters(target, secondPage, seen))
		assertEquals(
			listOf(1, 2, 3, 4, 5, 6),
			filterAndSortProChanChapters(target).map { it.getInt("id") },
		)
	}

	@Test
	fun routesMovedSeriesToTheCurrentWebsite() {
		assertEquals(
			"procomic.net",
			getProChanContentDomain(JSONObject().put("isBlockedSeries", true), "procomic.pro"),
		)
		assertEquals(
			"procomic.pro",
			getProChanContentDomain(JSONObject().put("isBlockedSeries", false), "procomic.pro"),
		)
	}
}
