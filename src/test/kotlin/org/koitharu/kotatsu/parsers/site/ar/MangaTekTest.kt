package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
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
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.junit.jupiter.api.Assertions.assertThrows
import org.json.JSONObject
import java.util.Base64
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class MangaTekTest {

    @Test
    fun proofMatchesTheOfficialReaderClientFromOctober18() {
        assertEquals("90505f61b137206022bf71d832cc74ca921e3ee8e2157685c89486572014aca6",
            MangaTek.computeUnlockProof("fixture-token", 123))
    }

    @Test
    fun cryptoBridgeStripsOnlyLeadingImportsAndDoesNotExecuteNetworkHelpers() {
        val module = "import{a}from'./react.js';import'./fingerprint.js';async function renamed(a,b,c,d){return 'importKey;';}export{renamed as default};"
        val script = MangaTekWebCrypto.buildScript(module, JSONObject().put("mode", "pages"), "fixture")
        assertFalse(script.contains("from'./react.js'"))
        assertFalse(script.contains("import'./fingerprint.js'"))
        assertFalse(script.contains("export{renamed"))
        assertTrue(script.contains("return 'importKey;';"))
        assertTrue(script.contains("return [renamed]"))
        assertTrue(script.contains("Network disabled in crypto bridge"))
    }

    @Test
    fun ev3SpeechUsesCurrentClientAndRetainsImageOffsetAndArabicText() = runBlocking {
        val page = JSONObject().put("page_number", 1).put("image_width", 800).put("image_height", 1600)
            .put("overlays", JSONArray().put(JSONObject().put("text", "نص الفصل الجديد").put("x", 20).put("y", 30).put("w", 200).put("h", 100)))
        var cryptoCalls = 0
        val parser = MangaTek(fixtureContext(page, unlockSuccess = true, overlayVersion = 3, browserResult = {
            cryptoCalls++
            JSONObject.quote(JSONObject().put("pages", JSONArray().put(page)).toString())
        }))
        val pages = parser.getPages(fixtureChapter())
        assertEquals(2, pages.size)
        assertFalse(pages.first().url.startsWith("mangatek-overlay://"))
        val overlay = String(Base64.getUrlDecoder().decode(pages[1].url.substringAfter("&overlay=")), Charsets.UTF_8)
        assertEquals("نص الفصل الجديد", JSONObject(overlay).getJSONArray("overlays").getJSONObject(0).getString("text"))
        assertEquals(1, cryptoCalls)
    }

    @Test
    fun failedEv3CryptoNeverReturnsTextFreeDownloadPages() {
        val parser = MangaTek(fixtureContext(JSONObject(), unlockSuccess = true, overlayVersion = 3, browserResult = {
            JSONObject.quote("{\"error\":\"client could not decrypt\"}")
        }))
        val error = assertThrows(ContentUnavailableException::class.java) { runBlocking { parser.getPages(fixtureChapter()) } }
        assertTrue(error.message.orEmpty().contains("could not decrypt"))
    }

    @Test
    fun navigatingAwayDuringEv3CryptoPreservesCancellation() = runBlocking {
        var entered = false
        var cleaned = false
        val parser = MangaTek(fixtureContext(JSONObject(), unlockSuccess = true, overlayVersion = 3, browserResult = {
            entered = true
            try { awaitCancellation() } finally { cleaned = true }
        }))
        val job = async { parser.getPages(fixtureChapter()) }
        withTimeout(5_000) { while (!entered) delay(1) }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(cleaned)
    }

    @Test
    fun catalogueReturnsImmediatelyWithoutStartingBrowser() = runBlocking {
        val parser = MangaTek(fixtureContext(JSONObject(), false, documentHtml = catalogueHtml, browserResult = {
            error("Usable HTTP catalogue must not start the browser")
        }))
        assertEquals("Fixture manga", parser.getList(0, SortOrder.UPDATED, MangaListFilter.EMPTY).single().title)
    }

    @Test
    fun stalledBrowserBecomesRetryableErrorAndNextAttemptCanSucceed() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        var browserCalls = 0
        var cleanedUp = false
        val parser = MangaTek(fixtureContext(JSONObject(), false, documentHtml = "<main></main>", browserResult = {
            browserCalls++
            if (browserCalls == 1) {
                try { awaitCancellation() } finally { cleanedUp = true }
            } else JSONObject.quote(catalogueHtml)
        }))
        val first = async(dispatcher) {
            runCatching { parser.getList(0, SortOrder.UPDATED, MangaListFilter.EMPTY) }
        }
        // OkHttp's callback uses a real thread. Drive it onto the virtual test
        // dispatcher before advancing the browser deadline (no real 15s sleep).
        withTimeout(5_000) {
            while (browserCalls == 0) { dispatcher.scheduler.runCurrent(); delay(1) }
        }
        dispatcher.scheduler.advanceTimeBy(MangaTek.BROWSER_TIMEOUT_MS)
        dispatcher.scheduler.runCurrent()
        val error = first.await().exceptionOrNull()
        assertTrue(error is SocketTimeoutException)
        assertTrue(error?.message.orEmpty().contains("MangaTek"))
        assertTrue(cleanedUp)
        assertEquals(15_000L, dispatcher.scheduler.currentTime)
        assertEquals("Fixture manga", parser.getList(0, SortOrder.UPDATED, MangaListFilter.EMPTY).single().title)
        assertEquals(2, browserCalls)
    }

    @Test
    fun navigatingAwayStillCancelsBrowserInsteadOfReportingFailure() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        var browserStarted = false
        var cleanedUp = false
        val parser = MangaTek(fixtureContext(JSONObject(), false, documentHtml = "<main></main>", browserResult = {
            browserStarted = true
            try { awaitCancellation() } finally { cleanedUp = true }
        }))
        val load = async(dispatcher) { parser.getList(0, SortOrder.UPDATED, MangaListFilter.EMPTY) }
        withTimeout(5_000) {
            while (!browserStarted) { dispatcher.scheduler.runCurrent(); delay(1) }
        }
        load.cancel()
        dispatcher.scheduler.runCurrent()
        assertTrue(load.isCancelled)
        assertTrue(cleanedUp)
        assertEquals(0L, dispatcher.scheduler.currentTime)
    }

    @Test
    fun absentBrowserResultIsAnOrdinaryErrorNotCancellation() {
        val parser = MangaTek(fixtureContext(JSONObject(), false, documentHtml = "<main></main>", browserResult = { null }))
        assertThrows(SocketTimeoutException::class.java) {
            runBlocking { parser.getList(0, SortOrder.UPDATED, MangaListFilter.EMPTY) }
        }
    }

    @Test
    fun browserDeadlineDoesNotHideAnAlreadyDetectedForbiddenResponse() {
        val parser = MangaTek(fixtureContext(JSONObject(), false, documentCode = 403, browserResult = { null }))
        val error = assertThrows(ContentUnavailableException::class.java) {
            runBlocking { parser.getPages(fixtureChapter()) }
        }
        assertTrue(error.message.orEmpty().contains("403"))
    }

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
    fun forbiddenUnlockDoesNotSendUsersToAnUnsolvableBrowserPage() {
        val parser = MangaTek(fixtureContext(JSONObject(), unlockSuccess = true, unlockCode = 403))
        val error = assertThrows(ContentUnavailableException::class.java) { runBlocking { parser.getPages(fixtureChapter()) } }
        assertTrue(error.message.orEmpty().contains("403"))
    }

    @Test
    fun blockedBrowserPageIsRejectedWithoutOfferingContinue() {
        val blocked = "<html><title>Attention Required! | Cloudflare</title><body><div id='cf-error-details'>Sorry, you have been blocked</div></body></html>"
        val parser = MangaTek(fixtureContext(JSONObject(), unlockSuccess = true, documentCode = 403, browserHtml = blocked))
        val error = assertThrows(ContentUnavailableException::class.java) { runBlocking { parser.getPages(fixtureChapter()) } }
        assertTrue(error.message.orEmpty().contains("رفع الحظر"))
        assertTrue(parser.isAccessBlocked(Jsoup.parse(blocked)))
        assertFalse(parser.isUsablePage(Jsoup.parse(blocked + "<div class='manga-page'><img src='/1.webp'></div>"), "div.manga-page img[src]"))
    }

    @Test
    fun realInteractiveChallengeStillOffersVerification() {
        val challenge = "<title>CAPTCHA</title><form id='challenge-form'><input name='cf-turnstile-response'></form>"
        val parser = MangaTek(fixtureContext(JSONObject(), unlockSuccess = true, documentCode = 403, browserHtml = challenge))
        assertThrows(BrowserRequested::class.java) { runBlocking { parser.getPages(fixtureChapter()) } }
    }

    @Test
    fun browserFallbackUsesTheSameIdentityAndWaitsForCurrentChapter() {
        val parser = MangaTek(fixtureContext(JSONObject(), unlockSuccess = false, documentCode = 403))
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
    fun usesTheCurrentHostBrowserIdentityRatherThanAnOldFixedChromeVersion() {
        val hostIdentity = "Current Android WebView identity fixture"
        val parser = MangaTek(fixtureContext(JSONObject(), false, userAgent = hostIdentity))
        assertEquals(hostIdentity, parser.getRequestHeaders()["User-Agent"])
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

    private class BrowserRequested : RuntimeException()

    private val catalogueHtml = "<main><div class='grid'><a class='manga-card' href='/manga/fixture'><h3>Fixture manga</h3><img src='/cover.webp'></a></div></main>"

    private fun fixtureContext(page: JSONObject, unlockSuccess: Boolean, unlockCode: Int = 200, documentCode: Int = 200, browserHtml: String? = null, userAgent: String = MangaTek.USER_AGENT, documentHtml: String? = null, overlayVersion: Int = 0, browserResult: (suspend () -> String?)? = null): MangaLoaderContext {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (it + 10).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(JSONObject().put("pages", JSONArray().put(page)).toString().toByteArray(Charsets.UTF_8))
        fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val encrypted = listOf(iv, sealed.copyOfRange(0, sealed.size - 16), sealed.copyOfRange(sealed.size - 16, sealed.size))
            .joinToString(":") { it.hex() }
        val html = """<astro-island component-url="/_astro/ChapterImageViewer.fixture.js" props='{"textMode":[0,"overlay"],"unlockToken":[0,"fixture-token"],"chapterId":[0,123]}'></astro-island>
            <div class="manga-page"><img data-url="/cover.webp"></div>
            <div class="manga-page"><img data-url="/page.webp"></div>"""
        return object : MangaLoaderContext() {
            override val cookieJar = CookieJar.NO_COOKIES
            override val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals(userAgent, request.header("User-Agent"))
                assertEquals(TimeUnit.MILLISECONDS.toNanos(MangaTek.HTTP_TIMEOUT_MS), chain.call().timeout().timeoutNanos(),
                    "Both document and unlock requests need a total call deadline, not only an idle read timeout")
                val isUnlock = request.url.host == "api.mangatek.com" && request.url.encodedPath == "/api/reader/unlock"
                val isModule = request.url.encodedPath == "/_astro/ChapterImageViewer.fixture.js"
                check(isUnlock || isModule || request.url.encodedPath in listOf("/reader/fixture/1", "/manga-list")) { "Unexpected fixture request" }
                val body = if (isUnlock) {
                    assertEquals("POST", request.method)
                    assertEquals("https://mangatek.com", request.header("Origin"))
                    assertEquals("https://mangatek.com/reader/fixture/1", request.header("Referer"))
                    JSONObject().put("success", unlockSuccess).put("key", if (overlayVersion >= 3) "" else key.hex())
                        .put("ks", if (overlayVersion >= 3) "fixture-seed" else "").put("ev", overlayVersion)
                        .put("overlay", encrypted).put("overlay_page_offset", 1).toString()
                } else if (isModule) "async function fixtureCrypto(a,b,c,d){return crypto.subtle && new TextDecoder();}export{fixtureCrypto as default};"
                else documentHtml ?: html
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (isUnlock) unlockCode else if (isModule) 200 else documentCode).message("Offline fixture")
                    .body(body.toResponseBody((if (isUnlock) "application/json" else "text/html").toMediaType())).build()
            }.build()
            override fun getConfig(source: MangaSource) = MangaLoaderContextMock.getConfig(source)
            override fun getDefaultUserAgent() = userAgent
            override suspend fun evaluateJs(script: String): String? = error("Unexpected WebView fallback")
            override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("Unexpected WebView fallback")
            override suspend fun evaluateJs(baseUrl: String, script: String, headers: Map<String, String>): String? {
                assertTrue(baseUrl in listOf("https://mangatek.com/reader/fixture/1", "https://mangatek.com/manga-list?sort=latest"))
                assertEquals(userAgent, headers["User-Agent"])
                assertTrue(script.contains("document.readyState") || script.contains("Network disabled in crypto bridge"))
                return if (browserResult != null) browserResult() else JSONObject.quote(browserHtml ?: html)
            }
            override fun requestBrowserAction(parser: org.koitharu.kotatsu.parsers.MangaParser, url: String): Nothing {
                assertEquals("https://mangatek.com/reader/fixture/1", url)
                throw BrowserRequested()
            }
            override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unused fixture operation")
            override fun createBitmap(width: Int, height: Int): Bitmap = error("Unused fixture operation")
        }
    }
}
