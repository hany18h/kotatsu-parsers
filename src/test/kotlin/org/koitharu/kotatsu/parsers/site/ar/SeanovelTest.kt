package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class SeanovelTest {

	@Test
	fun keepsReaderBodyAndRemovesScreenReaderMetadata() {
		val document = Jsoup.parse(
			"""
			<article class="reader-content" data-reader-initial-content="true">
			  <p class="sr-only">أنت تقرأ الفصل الأول من الرواية.</p>
			  <p>الفقرة الحقيقية الأولى.</p>
			  <p>الفقرة الحقيقية الثانية.</p>
			  <script>window.bad = true;</script>
			</article>
			""".trimIndent(),
		)
		val content = document.selectFirst("article.reader-content")!!

		Seanovel.sanitizeChapterContent(content)

		assertTrue(content.text().contains("الفقرة الحقيقية الأولى"))
		assertTrue(content.text().contains("الفقرة الحقيقية الثانية"))
		assertFalse(content.text().contains("أنت تقرأ الفصل"))
		assertFalse(content.html().contains("script"))
		assertFalse(content.html().contains("data-reader-initial-content"))
	}
}
