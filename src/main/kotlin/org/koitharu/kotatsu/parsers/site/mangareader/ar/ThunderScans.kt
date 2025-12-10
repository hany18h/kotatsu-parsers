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
    
    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )
    
    // ✅ تفعيل Cloudflare NetShield
    override val isNetShieldProtected = true
    
    // ✅ محدد الفصول الصحيح من الكود الآخر
    override val selectChapter = "div.eplister#chapterlist li"
    
    // ✅ محدد السكربت - يدعم كل الحالات
    override val selectTestScript = "script#ts-reader, script:contains(ts_reader.run), script:contains(ts_reader)"
    
    // ✅ محدد الصور العادية (fallback)
    override val selectPage = "div#readerarea img, div.reading-content img"
}
