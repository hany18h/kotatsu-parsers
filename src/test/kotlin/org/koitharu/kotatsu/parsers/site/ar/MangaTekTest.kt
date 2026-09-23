package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.network.UserAgents

internal class MangaTekTest {

    @Test
    fun usesChromeIdentityAcceptedByPublicPages() {
        val parser = MangaTek(MangaLoaderContextMock)

        assertEquals(UserAgents.CHROME_MOBILE, parser.getRequestHeaders()["User-Agent"])
    }

    @Test
    fun identifiesCaptchaDocumentsWithoutRejectingReaderHtml() {
        val parser = MangaTek(MangaLoaderContextMock)

        assertTrue(parser.isCaptchaPage(Jsoup.parse("<h1>Verify you are human</h1><div>CAPTCHA</div>")))
        assertFalse(parser.isCaptchaPage(Jsoup.parse("<div class='manga-page'><img src='/1.webp'></div>")))
    }

    @Test
    fun decodesHtmlReturnedByAndroidWebView() {
        val parser = MangaTek(MangaLoaderContextMock)

        assertEquals(
            "<main><div class=\"manga-page\"></div></main>",
            parser.decodeWebViewString("\"<main><div class=\\\"manga-page\\\"></div></main>\""),
        )
    }

    @Test
    fun prefersCanonicalLazyImageUrlOverPlaceholderSource() {
        val document = Jsoup.parse(
            """
            <div class="manga-page">
              <img src="data:image/gif;base64,placeholder" data-src="/lazy/1.webp" data-url="https://img.mangatek.com/1.webp">
              <img src="/2.webp">
            </div>
            """.trimIndent(),
        )

        assertEquals(
            listOf("https://img.mangatek.com/1.webp", "https://mangatek.com/2.webp"),
            MangaTek.extractReaderImageUrls(document, "mangatek.com"),
        )
    }
}
