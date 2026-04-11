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
import java.util.Base64

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
            isSearchSupported = false,
        )

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    }

    private val imageCdnHosts = setOf(
        "app.procomic.net",
        "app.procomic.pro",
        "cdn2.procomic.pro",
        "cdn3.procomic.pro",
        "cdn4.procomic.pro",
        "cdn2.prochan.net",
        "cdn3.prochan.net",
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val isProcomicHost = host.contains("procomic") || host.contains("prochan")
        val isImageCdnRequest = host in imageCdnHosts

        val newRequestBuilder = request.newBuilder()
            .header("Referer", "https://procomic.pro/")
            .header("Origin", "https://procomic.pro")
            .header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            )

        if (isImageCdnRequest) {
            newRequestBuilder
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .header("Sec-Fetch-Dest", "image")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "same-site")
        } else {
            newRequestBuilder.header("Accept", "*/*")
        }

        val response = chain.proceed(newRequestBuilder.build())

        val contentType = response.header("Content-Type") ?: ""
        if (contentType.contains("octet-stream") || contentType.isEmpty()) {
            val path = request.url.encodedPath.lowercase()
            val fixedType = when {
                path.endsWith(".avif") -> "image/avif"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".gif") -> "image/gif"
                isProcomicHost -> "image/jpeg"
                else -> null
            }
            if (fixedType != null) {
                return response.newBuilder()
                    .header("Content-Type", fixedType)
                    .build()
            }
        }
        return response
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
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

    private fun parseMangaFromList(item: JSONObject): Manga? {
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
            contentRating = if (item.optBoolean("isSensitiveImage")) {
                ContentRating.ADULT
            } else {
                null
            },
            coverUrl = coverUrl,
            tags = emptySet(),
            state = parseState(status),
            authors = emptySet(),
            description = null,
            chapters = emptyList(),
            source = source,
        )
    }

    private fun getBestCover(item: JSONObject): String {
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
        val parts = manga.url.split("/").filter { it.isNotEmpty() }
        if (parts.size < 4) return manga
        val id = parts[2]

        val chaptersUrl = "https://$domain/api/public/chapters" +
            "?contentId=$id&page=1&limit=2000&order=asc"

        val chaptersJson = runCatching {
            webClient.httpGet(chaptersUrl).parseJson()
        }.getOrElse { return manga }

        val chaptersData = chaptersJson.optJSONArray("chapters") ?: JSONArray()

        return manga.copy(
            chapters = parseChapters(chaptersData, manga.url).reversed(),
        )
    }

    private fun parseChapters(data: JSONArray, mangaUrl: String): List<MangaChapter> {
        return data.mapJSONNotNull { item ->
            val coinsRequired = item.optInt("coins_required", 0)
            val lockedForever = item.optBoolean("lockedForever", false)
            if (coinsRequired > 0 || lockedForever) return@mapJSONNotNull null

            val chapterId = item.optInt("id").takeIf { it > 0 } ?: return@mapJSONNotNull null

            val chapterNumber = item.optString("chapter_number").takeIf { it.isNotEmpty() }
                ?: item.optString("title").takeIf { it.isNotEmpty() }
                ?: return@mapJSONNotNull null

            val chapterNum = chapterNumber.toFloatOrNull() ?: return@mapJSONNotNull null

            val title = item.optString("title").takeIf {
                it.isNotEmpty() && it != "null" && it != chapterNumber
            }

            val publishedAt = item.optString("published_at", "")
                .takeIf { it.isNotEmpty() }
                ?: item.optString("publishedAt", "")

            val uploadDate = runCatching {
                dateFormat.parse(publishedAt)?.time ?: 0L
            }.getOrDefault(0L)

            val chapterUrl = "$mangaUrl/$chapterId/$chapterNumber"

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
        val parts = chapter.url.split("/").filter { it.isNotEmpty() }
        val chapterId = parts.getOrNull(4) ?: return emptyList()
        val chapterUrl = "https://$domain${chapter.url}"
        val doc = webClient.httpGet(chapterUrl).parseHtml()

        val pages = mutableListOf<MangaPage>()
        var pageIndex = 0

        // اجمع كل نصوص الـ scripts بما فيها __next_f
        val allText = doc.select("script")
            .joinToString("") { it.data() }
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")

        // استخرج appImages - الصور الكاملة غير المقسمة
        val appImagesPattern = Regex(
            """"appImages":\[(.*?)\]""",
            RegexOption.DOT_MATCHES_ALL,
        )

        var foundAppImages = false
        appImagesPattern.find(allText)?.groupValues?.get(1)?.let { raw ->
            runCatching {
                val arr = JSONArray("[$raw]")
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val url = item.optString("mobile").takeIf { it.isNotEmpty() }
                        ?: item.optString("desktop").takeIf { it.isNotEmpty() }
                        ?: continue
                    pages.add(
                        MangaPage(
                            id = generateUid("${chapter.id}-$pageIndex"),
                            url = url,
                            preview = null,
                            source = source,
                        ),
                    )
                    pageIndex++
                    foundAppImages = true
                }
            }
        }

        // استخرج deferredMedia token لتحميل باقي الصور
        val tokenPattern = Regex(""""token":"([^"]+)"""")
        val splitPattern = Regex(""""splitIndex":(\d+)""")
        val token = tokenPattern.find(allText)?.groupValues?.get(1)
        val splitIndex = splitPattern.find(allText)?.groupValues?.get(1) ?: "3"

        if (!token.isNullOrEmpty()) {
            val deferredUrl = "https://$domain/chapter-deferred-media/$chapterId" +
                "?token=$token&split=$splitIndex"

            val deferredData = runCatching {
                webClient.httpGet(deferredUrl).parseJson()
            }.getOrNull()?.optJSONObject("data")

            // صور كاملة من deferred
            deferredData?.optJSONArray("images")?.let { images ->
                for (i in 0 until images.length()) {
                    val imgUrl = images.optString(i).takeIf { it.isNotEmpty() } ?: continue
                    pages.add(
                        MangaPage(
                            id = generateUid("${chapter.id}-def-$pageIndex"),
                            url = imgUrl,
                            preview = null,
                            source = source,
                        ),
                    )
                    pageIndex++
                }
            }

            // maps - شرائح تحتاج دمج
            deferredData?.optJSONArray("maps")?.let { maps ->
                for (i in 0 until maps.length()) {
                    val map = maps.optJSONObject(i) ?: continue
                    val pieces = map.optJSONArray("pieces") ?: continue
                    val orderArr = map.optJSONArray("order") ?: continue
                    val dim = map.optJSONArray("dim") ?: continue
                    val width = dim.optInt(0, 800)
                    val height = dim.optInt(1, 1000)
                    val mode = map.optString("mode", "vertical")

                    val tempPieces = (0 until pieces.length()).map { pieces.optString(it) }
                    val orderedPieces = (0 until orderArr.length())
                        .mapNotNull { tempPieces.getOrNull(orderArr.optInt(it)) }
                        .filter { it.isNotEmpty() }

                    if (orderedPieces.isEmpty()) continue

                    val encodedPieces = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(orderedPieces.joinToString("|").toByteArray())

                    val mapUrl = "prochan-map://stitch?w=$width&h=$height" +
                        "&mode=$mode&pieces=$encodedPieces"

                    pages.add(
                        MangaPage(
                            id = generateUid("${chapter.id}-map-$pageIndex"),
                            url = mapUrl,
                            preview = null,
                            source = source,
                        ),
                    )
                    pageIndex++
                }
            }
        }

        // fallback - لو appImages فشل استخدم images من deferredMedia فقط
        if (!foundAppImages && pages.isEmpty()) {
            val imagesPattern = Regex(""""images":\["(.*?)"\]""", RegexOption.DOT_MATCHES_ALL)
            imagesPattern.find(allText)?.groupValues?.get(1)?.split("\",\"")?.forEach { url ->
                if (url.isNotEmpty()) {
                    pages.add(
                        MangaPage(
                            id = generateUid("${chapter.id}-fb-$pageIndex"),
                            url = url,
                            preview = null,
                            source = source,
                        ),
                    )
                    pageIndex++
                }
            }
        }

        return pages
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
}
