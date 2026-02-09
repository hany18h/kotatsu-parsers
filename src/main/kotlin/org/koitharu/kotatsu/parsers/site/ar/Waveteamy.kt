package org.koitharu.kotatsu.parsers.site.madara.ar

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("WAVETEAMY", "Waveteamy", "ar")
internal class Waveteamy(context: MangaLoaderContext) :
    MangaParser(context, MangaParserSource.WAVETEAMY) {

    override val configKeyDomain = ConfigKey.Domain("waveteamy.com")

    private val userAgentKey = ConfigKey.UserAgent(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val headers: Headers by lazy {
        headersBuilder()
            .add("User-Agent", userAgentKey.value)
            .add("Referer", "https://$domain/")
            .build()
    }

    override val sortOrders: Set<SortOrder> = emptySet()

    override val isMultipleTagsSupported = false

    override val isSearchSupported = true

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrBlank()) {
            return getSearch(filter.query, page)
        }

        // استخدام API endpoint الصحيح
        val url = "https://$domain/wapi/hanout/v1/series/releases-web"
        val formBody = buildMap {
            put("page", page.toString())
            put("limit", "50")
        }

        val response = webClient.httpPost(url.toHttpUrl(), formBody).parseJson()
        val chapters = response.getJSONArray("chapters")

        return (0 until chapters.length()).mapNotNull { i ->
            try {
                val item = chapters.getJSONObject(i)
                
                // فلترة المحتوى غير المناسب
                val title = item.optString("title", "")
                val imageUrl = item.optString("imageUrl", "")
                
                if (isAdultContent(title, imageUrl)) {
                    null
                } else {
                    parseMangaFromList(item)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun isAdultContent(title: String, imageUrl: String): Boolean {
        val suspiciousKeywords = listOf(
            "adult", "18+", "hentai", "ecchi", "mature",
            "nsfw", "xxx", "porn", "sex", "breast", "boob",
            "nude", "naked", "ero", "lewd", "perv", "sexy"
        )
        
        val titleLower = title.lowercase()
        val imageLower = imageUrl.lowercase()
        
        return suspiciousKeywords.any { 
            titleLower.contains(it) || imageLower.contains(it)
        }
    }

    private fun parseMangaFromList(json: JSONObject): Manga {
        val id = json.getLong("postId")
        val title = json.getString("title")
        val cover = json.getString("imageUrl")
        val rating = json.optString("ratingValue", "0").toFloatOrNull()?.div(2f) ?: RATING_UNKNOWN
        val statusVal = json.optInt("statusValue", 0)
        val state = when (statusVal) {
            0 -> MangaState.ONGOING
            1 -> MangaState.FINISHED
            2 -> MangaState.PAUSED
            else -> MangaState.ONGOING
        }
        
        val genres = json.optString("genre", "").split(",").mapNotNull { 
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

    private suspend fun getSearch(query: String, page: Int): List<Manga> {
        val token = fetchToken()
        val url = "https://$domain/wapi/hanout/v1/series/search-work-site"
        val formBody = buildMap {
            put("token", token)
            put("keyValue", query)
        }

        val response = webClient.httpPost(url.toHttpUrl(), formBody).parseJson()
        val results = response.getJSONArray("works")

        return (0 until results.length()).mapNotNull { i ->
            try {
                val item = results.getJSONObject(i)
                
                val title = item.optString("workName", "")
                val imageUrl = item.optString("imageUrl", "")
                
                if (isAdultContent(title, imageUrl)) {
                    null
                } else {
                    parseMangaFromSearch(item)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseMangaFromSearch(json: JSONObject): Manga {
        val id = json.getLong("postId")
        val title = json.getString("workName")
        val cover = json.getString("imageUrl")

        return Manga(
            id = generateUid(id),
            title = title,
            url = "/series/$id",
            publicUrl = "https://$domain/series/$id",
            coverUrl = resolveCover(cover),
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

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "https://$domain${manga.url}"
        val doc = webClient.httpGet(url).parseHtml()

        val scripts = doc.select("script:not([src])")
        val scriptContent = scripts.find { script ->
            val html = script.html()
            html.contains("\\\"workInfo\\\":{") || html.contains("\"workInfo\":{")
        }?.html()

        if (scriptContent == null) {
            return manga
        }

        val basicData = try {
            extractWorkInfo(scriptContent)
        } catch (e: Exception) {
            null
        }

        val chapters = try {
            extractChapters(scriptContent, manga.url)
        } catch (e: Exception) {
            null
        }

        return manga.copy(
            title = basicData?.title ?: manga.title,
            description = basicData?.description,
            coverUrl = basicData?.coverUrl ?: manga.coverUrl,
            state = basicData?.state ?: manga.state,
            authors = if (basicData?.authors?.isNotEmpty() == true) basicData.authors else manga.authors,
            tags = if (basicData?.tags?.isNotEmpty() == true) basicData.tags else manga.tags,
            chapters = chapters,
        )
    }

    private data class BasicMangaData(
        val title: String?,
        val description: String?,
        val coverUrl: String?,
        val state: MangaState?,
        val authors: Set<String>,
        val tags: Set<MangaTag>,
    )

    private fun extractWorkInfo(scriptContent: String): BasicMangaData? {
        try {
            val workInfoStart = scriptContent.indexOf("\\\"workInfo\\\":{")
            if (workInfoStart == -1) return null

            val dataStart = workInfoStart + "\\\"workInfo\\\":".length

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

    private fun extractChapters(scriptContent: String, mangaUrl: String): List<MangaChapter>? {
        try {
            val chaptersStart = scriptContent.indexOf("\"chapters\":[")
            if (chaptersStart == -1) return null

            val arrayStart = chaptersStart + "\"chapters\":".length

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

            if (depth != 0) return null

            val chaptersJson = scriptContent.substring(arrayStart, arrayEnd)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")

            val chaptersArray = JSONArray(chaptersJson)

            return (0 until chaptersArray.length()).map { i ->
                val chapterObj = chaptersArray.getJSONObject(i)
                val chapterId = chapterObj.getLong("id")
                val chapterNumber = chapterObj.optInt("chapter", i + 1).toFloat()
                val postTime = chapterObj.optString("postTime", "")
                val uploadDate = parseDate(postTime)

                MangaChapter(
                    id = generateUid(chapterId),
                    name = "Chapter $chapterNumber",
                    number = chapterNumber,
                    volume = 0,
                    url = "$mangaUrl/${chapterNumber.toInt()}",
                    scanlator = null,
                    uploadDate = uploadDate,
                    branch = null,
                    source = source
                )
            }.reversed()
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
            "yyyy-MM-dd HH:mm:ss"
        )

        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                continue
            }
        }

        return 0L
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "https://$domain${chapter.url}"
        val doc = webClient.httpGet(url).parseHtml()

        val allScripts = doc.select("script:not([src])")
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
            MangaPage(
                id = generateUid("${chapter.id}-$i"),
                url = resolveImageUrl(imagePath),
                preview = null,
                source = source
            )
        }
    }

    private fun extractImagesFromScript(scriptContent: String): List<String> {
        // محاولة الصيغة البسيطة أولاً
        val normalPattern = """"images":\[([^\]]+)\]""".toRegex()
        val normalMatch = normalPattern.find(scriptContent)
        
        if (normalMatch != null) {
            val imagesContent = normalMatch.groupValues[1]
            val imagePattern = """"([^"]+)"""".toRegex()
            return imagePattern.findAll(imagesContent).map { it.groupValues[1] }.toList()
        }
        
        // محاولة الصيغة escaped
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

        try {
            val currentChapter = JSONObject(currentChapterJson)
            val imagesArray = currentChapter.optJSONArray("images") ?: return emptyList()

            return (0 until imagesArray.length()).map { i ->
                imagesArray.getString(i)
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private suspend fun fetchToken(): String {
        val url = "https://$domain/_next/static/chunks/app/layout-5e10be381f5e699c.js"
        val response = webClient.httpGet(url).parseRaw()
        
        val content = response.use { res ->
            res.body.string()
        }

        val tokenPattern = """token:\s*"([^"]+)"""".toRegex()
        val match = tokenPattern.find(content)

        return match?.groupValues?.get(1) ?: "nmgFJGotf6O%rr7t84rjbNjity9tbgnbb"
    }

    private fun resolveCover(path: String): String {
        if (path.startsWith("http")) return path

        if (path.startsWith("series/") || path.startsWith("projects/") || path.startsWith("users/")) {
            return "https://wcloud.site/$path"
        }

        return "https://$domain/$path"
    }

    private fun resolveImageUrl(path: String): String {
        if (path.startsWith("http")) return path
        
        // Base64 encoded paths
        if (path.matches(Regex("^[A-Za-z0-9+/=]+\\.[a-f0-9]+$"))) {
            return "https://wcloud.site/$path"
        }
        
        // Normal paths
        if (path.startsWith("projects/") || path.startsWith("series/")) {
            return "https://wcloud.site/$path"
        }
        
        return "https://wcloud.site/$path"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()

        // معالجة خاصة لطلبات wcloud.site
        if (url.contains("wcloud.site")) {
            val newRequest = originalRequest.newBuilder()
                .removeHeader("Content-Encoding")
                .header("User-Agent", userAgentKey.value)
                .header("Referer", "https://$domain/")
                .header("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
                .header("Sec-Fetch-Dest", "image")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "cross-site")
                .build()
            return chain.proceed(newRequest)
        }

        // إزالة Content-Encoding من POST requests
        if (originalRequest.method == "POST") {
            val newRequest = originalRequest.newBuilder()
                .removeHeader("Content-Encoding")
                .build()
            return chain.proceed(newRequest)
        }

        return chain.proceed(originalRequest)
    }
}
