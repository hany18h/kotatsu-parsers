package org.koitharu.kotatsu.parsers.site.zeistmanga.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser

@MangaSourceParser("MURIM", "Murim", "ar")
internal class Murim(context: MangaLoaderContext) :
    ZeistMangaParser(context, MangaParserSource.MURIM, "www.murim.site")
