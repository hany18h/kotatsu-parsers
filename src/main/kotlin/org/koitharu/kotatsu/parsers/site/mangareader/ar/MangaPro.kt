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
    
    override suspend fun getDetails(manga: Manga): Manga {
        val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        val title = docs.selectFirst("h1")?.text() ?: manga.title
        val description = docs.select("p.whitespace-pre-line").firstOrNull()?.text()
        
        // استخراج الفصول - المحدد الصحيح
        val chapters = docs.select("a[href*='/series/'][href*='/']")
            .filter { link ->
                // التأكد من وجود عنوان الفصل
                val chapterText = link.selectFirst("span.break-words")?.text() ?: ""
                chapterText.isNotEmpty() && (
                    chapterText.contains("الفصل", ignoreCase = true) ||
                    chapterText.matches(Regex("\\d+"))
                )
            }
            .mapNotNull { link ->
                val href = link.attr("href")
                if (href.isEmpty() || !href.contains("/series/")) return@mapNotNull null
                
                // التحقق من تنسيق URL صحيح
                val urlParts = href.split("/").filter { it.isNotEmpty() }
                if (urlParts.size < 5) return@mapNotNull null
                
                val chapterTitle = link.selectFirst("span.break-words")?.text() 
                    ?: return@mapNotNull null
                
                // استخراج رقم الفصل
                val chapterNumber = Regex("\\d+").findAll(chapterTitle)
                    .lastOrNull()?.value?.toFloatOrNull() ?: 0f
                
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
        
        // 1. صور app.prochan.net (mobile/desktop)
        docs.select("img[src*='app.prochan.net']").forEach { img ->
            val src = img.attr("src")
            if (src.isNotEmpty()) {
                val fullUrl = if (src.startsWith("http")) src else "https:$src"
                if (pages.none { it.url.substringBefore("?") == fullUrl.substringBefore("?") }) {
                    pages.add(
                        MangaPage(
                            id = generateUid("$fullUrl#${pages.size}"),
                            url = fullUrl,
                            preview = null,
                            source = source,
                        )
                    )
                }
            }
        }
        
        // 2. صور cdn1.prochan.net (مع token)
        docs.select("img[src*='cdn1.prochan.net'], img[src*='cdn2.prochan.net'], img[src*='cdn3.prochan.net']").forEach { img ->
            val src = img.attr("src")
            if (src.isNotEmpty()) {
                val fullUrl = if (src.startsWith("http")) src else "https:$src"
                if (pages.none { it.url.substringBefore("?") == fullUrl.substringBefore("?") }) {
                    pages.add(
                        MangaPage(
                            id = generateUid("$fullUrl#${pages.size}"),
                            url = fullUrl,
                            preview = null,
                            source = source,
                        )
                    )
                }
            }
        }
        
        // 3. Fallback: محاولة استخراج من JSON إذا لم نجد صور
        if (pages.isEmpty()) {
            val nextData = extractNextData(docs)
            val props = nextData?.optJSONObject("props")?.optJSONObject("pageProps")
            
            // محاولة appImages أولاً
            val appImages = props?.optJSONArray("appImages")
            if (appImages != null && appImages.length() > 0) {
                for (i in 0 until appImages.length()) {
                    val imgObj = appImages.optJSONObject(i)
                    val mobileUrl = imgObj?.optString("mobile")
                    val desktopUrl = imgObj?.optString("desktop")
                    
                    val finalUrl = mobileUrl?.takeIf { it.isNotEmpty() } ?: desktopUrl
                    if (finalUrl != null && finalUrl.isNotEmpty()) {
                        val fullUrl = if (finalUrl.startsWith("http")) finalUrl else "https:$finalUrl"
                        pages.add(
                            MangaPage(
                                id = generateUid("$fullUrl#$i"),
                                url = fullUrl,
                                preview = null,
                                source = source,
                            )
                        )
                    }
                }
            }
            
            // محاولة images إذا لم نجد appImages
            if (pages.isEmpty()) {
                val images = props?.optJSONArray("images")
                if (images != null) {
                    for (i in 0 until images.length()) {
                        val imageUrl = images.optString(i)
                        if (imageUrl.isNotEmpty()) {
                            val finalUrl = if (imageUrl.startsWith("http")) {
                                imageUrl
                            } else {
                                "https://cdn3.prochan.net$imageUrl"
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
