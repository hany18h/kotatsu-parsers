package org.koitharu.kotatsu.parsers.site.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

/**
 * MeshManga (Swat Manga) - موقع مانجا عربي
 * Domain: meshmanga.com
 */
@MangaSourceParser("MESHMANGA", "MeshManga", "ar")
internal class MeshManga(context: MangaLoaderContext) :
    MangaReaderParser(
        context = context,
        source = MangaParserSource.MESHMANGA,
        domain = "meshmanga.com",
        pageSize = 36,
        searchPageSize = 20
    ) {

    override val listUrl = "/type/manga"
    override val datePattern = "MMMM dd, yyyy"

    // تخصيص selectors للموقع
    override val selectMangaList = ".postbody .bs .bsx"
    override val selectMangaListImg = "img"
    override val selectMangaListTitle = "div.tt"
    
    override val selectChapter = "#chapterlist ul li"
    override val selectPage = "div#readerarea img"
    override val detailsDescriptionSelector = "div.entry-content, div.summary__content"
    
    // استخدام ts_reader للصور
    override val selectTestScript = "script:contains(ts_reader)"
}
