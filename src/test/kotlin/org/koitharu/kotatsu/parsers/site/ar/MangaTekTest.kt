package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.json.JSONObject
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

    @Test
    fun createsProofAcceptedByReaderUnlockApi() {
        assertEquals(
            "282d70125f51440e7355eeeef7696ea40dee4a0f36d523510a4927e1268c5515",
            MangaTek.computeUnlockProof(
                "Mjg1MTIxfDE3OTAxODQ5OTh8ODk4NjBhZDk5YjRmM2RkMzJlfGY1NGY0NTM1NjBhMDBjZDFiZjAyZDNmZGFmMzE2Y2Fm",
                285121,
            ),
        )
    }

    @Test
    fun createsAppRenderableOverlayDescriptor() {
        val descriptor = MangaTek.buildOverlayPageUrl(
            "https://img.mangatek.com/page.webp?v=1",
            JSONObject().put("page_number", 1).put("overlays", emptyList<String>()),
        )

        assertTrue(descriptor.startsWith("mangatek-overlay://render?image="))
        assertTrue(descriptor.contains("&overlay="))
    }
}
