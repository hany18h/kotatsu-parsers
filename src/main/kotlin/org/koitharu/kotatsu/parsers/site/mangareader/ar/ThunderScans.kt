package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.domain
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.requireElementById
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow

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
    
    override val selectTestScript = 
        "script#ts-reader, script:contains(ts_reader.run), script:contains(ts_reader)"
    
    override val selectPage = "div#readerarea img, div.reading-content img"
    
    /**
     * استخراج الصور من JSON داخل script tag
     * الموقع يستخدم ts_reader.run({...}) لتحميل الصور
     */
    override suspend fun getPages(chapter: org.koitharu.kotatsu.parsers.model.MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        
        // البحث عن script الذي يحتوي على ts_reader.run
        val scripts = doc.select("script")
        var jsonText: String? = null
        
        for (script in scripts) {
            val scriptContent = script.html()
            if (scriptContent.contains("ts_reader.run")) {
                // استخراج JSON من داخل ts_reader.run({...})
                val startIndex = scriptContent.indexOf("ts_reader.run(") + "ts_reader.run(".length
                val endIndex = scriptContent.lastIndexOf(");")
                
                if (startIndex > 0 && endIndex > startIndex) {
                    jsonText = scriptContent.substring(startIndex, endIndex).trim()
                    break
                }
            }
        }
        
        if (jsonText.isNullOrEmpty()) {
            // إذا لم نجد JSON، نحاول الطريقة القديمة
            return super.getPages(chapter)
        }
        
        return try {
            // تحويل JSON إلى كائن
            val jsonObject = JSONObject(jsonText)
            val sources = jsonObject.optJSONArray("sources")
            
            if (sources == null || sources.length() == 0) {
                return emptyList()
            }
            
            // الحصول على أول source (عادة Server 1)
            val firstSource = sources.getJSONObject(0)
            val images = firstSource.optJSONArray("images")
            
            if (images == null || images.length() == 0) {
                return emptyList()
            }
            
            // تحويل الصور إلى قائمة MangaPage
            val pages = ArrayList<MangaPage>(images.length())
            for (i in 0 until images.length()) {
                val imageUrl = images.optString(i)
                if (imageUrl.isNotBlank()) {
                    pages.add(
                        MangaPage(
                            id = generateUid(imageUrl),
                            url = imageUrl,
                            preview = null,
                            source = source
                        )
                    )
                }
            }
            
            pages
            
        } catch (e: Exception) {
            // في حالة حدوث خطأ، نحاول الطريقة القديمة
            e.printStackTrace()
            super.getPages(chapter)
        }
    }
    
    /**
     * دالة مساعدة لتوليد ID فريد للصفحة
     */
    private fun generateUid(url: String): Long {
        return url.hashCode().toLong()
    }
}
