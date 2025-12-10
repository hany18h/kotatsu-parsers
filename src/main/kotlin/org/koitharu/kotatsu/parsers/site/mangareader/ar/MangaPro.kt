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
    MangaReaderParser(context, MangaParserSource.MANGAPRO, "prochan.pro", pageSize = 20, searchPageSize = 10) {
    
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
        
        // استخراج الفصول - نستهدف الروابط التي تحتوي على span.break-words
        val chapters = docs.select("a[href^='/series/']")
            .filter { link ->
                // التأكد أن الرابط يحتوي على span.break-words (عنوان الفصل)
                link.selectFirst("span.break-words") != null
            }
            .mapNotNull { link ->
                val href = link.attr("href")
                if (href.isEmpty()) return@mapNotNull null
                
                // التحقق من صحة الرابط (يجب أن يحتوي على ID الفصل)
                val urlParts = href.split("/")
                if (urlParts.size < 6) return@mapNotNull null
                
                val chapterTitle = link.selectFirst("span.break-words")?.text() 
                    ?: return@mapNotNull null
                
                // استخراج رقم الفصل من العنوان
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
        val pages = mutableListOf<MangaPage>()
        
        // 1. صور app.prochan.pro
        docs.select("img[src*='app.prochan.pro']").forEach { img ->
            val src = img.attr("src")
            if (src.isNotEmpty()) {
                pages.add(
                    MangaPage(
                        id = generateUid("$src#${pages.size}"),
                        url = if (src.startsWith("http")) src else "https:$src",
                        preview = null,
                        source = source,
                    )
                )
            }
        }
        
        // 2. صور cdn2/cdn3.prochan.pro
        docs.select("img[src*='cdn2.prochan.pro'], img[src*='cdn3.prochan.pro']").forEach { img ->
            val src = img.attr("src")
            if (src.isNotEmpty() && pages.none { it.url.contains(src.substringBefore("?")) }) {
                pages.add(
                    MangaPage(
                        id = generateUid("$src#${pages.size}"),
                        url = if (src.startsWith("http")) src else "https:$src",
                        preview = null,
                        source = source,
                    )
                )
            }
        }
        
        // 3. Fallback من JSON
        if (pages.isEmpty()) {
            val nextData = extractNextData(docs)
            val props = nextData?.optJSONObject("props")?.optJSONObject("pageProps")
            
            val appImages = props?.optJSONArray("appImages")
            if (appImages != null) {
                for (i in 0 until appImages.length()) {
                    val imgObj = appImages.optJSONObject(i)
                    val mobileUrl = imgObj?.optString("mobile")
                    val desktopUrl = imgObj?.optString("desktop")
                    
                    val finalUrl = mobileUrl?.takeIf { it.isNotEmpty() } ?: desktopUrl
                    if (finalUrl != null && finalUrl.isNotEmpty()) {
                        pages.add(
                            MangaPage(
                                id = generateUid("$finalUrl#$i"),
                                url = if (finalUrl.startsWith("http")) finalUrl else "https:$finalUrl",
                                preview = null,
                                source = source,
                            )
                        )
                    }
                }
            }
            
            if (pages.isEmpty()) {
                val images = props?.optJSONArray("images")
                if (images != null) {
                    for (i in 0 until images.length()) {
                        val imageUrl = images.optString(i)
                        if (imageUrl.isNotEmpty()) {
                            val finalUrl = if (imageUrl.startsWith("http")) {
                                imageUrl
                            } else {
                                "https://cdn3.prochan.pro$imageUrl"
                            }
                            
                            pages.add(
                                MangaPage(
                                    id = generateUid("$finalUrl#$i"),
                                    url = finalUrl,
                                    preview = null,
                                    source = source,
                                )
                            )
                        }
                    }
                }
            }
        }
        
        return pages.distinctBy { it.url.substringBefore("?") }
    }
}
