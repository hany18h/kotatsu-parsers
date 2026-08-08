package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SunovelsTest {

	@Test
	fun detectsZeroBasedPageCountWithoutDependingOnEnglishAriaLabels() {
		val document = Jsoup.parse(
			"""
			<nav class="pagination">
			  <a aria-label="الصفحة 1" href="?activeTab=chapters&amp;page=0">1</a>
			  <a aria-label="الصفحة 32" href="?activeTab=chapters&amp;page=31">32</a>
			</nav>
			""".trimIndent(),
		)

		assertEquals(32, Sunovels.findTotalChapterPages(document))
	}

	@Test
	fun readsPageCountFromNextPayloadAsFallback() {
		val document = Jsoup.parse(
			"""<script>self.__next_f.push([1,"{\"totalPages\":17}"])</script>""",
		)

		assertEquals(17, Sunovels.findTotalChapterPages(document))
	}
}
