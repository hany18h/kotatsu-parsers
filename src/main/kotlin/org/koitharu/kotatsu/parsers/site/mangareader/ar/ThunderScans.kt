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
        "lavatoons.com",
        pageSize = 20,
        searchPageSize = 10,
    ) {
    
    override val isNetShieldProtected = true
    
    override val selectChapter = "div.eplister#chapterlist li"
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl).parseHtml()
        
        // البحث عن script الذي يحتوي على ts_reader.run
        val scripts = doc.select("script")
        
        for (script in scripts) {
            val scriptContent = script.html()
            
            if (scriptContent.contains("ts_reader.run")) {
                try {
                    // استخراج JSON من ts_reader.run({...})
                    val startIdx = scriptContent.indexOf("ts_reader.run(") + 14
                    val endIdx = scriptContent.indexOf("});", startIdx)
                    
                    if (startIdx > 14 && endIdx > startIdx) {
                        val jsonText = scriptContent.substring(startIdx, endIdx + 1)
                        val json = JSONObject(jsonText)
                        val sources = json.getJSONArray("sources")
                        
                        if (sources.length() > 0) {
                            val images = sources.getJSONObject(0).getJSONArray("images")
                            val pages = ArrayList<MangaPage>(images.length())
                            
                            for (i in 0 until images.length()) {
                                val url = images.getString(i)
                                pages.add(
                                    MangaPage(
                                        id = generateUid(url),
                                        url = url,
                                        preview = null,
                                        source = source,
                                    )
                                )
                            }
                            
                            return pages
                        }
                    }
                } catch (e: Exception) {
                    // إذا فشل parsing، نحاول الطريقة القديمة
                }
            }
        }
        
        // fallback: الطريقة القديمة
        return doc.select("div#readerarea img").map { img ->
            val url = img.src() ?: img.attr("data-src")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
}
