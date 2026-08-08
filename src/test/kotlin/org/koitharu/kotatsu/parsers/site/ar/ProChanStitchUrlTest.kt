package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

internal class ProChanStitchUrlTest {

	@Test
	fun unwrapsNestedNextPayload() {
		val raw = """self.__next_f.push([1,"{\"appImages\":[{\"mobile\":\"https:\\/\\/app.procomic.pro\\/p1.avif\"}]}"])"""
		val decoded = decodeProChanNextPayload(raw)
		assertTrue(decoded.contains(""""appImages":[{"mobile":"https://app.procomic.pro/p1.avif"}]"""))
	}

	@Test
	fun keepsServerOrderAndMakesRelativePiecesAbsolute() {
		val map = JSONObject().apply {
			put("pieces", JSONArray(listOf("/piece-a.avif", "https://cdn.example/piece-b.avif")))
			put("order", JSONArray(listOf(1, 0)))
			put("dim", JSONArray(listOf(1200, 1800)))
			put("mode", "vertical_2")
			put(
				"rects",
				JSONArray(
					listOf(
						JSONObject().put("left", 0).put("top", 0).put("width", 1200).put("height", 900),
						JSONObject().put("left", 0).put("top", 900).put("width", 1200).put("height", 900),
					),
				),
			)
		}

		val stitchUrl = requireNotNull(buildProChanStitchUrl(map, "procomic.pro"))
		assertTrue(stitchUrl.startsWith("prochan-map://stitch?"))
		val query = URI(stitchUrl).rawQuery.split('&').associate { item ->
			item.substringBefore('=') to URLDecoder.decode(item.substringAfter('='), StandardCharsets.UTF_8)
		}
		assertEquals("1200", query["w"])
		assertEquals("1800", query["h"])
		assertEquals("vertical_2", query["mode"])
		val pieces = String(Base64.getUrlDecoder().decode(query.getValue("pieces")), Charsets.UTF_8)
		assertEquals(
			"https://cdn.example/piece-b.avif|https://procomic.pro/piece-a.avif",
			pieces,
		)
		val rects = JSONArray(
			String(Base64.getUrlDecoder().decode(query.getValue("rects")), Charsets.UTF_8),
		)
		assertEquals(2, rects.length())
		assertEquals(900, rects.getJSONObject(1).getInt("top"))
	}

	@Test
	fun buildsSignedImageProxyWithoutLosingRawUrl() {
		val raw = "https://cdn2.procomic.pro/667/46319/page 1.avif?x=1&y=2"
		val result = buildProChanSignedImageUrl(
			domain = "procomic.pro",
			rawUrl = raw,
			token = "signed/token+value",
			expires = 1_800_000_000L,
		)
		val query = URI(result).rawQuery.split('&').associate { item ->
			item.substringBefore('=') to URLDecoder.decode(item.substringAfter('='), StandardCharsets.UTF_8)
		}
		assertEquals(raw, query["url"])
		assertEquals("signed/token+value", query["token"])
		assertEquals("1800000000", query["expires"])
	}

	@Test
	fun keepsOnlyUnlockedArabicChaptersAndSortsNumerically() {
		val data = JSONArray(
			listOf(
				JSONObject().put("id", 4).put("chapter_number", "10").put("language", "AR"),
				JSONObject().put("id", 3).put("chapter_number", "2").put("language", "EN"),
				JSONObject().put("id", 2).put("chapter_number", "3").put("language", "AR")
					.put("lockedByCoins", true),
				JSONObject().put("id", 1).put("chapter_number", "1").put("language", "AR"),
			),
		)

		assertEquals(
			listOf(1, 4),
			filterAndSortProChanChapters(data).map { it.getInt("id") },
		)
	}
}
