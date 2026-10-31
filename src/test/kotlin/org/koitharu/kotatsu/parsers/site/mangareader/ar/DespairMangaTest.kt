package org.koitharu.kotatsu.parsers.site.mangareader.ar

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.model.*

internal class DespairMangaTest {
	private val manga = Manga(1, "Nano machine", emptySet(), "/manga/nano-machine/",
		"https://despair-world.com/manga/nano-machine/", RATING_UNKNOWN, null, null, emptySet(), null, emptySet(), source = MangaParserSource.DESPAIRMANGA)

	@Test fun newDomainRetainsTheReaderThemeChapterList() = runBlocking {
		val parser = parser("""<h1>Nano machine</h1><div id='chapterlist'><ul>
			<li><a href='/nano-machine-chapter-320/'><span class='chapternum'>Chapter 320</span></a></li>
			<li><a href='/nano-machine-chapter-319/'><span class='chapternum'>Chapter 319</span></a></li>
			</ul></div>""")
		assertEquals("despair-world.com", parser.configKeyDomain.defaultValue)
		val chapters = parser.getDetails(manga).chapters!!
		assertEquals(listOf("/nano-machine-chapter-319/", "/nano-machine-chapter-320/"), chapters.map { it.url })
	}

	@Test fun anUnexpectedHomePageDoesNotEraseTheChapterList() {
		assertThrows(ContentUnavailableException::class.java) { runBlocking { parser("<h1>DESPAIR</h1>").getDetails(manga) } }
	}

	private fun parser(html: String) = DespairManga(object : MangaLoaderContext() {
		override val cookieJar = CookieJar.NO_COOKIES
		override val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
			assertEquals("despair-world.com", chain.request().url.host)
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
				.body(html.toResponseBody()).build()
		}.build()
		override fun getConfig(source: MangaSource) = MangaLoaderContextMock.getConfig(source)
		override fun getDefaultUserAgent() = "Mozilla/5.0"
		override suspend fun evaluateJs(script: String): String? = error("Unused")
		override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("Unused")
		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unused")
		override fun createBitmap(width: Int, height: Int): Bitmap = error("Unused")
	})
}
