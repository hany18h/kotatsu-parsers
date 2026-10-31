package org.koitharu.kotatsu.parsers.site.madara.ar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.jsoup.HttpStatusException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock

internal class AsqTest {
	private val parser = Asq(MangaLoaderContextMock)
	@Test fun retriesTemporary503Once() = runTest {
		var attempts = 0
		assertEquals("ready", parser.retryUnavailable {
			if (++attempts == 1) throw HttpStatusException("Unavailable", 503, "https://3asq.online/")
			"ready"
		})
		assertEquals(2, attempts)
	}
	@Test fun persistent503StopsAfterOneRetry() = runTest {
		var attempts = 0
		try {
			parser.retryUnavailable { attempts++; throw HttpStatusException("Unavailable", 503, "https://3asq.online/") }
			fail<Unit>("Expected failure")
		} catch (error: HttpStatusException) { assertEquals(503, error.statusCode) }
		assertEquals(2, attempts)
	}
	@Test fun doesNotRetryProtectionErrorsOrCancellation() = runTest {
		for (error in listOf(HttpStatusException("Blocked", 403, "https://3asq.online/"), CancellationException("Leave"))) {
			var attempts = 0
			try { parser.retryUnavailable { attempts++; throw error }; fail<Unit>("Expected failure") }
			catch (actual: Exception) { assertSame(error, actual) }
			assertEquals(1, attempts)
		}
	}
}
