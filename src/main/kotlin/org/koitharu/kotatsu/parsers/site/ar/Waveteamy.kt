package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("WAVETEAMY", "Waveteamy", "ar")
internal class Waveteamy(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.WAVETEAMY, 50) {

    override val configKeyDomain = ConfigKey.Domain("waveteamy.com")
    
    override val iconUrl =
        "https://raw.githubusercontent.com/hany18h/kotatsu-parsers/master/src/main/kotlin/org/koitharu/kotatsu/parsers/icons/Waveteamy.png"

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        if (originalRequest.method == "POST") {
            val newRequest = originalRequest.newBuilder()
                .removeHeader("Content-Encoding")
                .build()
            return chain.proceed(newRequest)
        }

        return chain.proceed(originalRequest)
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = setOf(SortOrder.UPDATED)

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrBlank()) {
            return getSearch(filter.query, page)
        }

        val url = "https://$domain/wapi/hanout/v1/series/releases-web"
        val formBody = mapOf(
            "page" to page.toString(),
            "limit" to "50"
        )

        val response = webClient.httpPost(url.toHttpUrl(), formBody).parseJson()
        val chapters = response.getJSONArray("chapters")

        return (0 until chapters.length()).map { i ->
            val item = chapters.getJSONObject(i)
            parseMangaFromList(item)
        }
    }

    private suspend fun getSearch(query: String, page: Int): List<Manga> {
        if (page > 1) return emptyList()

        val token = fetchToken()
        val url = "https://$domain/wapi/hanout/v1/series/search-work-site"
        val formBody = mapOf(
            "token" to token,
            "keyValue" to query
        )

        val response = webClient.httpPost(url.toHttpUrl(), formBody).parseJson()
        
        if (!response.getBoolean("success")) {
            return emptyList()
        }

        val data = response.getJSONArray("data")
        return (0 until data.length()).map { i ->
            val item = data.getJSONObject(i)
            Manga(
                id = generateUid(item.getLong("postId")),
                title = item.getString("workName"),
                url = "/series/${item.getLong("postId")}",
                publicUrl = "https://$domain/series/${item.getLong("postId")}",
                coverUrl = resolveCover(item.getString("workImage")),
                source = source,
                rating = RATING_UNKNOWN,
                altTitles = emptySet(),
                contentRating = ContentRating.SAFE,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                largeCoverUrl = null,
                description = null,
                chapters = null,
            )
        }
    }

    private var cachedToken: String? = null

    private suspend fun fetchToken(): String {
        cachedToken?.let { return it }

        val mainPage = webClient.httpGet("https://$domain").parseHtml()
        val scriptSrc = mainPage.select("script[src*='layout-']").attr("src")
        
        if (scriptSrc.isNotEmpty()) {
            val scriptContent = webClient.httpGet(scriptSrc.toAbsoluteUrl(domain)).body?.string() ?: ""
            val tokenMatch = Regex("""["']?token["']?\s*:\s*["']([^"']+)["']""").find(scriptContent)
            if (tokenMatch != null) {
                val token = tokenMatch.groupValues[1]
                cachedToken = token
                return token
            }
        }
        
        throw ParseException("Could not find search token", "https://$domain")
    }

    private fun parseMangaFromList(json: JSONObject): Manga {
        val id = json.getLong("postId")
        val title = json.getString("title")
        val cover = json.getString("imageUrl")
        val rating = json.optString("ratingValue", "0").toFloatOrNull()?.div(2f) ?: RATING_UNKNOWN
        val statusVal = json.optInt("statusValue")
        val state = when (statusVal) {
            0 -> MangaState.ONGOING
            1 -> MangaState.FINISHED
            2 -> MangaState.PAUSED
            else -> MangaState.ONGOING
        }
        
        val genres = json.optString("genre").split(",").mapNotNull { 
            val trimmed = it.trim()
            if (trimmed.isNotEmpty()) MangaTag(key = trimmed, title = trimmed, source = source) else null
        }.toSet()

        return Manga(
            id = generateUid(id),
            title = title,
            url = "/series/$id",
            publicUrl = "https://$domain/series/$id",
            coverUrl = resolveCover(cover),
            source = source,
            rating = rating,
            altTitles = emptySet(),
            contentRating = ContentRating.SAFE,
            tags = genres,
            state = state,
            authors = emptySet(),
            largeCoverUrl = null,
            description = null,
            chapters = null,
        )
    }

    private fun resolveCover(path: String): String {
        if (path.startsWith("http")) return path

        if (path.startsWith("series/") || path.startsWith("projects/") || path.startsWith("users/")) {
            return "https://wcloud.site/$path"
        }

        return "https://$domain/$path"
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.substringAfterLast("/")
        val url = "https://$domain/series/$id"

        val doc = webClient.httpGet(url).parseHtml()

        val metaDescription = doc.selectFirst("meta[name=description]")?.attr("content")

        val allScripts = doc.select("script")

        val candidateScripts = allScripts.filter { script ->
            val html = script.html()
            html.contains("mangaData") && html.contains("chaptersData")
        }

        var scriptContent = candidateScripts.find { script ->
            val html = script.html()
            html.contains("\\\"postId\\\":") && html.contains("\\\"chaptersData\\\":[")
        }?.html()

        if (scriptContent == null && candidateScripts.isNotEmpty()) {
            scriptContent = candidateScripts.first().html()
        }

        if (scriptContent == null) {
            return manga.copy(
                description = metaDescription ?: "No description available",
                chapters = emptyList()
            )
        }

        val directChapters = try {
            extractChaptersDataDirectly(scriptContent, url)
        } catch (e: Exception) {
            emptyList()
        }

        if (directChapters.isNotEmpty()) {
            val basicMangaData = try {
                extractBasicMangaData(scriptContent, url)
            } catch (e: Exception) {
                null
            }

            val realCover = basicMangaData?.coverUrl ?: extractRealCoverFromHTML(doc) ?: manga.coverUrl

            return manga.copy(
                title = basicMangaData?.title ?: manga.title,
                description = basicMangaData?.description ?: metaDescription ?: "No description available",
                coverUrl = realCover,
                state = basicMangaData?.state,
                authors = basicMangaData?.authors ?: emptySet(),
                tags = basicMangaData?.tags ?: emptySet(),
                chapters = directChapters,
            )
        }

        try {
            val jsonStr = extractJson(scriptContent, url)
            val json = JSONObject(jsonStr)

            val mangaData = json.getJSONObject("mangaData")
            val title = mangaData.getString("name")
            val cover = mangaData.getString("cover")
            val description = mangaData.optString("story").takeIf { it.isNotEmpty() } ?: metaDescription
            val statusVal = mangaData.optInt("status")
            val state = when (statusVal) {
                0 -> MangaState.ONGOING
                1 -> MangaState.FINISHED
                else -> MangaState.ONGOING
            }

            val authors = mutableSetOf<String>()
            mangaData.optString("author").takeIf { it.isNotEmpty() && it != "null" }?.let { authors.add(it) }
            mangaData.optString("artist").takeIf { it.isNotEmpty() && it != "null" }?.let { authors.add(it) }

            val genres = mangaData.optJSONArray("genre")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val g = arr.getString(i)
                    if (g.isNotEmpty()) MangaTag(key = g, title = g, source = source) else null
                }.toSet()
            } ?: emptySet()

            val postId = mangaData.getLong("postId")

            val chaptersData = json.optJSONArray("chaptersData")
            val chapters = if (chaptersData != null) {
                (0 until chaptersData.length()).mapNotNull { i ->
                    try {
                        val ch = chaptersData.getJSONObject(i)
                        val chId = ch.getInt("id")
                        val chNum = ch.optDouble("chapter", 0.0).toFloat()
                        val chDate = ch.getString("postTime")
                        val chTitle = ch.optString("title").takeIf { it.isNotEmpty() && it != "null" }

                        MangaChapter(
                            id = generateUid(chId.toLong()),
                            title = chTitle,
                            number = chNum,
                            volume = 0,
                            url = "/series/$postId/${chNum.toInt()}",
                            uploadDate = parseDate(chDate),
                            source = source,
                            scanlator = null,
                            branch = null
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.reversed()
            } else {
                emptyList()
            }

            return manga.copy(
                title = title,
                description = description,
                coverUrl = resolveCover(cover),
                state = state,
                authors = authors,
                tags = genres,
                chapters = chapters,
            )
        } catch (e: Exception) {
            return manga.copy(
                description = metaDescription ?: "No description available",
                coverUrl = extractRealCoverFromHTML(doc) ?: manga.coverUrl,
                chapters = directChapters
            )
        }
    }

    private fun extractRealCoverFromHTML(doc: org.jsoup.nodes.Document): String? {
        val ogImage = doc.selectFirst("meta[property='og:image']")?.attr("content")
        if (!ogImage.isNullOrEmpty() && ogImage.startsWith("http")) {
            return ogImage
        }

        val jsonLdScripts = doc.select("script[type='application/ld+json']")
        for (script in jsonLdScripts) {
            try {
                val jsonStr = script.html()
                if (jsonStr.contains("\"image\"")) {
                    val json = JSONObject(jsonStr)
                    val image = json.optString("image")
                    if (image.isNotEmpty() && image.startsWith("http")) {
                        return image
                    }
                }
            } catch (e: Exception) {
                // Continue
            }
        }

        return null
    }

    private fun extractJson(script: String, url: String): String {
        val mangaDataIndex = script.indexOf("\"mangaData\":")
        val chaptersDataIndex = script.indexOf("\"chaptersData\":")

        if (mangaDataIndex == -1 || chaptersDataIndex == -1) {
            throw ParseException("Required data fields not found in script", url)
        }

        var startIndex = -1
        for (i in mangaDataIndex downTo 0) {
            if (script[i] == '{') {
                startIndex = i
                break
            }
        }

        if (startIndex == -1) throw ParseException("No opening brace found", url)

        var braceCount = 0
        var inString = false
        var escape = false

        for (i in startIndex until script.length) {
            val c = script[i]

            when {
                escape -> escape = false
                c == '\\' -> escape = true
                c == '"' && !escape -> inString = !inString
                !inString && c == '{' -> braceCount++
                !inString && c == '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        val candidate = script.substring(startIndex, i + 1)
                        if (candidate.contains("\"mangaData\":") && candidate.contains("\"chaptersData\":")) {
                            return candidate.replace("\\\"", "\"")
                                          .replace("\\\\", "\\")
                                          .replace("\\n", "\n")
                                          .replace("\\r", "\r")
                                          .replace("\\t", "\t")
                        }
                    }
                }
            }
        }

        throw ParseException("Failed to extract valid JSON object", url)
    }

    private fun extractChaptersDataDirectly(scriptContent: String?, url: String): List<MangaChapter> {
        if (scriptContent == null) return emptyList()

        val postIdMatch = Regex("""\\\"postId\\\":(\d+)""").find(scriptContent)
        val postId = postIdMatch?.groupValues?.get(1)?.toLongOrNull() ?: return emptyList()

        val chaptersStart = scriptContent.indexOf("\\\"chaptersData\\\":[")
        if (chaptersStart == -1) return emptyList()

        val arrayStart = chaptersStart + "\\\"chaptersData\\\":".length

        var depth = 0
        var inString = false
        var escape = false
        var arrayEnd = arrayStart

        for (i in arrayStart until scriptContent.length) {
            val char = scriptContent[i]

            when {
                escape -> escape = false
                char == '\\' -> escape = true
                char == '"' && !escape -> inString = !inString
                !inString -> {
                    when (char) {
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) {
                                arrayEnd = i + 1
                                break
                            }
                        }
                    }
                }
            }
        }

        if (depth != 0) return emptyList()

        val chaptersJson = scriptContent.substring(arrayStart, arrayEnd)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        try {
            val chaptersArray = org.json.JSONArray(chaptersJson)
            return (0 until chaptersArray.length()).mapNotNull { i ->
                try {
                    val ch = chaptersArray.getJSONObject(i)
                    val chId = ch.getInt("id")
                    val chNum = ch.optDouble("chapter", 0.0).toFloat()
                    val chDate = ch.getString("postTime")
                    val chTitle = ch.optString("title").takeIf { it.isNotEmpty() && it != "null" }

                    MangaChapter(
                        id = generateUid(chId.toLong()),
                        title = chTitle,
                        number = chNum,
                        volume = 0,
                        url = "/series/$postId/${chNum.toInt()}",
                        uploadDate = parseDate(chDate),
                        source = source,
                        scanlator = null,
                        branch = null
                    )
                } catch (e: Exception) {
                    null
                }
            }.reversed()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private data class BasicMangaData(
        val title: String?,
        val description: String?,
        val coverUrl: String?,
        val state: MangaState?,
        val authors: Set<String>,
        val tags: Set<MangaTag>
    )

    private fun extractBasicMangaData(scriptContent: String?, url: String): BasicMangaData? {
        if (scriptContent == null) return null

        try {
            val mangaDataStart = scriptContent.indexOf("\\\"mangaData\\\":{")
            if (mangaDataStart == -1) return null

            val dataStart = mangaDataStart + "\\\"mangaData\\\":".length

            var depth = 0
            var inString = false
            var escape = false
            var dataEnd = dataStart

            for (i in dataStart until scriptContent.length) {
                val char = scriptContent[i]

                when {
                    escape -> escape = false
                    char == '\\' -> escape = true
                    char == '"' && !escape -> inString = !inString
                    !inString -> {
                        when (char) {
                            '{' -> depth++
                            '}' -> {
                                depth--
                                if (depth == 0) {
                                    dataEnd = i + 1
                                    break
                                }
                            }
                        }
                    }
                }
            }

            if (depth != 0) return null

            val mangaDataJson = scriptContent.substring(dataStart, dataEnd)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")

            val mangaData = JSONObject(mangaDataJson)

            val title = mangaData.optString("name").takeIf { it.isNotEmpty() }
            val description = mangaData.optString("story").takeIf { it.isNotEmpty() && it != "null" }
            val cover = mangaData.optString("cover").takeIf { it.isNotEmpty() }
            val statusVal = mangaData.optInt("status", -1)
            val state = when (statusVal) {
                0 -> MangaState.ONGOING
                1 -> MangaState.FINISHED
                else -> null
            }

            val authors = mutableSetOf<String>()
            mangaData.optString("author").takeIf { it.isNotEmpty() && it != "null" }?.let { authors.add(it) }
            mangaData.optString("artist").takeIf { it.isNotEmpty() && it != "null" }?.let { authors.add(it) }

            val genres = mangaData.optJSONArray("genre")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val g = arr.optString(i)
                    if (g.isNotEmpty() && g != "null") MangaTag(key = g, title = g, source = source) else null
                }.toSet()
            } ?: emptySet()

            return BasicMangaData(
                title = title,
                description = description,
                coverUrl = cover?.let { resolveCover(it) },
                state = state,
                authors = authors,
                tags = genres
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L

        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss+00:00",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
        )

        for (pattern in formats) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                // Try next format
            }
        }

        try {
            val datePart = dateStr.substring(0, 10)
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(datePart)?.time ?: 0L
        } catch (e: Exception) {
            return 0L
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "https://$domain${chapter.url}"
        
        val doc = webClient.httpGet(url).parseHtml()

        val allScripts = doc.select("script")
        val scriptContent = allScripts.find { script ->
            val html = script.html()
            html.contains("currentChapter") && html.contains("images")
        }?.html()

        if (scriptContent == null) {
            throw ParseException("Could not find chapter images data", url)
        }

        val images = try {
            extractImagesFromScript(scriptContent)
        } catch (e: Exception) {
            throw ParseException("Failed to extract images: ${e.message}", url)
        }

        return images.mapIndexed { i, imagePath ->
            val imageUrl = processImageUrl(imagePath)

            MangaPage(
                id = generateUid("${chapter.id}-$i"),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun processImageUrl(imagePath: String): String {
        if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
            return imagePath
        }

        if (imagePath.contains(".") && imagePath.matches(Regex("^[A-Za-z0-9_=-]+\\.[A-Za-z0-9_=-]+$"))) {
            return "https://wcloud.site/$imagePath"
        }

        return "https://wcloud.site/$imagePath"
    }

    private fun extractImagesFromScript(scriptContent: String): List<String> {
        val currentChapterStart = scriptContent.indexOf("\\\"currentChapter\\\":{")
        if (currentChapterStart == -1) return emptyList()

        val dataStart = currentChapterStart + "\\\"currentChapter\\\":".length

        var depth = 0
        var inString = false
        var escape = false
        var dataEnd = dataStart

        for (i in dataStart until scriptContent.length) {
            val char = scriptContent[i]

            when {
                escape -> escape = false
                char == '\\' -> escape = true
                char == '"' && !escape -> inString = !inString
                !inString -> {
                    when (char) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                dataEnd = i + 1
                                break
                            }
                        }
                    }
                }
            }
        }

        if (depth != 0) return emptyList()

        val currentChapterJson = scriptContent.substring(dataStart, dataEnd)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        val currentChapter = JSONObject(currentChapterJson)
        val imagesArray = currentChapter.optJSONArray("images") ?: return emptyList()

        return (0 until imagesArray.length()).map { i ->
            imagesArray.getString(i)
        }
    }
}
