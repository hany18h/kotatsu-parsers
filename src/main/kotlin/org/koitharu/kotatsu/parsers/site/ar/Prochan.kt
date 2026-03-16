package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("PROCHAN", "ProChan", "ar")
internal class ProChan(context: MangaLoaderContext) : PagedMangaParser(
    context,
    source = MangaParserSource.PROCHAN,
    pageSize = 18,
), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("procomic.pro")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val newRequest = request.newBuilder()
            .header("Referer", "https://procomic.pro/")
            .header("Origin", "https://procomic.pro")
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            )
            .build()

        val response = chain.proceed(newRequest)
        val host = request.url.host

        if (host.contains("procomic") || host.contains("prochan")) {
            val contentType = response.header("Content-Type") ?: ""
            if (contentType.contains("octet-stream") || contentType.isEmpty()) {
                val path = request.url.encodedPath.lowercase()
                val fixedType = when {
                    path.endsWith(".avif") -> "image/avif"
                    path.endsWith(".webp") -> "image/webp"
                    path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                    path.endsWith(".png") -> "image/png"
                    path.endsWith(".gif") -> "image/gif"
                    else -> "image/jpeg"
                }
                return response.newBuilder()
                    .header("Content-Type", fixedType)
                    .build()
            }
        }
        return response
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            return searchManga(page, filter.query)
        }

        val endpoint = when (order) {
            SortOrder.UPDATED -> "latest-updates"
            SortOrder.POPULARITY -> "popular"
            SortOrder.ALPHABETICAL -> "az"
            else -> "latest-updates"
        }

        val url = "https://$domain/api/public/content/$endpoint" +
            "?limit=$pageSize&category=comics&page=$page"

        val json = webClient.httpGet(url).parseJson()
        val data = json.optJSONArray("data") ?: return emptyList()

        return data.mapJSONNotNull { item ->
            parseMangaFromList(item)
        }
    }

    private suspend fun searchManga(page: Int, query: String): List<Manga> {
        val url = "https://$domain/api/public/search" +
            "?q=${query.urlEncoded()}&page=$page&limit=$pageSize&category=comics"

        val json = webClient.httpGet(url).parseJson()
        val data = json.optJSONArray("data") ?: return emptyList()

        return data.mapJSONNotNull { item ->
            parseMangaFromList(item)
        }
    }

    private fun parseMangaFromList(item: JSONObject): Manga? {
        // Support both field name formats
        val id = item.optInt("mangaId").takeIf { it > 0 }
            ?: item.optInt("id").takeIf { it > 0 }
            ?: return null
        val slug = item.optString("mangaSlug").takeIf { it.isNotEmpty() }
            ?: item.optString("slug").takeIf { it.isNotEmpty() }
            ?: return null
        val title = item.optString("mangaTitle").takeIf { it.isNotEmpty() }
            ?: item.optString("title").takeIf { it.isNotEmpty() }
            ?: return null
        val type = item.optString("type", "manhua")

        if (type == "novel") return null

        val coverUrl = getBestCover(item)
        val status = item.optString("status", "")
        val mangaUrl = "/series/$type/$id/$slug"

        return Manga(
            id = generateUid(mangaUrl),
            title = title,
            altTitles = emptySet(),
            url = mangaUrl,
            publicUrl = "https://$domain$mangaUrl",
            rating = RATING_UNKNOWN,
            contentRating = if (item.optBoolean("isSensitiveImage")) ContentRating.ADULT else null,
            coverUrl = coverUrl,
            tags = emptySet(),
            state = parseState(status),
            authors = emptySet(),
            description = null,
            chapters = null,
            source = source,
        )
    }

    private fun getBestCover(item: JSONObject): String {
        // Try high quality app cover first
        val appCover = item.optJSONObject("coverImageApp")
        if (appCover != null) {
            val card = appCover.optJSONObject("card")
            val mobile = card?.optString("mobile")?.takeIf { it.isNotEmpty() }
            if (mobile != null) return mobile

            val desktop = appCover.optString("desktop").takeIf { it.isNotEmpty() }
            if (desktop != null) return desktop
        }
        return item.optString("coverImage", "")
    }

    private fun parseState(status: String): MangaState? = when {
        status.contains("مستمر", ignoreCase = true) -> MangaState.ONGOING
        status.contains("مكتمل", ignoreCase = true) -> MangaState.FINISHED
        status.contains("متوقف", ignoreCase = true) -> MangaState.ABANDONED
        status.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
        status.contains("completed", ignoreCase = true) -> MangaState.FINISHED
        else -> null
    }

    override suspend fun getDetails(manga: Manga): Manga {
        // URL: /series/{type}/{id}/{slug}
        val parts = manga.url.split("/").filter { it.isNotEmpty() }
        if (parts.size < 4) return manga

        val type = parts[1]
        val id = parts[2]

        // Fetch chapters
        val chaptersUrl = "https://$domain/api/public/series/$type/$id/chapters" +
            "?page=1&limit=2000&order=asc"

        val chaptersJson = webClient.httpGet(chaptersUrl).parseJson()
        val chaptersData = chaptersJson.optJSONArray("data") ?: JSONArray()

        // Fetch series details
        val detailsUrl = "https://$domain/api/public/series/$type/$id"
        val detailsJson = runCatching {
            webClient.httpGet(detailsUrl).parseJson()
        }.getOrElse { JSONObject() }

        val description = extractDescription(detailsJson)
        val tags = extractTags(detailsJson)
        val authors = extractAuthors(detailsJson)

        return manga.copy(
            description = description,
            tags = tags,
            authors = authors,
            chapters = parseChapters(chaptersData, manga.url),
        )
    }

    private fun extractDescription(json: JSONObject): String? {
        // Try direct description field
        json.optString("description").takeIf {
            it.isNotEmpty() && it != "null"
        }?.let { return it }

        // Try metadata.descriptions.ar then en
        val meta = json.optJSONObject("metadata") ?: return null
        val descriptions = meta.optJSONObject("descriptions") ?: return null
        return descriptions.optString("ar").takeIf { it.isNotEmpty() }
            ?: descriptions.optString("en").takeIf { it.isNotEmpty() }
    }

    private fun extractTags(json: JSONObject): Set<MangaTag> {
        val tags = mutableSetOf<MangaTag>()
        val meta = json.optJSONObject("metadata") ?: json
        val genres = meta.optJSONArray("genres") ?: return emptySet()

        for (i in 0 until genres.length()) {
            val genre = when (val g = genres.opt(i)) {
                is String -> g
                is JSONObject -> g.optString("name").takeIf { it.isNotEmpty() } ?: continue
                else -> continue
            }
            tags += MangaTag(key = genre.lowercase().replace(" ", "-"), title = genre, source = source)
        }
        return tags
    }

    private fun extractAuthors(json: JSONObject): Set<String> {
        val authors = mutableSetOf<String>()
        val meta = json.optJSONObject("metadata") ?: json

        fun addAuthor(value: String?) {
            value?.takeIf { it.isNotEmpty() && it != "null" }?.let {
                // Handle JSON array string like ["Author1","Author2"]
                if (it.startsWith("[")) {
                    try {
                        val arr = JSONArray(it)
                        for (i in 0 until arr.length()) {
                            arr.optString(i).takeIf { s -> s.isNotEmpty() }?.let { s -> authors.add(s) }
                        }
                    } catch (_: Exception) {
                        authors.add(it)
                    }
                } else {
                    authors.add(it)
                }
            }
        }

        addAuthor(meta.optString("author"))
        addAuthor(meta.optString("artist"))
        return authors
    }

    private fun parseChapters(data: JSONArray, mangaUrl: String): List<MangaChapter> {
        return data.mapJSONNotNull { item ->
            // Skip paid/locked chapters
            if (item.optBoolean("lockedByCoins") ||
                item.optBoolean("lockedForever") ||
                item.optBoolean("lockedByExclusive")
            ) {
                return@mapJSONNotNull null
            }

            val chapterId = item.optInt("id").takeIf { it > 0 } ?: return@mapJSONNotNull null
            val chapterSlug = item.optString("slug").takeIf { it.isNotEmpty() } ?: return@mapJSONNotNull null
            val chapterNum = item.optString("number").toFloatOrNull() ?: return@mapJSONNotNull null
            val title = item.optString("title").takeIf { it.isNotEmpty() && it != "null" }
            val publishedAt = item.optString("publishedAt", "")

            val uploadDate = runCatching {
                dateFormat.parse(publishedAt)?.time ?: 0L
            }.getOrDefault(0L)

            val chapterUrl = "$mangaUrl/$chapterId/$chapterSlug"

            MangaChapter(
                id = generateUid(chapterUrl),
                title = title,
                number = chapterNum,
                volume = 0,
                url = chapterUrl,
                scanlator = null,
                uploadDate = uploadDate,
                branch = null,
                source = source,
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // URL: /series/{type}/{mangaId}/{slug}/{chapterId}/{chapterSlug}
        val parts = chapter.url.split("/").filter { it.isNotEmpty() }
        val chapterId = parts.getOrNull(4) ?: return emptyList()

        val url = "https://$domain/api/public/chapters/$chapterId"
        val json = webClient.httpGet(url).parseJson()

        val cdnPath = json.optString("cdn_path").takeIf { it.isNotEmpty() } ?: "cdn2"

        // Images can be in root or metadata
        val images = json.optJSONArray("images")
            ?: json.optJSONObject("metadata")?.optJSONArray("images")
            ?: return emptyList()

        val result = mutableListOf<MangaPage>()
        for (i in 0 until images.length()) {
            val imagePath = images.optString(i).takeIf { it.isNotEmpty() } ?: continue
            val finalUrl = when {
                imagePath.startsWith("http") -> imagePath
                else -> "https://$cdnPath.procomic.net$imagePath"
            }
            result.add(
                MangaPage(
                    id = generateUid("${chapter.id}-$i"),
                    url = finalUrl,
                    preview = null,
                    source = source,
                ),
            )
        }
        return result
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
}
