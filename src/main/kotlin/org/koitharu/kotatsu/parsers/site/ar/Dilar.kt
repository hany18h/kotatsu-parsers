package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dilar - موقع مانجا عربي (API-based)
 * 
 * ملاحظة: هذا الموقع يستخدم API مخصص مع تشفير
 * نستخدم MangaReaderParser كـ base ونتجاوز الدوال المطلوبة
 */
@MangaSourceParser("DILAR", "Dilar", "ar")
internal class Dilar(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.DILAR, "dilar.tube", pageSize = 20, searchPageSize = 10) {

    override val listUrl = "/api/releases"
    override val datePattern = "yyyy-MM-dd'T'HH:mm:ss"

    // تجاوز دالة القائمة الرئيسية
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://$domain/api/releases?page=$page"
        val json = webClient.httpGet(url).parseJson()

        val releases = json.getJSONArray("releases")
        val mangaMap = mutableMapOf<Int, Manga>()

        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val manga = release.optJSONObject("manga") ?: continue
            
            // تخطي الروايات
            if (manga.optBoolean("is_novel", false)) continue

            val mangaId = manga.getInt("id")
            if (mangaMap.containsKey(mangaId)) continue

            val relUrl = "/api/mangas/$mangaId"
            mangaMap[mangaId] = Manga(
                id = generateUid(relUrl),
                url = relUrl,
                publicUrl = "https://$domain/mangas/$mangaId",
                title = manga.getString("title"),
                altTitles = emptySet(),
                coverUrl = "https://$domain/uploads/manga/cover/$mangaId/${manga.optString("cover")}",
                rating = manga.optString("rating", "0").toFloatOrNull()?.div(10) ?: RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE,
            )
        }

        return mangaMap.values.toList()
    }

    // تجاوز دالة التفاصيل - مع معالجة محسنة للأخطاء
    override suspend fun getDetails(manga: Manga): Manga {
        val infoUrl = "https://$domain${manga.url}"
        val chaptersUrl = "https://$domain${manga.url}/releases"

        // جلب معلومات المانجا
        val infoJson = webClient.httpGet(infoUrl).parseJson()
        
        // التحقق من وجود manga_data أو manga أو data
        val data = when {
            infoJson.has("manga_data") -> infoJson.getJSONObject("manga_data")
            infoJson.has("manga") -> infoJson.getJSONObject("manga")
            infoJson.has("data") -> infoJson.getJSONObject("data")
            else -> {
                // إذا لم نجد أي من المفاتيح المتوقعة، نطبع محتوى الـ JSON للتشخيص
                println("DEBUG: Unexpected JSON structure: ${infoJson.toString()}")
                // نرجع المانجا كما هي مع الفصول فقط
                return getChaptersOnly(manga, chaptersUrl)
            }
        }

        // جلب الفصول
        val chaptersJson = webClient.httpGet(chaptersUrl).parseJson()
        val releases = chaptersJson.optJSONArray("releases") ?: chaptersJson.optJSONArray("data")

        val chapters = if (releases != null) {
            (0 until releases.length()).mapNotNull { i ->
                val release = releases.optJSONObject(i) ?: return@mapNotNull null
                
                // تخطي الفصول المدفوعة
                val hasRevLink = release.optBoolean("has_rev_link", false)
                val supportLink = release.optString("support_link", "")
                if (hasRevLink && supportLink.isNotBlank()) return@mapNotNull null

                val releaseId = release.getInt("id")
                val chapterNum = release.optString("chapter", "0")
                val chapterTitle = release.optString("title", "")
                val timestamp = release.optLong("time_stamp", 0)

                val relUrl = "/r/$releaseId"
                MangaChapter(
                    id = generateUid(relUrl),
                    title = if (chapterTitle.isBlank()) "Chapter $chapterNum" else chapterTitle,
                    number = chapterNum.toFloatOrNull() ?: (i + 1).toFloat(),
                    volume = 0,
                    url = relUrl,
                    scanlator = release.optString("team_name", null),
                    uploadDate = timestamp * 1000,
                    branch = null,
                    source = source,
                )
            }.reversed()
        } else {
            emptyList()
        }

        val author = listOfNotNull(
            data.optString("creator_nick", null).takeIf { it?.isNotBlank() == true },
            data.optString("editor_nick", null).takeIf { it?.isNotBlank() == true }
        ).joinToString(", ")

        val mangaId = data.optInt("id", 0)
        val coverFilename = data.optString("cover", "")
        val coverUrl = if (mangaId > 0 && coverFilename.isNotBlank()) {
            "https://$domain/uploads/manga/cover/$mangaId/$coverFilename"
        } else {
            manga.coverUrl
        }

        return manga.copy(
            title = data.optString("title", manga.title),
            description = data.optString("summary", null),
            coverUrl = coverUrl,
            authors = if (author.isNotBlank()) setOf(author) else emptySet(),
            state = when (data.optInt("translation_status", 0)) {
                1 -> MangaState.ONGOING
                2 -> MangaState.FINISHED
                else -> null
            },
            rating = data.optString("rating", "0").toFloatOrNull()?.div(10) ?: manga.rating,
            chapters = chapters,
        )
    }

    // دالة مساعدة لجلب الفصول فقط في حالة فشل جلب التفاصيل
    private suspend fun getChaptersOnly(manga: Manga, chaptersUrl: String): Manga {
        val chaptersJson = webClient.httpGet(chaptersUrl).parseJson()
        val releases = chaptersJson.optJSONArray("releases") ?: chaptersJson.optJSONArray("data")

        val chapters = if (releases != null) {
            (0 until releases.length()).mapNotNull { i ->
                val release = releases.optJSONObject(i) ?: return@mapNotNull null
                
                val hasRevLink = release.optBoolean("has_rev_link", false)
                val supportLink = release.optString("support_link", "")
                if (hasRevLink && supportLink.isNotBlank()) return@mapNotNull null

                val releaseId = release.getInt("id")
                val chapterNum = release.optString("chapter", "0")
                val chapterTitle = release.optString("title", "")
                val timestamp = release.optLong("time_stamp", 0)

                val relUrl = "/r/$releaseId"
                MangaChapter(
                    id = generateUid(relUrl),
                    title = if (chapterTitle.isBlank()) "Chapter $chapterNum" else chapterTitle,
                    number = chapterNum.toFloatOrNull() ?: (i + 1).toFloat(),
                    volume = 0,
                    url = relUrl,
                    scanlator = release.optString("team_name", null),
                    uploadDate = timestamp * 1000,
                    branch = null,
                    source = source,
                )
            }.reversed()
        } else {
            emptyList()
        }

        return manga.copy(chapters = chapters)
    }

    // تجاوز دالة الصفحات
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = "https://$domain${chapter.url}"
        val html = webClient.httpGet(chapterUrl).parseHtml()

        val scriptElement = html.selectFirst(".js-react-on-rails-component")
        if (scriptElement == null) {
            // fallback للطريقة القديمة
            return html.select("#readerarea img, .rdminimal img").mapNotNull { img ->
                val url = img.src() ?: return@mapNotNull null
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }

        val scriptData = scriptElement.data()
        val root = JSONObject(scriptData)
        val release = root
            .getJSONObject("readerDataAction")
            .getJSONObject("readerData")
            .getJSONObject("release")

        val storageKey = release.getString("storage_key")
        
        // استخدام webp إذا كانت متوفرة
        val pagesArray = release.optJSONArray("webp_pages")
            ?: release.optJSONArray("pages")
            ?: return emptyList()
        
        val directory = if (release.has("webp_pages") && release.getJSONArray("webp_pages").length() > 0) {
            "hq_webp"
        } else {
            "hq"
        }

        return (0 until pagesArray.length()).map { i ->
            val filename = pagesArray.getString(i)
            val imageUrl = "https://$domain/uploads/releases/$storageKey/$directory/$filename"
            
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }
}
