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
        get() = MangaListFilterCapabilities(isSearchSupported = false)

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {
        val url = "https://$domain/api/public/content/latest-updates?limit=$pageSize&page=$page"
        val json = webClient.httpGet(url).parseJson()
        val data = json.optJSONArray("data") ?: return emptyList()

        return data.mapJSONNotNull { item ->
            val id = item.optInt("id")
            val slug = item.optString("slug")
            val title = item.optString("title")
            if (id == 0 || slug.isEmpty() || title.isEmpty()) return@mapJSONNotNull null

            Manga(
                id = generateUid("/series/$id/$slug"),
                title = title,
                url = "/series/$id/$slug",
                publicUrl = "https://$domain/series/$id/$slug",
                rating = RATING_UNKNOWN,
                coverUrl = item.optString("coverImage"),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return manga // مش محتاج تفاصيل هنا
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = "https://$domain${chapter.url}"
        val doc = webClient.httpGet(chapterUrl).parseHtml()

        val scriptContent = doc.select("script")
            .map { it.data() }
            .joinToString("")

        val unescaped = scriptContent
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        val pages = mutableListOf<MangaPage>()
        var pageIndex = 0

        // ===== maps =====
        val mapsRegex = Regex(""""maps":\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val mapsMatch = mapsRegex.find(unescaped)

        if (mapsMatch != null) {
            val mapsArr = JSONArray("[${mapsMatch.groupValues[1]}]")

            for (i in 0 until mapsArr.length()) {
                val map = mapsArr.optJSONObject(i) ?: continue
                val pieces = map.optJSONArray("pieces") ?: continue
                val orderArr = map.optJSONArray("order") ?: continue
                val dim = map.optJSONArray("dim") ?: continue

                val width = dim.optInt(0, 800)
                val height = dim.optInt(1, 1000)
                val type = map.optString("type", "vertical")

                val tempPieces = (0 until pieces.length()).map { pieces.optString(it) }
                val orderedPieces = (0 until orderArr.length())
                    .mapNotNull { tempPieces.getOrNull(orderArr.optInt(it)) }
                    .filter { it.isNotEmpty() }

                if (orderedPieces.isEmpty()) continue

                val encoded = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(orderedPieces.joinToString("|").toByteArray())

                val mapUrl = "prochan-map://stitch?w=$width&h=$height&type=$type&pieces=$encoded"

                pages.add(
                    MangaPage(
                        id = generateUid("${chapter.id}-map-$pageIndex"),
                        url = mapUrl,
                        preview = null,
                        source = source,
                    )
                )
                pageIndex++
            }
        }

        return pages
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
}
