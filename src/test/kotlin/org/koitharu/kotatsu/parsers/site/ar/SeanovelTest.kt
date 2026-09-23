package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class SeanovelTest {

	@Test
	fun combinesEveryReaderSegmentInsteadOfDroppingTheRemainder() {
		val document = Jsoup.parse(
			"""
			<article class="reader-content"><p>بداية الفصل</p></article>
			<article class="reader-content"><p>نهاية الفصل</p></article>
			""".trimIndent(),
		)

		val content = Seanovel.extractChapterContent(document)!!

		assertTrue(content.text().contains("بداية الفصل"))
		assertTrue(content.text().contains("نهاية الفصل"))
	}

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
