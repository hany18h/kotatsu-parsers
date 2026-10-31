package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.model.*
import org.jsoup.HttpStatusException
import kotlinx.coroutines.delay

@MangaSourceParser("ASQORG", "3Asq", "ar")
internal class Asq(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ASQORG, "3asq.online") {
	override val configKeyDomain = ConfigKey.Domain("3asq.online", "3asq.org")

	// The site's madara_load_more endpoint now returns an empty body. Its regular
	// archive/search pages still contain the complete manga cards.
	override val withoutAjax = true
	override val datePattern = "d MMMM، yyyy"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> =
		retryUnavailable { super.getListPage(page, order, filter) }
	override suspend fun getDetails(manga: Manga): Manga = retryUnavailable { super.getDetails(manga) }
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = retryUnavailable { super.getPages(chapter) }
	override suspend fun getFilterOptions(): MangaListFilterOptions = retryUnavailable { super.getFilterOptions() }

	internal suspend fun <T> retryUnavailable(load: suspend () -> T): T = try {
		load()
	} catch (error: HttpStatusException) {
		// A short server outage is recoverable. Real protection/authentication
		// errors and cancellation must still reach the application's resolver.
		if (error.statusCode != 503) throw error
		delay(600)
		load()
	}
}
