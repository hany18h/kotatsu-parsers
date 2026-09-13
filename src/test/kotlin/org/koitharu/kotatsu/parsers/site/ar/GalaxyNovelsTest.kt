package org.koitharu.kotatsu.parsers.site.ar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.jsoup.Jsoup
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class GalaxyNovelsTest {

	@Test
	fun followsCurrentSitePaginationInsteadOfRepeatingFirstPage() {
		val parser = GalaxyNovels(MangaLoaderContextMock)
		assertEquals("https://galaxynovels.com/recent/?recent_page=2", parser.getListPageUrl(2, SortOrder.UPDATED, MangaListFilter()))
		assertEquals("https://galaxynovels.com/library/?sort=name&library_page=2", parser.getListPageUrl(2, SortOrder.ALPHABETICAL, MangaListFilter()))
		assertEquals("https://galaxynovels.com/novels/page/2/?sort=popular&period=all", parser.getListPageUrl(2, SortOrder.POPULARITY, MangaListFilter()))
		assertEquals("https://galaxynovels.com/library/?library_page=2&q=test&sort=", parser.getListPageUrl(2, SortOrder.UPDATED, MangaListFilter(query = "test")))
	}

	@Test
	fun waitsForUsefulMarkupButAcceptsRealEmptySearchResults() {
		val parser = GalaxyNovels(MangaLoaderContextMock)
		val selector = "${GalaxyNovels.CATALOG_CARD_SELECTOR}, .wor-library-empty"
		assertEquals(false, parser.isReadableDocument(Jsoup.parse("<main>Loading...</main>"), selector))
		assertEquals(true, parser.isReadableDocument(Jsoup.parse("<div class='wor-library-empty'>No results</div>"), selector))
		assertEquals(false, parser.isReadableDocument(Jsoup.parse("<script>window._cf_chl_opt = {};</script>"), selector))
	}

	@Test
	fun neverTreatsAProtectionArticleAsChapterText() {
		val parser = GalaxyNovels(MangaLoaderContextMock)
		assertEquals(null, parser.extractChapterContent(Jsoup.parse(
			"<article><div class='entry-content'>Sorry, you have been blocked</div></article>",
		)))
		assertEquals(null, parser.extractChapterContent(Jsoup.parse("<article>Please enable JavaScript</article>")))
		assertEquals(true, parser.isBlockedDocument(Jsoup.parse("<form id='challenge-form'></form>")))
	}

	@Test
	fun resolvesChapterIdFromLegacyProtectedApiUrl() {
		assertEquals(
			"71040",
			GalaxyNovels.findLegacyChapterPostId(
				"/wp-json/wor-reader-app/v1/chapters/71040",
			),
		)
		assertEquals(null, GalaxyNovels.findLegacyChapterPostId("/novel/example/chapter-1/"))
	}

	@Test
	fun usesBrowserUserAgentAcceptedByReaderPages() {
		val parser = GalaxyNovels(MangaLoaderContextMock)

		assertEquals(UserAgents.CHROME_MOBILE, parser.getRequestHeaders()["User-Agent"])
	}

	@Test
	fun usesGalaxyReaderCookieAndContinuousHeaderForChapterPages() {
		val parser = GalaxyNovels(MangaLoaderContextMock)
		val headers = parser.siteHeaders("https://galaxynovels.com/", isChapterRequest = true)

		assertEquals("wor_reader_js=1", headers["Cookie"])
		assertEquals("1", headers["X-Wor-Continuous"])
	}

	@Test
	fun doesNotSendReaderTelemetryHeadersToCataloguePages() {
		val parser = GalaxyNovels(MangaLoaderContextMock)
		val headers = parser.siteHeaders("https://galaxynovels.com/")

		assertEquals(null, headers["Cookie"])
		assertEquals(null, headers["X-Wor-Continuous"])
	}

	@Test
	fun decodesChapterHtmlReturnedByAndroidWebView() {
		val parser = GalaxyNovels(MangaLoaderContextMock)

		assertEquals(
			"<div class=\"wor-reading-page__content\"><p>نص الفصل</p></div>",
			parser.decodeWebViewString(
				"\"<div class=\\\"wor-reading-page__content\\\"><p>نص الفصل</p></div>\"",
			),
		)
	}

	@Test
	fun recognizesServerBlockPagesBeforeParsingThemAsCatalogueContent() {
		val parser = GalaxyNovels(MangaLoaderContextMock)

		assertEquals(
			true,
			parser.isBlockedDocument(Jsoup.parse("<h1>Sorry, you have been blocked</h1>")),
		)
		assertEquals(
			false,
			parser.isBlockedDocument(Jsoup.parse("<main><h1>مكتبة الروايات</h1></main>")),
		)
	}

	@Test
	fun parsesCurrentCatalogueCards() {
		val parser = GalaxyNovels(MangaLoaderContextMock)
		val document = Jsoup.parse(
			"""
			<article class="wor-novel-card">
			  <a class="wor-novel-card__cover" href="/novel/first/"><img class="wor-cover-img" data-src="/first.jpg"></a>
			  <h3><a href="/novel/first/">الرواية الأولى</a></h3>
			</article>
			<article class="wor-library-card">
			  <a class="wor-library-card__cover" href="/novel/second/"><img src="https://galaxynovels.com/second.jpg"></a>
			  <h2 class="wor-library-card__title"><a href="/novel/second/">الرواية الثانية</a></h2>
			</article>
			""".trimIndent(),
		)

		val novels = parser.parseNovelList(document)

		assertEquals(listOf("الرواية الأولى", "الرواية الثانية"), novels.map { it.title })
		assertEquals("https://galaxynovels.com/first.jpg", novels.first().coverUrl)
	}

	@Test
	fun parsesAndOrdersCachedChapterMetadata() {
		val parser = GalaxyNovels(MangaLoaderContextMock)
		val chapters = parser.parseCachedChapters(
			"""
			{
			  "chapters": [
			    {"id":412,"position":12,"number":"12.5","label":"الفصل 12.5","title":"العودة","url":"/novel/a/chapter-12/","content_api":"/wp-json/wor-reader-app/v1/chapters/412","date_iso":"2026-08-10"},
			    {"position":2,"number":"2","label":"الفصل 2","title":"","url":"/novel/a/chapter-2/","date_iso":"2026-08-01"}
			  ]
			}
			""".trimIndent(),
		)

		assertEquals(listOf(12.5f, 2f), chapters.map { it.number })
		assertEquals("الفصل 12.5 — العودة", chapters.first().title)
		assertEquals("/novel/a/chapter-12/", chapters.first().url)
	}

	@Test
	fun discoversVersionedFullChapterPackFromManifest() {
		val parser = GalaxyNovels(MangaLoaderContextMock)

		val packUrl = parser.parseManifestPackUrl(
			"""
			{
			  "total": 3155,
			  "pack_url": "https://galaxynovels.com/wp-content/uploads/wor-reader-cache/chapters/packs/novel-269119-version.json"
			}
			""".trimIndent(),
		)

		assertEquals(
			"https://galaxynovels.com/wp-content/uploads/wor-reader-cache/chapters/packs/novel-269119-version.json",
			packUrl,
		)
	}

	@Test
	fun extractsCurrentReaderPageContentBeforeArticleFallback() {
		val parser = GalaxyNovels(MangaLoaderContextMock)
		val document = Jsoup.parse(
			"""
			<article class="wor-reading-page">
			  <h1>عنوان الفصل</h1>
			  <div class="wor-reading-page__content"><p>نص الفصل الصحيح</p></div>
			</article>
			""".trimIndent(),
		)

		val content = parser.extractChapterContent(document)

		assertEquals("نص الفصل الصحيح", content?.text())
		assertEquals("wor-reading-page__content", content?.className())
	}

	@Test
	fun extractsCurrentTextSurfaceUsedByLiveReader() {
		val parser = GalaxyNovels(MangaLoaderContextMock)
		val document = Jsoup.parse(
			"""
			<article class="wor-reading-page">
			  <header class="wor-reading-page__header">عنوان الفصل</header>
			  <div class="wor-reader-text-surface"><p>نص الفصل الحالي</p></div>
			</article>
			""".trimIndent(),
		)

		val content = parser.extractChapterContent(document)

		assertEquals("نص الفصل الحالي", content?.text())
		assertEquals("wor-reader-text-surface", content?.className())
	}

	@Test
	fun extractsNativeContentFromPublicReaderApi() {
		val parser = GalaxyNovels(MangaLoaderContextMock)
		val content = parser.parseReaderApiContent(
			JSONObject(
				"""
				{
				  "data": {
				    "url": "/novel/a/chapter-12/",
				    "content_html": "<p>نص الفصل من الواجهة العامة</p><script>bad()</script>"
				  }
				}
				""".trimIndent(),
			),
		)

		assertEquals("نص الفصل من الواجهة العامة", Jsoup.parseBodyFragment(content?.html.orEmpty()).text())
	}
}
