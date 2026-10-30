package org.koitharu.kotatsu.parsers.site.madara.ar

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.model.*

internal class LekMangaTest {
    @Test fun hiddenJavascriptNoticeDoesNotRenameTheWorkAndRepairsOldNames() = runBlocking {
        val parser = LekManga(context("""
            <noscript><h1>Access Denied</h1><p>Please enable JavaScript</p></noscript>
            <div class="post-title"><h1>Actual work</h1></div>
            <div class="listing-chapters_wrap"><li class="wp-manga-chapter"><a href="https://lekmanga.site/manga/fixture/1/">1</a></li></div>
        """))
        val seed = manga().copy(title = "Access Denied")
        val result = parser.getDetails(seed)
        assertEquals("Actual work", result.title)
        assertEquals(seed.id, result.id)
        assertEquals(seed.url, result.url)
        assertEquals(1, result.chapters?.size)
    }

    @Test fun aSuccessfulHttpStatusWithOnlyABlockNoticeCannotOverwriteMetadata() {
        val parser = LekManga(context("<title>Access Denied</title><h1>Access Denied</h1>"))
        assertThrows(ContentUnavailableException::class.java) { runBlocking { parser.getDetails(manga()) } }
    }

    @Test fun pageOrderAndRepeatedImageReferencesArePreserved() = runBlocking {
        val parser = LekManga(context("""
            <img src="/advert.webp">
            <div class="main-col-inner"><div class="reading-content">
              <div class="page-break"><img data-src="/1.webp" src="data:image/gif;base64,AA"></div>
              <div class="page-break"><img src="/2.webp"></div>
              <div class="page-break"><img src="/1.webp"></div>
            </div></div>
        """))
        val chapter = MangaChapter(id = 1, title = "1", number = 1f, volume = 0, url = "/manga/fixture/1/",
            uploadDate = 0, scanlator = null, branch = null, source = MangaParserSource.MANGALEK)
        assertEquals(listOf("/1.webp", "/2.webp", "/1.webp"), parser.getPages(chapter).map { it.url })
    }

    @Test fun requestsFollowTheCurrentHostBrowserIdentity() {
        assertEquals("Current WebView", LekManga(context("")).getRequestHeaders()["User-Agent"])
    }

    private fun manga() = Manga(id = 1, title = "Original title", altTitles = emptySet(), url = "/manga/fixture/",
        publicUrl = "https://lekmanga.site/manga/fixture/", rating = RATING_UNKNOWN, contentRating = null,
        coverUrl = null, tags = emptySet(), state = null, authors = emptySet(), source = MangaParserSource.MANGALEK)

    private fun context(html: String) = object : MangaLoaderContext() {
        override val cookieJar = CookieJar.NO_COOKIES
        override val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(200).message("Fixture").protocol(Protocol.HTTP_1_1)
                .body(html.toResponseBody("text/html".toMediaType())).build()
        }.build()
        override fun getConfig(source: MangaSource) = MangaLoaderContextMock.getConfig(source)
        override fun getDefaultUserAgent() = "Current WebView"
        override suspend fun evaluateJs(script: String): String? = error("Unexpected browser")
        override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("Unexpected browser")
        override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unused")
        override fun createBitmap(width: Int, height: Int): Bitmap = error("Unused")
    }
}
