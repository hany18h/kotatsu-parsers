package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

/**
 * MeshManga (Swat Manga) - موقع مانجا عربي (API-based)
 * Website: https://meshmanga.com
 * API: https://appswat.com/v2/api/v2/
 */
@MangaSourceParser("MESHMANGA", "MeshManga", "ar")
internal class MeshManga(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MESHMANGA, 100) {

    override val configKeyDomain = ConfigKey.Domain("meshmanga.com")
    private val apiDomain = "appswat.com"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val url = "https://$apiDomain/v2/api/v2/genres/"
        return try {
            val json = webClient.httpGet(url).parseJson()
            val results = json.getJSONArray("results")
            
            (0 until results.length()).mapNotNullToSet { i ->
                val genre = results.getJSONObject(i)
                MangaTag(
                    key = genre.getInt("id").toString(),
                    title = genre.getString("name"),
                    source = source,
                )
            }
        } catch (e: Exception) {
            emptySet()
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(apiDomain)
            append("/v2/api/v2/series/?")

            // Search query
            if (!filter.query.isNullOrEmpty()) {
                append("search=")
                append(filter.query.urlEncoded())
                append("&")
            }

            // Sort order
            append("order_by=")
            append(
                when (order) {
                    SortOrder.UPDATED -> "-updated_at"
                    SortOrder.POPULARITY -> "-views_count"
                    SortOrder.NEWEST -> "-created_at"
                    SortOrder.RATING -> "-rating"
                    else -> "-updated_at"
                }
            )

            // Genres filter
            filter.tags.forEach {
                append("&genres=")
                append(it.key)
            }

            // Type filter
            filter.types.oneOrThrowIfMany()?.let {
                val typeId = when (it) {
                    ContentType.MANGA -> "133"
                    ContentType.MANHWA -> "131"
                    ContentType.MANHUA -> "132"
                    else -> null
                }
                if (typeId != null) {
                    append("&type=")
                    append(typeId)
                }
            }

            // Status filter
            filter.states.oneOrThrowIfMany()?.let {
                val statusId = when (it) {
                    MangaState.ONGOING -> "79"
                    MangaState.FINISHED -> "80"
                    else -> null
                }
                if (statusId != null) {
                    append("&status=")
                    append(statusId)
                }
            }

            append("&page=")
            append(page)
            append("&page_size=100")
        }

        val json = webClient.httpGet(url).parseJson()
        val results = json.getJSONArray("results")

        return (0 until results.length()).mapNotNull { i ->
            parseMangaFromJson(results.getJSONObject(i))
        }
    }

    private fun parseMangaFromJson(obj: JSONObject): Manga? {
        val id = obj.getInt("id")
        val slug = obj.getString("slug")
        val title = obj.getString("title")

        val poster = obj.optJSONObject("poster")
        val coverUrl = poster?.optString("medium") 
            ?: poster?.optString("thumbnail")
            ?: ""

        val rating = obj.optString("rating", "0")
            .toFloatOrNull()
            ?.div(10) ?: RATING_UNKNOWN

        val genres = obj.optJSONArray("genres")
        val tags = if (genres != null) {
            (0 until genres.length()).mapNotNullToSet { i ->
                val genre = genres.optJSONObject(i) ?: return@mapNotNullToSet null
                MangaTag(
                    key = genre.getInt("id").toString(),
                    title = genre.getString("name"),
                    source = source,
                )
            }
        } else {
            emptySet()
        }

        val statusObj = obj.optJSONObject("status")
        val state = when (statusObj?.optString("name")) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            else -> null
        }

        val relUrl = "/series/$id"

        return Manga(
            id = generateUid(relUrl),
            url = relUrl,
            publicUrl = "https://$domain/series/$id",
            title = title,
            altTitles = emptySet(),
            coverUrl = coverUrl,
            rating = rating,
            tags = tags,
            state = state,
            authors = emptySet(),
            contentRating = ContentRating.SAFE,
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val seriesId = manga.url.substringAfterLast("/")
        
        // Get series details
        val detailsUrl = "https://$apiDomain/v2/api/v2/series/$seriesId/"
        val detailsJson = webClient.httpGet(detailsUrl).parseJson()

        val description = detailsJson.optString("description", null)
            ?.takeIf { it.isNotBlank() }

        // Get chapters
        val chaptersUrl = "https://$apiDomain/v2/api/v2/series/$seriesId/chapters/?page_size=1000"
        val chaptersJson = webClient.httpGet(chaptersUrl).parseJson()
        val chaptersArray = chaptersJson.getJSONArray("results")

        val chapters = (0 until chaptersArray.length()).mapNotNull { i ->
            val chap = chaptersArray.optJSONObject(i) ?: return@mapNotNull null
            
            val chapterId = chap.getInt("id")
            val chapterNum = chap.optString("number", (i + 1).toString())
            val chapterTitle = chap.optString("title", "Chapter $chapterNum")

            val relUrl = "/chapter/$chapterId"

            MangaChapter(
                id = generateUid(relUrl),
                url = relUrl,
                title = chapterTitle,
                number = chapterNum.toFloatOrNull() ?: (i + 1).toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }.reversed()

        return manga.copy(
            description = description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/")
        val url = "https://$apiDomain/v2/api/v2/chapters/$chapterId/"
        
        val json = webClient.httpGet(url).parseJson()
        val pagesArray = json.getJSONArray("pages")

        return (0 until pagesArray.length()).map { i ->
            val pageObj = pagesArray.getJSONObject(i)
            val imageUrl = pageObj.getString("image")

            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }
}
