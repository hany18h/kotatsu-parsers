package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("HIZOMANGA", "Hizo Manga", "ar")
internal class HizoManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HIZOMANGA, "hizomanga.net")
