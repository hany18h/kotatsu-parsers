package org.koitharu.kotatsu.parsers.site.ar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.jsoup.Jsoup
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.network.UserAgents

internal class GalaxyNovelsTest {

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
