package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class CeneleChapterLocatorTest {

	@Test
	fun storesAndReadsAjaxChapterLocatorWithoutChangingTheStablePath() {
		val path = "/novel/example/chapter-2426/"
		val parserUrl = attachCeneleChapterLocator(
			url = path,
			mangaId = "39080",
			chapterId = "84673",
		)

		assertEquals("$path#cenele=39080:84673", parserUrl)
		assertEquals(
			CeneleChapterLocator(mangaId = "39080", chapterId = "84673"),
			parseCeneleChapterLocator(parserUrl),
		)
	}

	@Test
	fun neverUsesAnUnrelatedTextBlockWhenRequestedChapterIsMissing() {
		val document = Jsoup.parse(
			"""
			<div class="page-content"><div class="text-left">عنوان فصل آخر فقط</div></div>
			<div id="chapter-84674" class="reading-content current">
			  <div class="text-left"><p>محتوى فصل مختلف</p></div>
			</div>
			""".trimIndent(),
		)

		assertNull(
			Cenele.findDirectChapterContent(
				document,
				CeneleChapterLocator(mangaId = "39080", chapterId = "84673"),
			),
		)
	}

	@Test
	fun selectsTheExactRequestedChapterBlock() {
		val document = Jsoup.parse(
			"""
			<div id="chapter-84673" class="reading-content">
			  <div class="text-left"><p>النص الصحيح للفصل</p></div>
			</div>
			<div id="chapter-84674" class="reading-content current">
			  <div class="text-left"><p>نص فصل آخر</p></div>
			</div>
			""".trimIndent(),
		)

		val content = Cenele.findDirectChapterContent(
			document,
			CeneleChapterLocator(mangaId = "39080", chapterId = "84673"),
		)
		assertEquals("النص الصحيح للفصل", content?.text())
	}
}
