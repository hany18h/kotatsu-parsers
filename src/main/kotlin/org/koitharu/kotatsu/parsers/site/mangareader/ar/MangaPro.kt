package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGAPRO", "MangaPro", "ar")
internal class MangaPro(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANGAPRO, "prochan.net", pageSize = 20, searchPageSize = 10) {
    
    override val listUrl = "/series"
    
    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )
    
    private fun extractNextData(docs: Document): JSONObject? {
        return try {
            val scripts = docs.select("script#__NEXT_DATA__")
            if (scripts.isEmpty()) return null
            
            val jsonText = scripts.first()?.html() ?: return null
            JSONObject(jsonText)
        } catch (e: Exception) {
            null
        }
    }
    
    override fun parseMangaList(docs: Document): List<Manga> {
        return docs.select("div.grid a[href^='/series/']").mapNotNull { a ->
            val href = a.attr("href")
            if (href.isNullOrEmpty()) return@mapNotNull null
            
            val relativeUrl = href.toRelativeUrl(domain)
            val title = a.selectFirst("h3")?.text() ?: return@mapNotNull null
            val coverUrl = a.selectFirst("img")?.attr("src")
            
            Manga(
                id = generateUid(relativeUrl),
                url = relativeUrl,
                title = title,
                altTitles = emptySet(),
                publicUrl = a.absUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = coverUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }
    
    override suspend fun getDetails(manga: Manga): Manga {
        val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        val title = docs.selectFirst("h1")?.text() ?: manga.title
        val description = docs.select("p.text-muted-foreground, div.description").firstOrNull()?.text()
        
        val chapters = docs.select("a[href^='/series/'].hover\\:underline")
            .mapNotNull { link ->
                val href = link.attr("href")
                if (href.isEmpty()) return@mapNotNull null
                
                val urlParts = href.split("/")
                if (urlParts.size < 6) return@mapNotNull null
                
                val chapterTitle = link.selectFirst("span.break-words")?.text() 
                    ?: return@mapNotNull null
                
                val chapterNumber = Regex("\\d+").find(chapterTitle)?.value
                    ?.toFloatOrNull() ?: 0f
                
                MangaChapter(
                    id = generateUid(href),
                    title = chapterTitle,
                    url = href.toRelativeUrl(domain),
                    number = chapterNumber,
                    volume = 0,
                    scanlator = "Pro Chan",
                    uploadDate = 0,
                    branch = null,
                    source = source,
                )
            }
            .distinctBy { it.url }
            .reversed()
        
        return manga.copy(
            title = title,
            description = description,
            chapters = chapters,
        )
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val docs = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val pages = mutableListOf<String>()
        
        // الطريقة 1: استخراج من __NEXT_DATA__ (JSON) - نبحث عن app.prochan.net أولاً
        val nextData = extractNextData(docs)
        val props = nextData?.optJSONObject("props")?.optJSONObject("pageProps")
        
        // محاولة استخراج من appImages (أفضل جودة وأكثر استقراراً من app.prochan.net)
        val appImages = props?.optJSONArray("appImages")
        if (appImages != null && appImages.length() > 0) {
            for (i in 0 until appImages.length()) {
                val imgObj = appImages.optJSONObject(i)
                
                // نستخدم mobile version فقط (أكثر استقراراً من cdn2/cdn3)
                val mobileUrl = imgObj?.optString("mobile")
                
                if (!mobileUrl.isNullOrEmpty() && mobileUrl.contains("app.prochan.net")) {
                    val fullUrl = if (mobileUrl.startsWith("http")) mobileUrl else "https:$mobileUrl"
                    pages.add(fullUrl)
                }
            }
        }
        
        // محاولة استخراج من images array - نفضل app.prochan.net
        if (pages.isEmpty()) {
            val images = props?.optJSONArray("images")
            if (images != null && images.length() > 0) {
                for (i in 0 until images.length()) {
                    val imageUrl = images.optString(i)
                    if (imageUrl.isNotEmpty()) {
                        val finalUrl = when {
                            imageUrl.startsWith("http") -> imageUrl
                            imageUrl.startsWith("//") -> "https:$imageUrl"
                            // نفضل app.prochan.net على cdn
                            imageUrl.startsWith("/chapters/") -> "https://app.prochan.net$imageUrl"
                            else -> "https://cdn3.prochan.net$imageUrl"
                        }
                        // نضيف فقط الصور من app.prochan.net إن وجدت
                        if (finalUrl.contains("app.prochan.net")) {
                            pages.add(finalUrl)
                        }
                    }
                }
                
                // إذا لم نجد صور من app.prochan.net، نستخدم أي صور متاحة
                if (pages.isEmpty()) {
                    for (i in 0 until images.length()) {
                        val imageUrl = images.optString(i)
                        if (imageUrl.isNotEmpty()) {
                            val finalUrl = when {
                                imageUrl.startsWith("http") -> imageUrl
                                imageUrl.startsWith("//") -> "https:$imageUrl"
                                else -> "https://cdn3.prochan.net$imageUrl"
                            }
                            pages.add(finalUrl)
                        }
                    }
                }
            }
        }
        
        // الطريقة 2: استخراج من HTML - نفضل app.prochan.net على cdn2/cdn3
        if (pages.isEmpty()) {
            val mobileImages = mutableListOf<String>()
            val desktopImages = mutableListOf<String>()
            val cdnImages = mutableListOf<String>()
            
            // نجمع كل الصور حسب المصدر
            docs.select("img").forEach { img ->
                val src = img.attr("src")
                val dataSrc = img.attr("data-src")
                val actualSrc = src.takeIf { it.isNotEmpty() } ?: dataSrc
                
                if (actualSrc.isEmpty() || !actualSrc.contains("prochan.net")) return@forEach
                if (actualSrc.contains("series-cards") || actualSrc.contains("image_series")) return@forEach
                
                val classes = img.className()
                
                when {
                    // صور mobile من app.prochan.net (الأولوية الأعلى)
                    actualSrc.contains("app.prochan.net/chapters") && 
                    ("md:hidden" in classes || "block md:hidden" in classes) -> {
                        mobileImages.add(actualSrc)
                    }
                    // صور desktop من app.prochan.net
                    actualSrc.contains("app.prochan.net/chapters") -> {
                        desktopImages.add(actualSrc)
                    }
                    // صور cdn (آخر خيار)
                    actualSrc.contains("/chapters/") -> {
                        cdnImages.add(actualSrc)
                    }
                }
            }
            
            // نفضل mobile، ثم desktop، ثم cdn
            pages.addAll(mobileImages.ifEmpty { desktopImages.ifEmpty { cdnImages } })
        }
        
        // الطريقة 3: محاولة البحث في script tags (آخر محاولة) - نفضل app.prochan.net
        if (pages.isEmpty()) {
            val foundUrls = mutableSetOf<String>()
            
            docs.select("script:not([src])").forEach { script ->
                val scriptContent = script.html()
                
                // البحث عن URLs من app.prochan.net أولاً
                val appPattern = Regex("""https?://app\.prochan\.net/chapters/[^\s"']+\.avif(?:\?[^\s"']*)?""")
                appPattern.findAll(scriptContent).forEach { match ->
                    foundUrls.add(match.value)
                }
                
                // إذا لم نجد، ابحث عن cdn2/cdn3
                if (foundUrls.isEmpty()) {
                    val cdnPattern = Regex("""https?://(?:cdn2|cdn3)\.prochan\.net/[^\s"']+\.avif(?:\?[^\s"']*)?""")
                    cdnPattern.findAll(scriptContent).forEach { match ->
                        foundUrls.add(match.value)
                    }
                }
            }
            
            pages.addAll(foundUrls)
        }
        
        // تحويل إلى MangaPage
        return pages.mapIndexed { index, url ->
            MangaPage(
                id = generateUid("$url#$index"),
                url = if (url.startsWith("http")) url else "https:$url",
                preview = null,
                source = source,
            )
        }
    }
}
