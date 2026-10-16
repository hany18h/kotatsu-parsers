package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.junit.jupiter.api.Assertions.assertThrows
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class MangaTekTest {

    @Test
    fun preservesAlternateSpeechAcrossUnlockDecryptionPageOffsetAndDescriptors() = runBlocking {
        val text = JSONObject().put("text", "نص عربي").put("x", 10).put("y", 10).put("w", 100).put("h", 80)
        val page = JSONObject().put("page_number", 1).put("image_width", 800).put("image_height", 1200)
            .put("overlays", JSONArray()).put("speech_overlays", JSONArray().put(text))
        val parser = MangaTek(fixtureContext(page, unlockSuccess = true))
        val pages = parser.getPages(fixtureChapter())
        assertEquals(2, pages.size)
        assertEquals("https://mangatek.com/cover.webp", pages[0].url)
        assertTrue(pages[1].url.startsWith("mangatek-overlay://render?"))
        val decoded = String(Base64.getUrlDecoder().decode(pages[1].url.substringAfter("overlay=")), Charsets.UTF_8)
        assertEquals("نص عربي", JSONObject(decoded).getJSONArray("speech_overlays").getJSONObject(0).getString("text"))
    }

    @Test
    fun failedUnlockDoesNotReturnTextFreePagesForDownload() {
        val parser = MangaTek(fixtureContext(JSONObject(), unlockSuccess = false))
        assertThrows(ContentUnavailableException::class.java) { runBlocking { parser.getPages(fixtureChapter()) } }
    }

    @Test
    fun keepsSpeechOverlayAndImageInPageDescriptor() {
        val image = "https://mangatek.com/page.webp"
        val overlay = JSONObject().put("page_number", 1).put("overlays", listOf(JSONObject().put("text", "نص الفقاعة")))
        val url = MangaTek.buildOverlayPageUrl(image, overlay)
        assertTrue(url.startsWith("mangatek-overlay://render?image="))
        val decoder = Base64.getUrlDecoder()
        assertEquals(image, String(decoder.decode(url.substringAfter("image=").substringBefore('&')), Charsets.UTF_8))
        assertEquals(overlay.toString(), String(decoder.decode(url.substringAfter("overlay=")), Charsets.UTF_8))
    }

    @Test
    fun preservesImageOrderAndIgnoresInlinePlaceholders() {
        val html = Jsoup.parse("""<div class='manga-page'><img src='data:image/gif;base64,AA' data-url='/1.webp'></div>
            <div class='manga-page'><img src='/2.webp'></div><div class='manga-page'><img src='/1.webp'></div>""")
        assertEquals(listOf("https://mangatek.com/1.webp", "https://mangatek.com/2.webp", "https://mangatek.com/1.webp"),
            MangaTek.extractReaderImageUrls(html, "mangatek.com"))
    }

    @Test
    fun decryptsSeparateArabicSpeechWithSixteenByteIv() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (it + 10).toByte() }
        val plaintext = """{"pages":[{"page_number":1,"overlays":[{"text":"نص الفقاعة"}]}]}"""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = listOf(iv, sealed.copyOfRange(0, sealed.size - 16), sealed.copyOfRange(sealed.size - 16, sealed.size))
            .joinToString(":") { bytes -> bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } }
        val keyHex = key.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("نص الفقاعة", MangaTek.decryptOverlayPages(payload, keyHex).getJSONObject(0).getJSONArray("overlays")
            .getJSONObject(0).getString("text"))
    }

    @Test
    fun usesChromeIdentityAcceptedByPublicPages() {
        val parser = MangaTek(MangaLoaderContextMock)

        assertEquals(MangaTek.USER_AGENT, parser.getRequestHeaders()["User-Agent"])
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
    fun waitsForCatalogueCardsInsteadOfAcceptingAnEmptyShell() {
        val parser = MangaTek(MangaLoaderContextMock)
        val selector = "a.manga-card"

        assertFalse(parser.isUsablePage(Jsoup.parse("<main><h1>قائمة المانجا</h1></main>"), selector))
        assertTrue(parser.isUsablePage(Jsoup.parse("<main><a class='manga-card' href='/manga/example'>Example</a></main>"), selector))
        assertFalse(parser.isUsablePage(Jsoup.parse("<h1>Verify you are human</h1><a class='manga-card'></a>"), selector))
    }

    private fun fixtureChapter() = MangaChapter(
        id = 123L, title = "Fixture chapter", number = 1f, volume = 0,
        url = "/reader/fixture/1", uploadDate = 0L, source = MangaParserSource.MANGATEK,
        scanlator = null, branch = null,
    )

    private fun fixtureContext(page: JSONObject, unlockSuccess: Boolean): MangaLoaderContext {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (it + 10).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(JSONObject().put("pages", JSONArray().put(page)).toString().toByteArray(Charsets.UTF_8))
        fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val encrypted = listOf(iv, sealed.copyOfRange(0, sealed.size - 16), sealed.copyOfRange(sealed.size - 16, sealed.size))
            .joinToString(":") { it.hex() }
        val html = """<astro-island component-url="/ChapterImageViewer.js" props='{"textMode":[0,"overlay"],"unlockToken":[0,"fixture-token"],"chapterId":[0,123]}'></astro-island>
            <div class="manga-page"><img data-url="/cover.webp"></div>
            <div class="manga-page"><img data-url="/page.webp"></div>"""
        return object : MangaLoaderContext() {
            override val cookieJar = CookieJar.NO_COOKIES
            override val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val isUnlock = request.url.host == "api.mangatek.com" && request.url.encodedPath == "/api/reader/unlock"
                check(isUnlock || request.url.encodedPath == "/reader/fixture/1") { "Unexpected fixture request" }
                val body = if (isUnlock) {
                    assertEquals("POST", request.method)
                    JSONObject().put("success", unlockSuccess).put("key", key.hex())
                        .put("overlay", encrypted).put("overlay_page_offset", 1).toString()
                } else html
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Offline fixture")
                    .body(body.toResponseBody((if (isUnlock) "application/json" else "text/html").toMediaType())).build()
            }.build()
            override fun getConfig(source: MangaSource) = MangaLoaderContextMock.getConfig(source)
            override fun getDefaultUserAgent() = MangaTek.USER_AGENT
            override suspend fun evaluateJs(script: String): String? = error("Unexpected WebView fallback")
            override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("Unexpected WebView fallback")
            override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unused fixture operation")
            override fun createBitmap(width: Int, height: Int): Bitmap = error("Unused fixture operation")
        }
    }
}
