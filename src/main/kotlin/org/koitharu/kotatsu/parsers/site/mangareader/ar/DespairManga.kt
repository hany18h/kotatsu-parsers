package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException

@MangaSourceParser("DESPAIRMANGA", "Despair Manga", "ar")
internal class DespairManga(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.DESPAIRMANGA, "despair-world.com", pageSize = 20, searchPageSize = 20) {
	override val configKeyDomain = ConfigKey.Domain("despair-world.com")

	override suspend fun getDetails(manga: Manga): Manga {
		val details = super.getDetails(manga)
		if (details.chapters.isNullOrEmpty()) throw ContentUnavailableException(UNAVAILABLE)
		return details
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> =
		super.getPages(chapter).ifEmpty { throw ContentUnavailableException(UNAVAILABLE) }

	private companion object {
		const val UNAVAILABLE = "لم يُرجع Despair محتوى الفصل. افتح المصدر في المتصفح للتحقق من إتاحته ثم أعد المحاولة."
	}
}
