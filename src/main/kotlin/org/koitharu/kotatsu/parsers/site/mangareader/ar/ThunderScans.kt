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
        
        // If both failed, return empty
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
        
        val jsonText = scriptWithJson
            .substringAfter("ts_reader.run(")
            .substringBeforeLast(");")
            .trim()
            .takeIf { it.startsWith("{") && it.endsWith("}") }
            ?: return emptyList()
        
        return try {
            val root = JSONObject(jsonText)
            val sources = root.optJSONArray("sources") ?: return emptyList()
            
            if (sources.length() == 0) return emptyList()
            
            val imagesArray = sources.getJSONObject(0).optJSONArray("images") 
                ?: return emptyList()
            
            (0 until imagesArray.length()).mapNotNull { idx ->
                val imageUrl = imagesArray.optString(idx).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
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
        val images = doc.select("div.reader-area#readerarea img.ts-main-image")
        
        return images.mapNotNull { img ->
            val imageUrl = img.attr("src").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }
}
