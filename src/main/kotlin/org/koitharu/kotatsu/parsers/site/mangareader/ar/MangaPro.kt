package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAPRO", "MangaPro", "ar")
internal class MangaPro(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANGAPRO, "prochan.pro", pageSize = 20, searchPageSize = 10) {
    
    override val listUrl = "/series"
    
    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )
    
    // استخراج البيانات من Next.js JSON المدمج في الصفحة
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
        // محاولة استخراج البيانات من Next.js data
        val nextData = extractNextData(docs)
        
        return if (nextData != null) {
            parseMangaListFromJson(nextData)
        } else {
            // Fallback للطريقة التقليدية
            parseMangaListFromHtml(docs)
        }
    }
    
    private fun parseMangaListFromJson(nextData: JSONObject): List<Manga> {
        val results = mutableListOf<Manga>()
        
        try {
            // البحث في props.pageProps عن البيانات
            val props = nextData.optJSONObject("props")?.optJSONObject("pageProps")
            
            // قد تكون البيانات في مكان مختلف حسب الصفحة
            // نحاول عدة مسارات ممكنة
            val seriesData = props?.optJSONArray("series") 
                ?: props?.optJSONArray("data")
                ?: props?.optJSONObject("initialData")?.optJSONArray("series")
            
            seriesData?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    
                    val id = item.optString("id")
                    val slug = item.optString("slug")
                    val type = item.optString("type", "manhwa")
                    val title = item.optString("title")
                    
                    if (id.isNullOrEmpty() || slug.isNullOrEmpty() || title.isNullOrEmpty()) continue
                    
                    val url = "/series/$type/$id/$slug"
                    
                    // استخراج الصورة
                    val coverUrl = item.optString("image_series")
                        .takeIf { it.isNotEmpty() }
                        ?.let { if (it.startsWith("http")) it else "https://cdn3.prochan.pro$it" }
                    
                    results.add(
                        Manga(
                            id = generateUid(url),
                            url = url,
                            title = title,
                            altTitles = emptySet(),
                            publicUrl = "https://$domain$url",
                            rating = RATING_UNKNOWN,
                            contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                            coverUrl = coverUrl,
                            tags = emptySet(),
                            state = null,
                            authors = emptySet(),
                            source = source,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // في حالة الفشل، نعود للطريقة التقليدية
        }
        
        return results
    }
    
    private fun parseMangaListFromHtml(docs: Document): List<Manga> {
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
        
        // استخراج العنوان والوصف
        val title = docs.selectFirst("h1")?.text() ?: manga.title
        val description = docs.select("p.text-muted-foreground, div.description").firstOrNull()?.text()
        
        // استخراج الفصول من البنية الصحيحة
        val chapters = docs.select("a[href^='/series/'].hover\\:underline")
            .mapNotNull { link ->
                val href = link.attr("href")
                if (href.isEmpty()) return@mapNotNull null
                
                // التأكد أن الرابط يحتوي على رقم الفصل
                val urlParts = href.split("/")
                if (urlParts.size < 6) return@mapNotNull null
                
                // استخراج النص من span
                val chapterTitle = link.selectFirst("span.break-words")?.text() 
                    ?: return@mapNotNull null
                
                // استخراج رقم الفصل
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
    
    private fun parseDetailsFromJson(manga: Manga, nextData: JSONObject, docs: Document): Manga {
        try {
            val props = nextData.optJSONObject("props")?.optJSONObject("pageProps")
            val seriesData = props?.optJSONObject("series") ?: props?.optJSONObject("data")
            
            if (seriesData != null) {
                val title = seriesData.optString("title", manga.title)
                val description = seriesData.optString("description")
                    .takeIf { it.isNotEmpty() }
                
                // استخراج الفصول
                val chaptersArray = seriesData.optJSONArray("chapters") 
                    ?: props?.optJSONArray("chapters")
                
                val chapters = chaptersArray?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        val chapter = array.optJSONObject(i) ?: return@mapNotNull null
                        
                        val chapterId = chapter.optString("id")
                        val chapterNumber = chapter.optString("number")
                        val chapterSlug = chapter.optString("slug", chapterNumber)
                        
                        if (chapterId.isEmpty() || chapterNumber.isEmpty()) return@mapNotNull null
                        
                        // استخراج الـ type و slug من URL الحالي
                        val urlParts = manga.url.split("/")
                        val type = urlParts.getOrNull(2) ?: "manhwa"
                        val seriesId = urlParts.getOrNull(3) ?: ""
                        val seriesSlug = urlParts.getOrNull(4) ?: ""
                        
                        val chapterUrl = "/series/$type/$seriesId/$seriesSlug/$chapterId/$chapterSlug"
                        
                        MangaChapter(
                            id = generateUid(chapterUrl),
                            title = chapter.optString("title")
                                .takeIf { it.isNotEmpty() }
                                ?: "الفصل $chapterNumber",
                            url = chapterUrl,
                            number = chapterNumber.toFloatOrNull() ?: i.toFloat() + 1f,
                            volume = 0,
                            scanlator = null,
                            uploadDate = parseChapterDate(chapter.optString("created_at")),
                            branch = null,
                            source = source,
                        )
                    }
                } ?: emptyList()
                
                // استخراج التصنيفات
                val tagsArray = seriesData.optJSONArray("tags")
                val tags = tagsArray?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        val tag = array.optJSONObject(i) ?: return@mapNotNull null
                        val tagName = tag.optString("name")
                        if (tagName.isEmpty()) null else MangaTag(
                            key = tag.optString("id", tagName),
                            title = tagName,
                            source = source,
                        )
                    }.toSet()
                } ?: emptySet()
                
                // استخراج المؤلفين
                val authorsArray = seriesData.optJSONArray("authors")
                val authors = authorsArray?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        array.optString(i).takeIf { it.isNotEmpty() }
                    }.toSet()
                } ?: emptySet()
                
                // حالة النشر
                val state = when (seriesData.optString("status")) {
                    "ongoing", "مستمر" -> MangaState.ONGOING
                    "completed", "مكتمل" -> MangaState.FINISHED
                    else -> null
                }
                
                return manga.copy(
                    title = title,
                    description = description,
                    chapters = chapters,
                    tags = tags,
                    authors = authors,
                    state = state,
                )
            }
        } catch (e: Exception) {
            // في حالة الفشل، نحاول الطريقة التقليدية
        }
        
        return parseDetailsFromHtml(manga, docs)
    }
    
    private fun parseDetailsFromHtml(manga: Manga, docs: Document): Manga {
        val title = docs.selectFirst("h1")?.text() ?: manga.title
        val description = docs.selectFirst("p.text-sm, div.description, div[class*='description']")
            ?.text()
        
        // محاولة استخراج الفصول من HTML
        val chapters = docs.select("a[href*='/series/'][href*='/chapter'], a[href*='/ch/']")
            .mapIndexedNotNull { index, element ->
                val url = element.attrAsRelativeUrlOrNull("href") ?: return@mapIndexedNotNull null
                
                MangaChapter(
                    id = generateUid(url),
                    title = element.text().ifEmpty { "الفصل ${index + 1}" },
                    url = url,
                    number = (index + 1).toFloat(),
                    volume = 0,
                    scanlator = null,
                    uploadDate = 0,
                    branch = null,
                    source = source,
                )
            }.reversed()
        
        return manga.copy(
            title = title,
            description = description,
            chapters = chapters.ifEmpty { null },
        )
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val docs = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        
        // محاولة استخراج البيانات من Next.js
        val nextData = extractNextData(docs)
        
        return if (nextData != null) {
            parsePagesFromJson(chapter, nextData)
        } else {
            parsePagesFromHtml(chapter, docs)
        }
    }
    
    private fun parsePagesFromJson(chapter: MangaChapter, nextData: JSONObject): List<MangaPage> {
        try {
            val props = nextData.optJSONObject("props")?.optJSONObject("pageProps")
            
            // البحث عن الصور في عدة أماكن محتملة
            val imagesArray = props?.optJSONArray("images")
                ?: props?.optJSONArray("pages")
                ?: props?.optJSONObject("chapter")?.optJSONArray("images")
            
            if (imagesArray != null) {
                return (0 until imagesArray.length()).map { i ->
                    val imageUrl = when (val item = imagesArray.get(i)) {
                        is String -> item
                        is JSONObject -> item.optString("url") 
                            ?: item.optString("image")
                            ?: item.optString("src")
                        else -> ""
                    }
                    
                    val finalUrl = if (imageUrl.startsWith("http")) {
                        imageUrl
                    } else {
                        "https://cdn3.prochan.pro$imageUrl"
                    }
                    
                    MangaPage(
                        id = generateUid("$finalUrl#$i"),
                        url = finalUrl,
                        preview = null,
                        source = source,
                    )
                }
            }
        } catch (e: Exception) {
            // في حالة الفشل
        }
        
        return emptyList()
    }
    
    private fun parsePagesFromHtml(chapter: MangaChapter, docs: Document): List<MangaPage> {
        return docs.select("img[src*='cdn'], img[data-src*='cdn']")
            .mapIndexedNotNull { i, img ->
                val url = img.attr("src").ifEmpty { img.attr("data-src") }
                if (url.isEmpty()) return@mapIndexedNotNull null
                
                val finalUrl = if (url.startsWith("http")) url else "https://cdn3.prochan.pro$url"
                
                MangaPage(
                    id = generateUid("$finalUrl#$i"),
                    url = finalUrl,
                    preview = null,
                    source = source,
                )
            }
    }
    
    private fun parseChapterDate(dateString: String): Long {
        if (dateString.isEmpty()) return 0L
        
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
