package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("UMIMANGA", "UmiManga", "ar")
internal class UmiManga(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.UMIMANGA, "www.umimanga.com", pageSize = 20, searchPageSize = 10) {

    // التأكد من استخدام المحدد الصحيح (اسم المتغير هو selectPage بناءً على ملفك)
    override val selectPage = "div#readerarea img"

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )

    // نقوم بإعادة كتابة دالة جلب الصفحات للتعامل مع data-src
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val docs = webClient.httpGet(chapterUrl).parseHtml()

        // 1. محاولة استخدام السكربت (الطريقة الافتراضية للأنظمة الحديثة)
        val script = docs.select(selectTestScript).firstOrNull()
        if (script != null) {
            // إذا وجدنا السكربت، نترك الكلاس الأب يقوم بعمله المعقد
            return super.getPages(chapter)
        }

        // 2. إذا لم يوجد سكربت، نستخدم طريقتنا الخاصة لجلب الصور (Fallback)
        return docs.select(selectPage).map { img ->
            // هنا الحل السحري: نتحقق من data-src أولاً، وإذا كانت فارغة نأخذ src
            val url = (img.attr("data-src").ifEmpty { img.attr("src") }).trim()
            
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
}
