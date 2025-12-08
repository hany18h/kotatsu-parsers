package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("THUNDERSCANS", "ThunderScans", "ar")
internal class ThunderScans(context: MangaLoaderContext) :
    MangaReaderParser(
        context,
        MangaParserSource.THUNDERSCANS,
        "lavatoons.com",
        pageSize = 32,
        searchPageSize = 10,
    ) {
    
    override val isNetShieldProtected = true
    
    // ✅ الحل: المحدد الصحيح بناءً على HTML الفعلي
    override val selectChapter = "div.eplister ul li a"
    
    // محددات الصور
    override val selectPage = "div.reading-content img, div#readerarea img"
    
    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )
}
