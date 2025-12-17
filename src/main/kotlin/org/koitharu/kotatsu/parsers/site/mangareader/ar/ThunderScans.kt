package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("LAVATOONS", "Lavatoons", "ar", ContentType.MANGA)
internal class Lavatoons(context: MangaLoaderContext) :
    MangaReaderParser(
        context,
        MangaParserSource.LAVATOONS,
        "lavatoons.com",
        pageSize = 20,
        searchPageSize = 10,
    ) {
    
    override val isNetShieldProtected = true
    
    override val selectChapter = "div.eplister#chapterlist li"
    
    override val selectTestScript = 
        "script#ts-reader, script:contains(ts_reader.run), script:contains(ts_reader)"
    
    override val selectPage = "div#readerarea img, div.reading-content img"
}
