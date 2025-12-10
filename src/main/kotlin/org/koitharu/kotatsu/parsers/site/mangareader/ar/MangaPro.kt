override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
    val docs = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
    val pages = mutableListOf<MangaPage>()
    
    // استخراج جميع الصور من container الرئيسي
    val imageContainer = docs.selectFirst("div.flex.flex-col") 
        ?: docs.selectFirst("div[class*='flex'][class*='flex-col']")
    
    if (imageContainer != null) {
        // البحث عن جميع img tags داخل الـ container
        imageContainer.select("img[src]").forEach { img ->
            val src = img.attr("src")
            if (src.isNotEmpty() && (
                src.contains("app.prochan.net") || 
                src.contains("cdn1.prochan.net") ||
                src.contains("cdn2.prochan.net") ||
                src.contains("cdn3.prochan.net")
            )) {
                val fullUrl = if (src.startsWith("http")) src else "https:$src"
                pages.add(
                    MangaPage(
                        id = generateUid(fullUrl),
                        url = fullUrl,
                        preview = null,
                        source = source,
                    )
                )
            }
        }
    }
    
    // Fallback: إذا لم نجد صور، نبحث في كامل الصفحة
    if (pages.isEmpty()) {
        docs.select("img[src*='prochan.net']").forEach { img ->
            val src = img.attr("src")
            if (src.isNotEmpty() && !src.contains("series-cards")) { // تجنب صور الكفر
                val fullUrl = if (src.startsWith("http")) src else "https:$src"
                pages.add(
                    MangaPage(
                        id = generateUid(fullUrl),
                        url = fullUrl,
                        preview = null,
                        source = source,
                    )
                )
            }
        }
    }
    
    // إزالة التكرار
    return pages.distinctBy { it.url.substringBefore("?") }
}
