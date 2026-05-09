package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("ANYONEMANGA", "AnyoneManga", "ar")
internal class Eshadow(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ESHADOW, "anyonemanga.com", pageSize = 20)
