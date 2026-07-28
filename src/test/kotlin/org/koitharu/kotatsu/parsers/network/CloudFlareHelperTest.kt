package org.koitharu.kotatsu.parsers.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class CloudFlareHelperTest {

	@Test
	fun detectsCurrentManagedChallenge() {
		val response = response(
			code = 403,
			html = """
				<html>
				<head><title>Just a moment...</title></head>
				<body>
					<form id="challenge-form"></form>
					<script src="/cdn-cgi/challenge-platform/h/g/orchestrate/chl_page/v1"></script>
				</body>
				</html>
			""".trimIndent(),
		)

		response.use {
			assertEquals(
				CloudFlareHelper.PROTECTION_CAPTCHA,
				CloudFlareHelper.checkResponseForProtection(it),
			)
		}
	}

	@Test
	fun detectsCloudflareAccessDeniedPageAsBlocked() {
		val response = response(
			code = 403,
			html = "<html><body><h1>Access denied</h1></body></html>",
			server = "cloudflare",
		)

		response.use {
			assertEquals(
				CloudFlareHelper.PROTECTION_BLOCKED,
				CloudFlareHelper.checkResponseForProtection(it),
			)
		}
	}

	@Test
	fun ignoresOrdinaryForbiddenResponse() {
		val response = response(
			code = 403,
			html = """{"code":"rest_forbidden","message":"Forbidden"}""",
		)

		response.use {
			assertEquals(
				CloudFlareHelper.PROTECTION_NOT_DETECTED,
				CloudFlareHelper.checkResponseForProtection(it),
			)
		}
	}

	private fun response(code: Int, html: String, server: String? = null): Response {
		val builder = Response.Builder()
			.request(Request.Builder().url("https://example.org/").build())
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("test")
			.body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
		server?.let { builder.header("Server", it) }
		return builder.build()
	}
}
