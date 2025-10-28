package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("AZORAMOON", "AzoraMoon", "ar")
internal class AzoraMoon(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.AZORAMOON, "azoramoon.com", pageSize = 10) {
	
	override val tagPrefix = "series-genre/"
	override val listUrl = "series/"
	
	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		return emptyList()
	}
}
