package org.koitharu.kotatsu.parsers.site.ar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import javax.crypto.AEADBadTagException
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
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

	private fun String.fromHex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

	private fun ByteArray.toHex(): String = joinToString(separator = "") {
		"%02x".format(it.toInt() and 0xff)
	}
}
