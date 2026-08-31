package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock

internal class KolNovelTest {

	@Test
	fun removesBareSholaCssAndJavascriptTail() {
		val parser = KolNovel(MangaLoaderContextMock)
		val element = Jsoup.parseBodyFragment(
			"""
			<div id="kol_content">
			  <p>النص الصحيح للفصل</p>
			  .shola-widget { background: #fff; border: 1px solid #ddd; }
			  function sholaTab() { alert('bad'); }
			  <div class="shola-widget">ادعمنا</div>
			</div>
			""".trimIndent(),
		).selectFirst("#kol_content")!!

		val clean = parser.sanitizeChapterElement(element)

		assertTrue(clean.text().contains("النص الصحيح للفصل"))
		assertFalse(clean.text().contains("shola-widget"))
		assertFalse(clean.text().contains("function shola"))
	}
}
