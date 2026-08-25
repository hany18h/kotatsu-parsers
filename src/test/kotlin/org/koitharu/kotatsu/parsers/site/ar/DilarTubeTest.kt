package org.koitharu.kotatsu.parsers.site.ar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

	private fun ByteArray.toHex(): String = joinToString(separator = "") {
		"%02x".format(it.toInt() and 0xff)
	}
}
