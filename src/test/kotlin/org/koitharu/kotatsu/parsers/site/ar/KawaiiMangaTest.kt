package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class KawaiiMangaTest {

	@Test
	fun fallsBackToEnglishTitleWhenArabicTitleIsJsonNull() {
		val parser = KawaiiManga(MangaLoaderContextMock)
		val manga = parser.parseManga(
			JSONObject("""{"id":"series-id","slug":"series-slug","titleAr":null,"title":"English title","titleEn":"English title","coverUrl":null,"altTitles":[null,"Other title"]}"""),
		)

		assertEquals("English title", manga.title)
		assertEquals(setOf("Other title"), manga.altTitles)
		assertNull(manga.coverUrl)
		assertFalse(manga.title.equals("null", ignoreCase = true))
	}

	@Test
	fun decryptsTheEncryptedPageListReturnedByTheCurrentApi() {
		val parser = KawaiiManga(MangaLoaderContextMock)
		val token = "preview-token-012345678901234567890123"
		val iv = ByteArray(12) { it.toByte() }
		val plain = """{"pages":["https://cdn.example/page-1.webp"]}"""
		val keyMaterial = "1153b3c534368ded1f8386fe03beaa5ebb166a15f4275e25|${token.takeLast(24)}"
		val key = MessageDigest.getInstance("SHA-256").digest(keyMaterial.toByteArray(Charsets.UTF_8))
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
		val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
		val box = JSONObject()
			.put("i", Base64.getEncoder().encodeToString(iv))
			.put("d", Base64.getEncoder().encodeToString(encrypted))

		val pages = parser.decryptPages(box, token)

		assertEquals("https://cdn.example/page-1.webp", pages.getString(0))
	}
}
