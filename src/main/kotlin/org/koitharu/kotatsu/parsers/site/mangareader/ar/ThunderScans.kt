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
        "lavatoons.com", // 1. تم تصحيح الدومين هنا
        pageSize = 32,
        searchPageSize = 10,
    ) {

    // 2. تفعيل الحماية لتجاوز كلاود فلير
    override val isNetShieldProtected = true

    // 3. محدد الفصول: أضفت محددات أكثر شمولاً لأن القالب قد يختلف بين المانجات
    override val selectChapter = "li.wp-manga-chapter, div.eplister ul li, #chapterlist ul li"

    // 4. محدد الصور: يدعم الصور المباشرة + السكربت الجديد
    override val selectPage = ".reading-content img, #readerarea img"
    override val selectTestScript = "script#ts-reader, script:contains(ts_reader)"
    
    // 5. فلترة التاغز (اختياري، كما كان في كودك القديم)
    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )
}
