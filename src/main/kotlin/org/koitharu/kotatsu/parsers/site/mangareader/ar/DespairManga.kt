package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("DESPAIRMANGA", "Despair Manga", "ar")
internal class DespairManga(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.DESPAIRMANGA, "despair-manga.net", pageSize = 20, searchPageSize = 10)
