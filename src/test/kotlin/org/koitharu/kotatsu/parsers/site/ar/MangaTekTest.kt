package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class MangaTekTest {

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
}
