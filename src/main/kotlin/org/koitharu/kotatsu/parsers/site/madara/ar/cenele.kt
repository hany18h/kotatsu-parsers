package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("FADAA_ALRIWAYAT", "فضاء الروايات", "ar", ContentType.NOVEL)
internal class FadaaAlriwayat(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.FADAA_ALRIWAYAT, "cenele.com") {

    override val listUrl = "cont/"
    override val tagPrefix = "cont-genre/"
    override val datePattern = "MMMM d, yyyy"
}
