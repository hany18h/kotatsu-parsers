import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import android.util.Base64
import java.net.URLDecoder

class HadessParser {
    
    // 1. استخراج قائمة الفصول
    fun parseChapterList(html: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val doc = Jsoup.parse(html)
        
        // الطريقة 1: من القائمة الرئيسية (الأكثر موثوقية)
        doc.select("div.list-body-hh a.chapter-link").forEach { link ->
            val chapterTitle = link.selectFirst("div.chapter-title")?.text()?.trim() ?: ""
            val chapterUrl = link.attr("abs:href")
            val dateText = link.selectFirst("div.meta-item")?.text()?.trim() ?: ""
            
            if (chapterTitle.isNotEmpty() && chapterUrl.isNotEmpty()) {
                chapters.add(
                    SChapter.create().apply {
                        name = chapterTitle
                        url = chapterUrl
                        date_upload = parseDate(dateText)
                    }
                )
            }
        }
        
        // الطريقة 2: من القائمة المنسدلة (كنسخة احتياطية)
        if (chapters.isEmpty()) {
            doc.select("div.chapter-list ul li.chapter-item a").forEach { link ->
                val chapterTitle = link.text().trim()
                val chapterUrl = link.attr("abs:href")
                
                if (chapterTitle.isNotEmpty() && chapterUrl.isNotEmpty()) {
                    chapters.add(
                        SChapter.create().apply {
                            name = chapterTitle
                            url = chapterUrl
                            date_upload = 0L
                        }
                    )
                }
            }
        }
        
        return chapters
    }
    
    // 2. استخراج صور الفصل
    fun parseChapterImages(html: String): List<Page> {
        val images = mutableListOf<Page>()
        val doc = Jsoup.parse(html)
        
        // الطريقة 1: استخراج الصور المحمية (Base64)
        doc.select("div.protected-image-data").forEachIndexed { index, div ->
            val encodedUrl = div.attr("data-src")
            
            if (encodedUrl.isNotEmpty()) {
                try {
                    // فك تشفير Base64
                    val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                    
                    if (decodedUrl.startsWith("http")) {
                        images.add(Page(index, imageUrl = decodedUrl))
                    }
                } catch (e: Exception) {
                    println("خطأ في فك تشفير الصورة $index: ${e.message}")
                }
            }
        }
        
        // الطريقة 2: استخراج الصور العادية (كنسخة احتياطية)
        if (images.isEmpty()) {
            doc.select("img.preload-image[data-src], img[data-src]").forEachIndexed { index, img ->
                val imageUrl = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
                
                if (imageUrl.startsWith("http")) {
                    images.add(Page(index, imageUrl = imageUrl))
                }
            }
        }
        
        // الطريقة 3: استخراج من figure > img
        if (images.isEmpty()) {
            doc.select("figure.page img").forEachIndexed { index, img ->
                val imageUrl = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
                
                if (imageUrl.startsWith("http")) {
                    images.add(Page(index, imageUrl = imageUrl))
                }
            }
        }
        
        return images
    }
    
    // 3. تحويل التاريخ العربي إلى timestamp
    private fun parseDate(dateText: String): Long {
        return try {
            // مثال: "29 سبتمبر، 2025"
            val arabicMonths = mapOf(
                "يناير" to 1, "فبراير" to 2, "مارس" to 3,
                "أبريل" to 4, "مايو" to 5, "يونيو" to 6,
                "يوليو" to 7, "أغسطس" to 8, "سبتمبر" to 9,
                "أكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12
            )
            
            val parts = dateText.replace("،", "").split(" ")
            if (parts.size >= 3) {
                val day = parts[0].toIntOrNull() ?: 1
                val month = arabicMonths[parts[1]] ?: 1
                val year = parts[2].toIntOrNull() ?: 2025
                
                val calendar = Calendar.getInstance()
                calendar.set(year, month - 1, day)
                calendar.timeInMillis
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }
    
    // 4. استخراج تفاصيل المانجا
    fun parseMangaDetails(html: String): SManga {
        val doc = Jsoup.parse(html)
        
        return SManga.create().apply {
            // العنوان
            title = doc.selectFirst("h1.manga-title, h1.post-title")?.text()?.trim() ?: ""
            
            // الصورة
            thumbnail_url = doc.selectFirst("div.manga-cover img, img.manga-thumbnail")
                ?.attr("abs:src") ?: ""
            
            // الوصف
            description = doc.selectFirst("div.manga-description, div.summary")
                ?.text()?.trim() ?: ""
            
            // المؤلف
            author = doc.selectFirst("div.author-content, span.author")
                ?.text()?.trim() ?: ""
            
            // الفنان
            artist = doc.selectFirst("div.artist-content, span.artist")
                ?.text()?.trim() ?: ""
            
            // الحالة
            status = parseStatus(
                doc.selectFirst("div.post-status span")?.text() ?: ""
            )
            
            // التصنيفات
            genre = doc.select("div.genres a, span.genre")
                .joinToString { it.text().trim() }
        }
    }
    
    private fun parseStatus(statusText: String): Int {
        return when {
            statusText.contains("مستمر", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("مكتمل", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("متوقف", ignoreCase = true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }
}

// Data classes
data class SChapter(
    var name: String = "",
    var url: String = "",
    var date_upload: Long = 0L
) {
    companion object {
        fun create() = SChapter()
    }
}

data class Page(
    val index: Int,
    val imageUrl: String? = null
)

data class SManga(
    var title: String = "",
    var thumbnail_url: String = "",
    var description: String = "",
    var author: String = "",
    var artist: String = "",
    var status: Int = UNKNOWN,
    var genre: String = ""
) {
    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val ON_HIATUS = 6
        
        fun create() = SManga()
    }
}

// مثال على الاستخدام في Extension:
class HadessExtension {
    private val parser = HadessParser()
    
    fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body?.string() ?: ""
        return parser.parseChapterList(html)
    }
    
    fun pageListParse(response: Response): List<Page> {
        val html = response.body?.string() ?: ""
        return parser.parseChapterImages(html)
    }
    
    fun mangaDetailsParse(response: Response): SManga {
        val html = response.body?.string() ?: ""
        return parser.parseMangaDetails(html)
    }
}

// مثال للاختبار:
fun main() {
    val html = """
        <!-- HTML من الموقع -->
    """.trimIndent()
    
    val parser = HadessParser()
    
    // اختبار استخراج الفصول
    val chapters = parser.parseChapterList(html)
    println("عدد الفصول: ${chapters.size}")
    chapters.take(5).forEach {
        println("${it.name} - ${it.url}")
    }
    
    // اختبار استخراج الصور
    val images = parser.parseChapterImages(html)
    println("\nعدد الصور: ${images.size}")
    images.take(5).forEach {
        println("صورة ${it.index}: ${it.imageUrl}")
    }
}
