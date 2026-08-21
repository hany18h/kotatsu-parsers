package org.koitharu.kotatsu.parsers.site.ar

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DilarTubeTest {

	@Test
	fun opensBrowserForCloudflareAndEnrollmentResponses() {
		assertTrue(DilarTube.requiresBrowserEnrollment(403))
		assertTrue(DilarTube.requiresBrowserEnrollment(428))
		assertFalse(DilarTube.requiresBrowserEnrollment(404))
	}
}
