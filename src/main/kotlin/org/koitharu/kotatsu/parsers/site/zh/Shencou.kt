package org.koitharu.kotatsu.parsers.site.zh

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.*
import java.net.URLEncoder
import java.util.EnumSet

@MangaSourceParser("SHENCOU", "神凑轻小说", "zh", type = ContentType.NOVEL)
internal class Shencou(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SHENCOU, pageSize = 30),
    Interceptor,
    MangaParserAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("www.shencou.com")

    override val userAgentKey = ConfigKey.UserAgent(
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.NEWEST
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {

        val categoryTags = linkedSetOf(
            MangaTag("全部", "class:0", source),
            MangaTag("电击文库", "class:1", source),
            MangaTag("富士见文库", "class:2", source),
            MangaTag("角川文库", "class:3", source),
            MangaTag("MFJ文库", "class:4", source),
            MangaTag("Fami通文库", "class:5", source),
            MangaTag("GA文库", "class:6", source),
            MangaTag("HJ文库", "class:7", source),
            MangaTag("一迅社", "class:8", source),
            MangaTag("集英社", "class:9", source),
            MangaTag("少女文库", "class:10", source),
            MangaTag("SF文库", "class:11", source),
            MangaTag("讲谈社", "class:12", source)
        )

        val rankTags = linkedSetOf(
            MangaTag("默认", "sort:default", source),
            MangaTag("总排行榜", "sort:allvisit", source),
            MangaTag("总推荐榜", "sort:allvote", source),
            MangaTag("月排行榜", "sort:monthvisit", source),
            MangaTag("月推荐榜", "sort:monthvote", source),
            MangaTag("周排行榜", "sort:weekvisit", source),
            MangaTag("周推荐榜", "sort:weekvote", source),
            MangaTag("最新入库", "sort:postdate", source),
            MangaTag("最近更新", "sort:lastupdate", source),
            MangaTag("总收藏榜", "sort:goodnum", source),
            MangaTag("字数排行", "sort:size", source)
        )

        return MangaListFilterOptions(
            availableTags = (categoryTags + rankTags).toSet()
        )
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("User-Agent", userAgentKey.defaultValue)
        .add("Referer", "https://$domain/")
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.header("Referer") != null) {
            return chain.proceed(request)
        }

        val newRequest = request.newBuilder()
            .header("Referer", "https://$domain/")
            .build()

        return chain.proceed(newRequest)
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {

        val query = filter.query?.trim().orEmpty()

        if (query.isNotEmpty()) {

            val encodedQuery = try {
                URLEncoder.encode(query, "GBK")
            } catch (e: Exception) {
                query
            }

            val url =
                "https://$domain/modules/article/search.php?searchtype=articlename&searchkey=$encodedQuery&page=$page"

            val response = webClient.httpGet(url, getRequestHeaders())

            if (CloudFlareHelper.checkResponseForProtection(response)
                != CloudFlareHelper.PROTECTION_NOT_DETECTED
            ) {
                context.requestBrowserAction(this, url)
            }

            return parseSearchList(response.parseHtml())
        }

        val url = "https://$domain/modules/article/articlelist.php?page=$page"

        val response = webClient.httpGet(url, getRequestHeaders())

        if (CloudFlareHelper.checkResponseForProtection(response)
            != CloudFlareHelper.PROTECTION_NOT_DETECTED
        ) {
            context.requestBrowserAction(this, url)
        }

        return parseExploreList(response.parseHtml())
    }

    private fun parseSearchList(doc: Document): List<Manga> {

        val list = mutableListOf<Manga>()

        val rows = doc.select("table tr")

        rows.drop(1).forEach { tr ->

            val a = tr.selectFirst("a") ?: return@forEach

            val href = a.attrAsAbsoluteUrlOrNull("href")?.toRelativePath() ?: return@forEach

            list += Manga(
                id = generateUid(href),
                title = a.text(),
                url = href,
                publicUrl = a.absUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = sourceContentRating,
                coverUrl = generateCoverUrl(href),
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source
            )
        }

        return list
    }

    private fun parseExploreList(doc: Document): List<Manga> {

        val list = mutableListOf<Manga>()

        doc.select("table tr").drop(1).forEach { tr ->

            val a = tr.selectFirst("a") ?: return@forEach

            val href = a.attrAsAbsoluteUrlOrNull("href")?.toRelativePath() ?: return@forEach

            list += Manga(
                id = generateUid(href),
                title = a.text(),
                url = href,
                publicUrl = a.absUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = sourceContentRating,
                coverUrl = generateCoverUrl(href),
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source
            )
        }

        return list
    }

    override suspend fun getDetails(manga: Manga): Manga {

        val url = "https://$domain${manga.url}"

        val response = webClient.httpGet(url, getRequestHeaders())

        if (CloudFlareHelper.checkResponseForProtection(response)
            != CloudFlareHelper.PROTECTION_NOT_DETECTED
        ) {
            context.requestBrowserAction(this, url)
        }

        val doc = response.parseHtml()

        val desc = doc.body().text()

        return manga.copy(
            description = desc
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {

        val url = "https://$domain${chapter.url}"

        val response = webClient.httpGet(url, getRequestHeaders())

        val doc = response.parseHtml()

        val html = doc.body().html()

        return listOf(
            MangaPage(
                id = generateUid(chapter.url),
                url = html.toDataUrl(context),
                preview = null,
                source = source
            )
        )
    }

    override val authUrl: String = "https://$domain/login.php"

    override suspend fun isAuthorized(): Boolean {
        return context.cookieJar.getCookies(domain)
            .any { it.name == "jieqiUserInfo" }
    }

    override suspend fun getUsername(): String {

        val cookie = context.cookieJar.getCookies(domain)
            .find { it.name == "jieqiUserInfo" }
            ?.value ?: throw AuthRequiredException(source)

        return cookie
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private fun generateCoverUrl(mangaUrl: String): String {

        val id = Regex("(\\d+)").find(mangaUrl)?.groupValues?.get(1) ?: return ""

        val iid = id.toInt() / 1000

        return "https://www.shencou.com/files/article/image/$iid/$id/${id}s.jpg"
    }

    private fun String.toRelativePath(): String {
        return this.replace(
            Regex("^https?://(www\\.)?shencou\\.com/?"),
            "/"
        )
    }

    private fun String.toDataUrl(context: MangaLoaderContext): String {
        val encoded = context.encodeBase64(toByteArray(Charsets.UTF_8))
        return "data:text/html;base64,$encoded"
    }
}
