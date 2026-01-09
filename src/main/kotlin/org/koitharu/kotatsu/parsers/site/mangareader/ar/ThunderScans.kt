package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.network.UserAgents

@MangaSourceParser("LAVATOONS", "Lavatoons", "ar", ContentType.MANGA)
internal class Lavatoons(context: MangaLoaderContext) :
    MangaReaderParser(
        context,
        MangaParserSource.LAVATOONS,
        "lavascans.com",
        pageSize = 20,
        searchPageSize = 10,
    ) {
    
    override val isNetShieldProtected = true
    override val selectChapter = "div.eplister#chapterlist li"
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        
        // استخدام httpGet بدون cache للحصول على محتوى جديد دائماً
        val doc = webClient.httpGet(chapterUrl) {
            // إزالة If-Modified-Since header لتجنب 304 response
            headers.remove("If-Modified-Since")
            // إضافة Cache-Control لفرض طلب جديد
            header("Cache-Control", "no-cache")
            header("Pragma", "no-cache")
        }.parseHtml()
        
        // ===== METHOD 1: Try JSON (ts_reader.run) =====
        val pagesFromJson = tryGetPagesFromJson(doc)
        if (pagesFromJson.isNotEmpty()) {
            return pagesFromJson
        }
        
        // ===== METHOD 2: Try HTML <img> tags =====
        val pagesFromHtml = tryGetPagesFromHtml(doc)
        if (pagesFromHtml.isNotEmpty()) {
            return pagesFromHtml
        }
        
        // ===== METHOD 3: Try alternative JSON structure =====
        val pagesFromAltJson = tryGetPagesFromAlternativeJson(doc)
        if (pagesFromAltJson.isNotEmpty()) {
            return pagesFromAltJson
        }
        
        // If all methods failed, throw exception for debugging
        throw IllegalStateException("Failed to extract pages from chapter: $chapterUrl")
    }
    
    /**
     * Extract pages from JSON in ts_reader.run({...})
     */
    private fun tryGetPagesFromJson(doc: org.jsoup.nodes.Document): List<MangaPage> {
        val scriptWithJson = doc.select("script")
            .map { it.html() }
            .firstOrNull { it.contains("ts_reader.run") }
            ?: return emptyList()
        
        // Extract JSON text with better pattern matching
        val jsonText = extractJsonFromScript(scriptWithJson, "ts_reader.run(", ");")
            ?: return emptyList()
        
        return try {
            val root = JSONObject(jsonText)
            val sources = root.optJSONArray("sources") ?: return emptyList()
            
            if (sources.length() == 0) return emptyList()
            
            val firstSource = sources.getJSONObject(0)
            val imagesArray = firstSource.optJSONArray("images") 
                ?: return emptyList()
            
            (0 until imagesArray.length()).mapNotNull { idx ->
                val imageUrl = imagesArray.optString(idx)
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl.toAbsoluteUrl(domain),
                    preview = null,
                    source = source,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Extract pages from <img> tags inside #readerarea
     */
    private fun tryGetPagesFromHtml(doc: org.jsoup.nodes.Document): List<MangaPage> {
        // Try multiple selectors for broader compatibility
        val selectors = listOf(
            "div.reader-area#readerarea img.ts-main-image",
            "div#readerarea img[src]",
            "div.reader-area img[src]",
            "#readerarea img",
            "div.reading-content img",
            "div[id*=reader] img"
        )
        
        for (selector in selectors) {
            val images = doc.select(selector)
            if (images.isNotEmpty()) {
                val pages = images.mapNotNull { img ->
                    // Try both src and data-src attributes
                    val imageUrl = img.attr("src")
                        .ifBlank { img.attr("data-src") }
                        .ifBlank { img.attr("data-lazy-src") }
                        .ifBlank { img.attr("data-original") }
                        .takeIf { it.isNotBlank() && !it.contains("data:image") }
                        ?: return@mapNotNull null
                    
                    MangaPage(
                        id = generateUid(imageUrl),
                        url = imageUrl.toAbsoluteUrl(domain),
                        preview = null,
                        source = source,
                    )
                }
                
                if (pages.isNotEmpty()) {
                    return pages
                }
            }
        }
        
        return emptyList()
    }
    
    /**
     * Try alternative JSON structures that some MangaReader sites use
     */
    private fun tryGetPagesFromAlternativeJson(doc: org.jsoup.nodes.Document): List<MangaPage> {
        val scripts = doc.select("script")
            .map { it.html() }
        
        // Look for common variable patterns
        val patterns = listOf(
            Pair("var images = ", ";"),
            Pair("const images = ", ";"),
            Pair("var pages = ", ";"),
            Pair("const pages = ", ";"),
            Pair("let images = ", ";"),
            Pair("let pages = ", ";")
        )
        
        for (script in scripts) {
            for ((prefix, suffix) in patterns) {
                if (!script.contains(prefix)) continue
                
                val jsonText = extractJsonFromScript(script, prefix, suffix)
                    ?: continue
                
                try {
                    val imagesArray = org.json.JSONArray(jsonText)
                    if (imagesArray.length() > 0) {
                        return (0 until imagesArray.length()).mapNotNull { idx ->
                            val imageUrl = when (val item = imagesArray.get(idx)) {
                                is String -> item
                                is JSONObject -> item.optString("url")
                                    .ifBlank { item.optString("src") }
                                else -> null
                            }?.takeIf { it.isNotBlank() }
                                ?: return@mapNotNull null
                            
                            MangaPage(
                                id = generateUid(imageUrl),
                                url = imageUrl.toAbsoluteUrl(domain),
                                preview = null,
                                source = source,
                            )
                        }
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }
        
        return emptyList()
    }
    
    /**
     * Helper function to extract JSON from script tags
     */
    private fun extractJsonFromScript(
        script: String,
        prefix: String,
        suffix: String
    ): String? {
        if (!script.contains(prefix)) return null
        
        val afterPrefix = script.substringAfter(prefix)
        val jsonText = if (suffix.isNotEmpty()) {
            // Handle multiple possible endings
            var text = afterPrefix
            for (end in listOf(suffix, "\n", "\r")) {
                if (text.contains(end)) {
                    text = text.substringBefore(end)
                    break
                }
            }
            text
        } else {
            afterPrefix
        }.trim()
        
        // Remove trailing comma or semicolon if present
        val cleaned = jsonText.trimEnd(',', ';')
        
        // Validate it looks like JSON
        return cleaned.takeIf { 
            (it.startsWith("{") && it.endsWith("}")) || 
            (it.startsWith("[") && it.endsWith("]"))
        }
    }
}
