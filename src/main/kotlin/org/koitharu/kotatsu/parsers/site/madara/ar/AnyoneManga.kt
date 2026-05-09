package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("ANYONEMANGA", "AnyoneManga", "ar")
internal class AnyoneManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ANYONEMANGA, "anyonemanga.com", pageSize = 10) {
	override val datePattern = "d MMMM، yyyy"
	override val stylePage = ""
    override val withoutAjax = true
}
