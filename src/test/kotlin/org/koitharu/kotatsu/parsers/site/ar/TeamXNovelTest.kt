package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.junit.jupiter.api.Test
import java.io.IOException

internal class TeamXNovelTest {
	@Test fun keepsLockedCardsEvenWhenTheSourceOmitsTheirLinks() {
		val doc = Jsoup.parse("""
			<div class='enhanced-chapters-grid'>
			<div class='chapter-card' data-number='24'><span class='chapter-number'>24</span><span class='status-badge locked'>مقفل</span></div>
			<div class='chapter-card' data-number='23'><a class='chapter-link' href='/index.php/series/test/23'><span class='chapter-number'>23</span></a></div>
			</div>
		""".trimIndent(), "https://olympustaff.com/index.php/series/test?page=1")
		val chapters = TeamXNovel(MangaLoaderContextMock).parseChapters(doc)
		assertEquals(listOf(true, false), chapters.map { it.isLocked })
		assertEquals(listOf("/index.php/series/test/24", "/index.php/series/test/23"), chapters.map { it.url })
	}
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
