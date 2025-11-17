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
        "lavascans.com",
        pageSize = 32,
        searchPageSize = 10,
    ) {

    // مهم علشان تعدي Cloudflare
    override val isNetShieldProtected = true

    // المسار الرسمي في lavatoons/lavascans
    override val listUrl = "/manga"

    // بعض المواقع بتستخدم الاتنين
    override val selectChapter =
        "div.eplister ul li, #chapterlist ul li"

    // دعم ts_reader الجديد
    override val selectTestScript =
        "script#ts-reader, script:contains(ts_reader)"
}
