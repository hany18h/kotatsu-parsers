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
	fun keepsServerOrderAndMakesRelativePiecesAbsolute() {
		val map = JSONObject().apply {
			put("pieces", JSONArray(listOf("/piece-a.avif", "https://cdn.example/piece-b.avif")))
			put("order", JSONArray(listOf(1, 0)))
			put("dim", JSONArray(listOf(1200, 1800)))
			put("mode", "vertical_2")
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
	}
}
