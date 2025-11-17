@MangaSourceParser("THUNDERSCANS", "ThunderScans", "ar")
internal class ThunderScans(context: MangaLoaderContext) :
    MangaReaderParser(
        context,
        MangaParserSource.THUNDERSCANS,
        "lavascans.com",
        pageSize = 32,
        searchPageSize = 10,
    ) {

    // لازم علشان تعدّي Cloudflare
    override val isNetShieldProtected = true

    // المسار الأساسي في lavatoons/lavascans
    override val listUrl = "/manga"

    // مواقع الوردبريس بدأت تغيّر الهيكل
    override val selectChapter =
        "div.eplister ul li, #chapterlist ul li"

    // دعم شكل ts_reader القديم والجديد
    override val selectTestScript =
        "script#ts-reader, script:contains(ts_reader)"
}
