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
 */
@MangaSourceParser("DILAR", "Dilar", "ar")
internal class Dilar(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.DILAR, "dilar.tube", pageSize = 20, searchPageSize = 10) {

    override val listUrl = "/api/releases"
    override val datePattern = "yyyy-MM-dd'T'HH:mm:ss"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://$domain/api/releases?page=$page"
        val json = webClient.httpGet(url).parseJson()

        val releases = json.getJSONArray("releases")
        val mangaMap = mutableMapOf<Int, Manga>()

        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val manga = release.optJSONObject("manga") ?: continue
            
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

    override suspend fun getDetails(manga: Manga): Manga {
        val infoUrl = "https://$domain${manga.url}"
        val chaptersUrl = "https://$domain${manga.url}/releases"

        val infoJson = webClient.httpGet(infoUrl).parseJson()
        val chaptersJson = webClient.httpGet(chaptersUrl).parseJson()

        val data = infoJson.optJSONObject("mangaData")
            ?: infoJson.optJSONObject("manga")
            ?: infoJson
            
        val releases = chaptersJson.getJSONArray("releases")
        val chapters = (0 until releases.length()).mapNotNull { i ->
            val release = releases.optJSONObject(i) ?: return@mapNotNull null
            
            val hasRevLink = release.optBoolean("has_rev_link", false)
            val supportLink = release.optString("support_link", "")
            if (hasRevLink && supportLink.isNotBlank()) return@mapNotNull null

            val releaseId = release.getInt("id")
            val chapterNum = release.optString("chapter", "0")
            val chapterTitle = release.optString("title", "")
            val timestamp = release.optLong("time_stamp", 0)

            // استخدام URL كامل للفصل
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

        val author = listOfNotNull(
            data.optString("creator_nick", null).takeIf { it?.isNotBlank() == true },
            data.optString("editor_nick", null).takeIf { it?.isNotBlank() == true }
        ).joinToString(", ")

        return manga.copy(
            title = data.optString("title", manga.title),
            description = data.optString("summary", null),
            coverUrl = "https://$domain/uploads/manga/cover/${data.getInt("id")}/${data.optString("cover")}",
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

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = "https://$domain${chapter.url}"
        val html = webClient.httpGet(chapterUrl).parseHtml()

        // البحث عن جميع الـ scripts التي تحتوي على بيانات React
        val scripts = html.select("script[type='application/json']")
        
        for (script in scripts) {
            try {
                val scriptData = script.data()
                if (scriptData.isBlank()) continue
                
                val root = JSONObject(scriptData)
                
                // البحث عن release في جميع المستويات الممكنة
                val release = findReleaseData(root)
                if (release != null) {
                    return extractPagesFromRelease(release)
                }
            } catch (e: Exception) {
                // تجاهل وتجربة الـ script التالي
                continue
            }
        }
        
        // Fallback: البحث في الـ component القديم
        val scriptElement = html.selectFirst(".js-react-on-rails-component")
        if (scriptElement != null) {
            try {
                val scriptData = scriptElement.data()
                val root = JSONObject(scriptData)
                val release = findReleaseData(root)
                if (release != null) {
                    return extractPagesFromRelease(release)
                }
            } catch (e: Exception) {
                // تجاهل
            }
        }
        
        // Fallback النهائي: البحث عن الصور مباشرة في HTML
        val images = html.select("#readerarea img, .rdminimal img, .reader-area img, .chapter-images img")
        if (images.isNotEmpty()) {
            return images.mapNotNull { img ->
                val url = img.src()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }
        
        throw Exception("Could not find chapter images. Please report this manga.")
    }
    
    // البحث عن بيانات release في أي مستوى من JSON
    private fun findReleaseData(obj: JSONObject): JSONObject? {
        // محاولة 1: البحث المباشر
        if (obj.has("release")) {
            val release = obj.optJSONObject("release")
            if (release != null && release.has("storage_key")) {
                return release
            }
        }
        
        // محاولة 2: البحث في readerDataAction.readerData.release
        val readerDataAction = obj.optJSONObject("readerDataAction")
        if (readerDataAction != null) {
            val readerData = readerDataAction.optJSONObject("readerData")
            if (readerData != null) {
                val release = readerData.optJSONObject("release")
                if (release != null && release.has("storage_key")) {
                    return release
                }
            }
        }
        
        // محاولة 3: البحث في props أو data أو component
        for (key in listOf("props", "data", "component", "initialState")) {
            val subObj = obj.optJSONObject(key)
            if (subObj != null) {
                val foundRelease = findReleaseData(subObj)
                if (foundRelease != null) {
                    return foundRelease
                }
            }
        }
        
        return null
    }
    
    private fun extractPagesFromRelease(release: JSONObject): List<MangaPage> {
        val storageKey = release.optString("storage_key", "")
        if (storageKey.isBlank()) {
            throw Exception("No storage_key found in release data")
        }
        
        // استخدام webp إذا كانت متوفرة، وإلا استخدام pages العادية
        val pagesArray = release.optJSONArray("webp_pages")?.takeIf { it.length() > 0 }
            ?: release.optJSONArray("pages")
            ?: throw Exception("No pages found in release data")
        
        if (pagesArray.length() == 0) {
            throw Exception("Pages array is empty")
        }
        
        // تحديد المجلد: hq_webp أو hq
        val directory = if (release.has("webp_pages") && 
                            release.optJSONArray("webp_pages")?.length() ?: 0 > 0) {
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
