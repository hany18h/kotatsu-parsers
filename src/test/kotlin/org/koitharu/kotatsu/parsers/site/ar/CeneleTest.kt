package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CeneleTest {

	@Test
	fun preservesOfficialNovelAndChapterLocatorsWithoutChangingPublicPath() {
		val novel = attachCeneleNovelLocator("/cont/return-hua/", "20368")
		val chapter = attachCeneleChapterLocator(
			"/cont/return-hua/%d8%a7%d9%84%d9%81%d8%b5%d9%84-1822/",
			"20368",
			"87692",
		)

		assertEquals("20368", parseCeneleNovelLocator(novel))
		assertEquals("return-hua", extractCeneleNovelSlug(novel))
		assertEquals(CeneleChapterLocator("20368", "87692"), parseCeneleChapterLocator(chapter))
	}

	@Test
	fun extractsVolumeOnlyFromNestedChapterUrls() {
		assertEquals(
			"%d8%a7%d9%84%d9%85%d8%ac%d9%84%d8%af-2",
			extractCeneleVolumeSlug(
				"/cont/example/%d8%a7%d9%84%d9%85%d8%ac%d9%84%d8%af-2/%d8%a7%d9%84%d9%81%d8%b5%d9%84-80/",
			),
		)
		assertEquals(null, extractCeneleVolumeSlug("/cont/return-hua/%d8%a7%d9%84%d9%81%d8%b5%d9%84-1822/"))
	}

	@Test
	fun parsesCleanOfficialAppChapterPayload() {
		val parser = Cenele(org.koitharu.kotatsu.parsers.MangaLoaderContextMock)
		val content = parser.parseAppChapterContent(
			JSONObject(
				"""
				{
				  "success": true,
				  "data": {
				    "content": "<p>النص الحقيقي للفصل</p><img data-src='/image.webp'>",
				    "chapter": {"chapter_name": "الفصل 280"}
				  }
				}
				""".trimIndent(),
			),
			"https://cenele.com/cont/example/chapter-280/",
			"",
		)

		assertTrue(content?.html?.contains("النص الحقيقي للفصل") == true)
		assertTrue(content?.html?.contains("الفصل 280") == true)
		assertEquals("https://cenele.com/image.webp", content?.images?.single()?.url)
	}

	@Test
	fun acceptsArticleAsChapterContentContainer() {
		val document = Jsoup.parse(
			"""<article class="text-left"><p>chapter body</p></article>""",
		)

		val content = document.selectFirst(".text-left")

		assertTrue(content != null)
		assertTrue(content?.tagName() == "article")
	}

	@Test
	fun detectsRandomizedAsideBeforeSiteAddsTextLeftClass() {
		val document = Jsoup.parse(
			"""
			<div id="chapter-53320" class="reading-content current" data-block-chapter-id="53320">
			  <div class="chapter-warning"><p>support form</p></div>
			  <aside class="t4644676f">
			    <input type="hidden" id="chapter-url-53320" value="https://cenele.com/cont/example/3/">
			    <style>.reading-content .bait{display:none!important;}</style>
			    <p>real chapter body</p>
			  </aside>
			  <script>document.currentScript.previousElementSibling.classList.add('text-left')</script>
			</div>
			""".trimIndent(),
		)

		val withLocator = Cenele.findDirectChapterContent(
			document,
			CeneleChapterLocator("67184", "53320"),
		)
		val legacyUrl = Cenele.findDirectChapterContent(document, null)

		assertTrue(withLocator?.tagName() == "aside")
		assertTrue(withLocator?.text()?.contains("real chapter body") == true)
		assertTrue(legacyUrl === withLocator)
	}

	@Test
	fun detectsRandomizedSectionFromCurrentSiteMarkup() {
		val document = Jsoup.parse(
			"""
			<div id="chapter-53320" class="reading-content current" data-block-chapter-id="53320">
			  <div class="chapter-warning"><p>support form</p></div>
			  <section class="tab635be0">
			    <input type="hidden" id="chapter-url-53320" value="https://cenele.com/cont/example/3/">
			    <style>.reading-content .bait{display:none!important;}</style>
			    <p>real current chapter body</p>
			  </section>
			</div>
			""".trimIndent(),
		)

		val content = Cenele.findDirectChapterContent(
			document,
			CeneleChapterLocator("67184", "53320"),
		)

		assertTrue(content?.tagName() == "section")
		assertTrue(content?.text()?.contains("real current chapter body") == true)
	}

	@Test
	fun prefersExactRandomizedChapterWrapperOverPromoArticle() {
		val document = Jsoup.parse(
			"""
			<div id="chapter-87692" class="reading-content current" data-block-chapter-id="87692">
			  <article class="reader-promo"><p>دعم الموقع</p></article>
			  <section class="tab635be0">
			    <input type="hidden" id="chapter-url-87692" value="https://cenele.com/cont/example/1822/">
			    <p>النص الحقيقي للفصل 1822</p>
			  </section>
			</div>
			""".trimIndent(),
		)

		val content = Cenele.findDirectChapterContent(
			document,
			CeneleChapterLocator("20368", "87692"),
		)

		assertEquals("section", content?.tagName())
		assertTrue(content?.text()?.contains("النص الحقيقي") == true)
	}

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

	@Test
	fun removesRandomAntiCopyClassDeclaredHiddenByInlineCss() {
		val document = Jsoup.parse(
			"""
			<article class="random-body">
			  <style>.reading-content .r04dfb668cee13df{display:none!important;}</style>
			  <p>الفقرة الحقيقية الأولى</p>
			  <div class="r04dfb668cee13df"><p>نص طُعم متغير</p></div>
			  <p>الفقرة الحقيقية الثانية</p>
			</article>
			""".trimIndent(),
		)
		val content = document.selectFirst("article")!!

		Cenele.sanitizeChapterContent(content)

		assertTrue(content.text().contains("الفقرة الحقيقية الأولى"))
		assertTrue(content.text().contains("الفقرة الحقيقية الثانية"))
		assertFalse(content.text().contains("نص طُعم متغير"))
	}
}
