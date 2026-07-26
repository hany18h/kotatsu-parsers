package org.koitharu.kotatsu.parsers.site.anime.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class Anime3rbTest {

	@Test
	fun enablesCloudflareResolutionByDefault() {
		val keys = mutableListOf<ConfigKey<*>>()
		Anime3rb(MangaLoaderContextMock).onCreateConfig(keys)

		assertTrue(
			keys.filterIsInstance<ConfigKey.InterceptCloudflare>().single().defaultValue,
		)
	}

	@Test
	fun extractsPlayerUrlFromLivewireSnapshot() {
		val snapshot = """
			{"data":{"video_url":"https:\/\/video.vid3rb.com\/player\/episode-id?token=abc\u0026expires=123"}}
		""".trimIndent()
		val document = Jsoup.parse("""<div wire:snapshot='$snapshot'></div>""")

		assertEquals(
			listOf("https://video.vid3rb.com/player/episode-id?token=abc&expires=123"),
			Anime3rb.findPlayerUrls(document),
		)
	}

	@Test
	fun extractsFreeQualitiesFromPlayer() {
		val html = """
			<script>
			var video_sources = [];
			var video_sources = [
			  {"src":"https:\/\/video.vid3rb.com\/video\/720?token=a","label":"720p","res":"720","premium":false},
			  {"src":"https:\/\/video.vid3rb.com\/video\/1080?token=b","label":"1080p","res":"1080","premium":true},
			  {"src":"https:\/\/video.vid3rb.com\/video\/480?token=c","label":"480p","res":"480","premium":false}
			];
			</script>
		""".trimIndent()

		assertEquals(
			listOf(
				ParsedVideoSource("https://video.vid3rb.com/video/720?token=a", "720p"),
				ParsedVideoSource("https://video.vid3rb.com/video/480?token=c", "480p"),
			),
			Anime3rb.findVideoSources(html),
		)
	}
}
