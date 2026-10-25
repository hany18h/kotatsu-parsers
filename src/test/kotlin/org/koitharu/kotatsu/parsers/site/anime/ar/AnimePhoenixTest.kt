package org.koitharu.kotatsu.parsers.site.anime.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class AnimePhoenixTest {
	@Test fun readsCurrentPublicApiSettingsWithoutLegacyNonce() {
		val document = Jsoup.parse("""<script>var PhoenixSearch = {"api_url":"https://anime-phoenix.com/api/search.php","public_key":"public-key"};</script>""")
		assertEquals("https://anime-phoenix.com/api/search.php", AnimePhoenix.extractSearchConfig(document)?.getString("api_url"))
	}

	@Test fun skipsDropdownMentionsAndKeepsLegacyCompatibility() {
		val document = Jsoup.parse("""<script>if (PhoenixSearch) { console.log('search'); }</script>
			<script>var fjSearchPageData = {"nonce":"nonce-123","ajax_url":"https://anime-phoenix.com/wp-admin/admin-ajax.php"};</script>""")
		assertEquals("nonce-123", AnimePhoenix.extractSearchConfig(document)?.getString("nonce"))
		assertNull(AnimePhoenix.extractSearchConfig(Jsoup.parse("<title>Just a moment...</title>")))
	}

	@Test fun signsExactlyThePublicWebClientCanonicalFieldsIncludingArabic() {
		val params = mapOf("q" to "ون بيس", "type" to "tvshow", "genre" to "action", "release_status" to "releasing",
			"year" to "2026", "season" to "fall", "page" to "2", "sort" to "date")
		assertEquals("72247ffdd408f969d0a8ba65a8691af040aff7bcb0d7f9eac589d833a5137440",
			AnimePhoenix.signSearch(params, "1700000000", "public-key"))
		assertNotEquals(AnimePhoenix.signSearch(params, "1700000000", "public-key"),
			AnimePhoenix.signSearch(params + ("page" to "3"), "1700000000", "public-key"))
	}

	@Test fun readsEncodedDirectServersWithoutLosingQueryEscapes() {
		val json = """{"name":"Phoenix","link":"https://cdn.example.com/episode%201.mp4?token=a%2Bb"}"""
		val payload = java.util.Base64.getEncoder().encodeToString(java.net.URLEncoder.encode(json, "UTF-8").toByteArray())
		assertEquals("https://cdn.example.com/episode%201.mp4?token=a%2Bb", AnimePhoenix.decodeServerPayload(payload)?.getString("link"))
		assertEquals("1080p", AnimePhoenix.detectQuality("https://cdn.example.com/%5B1080p%5D.mkv"))
	}
}
