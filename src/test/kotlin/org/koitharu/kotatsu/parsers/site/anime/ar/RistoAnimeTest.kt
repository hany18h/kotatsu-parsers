package org.koitharu.kotatsu.parsers.site.anime.ar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RistoAnimeTest {

	@Test
	fun `extracts direct HLS and MP4 URLs without duplicates`() {
		val html = """
			<script>
				file: "https://cdn.example/video/master.m3u8?token=abc",
				backup: 'https://cdn.example/video/episode.mp4',
				again: "https://cdn.example/video/master.m3u8?token=abc"
			</script>
		""".trimIndent()

		assertEquals(
			listOf(
				"https://cdn.example/video/master.m3u8?token=abc",
				"https://cdn.example/video/episode.mp4",
			),
			RistoAnime.findDirectMediaUrls(html),
		)
	}
}
