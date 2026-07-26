package org.koitharu.kotatsu.parsers.site.anime.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AnimeSlayerTest {

	@Test
	fun parsesAlternativeServerLinks() {
		val raw = """
			[
			  "https:\/\/www.mediafire.com\/file_premium\/key\/episode_h.mp4\/file",
			  "https:\/\/streamtape.to\/v\/video-id"
			]
		""".trimIndent()

		assertEquals(
			listOf(
				"https://www.mediafire.com/file_premium/key/episode_h.mp4/file",
				"https://streamtape.to/v/video-id",
			),
			AnimeSlayer.parseAlternativeLinks(raw),
		)
	}

	@Test
	fun extractsMediaFireDownloadButton() {
		val document = Jsoup.parse(
			"""<a id="downloadButton" href="https://download.example.com/episode_h.mp4">Download</a>""",
		)

		assertEquals(
			"https://download.example.com/episode_h.mp4",
			AnimeSlayer.extractMediaFireDirectUrl(document),
		)
	}

	@Test
	fun extractsDirectVideoFromEscapedPlayerHtml() {
		val raw = """sources: [{"file":"https:\/\/cdn.example.com\/episode\/720p.m3u8?token=abc\u0026e=1"}]"""

		assertEquals(
			listOf("https://cdn.example.com/episode/720p.m3u8?token=abc&e=1"),
			AnimeSlayer.findDirectMediaUrls(raw),
		)
	}
}
