package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class MarkazRiwayatTest {

	@Test
	fun removesHiddenAntiCopyTextAndPromotesImages() {
		val document = Jsoup.parse(
			"""
			<div class="reading-content">
			  <p>نص صحيح</p>
			  <span class="theam-chobf" aria-hidden="true">محتوى مسروق</span>
			  <p>=====================</p>
			  <img src="data:image/gif;base64,AA" data-src="https://markazriwayat.com/image.jpg">
			</div>
			""".trimIndent(),
			"https://markazriwayat.com/chapter/",
		)
		val content = document.selectFirst(".reading-content")!!

		MarkazRiwayat.sanitizeChapterContent(content)

		assertTrue(content.text().contains("نص صحيح"))
		assertFalse(content.text().contains("محتوى مسروق"))
		assertFalse(content.text().contains("===="))
		assertEquals("https://markazriwayat.com/image.jpg", content.selectFirst("img")?.attr("src"))
	}

	@Test
	fun convertsSummaryMarkdownBold() {
		assertEquals(
			"<p><strong>الوصف</strong></p>",
			MarkazRiwayat.normalizeMarkdownBold("<p>**الوصف**</p>"),
		)
	}
}
