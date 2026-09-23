package org.koitharu.kotatsu.parsers.site.madara

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class MadaraParserTest {

	@Test
	fun recognizesAccessInterstitalsWithoutRejectingNormalMangaTitles() {
		assertTrue(MadaraParser.isAccessChallengePage(Jsoup.parse("<title>Access Denied</title>")))
		assertTrue(MadaraParser.isAccessChallengePage(Jsoup.parse("<div class='cf-turnstile'></div>")))
		assertFalse(
			MadaraParser.isAccessChallengePage(
				Jsoup.parse("<title>Manga List</title><h2>Access Denied: The Hero</h2>"),
			),
		)
	}
}
