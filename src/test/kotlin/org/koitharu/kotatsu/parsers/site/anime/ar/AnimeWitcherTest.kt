package org.koitharu.kotatsu.parsers.site.anime.ar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class AnimeWitcherTest {

	@Test
	fun convertsPixelDrainPageToDirectVideo() {
		assertEquals(
			"https://pixeldrain.com/api/file/S4LJMf3n",
			AnimeWitcher.toDirectVideoUrl(
				link = "https://pixeldrain.com/u/S4LJMf3n",
				directLink = false,
			),
		)
	}

	@Test
	fun acceptsOnlyExplicitDirectVideoLinks() {
		assertEquals(
			"https://cdn.example.org/anime/episode.m3u8?token=abc",
			AnimeWitcher.toDirectVideoUrl(
				link = "https://cdn.example.org/anime/episode.m3u8?token=abc",
				directLink = false,
			),
		)
		assertNull(
			AnimeWitcher.toDirectVideoUrl(
				link = "https://example.org/watch/episode",
				directLink = false,
			),
		)
	}

	@Test
	fun rejectsEmbedPagesThatOnlyLookLikeDirectVideos() {
		assertNull(
			AnimeWitcher.toDirectVideoUrl(
				link = "https://streamtape.com/v/Rqyo3YvzljtdW2Z/episode.mp4",
				directLink = false,
			),
		)
	}
}
