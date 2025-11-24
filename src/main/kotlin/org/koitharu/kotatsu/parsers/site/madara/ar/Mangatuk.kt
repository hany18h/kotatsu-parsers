package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MANGATUK", "Mangatuk", "ar")
internal class Mangatuk(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGATUK, "mangatuk.com", pageSize = 20)
