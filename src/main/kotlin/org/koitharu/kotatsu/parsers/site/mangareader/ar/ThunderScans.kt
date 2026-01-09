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
        val doc = webClient.httpGet(chapterUrl).parseHtml()
        
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
        
        // If all methods failed, return empty
        return emptyList()
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
            "div#readerarea img",
            "div.reader-area img",
            "#readerarea img[src]"
        )
        
        for (selector in selectors) {
            val images = doc.select(selector)
            if (images.isNotEmpty()) {
                val pages = images.mapNotNull { img ->
                    // Try both src and data-src attributes
                    val imageUrl = img.attr("src")
                        .ifBlank { img.attr("data-src") }
                        .ifBlank { img.attr("data-lazy-src") }
                        .takeIf { it.isNotBlank() }
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
            Pair("const pages = ", ";")
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
            afterPrefix.substringBefore(suffix)
        } else {
            afterPrefix
        }.trim()
        
        // Validate it looks like JSON
        return jsonText.takeIf { 
            (it.startsWith("{") && it.endsWith("}")) || 
            (it.startsWith("[") && it.endsWith("]"))
        }
    }
}
