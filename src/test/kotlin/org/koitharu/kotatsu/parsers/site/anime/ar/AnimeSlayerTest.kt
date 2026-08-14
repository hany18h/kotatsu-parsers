package org.koitharu.kotatsu.parsers.site.anime.ar

import org.json.JSONArray
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.network.UserAgents

internal class AnimeSlayerTest {

	@Test
	fun ignoresPersistedUserAgentAndUsesTheSameIdentityInDebugAndRelease() {
		val keys = mutableListOf<ConfigKey<*>>()
		val parser = AnimeSlayer(MangaLoaderContextMock)
		parser.onCreateConfig(keys)

		assertEquals(UserAgents.FIREFOX_MOBILE, parser.getRequestHeaders()["User-Agent"])
		assertEquals(emptyList<ConfigKey.UserAgent>(), keys.filterIsInstance<ConfigKey.UserAgent>())
	}

	@Test
	fun findsCurrentEpisodeByNumberWhenStoredIdIsStale() {
		val episodes = JSONArray(
			"""[{"episode_id":"new-id","episode_number":"12"}]""",
		)

		assertEquals(
			"new-id",
			AnimeSlayer.findEpisode(episodes, episodeId = "old-id", episodeNumber = 12f)
				?.optString("episode_id"),
		)
	}

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
	fun parsesCurrentAnimeSlayerAlternativePayload() {
		val raw = """
			[
			  "https:\/\/www.mediafire.com\/file_premium\/mdqifsktm3brygw\/episode_uhd_1080p.mp4\/file",
			  "https:\/\/www.mediafire.com\/file_premium\/43hooa5vmr49l8j\/episode_h.mp4\/file",
			  "https:\/\/streamtape.to\/v\/RQ4Ygwwa2GILr0"
			]
		""".trimIndent()

		assertEquals(
			listOf(
				"https://www.mediafire.com/file_premium/mdqifsktm3brygw/episode_uhd_1080p.mp4/file",
				"https://www.mediafire.com/file_premium/43hooa5vmr49l8j/episode_h.mp4/file",
				"https://streamtape.to/v/RQ4Ygwwa2GILr0",
			),
			AnimeSlayer.parseAlternativeLinks(raw),
		)
	}

	@Test
	fun parsesAlternativeLinksFromObjectsAndEscapedHtml() {
		assertEquals(
			listOf(
				"https://streamtape.to/v/video-id",
				"https://cdn.example.com/episode.mp4",
			),
			AnimeSlayer.parseAlternativeLinks(
				"""[{"url":"https:\/\/streamtape.to\/v\/video-id"},{"file":"https:\/\/cdn.example.com\/episode.mp4"}]""",
			),
		)
		assertEquals(
			listOf("https://streamtape.to/v/fallback-id"),
			AnimeSlayer.parseAlternativeLinks(
				"""<a href="https:\/\/streamtape.to\/v\/fallback-id">watch</a>""",
			),
		)
	}

	@Test
	fun parsesNestedAlternativePayloads() {
		val raw = """{"response":{"servers":[{"provider":{"file":"https:\/\/cdn.example.com\/episode.mp4"}}]}}"""

		assertEquals(
			listOf("https://cdn.example.com/episode.mp4"),
			AnimeSlayer.parseAlternativeLinks(raw),
		)
	}

	@Test
	fun buildsEncodedAndSlashFallbacksForTheAlternativeEndpoint() {
		assertEquals(
			listOf(
				"https://a-reslayer.com/la/public/api/f?n=title%5C1",
				"https://a-reslayer.com/la/public/api/f?n=title/1",
				"https://a-reslayer.com/la/public/api/f?n=title\\1",
			),
			AnimeSlayer.alternativeEndpointCandidates(
				"https://a-reslayer.com/la/public/api/f?n=title\\1",
			),
		)
	}

	@Test
	fun convertsBrokenLegacyCdnUrlToAlternativeEndpoint() {
		assertEquals(
			"https://a-reslayer.com/la/public/api/f?n=anime_slug%5C5",
			AnimeSlayer.alternativeUrlFromCdn(
				"https://anslayer.com/anime/public/vq.php?f=anime_slug&e=5%7Ccbm",
			),
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
	fun extractsMediaFireDirectLinkWhenButtonIdChanges() {
		val document = Jsoup.parse(
			"""<div class="download_link"><a href="https://download.example.com/episode_h.mp4">Download</a></div>""",
			"https://www.mediafire.com/file/example/file",
		)

		assertEquals(
			"https://download.example.com/episode_h.mp4",
			AnimeSlayer.extractMediaFireDirectUrl(document),
		)
	}

	@Test
	fun convertsOkRuWatchUrlsToTheLightweightEmbedPlayer() {
		assertEquals(
			"https://ok.ru/videoembed/123456789",
			AnimeSlayer.okRuEmbedUrl("https://m.ok.ru/video/123456789"),
		)
	}

	@Test
	fun extractsOkRuHlsAndQualityStreamsFromPlayerMetadata() {
		val metadata = org.json.JSONObject()
			.put(
				"movie",
				org.json.JSONObject()
					.put("hlsManifestUrl", "https://vd.okcdn.ru/master.m3u8?token=abc")
					.put(
						"videos",
						JSONArray()
							.put(
								org.json.JSONObject()
									.put("name", "hd")
									.put("url", "https://vd.okcdn.ru/video-720.mp4"),
							)
							.put(
								org.json.JSONObject()
									.put("name", "sd")
									.put("url", "https://www.youtube.com/v/external"),
							),
					),
			)
			.toString()
		val options = org.json.JSONObject()
			.put("flashvars", org.json.JSONObject().put("metadata", metadata))
			.toString()
		val document = Jsoup.parse("""<div data-options='$options'></div>""")

		assertEquals(
			listOf(
				"https://vd.okcdn.ru/master.m3u8?token=abc" to null,
				"https://vd.okcdn.ru/video-720.mp4" to "720p",
			),
			AnimeSlayer.extractOkRuVideos(document),
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

	@Test
	fun extractsStreamTapeUrlFromHiddenPlayerNode() {
		val document = Jsoup.parse(
			"""
			<div id="norobotlink" style="display:none">
			  /streamtape.to/get_video?id=video-id&amp;expires=123&amp;token=abc
			</div>
			""".trimIndent(),
			"https://streamtape.to/v/video-id",
		)

		assertEquals(
			"https://streamtape.to/get_video?id=video-id&expires=123&token=abc&stream=1",
			AnimeSlayer.extractStreamTapeDirectUrl(document, "https://streamtape.to/v/video-id"),
		)
	}

	@Test
	fun extractsCurrentStreamTapeUrlFromObfuscatedAssignment() {
		val raw = """
			<div id="ideoooolink" style="display:none;">
			  /streamtape.to/get_video?id=wrong&token=wrong
			</div>
			<script>
			document.getElementById('ideoooolink').innerHTML =
				"/streamtape.to/get_video?id=YV" +
				('xcddxljjYGwRuvYQj&expires=1785425346&token=wrong').substring(1).substring(2);
			document.getElementById('captchalink').innerHTML =
				'//stre' +
				('defgamtape.to/get_video?id=YVxljjYGwRuvYQj&expires=1785425346&token=working').substring(4);
			</script>
		""".trimIndent()

		assertEquals(
			"https://streamtape.to/get_video?id=YVxljjYGwRuvYQj" +
				"&expires=1785425346&token=working&stream=1",
			AnimeSlayer.extractStreamTapeScriptUrl(raw, "https://streamtape.to/v/YVxljjYGwRuvYQj"),
		)
	}

	@Test
	fun extractsStreamTapeUrlAcrossSubstringSliceAndSubstrForms() {
		val raw = """
			<script>
			document.getElementById('captchalink').innerHTML =
				'//streamtape.to/get_vid' +
				('defgeo?id=video-id&expires=1785564681&token=working').slice(2).substr(2);
			</script>
		""".trimIndent()

		assertEquals(
			"https://streamtape.to/get_video?id=video-id&expires=1785564681&token=working&stream=1",
			AnimeSlayer.extractStreamTapeScriptUrl(raw, "https://streamtape.to/v/video-id"),
		)
	}
}
