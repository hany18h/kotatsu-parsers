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

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("/").filter { it.isNotEmpty() }
        val chapterId = parts.getOrNull(4) ?: return emptyList()

        val chapterUrl = "https://$domain${chapter.url}"
        val doc = webClient.httpGet(chapterUrl).parseHtml()

        val scriptContent = doc.select("script")
            .map { it.data() }
            .filter { it.contains("appImages") || it.contains("deferredMedia") || it.contains("maps") }
            .joinToString("")

        if (scriptContent.isEmpty()) return emptyList()

        val unescaped = scriptContent
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        val pages = mutableListOf<MangaPage>()
        var pageIndex = 0

        // ================= appImages =================
        val appImagesRegex = Regex(""""appImages":\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val appImagesMatch = appImagesRegex.find(unescaped)

        if (appImagesMatch != null) {
            runCatching {
                val arr = JSONArray("[${appImagesMatch.groupValues[1]}]")
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val imgUrl = item.optString("mobile").ifEmpty {
                        item.optString("desktop")
                    }
                    if (imgUrl.isEmpty()) continue

                    pages.add(
                        MangaPage(
                            id = generateUid("${chapter.id}-$pageIndex"),
                            url = imgUrl,
                            preview = null,
                            source = source,
                        ),
                    )
                    pageIndex++
                }
            }
        }

        // ================= deferred =================
        val tokenMatch = Regex(""""token":"([^"]+)"""").find(unescaped)
        val splitMatch = Regex(""""splitIndex":(\d+)""").find(unescaped)

        if (tokenMatch != null && splitMatch != null) {
            val token = tokenMatch.groupValues[1]
            val split = splitMatch.groupValues[1]

            val deferredUrl =
                "https://$domain/chapter-deferred-media/$chapterId?token=$token&split=$split"

            val deferredJson = runCatching {
                webClient.httpGet(deferredUrl).parseJson()
            }.getOrNull()

            val images = deferredJson
                ?.optJSONObject("data")
                ?.optJSONArray("images")

            if (images != null) {
                for (i in 0 until images.length()) {
                    val imgUrl = images.optString(i)
                    if (imgUrl.isEmpty()) continue

                    pages.add(
                        MangaPage(
                            id = generateUid("${chapter.id}-$pageIndex"),
                            url = imgUrl,
                            preview = null,
                            source = source,
                        ),
                    )
                    pageIndex++
                }
            }
        }

        // ================= MAPS (المهم) =================
        val mapsRegex = Regex(""""maps":\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val mapsMatch = mapsRegex.find(unescaped)

        if (mapsMatch != null) {
            runCatching {
                val mapsArr = JSONArray("[${mapsMatch.groupValues[1]}]")

                for (i in 0 until mapsArr.length()) {
                    val map = mapsArr.optJSONObject(i) ?: continue

                    val pieces = map.optJSONArray("pieces") ?: continue
                    val orderArr = map.optJSONArray("order") ?: continue
                    val dim = map.optJSONArray("dim") ?: continue

                    val width = dim.optInt(0, 800)
                    val height = dim.optInt(1, 1000)
                    val type = map.optString("type", "vertical")

                    val tempPieces =
                        (0 until pieces.length()).map { pieces.optString(it) }

                    val orderedPieces =
                        (0 until orderArr.length())
                            .mapNotNull { tempPieces.getOrNull(orderArr.optInt(it)) }
                            .filter { it.isNotEmpty() }

                    if (orderedPieces.isEmpty()) continue

                    val encodedPieces = orderedPieces.joinToString("|")

                    val mapUrl =
                        "prochan-map://stitch?w=$width&h=$height&type=$type&pieces=${
                            android.util.Base64.encodeToString(
                                encodedPieces.toByteArray(),
                                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                            )
                        }"

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

        return pages
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
}
