package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("ASQORG", "3Asq", "ar")
internal class Asq(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ASQORG, "3asq.online") {
	override val configKeyDomain = ConfigKey.Domain("3asq.online", "3asq.org")

	// The site's madara_load_more endpoint now returns an empty body. Its regular
	// archive/search pages still contain the complete manga cards.
	override val withoutAjax = true
	override val datePattern = "d MMMM، yyyy"
}
