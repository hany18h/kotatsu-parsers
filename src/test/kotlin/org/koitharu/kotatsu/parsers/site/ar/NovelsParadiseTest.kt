package org.koitharu.kotatsu.parsers.site.ar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock

internal class NovelsParadiseTest {

	@Test
	fun parsesListingAndAscendingChapters() {
		val parser = NovelsParadise(MangaLoaderContextMock)
		val listing = parser.parseMangaList(
			Jsoup.parse(
				"""
				<div class="listupd"><article class="maindet">
				  <div class="mdthumb"><a href="https://novelsparadise.site/series/test/" title="رواية الاختبار"><img src="https://img/cover.webp"></a></div>
				  <div class="mdinfo"><span class="mdminf">9</span></div>
				</article></div>
				""".trimIndent(),
				"https://novelsparadise.site/",
			),
		)
		val chapters = parser.parseChapters(
			Jsoup.parse(
				"""
				<div class="eplister"><ul>
				  <li><a href="/test-2/"><div class="epl-num">الفصل. 2</div><div class="epl-title">الثاني</div></a></li>
				  <li><a href="/test-1/"><div class="epl-num">الفصل. 1</div><div class="epl-title">الأول</div></a></li>
				</ul></div>
				""".trimIndent(),
				"https://novelsparadise.site/",
			),
		)

		assertEquals("رواية الاختبار", listing.single().title)
		assertEquals(listOf(1f, 2f), chapters.map { it.number })
	}

	@Test
	fun selectsTheRealChapterBodyInsteadOfShortWidgets() {
		val parser = NovelsParadise(MangaLoaderContextMock)
		val document = Jsoup.parse(
			"""
			<div class="entry-content"><p>وصف قصير</p></div>
			<div class="epcontent entry-content"><p>${"نص الفصل الطويل ".repeat(20)}</p></div>
			""".trimIndent(),
		)

		val content = parser.selectChapterContent(document)

		assertTrue(content?.text()?.startsWith("نص الفصل الطويل") == true)
	}
}
