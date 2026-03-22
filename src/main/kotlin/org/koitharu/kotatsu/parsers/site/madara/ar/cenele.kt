package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("CENELE", "فضاء الروايات", "ar", ContentType.OTHER)
internal class Cenele(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.CENELE, "cenele.com") {

    override val listUrl = "novel/"
    override val tagPrefix = "novel-genre/"
    override val datePattern = "MMMM d, yyyy"
}
