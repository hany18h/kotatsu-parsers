package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import javax.crypto.AEADBadTagException
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.UserAgents

internal class DilarTubeTest {

	@Test
	fun usesBrowserUserAgentAcceptedByDilar() {
		val parser = DilarTube(MangaLoaderContextMock)

		assertEquals(UserAgents.CHROME_MOBILE, parser.getRequestHeaders()["User-Agent"])
	}

	@Test
	fun retriesEnrollmentForAuthenticationResponses() {
		assertTrue(DilarTube.requiresClientReenrollment(403))
		assertTrue(DilarTube.requiresClientReenrollment(428))
		assertFalse(DilarTube.requiresClientReenrollment(404))
	}

	@Test
	fun derivesVersion11KeyAndNonceLikeDilarWebClient() {
		val parser = DilarTube(MangaLoaderContextMock)
		val material = parser.deriveV11KeyMaterial(
			sharedSecret = ByteArray(32) { (it + 1).toByte() },
			clientPublicKey = ByteArray(65) { it.toByte() },
			serverPublicKey = ByteArray(65) { (64 - it).toByte() },
			envelopeIv = ByteArray(12) { (it + 10).toByte() },
			epoch = 1_787_350_000L,
		)

		assertEquals(
			"0d25c3c60161bea9d565bca7ac1324b4ac473df8d163efb2d96344f32aa36ca7" +
				"286a51c7d545230c8d669574",
			material.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) },
		)
	}

	@Test
	fun derivesVersion12KeyNonceAndAdditionalDataLikeDilarWebClient() {
		val parser = DilarTube(MangaLoaderContextMock)
		val sharedSecret = ByteArray(32) { (it + 1).toByte() }
		val clientPublicKey = ByteArray(65) { it.toByte() }
		val serverPublicKey = ByteArray(65) { (64 - it).toByte() }
		val envelopeIv = ByteArray(12) { (it + 10).toByte() }
		val epoch = 1_787_350_000L

		val material = parser.deriveV12KeyMaterial(
			sharedSecret,
			clientPublicKey,
			serverPublicKey,
			envelopeIv,
			epoch,
		)
		val additionalData = parser.buildV12AdditionalData(
			version = 12,
			epoch = epoch,
			serverPublicKey = serverPublicKey,
			envelopeIv = envelopeIv,
			cipherTextLength = 1234,
		)

		assertEquals(
			"dfc650e264729e95485091bda472dca5c48c97d8d4d848fa59a31542e7d5598" +
				"b0f4cb0cc48a0c5b12c2cc993",
			material.toHex(),
		)
		assertEquals(
			"85b82973319e50e941123ed0603a0238fa1e726c17642e804bf47d291f34d0a6",
			additionalData.toHex(),
		)
	}

	@Test
	fun derivesVersion13ChaChaKeyNonceAndAdditionalDataLikeDilarWebClient() {
		val parser = DilarTube(MangaLoaderContextMock)
		val sharedSecret = ByteArray(32) { (it + 1).toByte() }
		val clientPublicKey = ByteArray(65) { it.toByte() }
		val serverPublicKey = ByteArray(65) { (64 - it).toByte() }
		val envelopeIv = ByteArray(12) { (it + 10).toByte() }
		val epoch = 1_787_350_000L

		val material = parser.deriveV13KeyMaterial(
			sharedSecret,
			clientPublicKey,
			serverPublicKey,
			envelopeIv,
			epoch,
		)
		val additionalData = parser.buildV13AdditionalData(
			epoch,
			serverPublicKey,
			envelopeIv,
			1234,
		)

		assertEquals(
			"03de6cb4646b82983e6f2f3d3761facc616e794d0abeee4e452c93f3007e9cea" +
				"faf729191ba8b1741b14de6c",
			material.toHex(),
		)
		assertEquals(
			"0b3f9c909a2581994234576aaa33813957cf5d80e7217c963a911e01ad5da2e8",
			additionalData.toHex(),
		)
	}

	@Test
	fun derivesVersion15MaterialLikeCurrentDilarWebClient() {
		val parser = DilarTube(MangaLoaderContextMock)
		val client = ByteArray(65) { it.toByte() }
		val server = ByteArray(65) { (64 - it).toByte() }
		val iv = ByteArray(12) { (it + 10).toByte() }
		assertEquals(
			"42a5a612322a78cee9662e5170dde45f8b442bd91502fecf04863f909132a7499" +
				"7e24e8cf231c2c0514c17bfbc579e299d3b3d1c7d49531f1667906fb717d7a5" +
				"06121589b229d87da3a46f44",
			parser.deriveV15KeyMaterial(ByteArray(32) { (it + 1).toByte() }, client, server, iv, 1787350000L).toHex(),
		)
		assertEquals(
			"0e75a12c4af93bbc4e4aa919d75090e6d3df131b34b9957d6c9f1ab86ef2e446",
			parser.buildV15AdditionalData(1787350000L, server, iv, 1234).toHex(),
		)
	}

	@Test
	fun opensAesSivRfc5297VectorAndRejectsTampering() {
		val key = "fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff".fromHex()
		val tag = "85632d07c6e8f37f950acd320a2ecc93".fromHex()
		val ciphertext = "40c02b9690c4dc04daef7f6afe5c".fromHex()
		val aad = listOf("101112131415161718191a1b1c1d1e1f2021222324252627".fromHex())
		assertEquals("112233445566778899aabbccddee", DilarAesSiv.decrypt(key, tag, ciphertext, aad).toHex())
		ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
		assertThrows(AEADBadTagException::class.java) { DilarAesSiv.decrypt(key, tag, ciphertext, aad) }
	}

	@Test
	fun opensVersion15Aes256SivVectorFromDilarBrowserLibrary() {
		val result = DilarAesSiv.decrypt(
			ByteArray(64) { it.toByte() },
			"616e35dc27b89555fe21c4a77dc580ae".fromHex(),
			"fdd8d15c40013d988a599e2dd22332c752a90807a6c408f76573349ca7073731756acd56d0e6ad1a49308bd101bea72f7e324cc9421eb0060a18".fromHex(),
			listOf(ByteArray(32) { (it + 32).toByte() }, ByteArray(12) { (it + 64).toByte() }),
		)
		assertEquals("""{"pages":[{"url":"page.webp"}],"test":"Dilar v15 AES-SIV"}""", String(result, Charsets.UTF_8))
	}

	@Test
	fun opensAesSivNonceVectorWithMultipleAssociatedDataFields() {
		val result = DilarAesSiv.decrypt(
			"7f7e7d7c7b7a79787776757473727170404142434445464748494a4b4c4d4e4f".fromHex(),
			"7bdb6e3b432667eb06f4d14bff2fbd0f".fromHex(),
			"cb900f2fddbe404326601965c889bf17dba77ceb094fa663b7a3f748ba8af829ea64ad544a272e9c485b62a3fd5c0d".fromHex(),
			listOf(
				"00112233445566778899aabbccddeeffdeaddadadeaddadaffeeddccbbaa99887766554433221100".fromHex(),
				"102030405060708090a0".fromHex(),
				"09f911029d74e35bd84156c5635688c0".fromHex(),
			),
		)
		assertEquals("this is some plaintext to encrypt using SIV-AES", String(result, Charsets.UTF_8))
	}

	@Test
	fun derivesVersion16MaterialLikeCurrentDilarWebClient() {
		val parser = DilarTube(MangaLoaderContextMock)
		val client = ByteArray(65) { it.toByte() }
		val server = ByteArray(65) { (64 - it).toByte() }
		val iv = ByteArray(12) { (it + 10).toByte() }
		assertEquals(
			"c5017c7b586ee679d60f41af478f46a94ed1977237b5b71e453334a51afacf89c" +
				"6350d6adcbc565fc431e4f149115771e1b0256be8a84939",
			parser.deriveV16KeyMaterial(ByteArray(32) { (it + 1).toByte() }, client, server, iv, 1787350000L).toHex(),
		)
		assertEquals(
			"7f98832b745e1966d493b043d9b79a157d84808d74f8289f7deac0da176086b1",
			parser.buildV16AdditionalData(1787350000L, server, iv, 1234).toHex(),
		)
		assertTrue(DilarTube.supportsNativeVersion(16))
		assertFalse(DilarTube.supportsNativeVersion(17))
	}

	@Test
	fun hChaCha20MatchesPublishedDraftVector() {
		assertEquals(
			"82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
			DilarXChaCha20.hChaCha20(ByteArray(32) { it.toByte() }, "000000090000004a0000000031415927".fromHex()).toHex(),
		)
	}

	@Test
	fun opensXChaCha20VectorFromDilarLibraryAndRejectsTampering() {
		val key = ByteArray(32) { it.toByte() }
		val nonce = ByteArray(24) { (it + 32).toByte() }
		val aad = ByteArray(32) { (it + 64).toByte() }
		val ciphertext = ("667b3daa1c5267a902e52b9f3d356df30777a15db8f628c284032c0b7264c620" +
			"45245336e3d9044a3bc8543c9de70732498beea38d8495b5e2a050726e883d599fb4b9a05faafd41da0918e1").fromHex()
		assertEquals(
			"""{"pages":[{"url":"page.webp"}],"test":"Dilar v16 XChaCha20"}""",
			String(DilarXChaCha20.decrypt(key, nonce, ciphertext, aad), Charsets.UTF_8),
		)
		aad[0] = (aad[0].toInt() xor 1).toByte()
		assertThrows(AEADBadTagException::class.java) { DilarXChaCha20.decrypt(key, nonce, ciphertext, aad) }
		aad[0] = (aad[0].toInt() xor 1).toByte()
		ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
		assertThrows(AEADBadTagException::class.java) { DilarXChaCha20.decrypt(key, nonce, ciphertext, aad) }
	}

	@Test
	fun futureVersionUsesCurrentWebClientAndDoesNotGuessItsCipher() = runBlocking {
		for (version in listOf(17, 18, 99)) {
			val context = FutureDilarContext(version)
			val parser = DilarTube(context)
			val chapter = MangaChapter(
				id = 1L, title = "Test", number = 1f, volume = 0, url = "/api/chapters/123",
				uploadDate = 0L, scanlator = null, branch = null, source = MangaParserSource.DILARTUBE,
			)
			assertEquals("https://dilar.tube/uploads/page.webp", parser.getPages(chapter).single().url)
			assertEquals("https://dilar.tube/uploads/page.webp", parser.getPages(chapter).single().url)
			assertEquals(1, context.nativeRequests, "Subsequent chapters reuse the current-client strategy")
			assertEquals(2, context.browserRequests)
			assertTrue(context.lastScript.contains("import(entry.href)"))
		}
	}

	@Test
	fun webClientRejectsEncryptedOrMissingResultsAndPreservesAuthenticationErrors() {
		val url = "https://dilar.tube/api/chapters/123"
		assertThrows(ContentUnavailableException::class.java) { DilarWebClient.parseResult(null, url) }
		assertThrows(ContentUnavailableException::class.java) {
			DilarWebClient.parseResult(JSONObject.quote("""{"data":{"v":17,"epk":"x","ct":"x","pages":[]}}"""), url)
		}
		assertThrows(ContentUnavailableException::class.java) {
			DilarWebClient.parseResult(JSONObject.quote("""{"data":{"title":"error"}}"""), url)
		}
		val error = assertThrows(HttpStatusException::class.java) {
			DilarWebClient.parseResult(JSONObject.quote("""{"error":"rejected","status":403}"""), url)
		}
		assertEquals(403, error.statusCode)
	}

	@Test
	fun futureVersionPreservesTheNormalFreeChapterUnlockFlow() = runBlocking {
		val context = FutureDilarContext(17, requiresFreePass = true)
		val parser = DilarTube(context)
		val chapter = MangaChapter(
			id = 1L, title = "Test", number = 1f, volume = 0, url = "/api/chapters/123",
			uploadDate = 0L, scanlator = null, branch = null, source = MangaParserSource.DILARTUBE,
		)
		assertEquals("https://dilar.tube/uploads/page.webp", parser.getPages(chapter).single().url)
		assertEquals(2, context.nativeRequests, "Native negotiation plus the normal unlock endpoint")
		assertEquals(2, context.browserRequests, "Locked and unlocked chapter requests")
		assertTrue(context.lastScript.contains("const pass = \"issued-free-pass\""))
	}

	@Test
	fun browserRequestEscapesCredentialsAndPreservesFreePass() {
		val token = "token\"; throw new Error('injected'); //"
		val script = DilarWebClient.buildScript("https://dilar.tube/", "https://dilar.tube/api/chapters/123", token, "free-pass", "id")
		assertTrue(script.contains(JSONObject.quote(token)))
		assertTrue(script.contains("headers['X-Unlock-Free-Chapter'] = pass"))
		assertFalse(script.contains("X-Crypto-Caps"), "The current client, not this parser, advertises future ciphers")
	}

	private class FutureDilarContext(version: Int, private val requiresFreePass: Boolean = false) : MangaLoaderContext() {
		var nativeRequests = 0
		var browserRequests = 0
		var lastScript = ""
		override val cookieJar = MangaLoaderContextMock.cookieJar
		override val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
			nativeRequests++
			val isUnlock = chain.request().url.encodedPath.endsWith("/unlock/free")
			if (isUnlock) {
				assertEquals("POST", chain.request().method)
				assertEquals("existing-token", chain.request().header("X-Client-Token"))
			} else {
				assertEquals(DilarTube.NATIVE_CRYPTO_CAPABILITIES, chain.request().header("X-Crypto-Caps"))
			}
			val body = if (isUnlock) """{"token":"issued-free-pass"}""" else """{"v":$version,"epk":"future","ct":"future"}"""
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
				.body(body.toResponseBody()).build()
		}.build()
		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = error("Base URL is required")
		override suspend fun evaluateJs(baseUrl: String, script: String): String? = evaluateJs(baseUrl, script, emptyMap())
		override suspend fun evaluateJs(baseUrl: String, script: String, headers: Map<String, String>): String? {
			if (script.contains("dilar.client.credential")) return JSONObject.quote("existing-token")
			assertEquals("https://dilar.tube/", baseUrl)
			assertEquals(UserAgents.CHROME_MOBILE, headers["User-Agent"])
			assertEquals("existing-token", headers["X-Client-Token"])
			browserRequests++
			lastScript = script
			if (requiresFreePass && browserRequests == 1) {
				return JSONObject.quote("""{"data":{"free_pass_required":true,"pages":[]}}""")
			}
			return JSONObject.quote("""{"data":{"pages":[{"url":"page.webp"}]}}""")
		}
		override fun getConfig(source: MangaSource) = MangaLoaderContextMock.getConfig(source)
		override fun getDefaultUserAgent() = UserAgents.CHROME_MOBILE
		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap) = error("Not used")
		override fun createBitmap(width: Int, height: Int): Bitmap = error("Not used")
	}

	private fun String.fromHex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

	private fun ByteArray.toHex(): String = joinToString(separator = "") {
		"%02x".format(it.toInt() and 0xff)
	}
}
