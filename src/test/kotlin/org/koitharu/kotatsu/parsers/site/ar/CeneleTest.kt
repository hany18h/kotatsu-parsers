package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock

internal class CeneleTest {

	@Test
	fun usesReaderAcceptedMobileUserAgent() {
		val parser = Cenele(MangaLoaderContextMock)

		assertEquals(Cenele.CENELE_MOBILE_USER_AGENT, parser.getRequestHeaders()["User-Agent"])
		assertFalse(parser.getRequestHeaders()["User-Agent"].orEmpty().contains("Chrome/114"))
	}

	@Test
	fun upgradesPreviouslySavedLegacyUserAgent() {
		val legacy =
			"Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
				"Chrome/114.0.5735.196 Mobile Safari/537.36"
		val custom = "Custom browser user agent"

		assertEquals(Cenele.CENELE_MOBILE_USER_AGENT, Cenele.upgradeReaderUserAgent(legacy))
		assertEquals(custom, Cenele.upgradeReaderUserAgent(custom))
	}

	@Test
	fun decodesPublicPageHtmlReturnedByWebView() {
		val encoded = "\"\\u003Chtml\\u003E\\u003Cbody\\u003Echapter\\u003C/body\\u003E\\u003C/html\\u003E\""

		assertEquals("<html><body>chapter</body></html>", Cenele.decodeWebViewString(encoded))
	}

	@Test
	fun recognizesServerBlockPage() {
		val blocked = Jsoup.parse("<title>Blocked</title><p>تم حظرك من قبل الخادم</p>")
		val content = Jsoup.parse("<title>Novel</title><p>نص الفصل الحقيقي</p>")

		assertTrue(Cenele.isBlockedDocument(blocked))
		assertFalse(Cenele.isBlockedDocument(content))
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

	@Test
	fun acceptsCompleteChapterWithCloudflareJsdFooter() {
		val document = Jsoup.parse(
			"""
			<div class="reading-content current"><p>نص الفصل الحقيقي</p></div>
			<script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script>
			""".trimIndent(),
		)

		assertFalse(Cenele.isBlockedDocument(document))
	}

	@Test
	fun rejectsCloudflareChallengeScriptWithoutPublicContent() {
		val document = Jsoup.parse(
			"""<script src="/cdn-cgi/challenge-platform/h/g/orchestrate/chl_page/v1"></script>""",
		)

		assertTrue(Cenele.isBlockedDocument(document))
	}

	@Test
	fun removesRepeatedOffscreenBaitWithRandomClassesAndText() {
		// Current public pages position these figures offscreen instead of using display:none.
		val content = Jsoup.parseBodyFragment(
			"""
			<style>.reading-content .random{position:fixed!important;inset:auto auto -200vh -200vw!important;width:1px!important;height:1px!important;overflow:hidden!important;}</style>
			<p>الفقرة الأولى.</p>
			<figure class="random" inert data-nosnippet="true"><p>رسالة متغيرة 45fba08117</p><i></i></figure>
			<p>الفقرة الثانية.</p>
			<section class="other-random" inert data-nosnippet="true"><p>رسالة أخرى cdc5e866c6</p></section>
			<p>الفقرة الثالثة.</p>
			""".trimIndent(),
		).body()

		Cenele.sanitizeChapterContent(content)

		assertEquals(listOf("الفقرة الأولى.", "الفقرة الثانية.", "الفقرة الثالثة."), content.select("p").eachText())
		assertTrue(content.select("[inert][data-nosnippet]").isEmpty())
	}

	@Test
	fun removesReportedWarningWithoutStructuralMarkers() {
		val warning = "توقف الصدى للحظة واحدة. هذا التطبيق يسرق من موقع وتطبيق فضاء الروايات 45fba08117 " +
			"اقرأ آلاف الفصول لأشهر الروايات على موقع وتطبيق فضاء الروايات 45fba08117"
		val content = Jsoup.parseBodyFragment("<p>قبل الرسالة.</p><p>$warning</p><p>بعد الرسالة.</p><p>$warning</p>").body()

		Cenele.sanitizeChapterContent(content)

		assertEquals(listOf("قبل الرسالة.", "بعد الرسالة."), content.select("p").eachText())
	}

	@Test
	fun removesDecoratedInlineWarningWhileKeepingSurroundingText() {
		val warning = "هـٰـذَا اﻟـتـطـبـيـق يـسـرـق مِـن مـوـقـع وـتـطـبـيـق فــضـاـء اﻟـرـوـاـيـاـت"
		val content = Jsoup.parseBodyFragment(
			"<p>بداية الفقرة. <span><b>${warning.replace(" ", "&nbsp;\u2063")}</b></span> <em>نهاية الفقرة.</em></p>",
		).body()

		Cenele.sanitizeChapterContent(content)

		assertEquals("بداية الفقرة. نهاية الفقرة.", content.text())
		assertEquals("نهاية الفقرة.", content.selectFirst("em")?.text())
	}

	@Test
	fun keepsOrdinaryFiguresSourceMentionsAndEitherAttributeAlone() {
		val content = Jsoup.parseBodyFragment(
			"""
			<p>توقف الصدى للحظة واحدة.</p>
			<p>ترجمة فضاء الروايات، القس المجنون.</p>
			<figure><img src="https://cenele.com/illustration.jpg"><figcaption>رسم توضيحي.</figcaption></figure>
			<p inert>نص حقيقي غير تفاعلي.</p>
			<p data-nosnippet="true">نص حقيقي مستبعد من نتائج البحث.</p>
			""".trimIndent(),
		).body()
		val expectedText = content.text()

		Cenele.sanitizeChapterContent(content)

		assertEquals(expectedText, content.text())
		assertEquals("https://cenele.com/illustration.jpg", content.selectFirst("img")?.attr("src"))
	}
}
