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
        
        // الطريقة 1: استخراج من __NEXT_DATA__ (JSON)
        val nextData = extractNextData(docs)
        val props = nextData?.optJSONObject("props")?.optJSONObject("pageProps")
        
        // محاولة استخراج من appImages
        val appImages = props?.optJSONArray("appImages")
        if (appImages != null && appImages.length() > 0) {
            for (i in 0 until appImages.length()) {
                val imgObj = appImages.optJSONObject(i)
                // نفضل mobile أولاً للتوافق
                val mobileUrl = imgObj?.optString("mobile")
                val desktopUrl = imgObj?.optString("desktop")
                
                val finalUrl = mobileUrl?.takeIf { it.isNotEmpty() } ?: desktopUrl
                if (finalUrl != null && finalUrl.isNotEmpty()) {
                    val fullUrl = if (finalUrl.startsWith("http")) finalUrl else "https:$finalUrl"
                    pages.add(fullUrl)
                }
            }
        }
        
        // محاولة استخراج من images array
        if (pages.isEmpty()) {
            val images = props?.optJSONArray("images")
            if (images != null && images.length() > 0) {
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
        
        // الطريقة 2: استخراج من HTML مباشرة
        if (pages.isEmpty()) {
            // 1. صور app.prochan.net/chapters (mobile + desktop)
            docs.select("img[src*='app.prochan.net/chapters']").forEach { img ->
                val src = img.attr("src")
                if (src.isNotEmpty()) {
                    pages.add(src)
                }
            }
            
            // 2. صور cdn3.prochan.net (مع tokens)
            docs.select("img[src*='cdn3.prochan.net']").forEach { img ->
                val src = img.attr("src")
                if (src.isNotEmpty()) {
                    pages.add(src)
                }
            }
            
            // 3. صور cdn2.prochan.net
            docs.select("img[src*='cdn2.prochan.net']").forEach { img ->
                val src = img.attr("src")
                if (src.isNotEmpty()) {
                    pages.add(src)
                }
            }
        }
        
        // الطريقة 3: محاولة البحث في كل نصوص الـ script tags عن URLs
        if (pages.isEmpty()) {
            docs.select("script:not([src])").forEach { script ->
                val scriptContent = script.html()
                
                // البحث عن URLs الصور في الـ JavaScript
                val imageUrlPattern = Regex("""https?://(?:app|cdn2|cdn3)\.prochan\.net/[^\s"']+\.(?:avif|jpg|jpeg|png|webp)(?:\?[^\s"']*)?""")
                val matches = imageUrlPattern.findAll(scriptContent)
                
                matches.forEach { match ->
                    pages.add(match.value)
                }
            }
        }
        
        // تحويل إلى MangaPage وإزالة التكرارات
        return pages
            .distinctBy { it.substringBefore("?") }
            .mapIndexed { index, url ->
                MangaPage(
                    id = generateUid("$url#$index"),
                    url = if (url.startsWith("http")) url else "https:$url",
                    preview = null,
                    source = source,
                )
            }
    }
}
