package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CeneleTest {

	@Test
	fun keepsRealTextWhenHiddenBaitIsInsideTheSameParagraph() {
		val document = Jsoup.parse(
			"""
			<div class="text-left">
			  <p><strong>هذا نص الفصل الحقيقي</strong>
			    <span aria-hidden="true" role="presentation">
			      هذا نص تمويهي من موقع فضاء الروايات فقط، تطبيق سارق cenele.com
			    </span>
			  </p>
			  <template data-nhv-rb="1"></template>
			  <p>هذا تنبيه من موقع فضاء الروايات، تطبيق سارق cenele.com</p>
			  <p><strong>فقرة حقيقية ثانية</strong></p>
			</div>
			""".trimIndent(),
		)
		val content = document.selectFirst(".text-left")!!

		Cenele.sanitizeChapterContent(content)

		assertTrue(content.text().contains("هذا نص الفصل الحقيقي"))
		assertTrue(content.text().contains("فقرة حقيقية ثانية"))
		assertFalse(content.text().contains("نص تمويهي"))
		assertFalse(content.text().contains("هذا تنبيه"))
	}

	@Test
	fun keepsRealParagraphAfterBaitMarker() {
		val document = Jsoup.parse(
			"""
			<div class="text-left">
			  <template data-nhv-rb="1"></template>
			  <p>هذا هو النص الحقيقي للفصل</p>
			  <p aria-hidden="true">هذا نص​ ت⁣موي​ه⁣ي من موقع فضاء الروايات، المصدر مسروق cenele.com</p>
			</div>
			""".trimIndent(),
		)
		val content = document.selectFirst(".text-left")!!

		Cenele.sanitizeChapterContent(content)

		assertTrue(content.text().contains("النص الحقيقي"))
		assertFalse(content.text().contains("تمويهي"))
		assertFalse(content.html().contains("template"))
	}

	@Test
	fun detectsAntiCopyTextContainingZeroWidthMarks() {
		assertTrue(
			Cenele.isAntiCopyText(
				"هذا نص\u200B ت\u2063موي\u200Bهي من موقع فضاء الروايات، تطبيق سارق cenele.com",
			),
		)
		assertFalse(Cenele.isAntiCopyText("هذا نص حقيقي من الفصل"))
	}

	@Test
	fun keepsCompleteLiveStyleChapterBody() {
		val document = Jsoup.parse(
			"""
			<div class="reading-content current">
			  <h3 class="chapter-name">الفصل 90</h3>
			  <div class="text-left">
			    <style id="nhv-reader-bait-style">template + p { position:absolute }</style>
			    <p>لورد الغوامض المجلد الأول</p>
			    <p>كانت غرفة النوم أكبر من غرفة المعيشة.
			      <span aria-hidden="true" role="presentation">
			        ه⁣ذا ن​ص ت​موي⁣ه​ي من موقع⁣ فض​اء ا​لرو​اي⁣ات⁣ فقط، تطبيق سارق cenele.com
			      </span>
			    </p>
			    <template data-nhv-rb="1"></template>
			    <p>هذا تنبيه من موقع فضاء الروايات، تطبيق سارق cenele.com</p>
			    <p>تطلع كلاين حوله ببطء للبحث عن آثار أخرى.</p>
			  </div>
			</div>
			""".trimIndent(),
		)
		val content = document.selectFirst(".text-left")!!

		Cenele.sanitizeChapterContent(content)

		assertTrue(content.select("p").size >= 3)
		assertTrue(content.text().contains("كانت غرفة النوم أكبر"))
		assertTrue(content.text().contains("تطلع كلاين حوله"))
		assertFalse(content.text().contains("هذا تنبيه"))
		assertFalse(content.text().contains("نص تمويهي"))
	}
}
