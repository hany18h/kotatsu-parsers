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

	@Test
	fun removesRandomizedDecoyParagraphsDeclaredHiddenByPageCss() {
		val parser = KolNovel(MangaLoaderContextMock)
		val document = Jsoup.parse(
			"""
			<style>
			  .af9f444205b918f8fcec4d92cecc941d9,.abb05c6638debb72cc569ee44eab7d835 {
			    height: 0.1px; overflow: hidden; position: fixed; opacity: 0;
			    text-indent: -99999px; bottom: -999px;
			  }
			  .unrelated { opacity: 0; }
			</style>
			<div id="kol_content">
			  <p>هل بدا الاثنان متشابهين؟</p>
			  <p class="af9f444205b918f8fcec4d92cecc941d9">فقرة تمويه أولى</p>
			  <p>حتى لو قفز ملك السموم احتجاجًا.</p>
			  <p class="abb05c6638debb72cc569ee44eab7d835">فقرة تمويه ثانية</p>
			  <p class="unrelated">فقرة غير مصنفة كتمويه</p>
			</div>
			""".trimIndent(),
		)
		val source = document.selectFirst("#kol_content")!!
		val hiddenClasses = parser.findHiddenChapterClasses(document, source)
		val clean = parser.sanitizeChapterElement(source, hiddenClasses)

		assertTrue(clean.text().contains("هل بدا الاثنان متشابهين؟"))
		assertTrue(clean.text().contains("حتى لو قفز ملك السموم احتجاجًا."))
		assertTrue(clean.text().contains("فقرة غير مصنفة كتمويه"))
		assertFalse(clean.text().contains("فقرة تمويه أولى"))
		assertFalse(clean.text().contains("فقرة تمويه ثانية"))
	}
}
