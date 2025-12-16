package me.manga.yami.sources_repositry.ar.lavatoon

import android.util.Log
import kotlinx.coroutines.flow.Flow
import me.manga.yami.core.states.State
import me.manga.yami.core.storage.DataStoreHelper
import me.manga.yami.data.local.dao.SourcesDao
import me.manga.yami.data.remote.api.IMangaDataApiServices
import me.manga.yami.domain.model.ChapterItem
import me.manga.yami.domain.model.MangaInfo
import me.manga.yami.domain.model.MangaItem
import me.manga.yami.domain.model.PopularManga
import me.manga.yami.presentation.features.home.data.SearchType
import me.manga.yami.sources_repositry.common.NormalSites
import me.manga.yami.sources_repositry.data.MangaSource
import okhttp3.FormBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject

class LavatoonsRepositoryv2 @Inject constructor(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
) : NormalSites(dataStore, api, sourcesRepository) {

    companion object {
        private const val TAG = "LavatoonsRepo"
    }

    override val mangaSource: MangaSource
        get() = MangaSource.LAVATOONS

    override var imgBaseUrl: String = mangaSource.BASEURL
    override var imgUrlVersion: Int = 0

    override val BASE_URL: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }
    override val API: String = mangaSource.API
    override val LANGUAGE: String by lazy { mangaSource.LANGUAGE.Language }
    override val homeUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }
    override val popularUrl: String by lazy { baseUrl.ifBlank { mangaSource.BASEURL } }

    override fun handelLoadMoreUrl(page: Int): String {
        return "${baseUrl.ifBlank { mangaSource.BASEURL }}manga/?page=$page&status=&type=&order=update"
    }

    override fun handelSearchUrl(searchType: SearchType): String =
        when (searchType) {
            is SearchType.Normal -> "${baseUrl.ifBlank { mangaSource.BASEURL }}?s=${searchType.toNormalQuery()}"
            is SearchType.GENRES -> ""
            is SearchType.SORT -> ""
        }

    override suspend fun initSite(): Int {
        val headers = dataStore.getHeadersForApi(API) ?: emptyMap()
        _cachedHeaders = headers
        return super.initSite()
    }

    private val refererHeader = "Referer" to "https://lavatoons.com/"

    @Volatile
    private var _cachedHeaders: Map<String, String>? = null

    override val defaultHeaders: Map<String, String>
        get() {
            val base = _cachedHeaders ?: emptyMap()
            return base + refererHeader
        }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        val merged = newHeaders + refererHeader
        _cachedHeaders = merged
        dataStore.saveHeadersForApi(API, merged)
    }

    override fun handelFormBody(page: Int, popular: Boolean): FormBody? = null

    override val sortTypes: Set<String> get() = setOf()
    override val allGenres: Set<String> get() = setOf()
    override val blackListGenres: Set<String> get() = setOf()

    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        return fetchDataWithHeaders({ api.get(url, defaultHeaders) }) { html -> 
            getSearchResults(html) 
        }
    }

    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>> {
        return kotlinx.coroutines.flow.flow { emit(State.Error.fromCode(0)) }
    }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        return kotlinx.coroutines.flow.flow { emit(State.Error.fromCode(0)) }
    }

    override fun normalSearchFormBody(searchType: SearchType.Normal): FormBody? = null
    override fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody? = null
    override fun sortFormBody(searchType: SearchType.SORT): FormBody? = null

    // ✅ الدالة الأهم: استخراج صور الفصل
    override fun getChapterImages(html: String): List<String> {
        Log.d(TAG, "getChapterImages: Starting extraction")
        
        try {
            val doc = Jsoup.parse(html)

            // 🔥 Method 1: Extract from ts_reader.run() JavaScript
            val scriptWithJson = doc.select("script")
                .map { it.html() }
                .firstOrNull { it.contains("ts_reader.run") }

            if (scriptWithJson != null) {
                Log.d(TAG, "getChapterImages: Found ts_reader.run script")
                
                try {
                    // Extract JSON from: ts_reader.run({...});
                    val jsonText = scriptWithJson
                        .substringAfter("ts_reader.run(")
                        .substringBeforeLast(");")
                        .trim()

                    if (jsonText.startsWith("{") && jsonText.endsWith("}")) {
                        val root = JSONObject(jsonText)
                        val sources = root.optJSONArray("sources")
                        
                        if (sources != null && sources.length() > 0) {
                            val firstSource = sources.getJSONObject(0)
                            val imagesArray = firstSource.optJSONArray("images")
                            
                            if (imagesArray != null && imagesArray.length() > 0) {
                                val imagesList = (0 until imagesArray.length())
                                    .mapNotNull { idx -> 
                                        imagesArray.optString(idx).takeIf { it.isNotBlank() }
                                    }
                                
                                Log.d(TAG, "getChapterImages: Extracted ${imagesList.size} images from ts_reader")
                                return imagesList
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "getChapterImages: Error parsing ts_reader JSON", e)
                }
            }

            // 🔥 Method 2: Fallback - Extract from div#readerarea
            Log.d(TAG, "getChapterImages: Trying fallback method")
            val normalImages = doc.select("div#readerarea img, div.reading-content img")
                .mapNotNull { img ->
                    img.attr("src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                }

            if (normalImages.isNotEmpty()) {
                Log.d(TAG, "getChapterImages: Found ${normalImages.size} images using fallback")
                return normalImages
            }

            Log.w(TAG, "getChapterImages: No images found!")
            return emptyList()

        } catch (e: Exception) {
            Log.e(TAG, "getChapterImages: Fatal error", e)
            return emptyList()
        }
    }

    override fun getSearchResults(html: String): List<MangaItem> {
        val doc = Jsoup.parse(html)

        return doc.select("div.listupd div.bsx a").mapNotNull { link ->
            val pageUrl = link.absUrl("href").takeIf { it.isNotBlank() } 
                ?: return@mapNotNull null

            val imgEl = link.selectFirst("img") ?: return@mapNotNull null
            val imageUrl = imgEl.absUrl("src")

            val titleEl = link.selectFirst("div.tt") ?: return@mapNotNull null
            val title = titleEl.text().trim()

            val rating = link.selectFirst("div.numscore")
                ?.text()
                ?.toFloatOrNull()
                ?.times(10f)
                ?.toInt()
                ?: 0

            MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = pageUrl,
                imageUrl = imageUrl,
                rating = rating,
                chapters = emptyList(),
                genres = emptyList()
            )
        }
    }

    fun parseChapterDate(dateStr: String): LocalDate? {
        if (dateStr.isBlank() || dateStr.equals("NEW", ignoreCase = true)) {
            return LocalDate.now()
        }

        if (dateStr.trim().equals("يومين ago", ignoreCase = true)) {
            return LocalDate.now().minusDays(2)
        }

        val relRegex = """(\d+)\s*(ثانية|ثواني|دقيقة|دقائق|ساعة|ساعات|يوم|أيام|يومين|يومان)\s*ago""".toRegex()
        relRegex.find(dateStr)?.let { m ->
            val amount = m.groupValues[1].toLong()
            val unit = m.groupValues[2]
            val now = LocalDateTime.now()
            val dt = when (unit) {
                "ثانية", "ثواني" -> now.minusSeconds(amount)
                "دقيقة", "دقائق" -> now.minusMinutes(amount)
                "ساعة", "ساعات" -> now.minusHours(amount)
                "يوم", "أيام", "يومين", "يومان" -> now.minusDays(amount)
                else -> now
            }
            return dt.toLocalDate()
        }

        val arabicFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale("ar"))
        try {
            return LocalDate.parse(dateStr, arabicFormatter)
        } catch (_: DateTimeParseException) { }

        val englishFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
        return try {
            LocalDate.parse(dateStr, englishFormatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    override fun extractHomeMangaItems(html: String): MutableList<MangaItem> {
        val updates = mutableListOf<MangaItem>()
        val doc = Jsoup.parse(html)

        val updatesContainer = doc.getElementById("manga-posts") ?: return updates

        for (bsx in updatesContainer.select(".bsx")) {
            val mangaLink = bsx.selectFirst("a[title]") ?: continue
            val title = mangaLink.attr("title").trim()
            val url = mangaLink.attr("href").trim()

            val imageUrl = bsx.selectFirst(".limit img")
                ?.attr("src")
                ?.trim()
                ?: continue

            val chapters = bsx.select(".chapter-list a")
                .filter { chA -> chA.selectFirst("i.fas.fa-coins") == null }
                .mapNotNull { chA ->
                    val chapNum = chA.selectFirst(".epxs")?.text()?.trim() 
                        ?: return@mapNotNull null
                    val chapUrl = chA.attr("href").trim()
                    val dateTxt = chA.selectFirst(".epxdate")?.text()?.trim() ?: ""
                    val date = parseChapterDate(dateTxt) ?: LocalDate.now()

                    ChapterItem(
                        number = chapNum,
                        name = chapNum,
                        url = chapUrl,
                        date = date
                    )
                }

            updates += MangaItem(
                api = API,
                language = LANGUAGE,
                title = title,
                url = url,
                imageUrl = imageUrl,
                rating = 0,
                chapters = chapters,
                genres = emptyList()
            )
        }

        return updates
    }

    override fun extractMangaList(html: String): List<PopularManga> {
        val popularList = mutableListOf<PopularManga>()
        val doc: Document = Jsoup.parse(html)

        val popularSection = doc.selectFirst(
            ".hotslid:has(.releases h2:matchesOwn(الأعمال الرائجة))"
        ) ?: return popularList

        val slideItems = popularSection.select(".pop-list .swiper-slide")

        for (item in slideItems) {
            val linkElement = item.selectFirst("a")
            val imageElement = item.selectFirst(".limit img") 
                ?: item.selectFirst("img.ts-post-image")

            if (linkElement != null && imageElement != null) {
                val title = linkElement.attr("title")
                    .ifBlank { imageElement.attr("alt").ifBlank { "Untitled" } }
                    .trim()
                val url = linkElement.attr("href").trim()
                val imageUrl = imageElement.attr("src").trim()

                popularList.add(
                    PopularManga(
                        api = API,
                        language = LANGUAGE,
                        title = title,
                        url = url,
                        imageUrl = imageUrl
                    )
                )
            }
        }

        return popularList
    }

    override suspend fun extractMangaInfo(html: String, url: String): MangaInfo {
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.entry-title")?.text().orEmpty()
        val imageUrl = doc.selectFirst("img[itemprop=image]")?.attr("src").orEmpty()
        val rating = doc.selectFirst("div.numscore")?.text().orEmpty()
        val ratingCount = ""

        val description = doc.select("div.entry-content.entry-content-single p")
            .map { it.text() }
            .joinToString("\n")
        val otherNames = ""

        val author = doc.selectFirst(".tsinfo span[itemprop=author] i[itemprop=name]")?.text().orEmpty()
        val artist = ""

        val genres = doc.select("span.mgen a").eachText()
        val tags = emptyList<String>()

        val datePublished = doc.selectFirst("time[itemprop=datePublished]")?.attr("datetime").orEmpty()
        val yearOfProduction = runCatching {
            LocalDate.parse(datePublished.take(10)).year.toString()
        }.getOrDefault("")
        
        val status = doc.selectFirst("div.status i")?.text().orEmpty()
        val favoritesCount = doc.select("div.tsinfo .imptdt")
            .firstOrNull { it.text().contains("المشاهدات") }
            ?.selectFirst("i")?.text().orEmpty()

        val chapters = doc.select("div.eplister#chapterlist li")
            .mapNotNull { el ->
                val link = el.selectFirst("a") ?: return@mapNotNull null
                val href = link.attr("href").takeIf { it.isNotBlank() } 
                    ?: return@mapNotNull null

                val numText = el.selectFirst("span.chapternum")?.text().orEmpty()
                val rawDate = el.selectFirst("span.chapterdate")?.text().orEmpty()

                ChapterItem(
                    number = numText,
                    name = numText,
                    url = href,
                    date = parseChapterDate(rawDate) ?: LocalDate.now(),
                    isDownloaded = false,
                    isBookmarked = false,
                    chaptersImages = emptyList()
                )
            }
            .toMutableList()

        return MangaInfo(
            api = API,
            language = LANGUAGE,
            url = url,
            title = title,
            imageUrl = imageUrl,
            rating = rating,
            ratingCount = ratingCount,
            description = description,
            otherNames = otherNames,
            author = author,
            artist = artist,
            genres = genres,
            tags = tags,
            yearOfProduction = yearOfProduction,
            status = status,
            favoritesCount = favoritesCount,
            chapters = chapters
        )
    }
}
