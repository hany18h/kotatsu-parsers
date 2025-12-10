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
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val docs = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        
        // استخراج __NEXT_DATA__
        val nextDataScript = docs.selectFirst("script#__NEXT_DATA__")
        if (nextDataScript != null) {
            try {
                val jsonText = nextDataScript.html()
                val jsonObj = JSONObject(jsonText)
                val props = jsonObj
                    .optJSONObject("props")
                    ?.optJSONObject("pageProps")
                
                // الطريقة 1: استخدام appImages (الأفضل - mobile فقط)
                val appImages = props?.optJSONArray("appImages")
                if (appImages != null && appImages.length() > 0) {
                    val pages = mutableListOf<MangaPage>()
                    for (i in 0 until appImages.length()) {
                        val imgObj = appImages.optJSONObject(i) ?: continue
                        
                        // نأخذ mobile version فقط (أكثر استقراراً)
                        val mobileUrl = imgObj.optString("mobile")
                        if (!mobileUrl.isNullOrEmpty()) {
                            val fullUrl = when {
                                mobileUrl.startsWith("http") -> mobileUrl
                                mobileUrl.startsWith("//") -> "https:$mobileUrl"
                                else -> "https://app.prochan.net$mobileUrl"
                            }
                            
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
                    
                    if (pages.isNotEmpty()) {
                        return pages
                    }
                }
                
                // الطريقة 2: استخدام images array (fallback)
                val images = props?.optJSONArray("images")
                if (images != null && images.length() > 0) {
                    val pages = mutableListOf<MangaPage>()
                    for (i in 0 until images.length()) {
                        val imageUrl = images.optString(i)
                        if (imageUrl.isNotEmpty()) {
                            val fullUrl = when {
                                imageUrl.startsWith("http") -> imageUrl
                                imageUrl.startsWith("//") -> "https:$imageUrl"
                                imageUrl.startsWith("/") -> "https://app.prochan.net$imageUrl"
                                else -> imageUrl
                            }
                            
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
                    
                    if (pages.isNotEmpty()) {
                        return pages
                    }
                }
            } catch (e: Exception) {
                // تجاهل الأخطاء ونستمر للطرق الأخرى
            }
        }
        
        // الطريقة 3: استخراج من HTML (آخر محاولة)
        val mobileImages = mutableListOf<String>()
        
        docs.select("img[src*='/chapters/'], img[data-src*='/chapters/']").forEach { img ->
            val src = img.attr("src").ifEmpty { img.attr("data-src") }
            if (src.isEmpty()) return@forEach
            
            // تفضيل mobile images
            val classes = img.className()
            if ("mobile" in classes || "md:hidden" in classes) {
                val fullUrl = when {
                    src.startsWith("http") -> src
                    src.startsWith("//") -> "https:$src"
                    else -> "https://app.prochan.net$src"
                }
                mobileImages.add(fullUrl)
            }
        }
        
        // إذا لم نجد mobile، نأخذ أي صور
        if (mobileImages.isEmpty()) {
            docs.select("img[src*='/chapters/'], img[data-src*='/chapters/']").forEach { img ->
                val src = img.attr("src").ifEmpty { img.attr("data-src") }
                if (src.isNotEmpty() && !src.contains("series-cards")) {
                    val fullUrl = when {
                        src.startsWith("http") -> src
                        src.startsWith("//") -> "https:$src"
                        else -> "https://app.prochan.net$src"
                    }
                    mobileImages.add(fullUrl)
                }
            }
        }
        
        return mobileImages.distinct().mapIndexed { index, url ->
            MangaPage(
                id = generateUid("$url#$index"),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
}
