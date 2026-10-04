package org.koitharu.kotatsu.parsers.site.anime.ar

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class AnimeWitcherTest {

	@Test
	fun usesCurrentAlgoliaApplicationForFallbackHosts() {
		assertEquals(
			listOf(
				"CURRENTID-dsn.algolia.net",
				"CURRENTID-1.algolianet.com",
				"CURRENTID-2.algolianet.com",
				"CURRENTID-3.algolianet.com",
			),
			AnimeWitcher.algoliaReadHosts("CURRENTID"),
		)
	}

	@Test
	fun readsCatalogSettingsFromOfficialFirestoreDocument() {
		val document = JSONObject(
			"""{"fields":{"search_settings":{"mapValue":{"fields":{
				"app_id_v4":{"stringValue":"CURRENTID"},
				"browse_api_key":{"stringValue":"current-search-key"}
			}}}}}""",
		)
		assertEquals(
			"CURRENTID" to "current-search-key",
			AnimeWitcher.extractAlgoliaCatalogCredentials(document),
		)
	}

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

	@Test
	fun extractsEscapedDirectVideoFromServerPage() {
		assertEquals(
			"https://cdn.example.org/anime/episode.mp4?token=a&expires=2",
			AnimeWitcher.findDirectVideoInPage(
				"""var file = "https:\/\/cdn.example.org\/anime\/episode.mp4?token=a&amp;expires=2";""",
			),
		)
	}
}
