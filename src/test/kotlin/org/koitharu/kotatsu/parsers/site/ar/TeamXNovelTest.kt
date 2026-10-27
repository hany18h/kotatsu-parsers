package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

internal class TeamXNovelTest {
	@Test fun rejectsDiscordRedirectInsteadOfReturningAnEmptyCatalogue() {
		val doc = Jsoup.parse("<title>Discord</title><p>Enable JavaScript</p>", "https://discord.com/invite/example")
		assertThrows(IOException::class.java) { TeamXNovel.validateDocument(doc, "olympustaff.com", ".listupd") }
	}
	@Test fun rejectsChallengeAndMaintenancePages() {
		for (html in listOf("<title>Just a moment...</title><div class='listupd'></div>", "<p>Maintenance</p>")) {
			val doc = Jsoup.parse(html, "https://olympustaff.com/")
			assertThrows(IOException::class.java) { TeamXNovel.validateDocument(doc, "olympustaff.com", ".listupd") }
		}
	}
	@Test fun acceptsReadyCatalogueAndValidEmptySearchOnTheSource() {
		for (host in listOf("olympustaff.com", "www.olympustaff.com")) {
			val doc = Jsoup.parse("<div class='listupd'></div>", "https://$host/?search=missing")
			assertDoesNotThrow { TeamXNovel.validateDocument(doc, "olympustaff.com", ".listupd") }
		}
	}
	@Test fun refusesMissingChapterImages() {
		val doc = Jsoup.parse("<div class='image_list'></div>", "https://olympustaff.com/chapter/1")
		assertThrows(IOException::class.java) { TeamXNovel.validateDocument(doc, "olympustaff.com", ".image_list img, .image_list canvas") }
	}
}
