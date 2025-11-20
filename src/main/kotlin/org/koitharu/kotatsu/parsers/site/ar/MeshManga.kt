package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("MESHMANGA", "MeshManga", "ar")
internal class MeshManga(
    context: MangaLoaderContext
) : MangaReaderParser(
    context,
    MangaParserSource.MESHMANGA,
    "meshmanga.com",
    pageSize = 40,
    searchPageSize = 40
) {

    private val api = "https://meshmanga.com/api"

    override val listUrl = "/api/series"

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = false,
        isMultipleTagsSupported = false,
        isTagsExclusionSupported = false
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = emptySet(),
            availableStates = emptySet(),
            availableContentTypes = setOf(ContentType.MANGA)
        )
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {

        val url = "$api/series?page=$page"
        val json = JSONObject(webClient.httpGet(url).parseRaw())

        val arr = json.getJSONArray("data")
        val list = ArrayList<Manga>()

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)

            val id = item.getInt("id")
            val title = item.getString("title")
            val cover = item.getString("cover")

            val slug = "/series/$id"

            list.add(
                Manga(
                    id = generateUid(slug),
                    url = slug,
                    publicUrl = "https://meshmanga.com$slug",
                    title = title,
                    altTitles = emptySet(),
                    coverUrl = cover,
                    rating = RATING_UNKNOWN,
                    tags = emptySet(),
                    authors = emptySet(),
                    state = null,
                    source = source
                )
            )
        }

        return list
    }

    override suspend fun getDetails(manga: Manga): Manga {

        val id = manga.url.substringAfter("/series/").toInt()
        val infoUrl = "$api/series/$id"

        val json = JSONObject(webClient.httpGet(infoUrl).parseRaw())

        val title = json.getString("title")
        val desc = json.optString("description", "")

        val state = when (json.optString("status")) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            else -> null
        }

        val chaptersArr = json.getJSONArray("chapters")
        val chapters = ArrayList<MangaChapter>()

        for (i in 0 until chaptersArr.length()) {
            val ch = chaptersArr.getJSONObject(i)

            val chId = ch.getInt("id")
            val chTitle = ch.getString("title")
            val slug = "/chapter/$chId"

            chapters.add(
                MangaChapter(
                    id = generateUid(slug),
                    title = chTitle,
                    number = (i + 1).toFloat(),
                    volume = 0,
                    url = slug,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source
                )
            )
        }

        return manga.copy(
            title = title,
            description = desc,
            chapters = chapters,
            state = state
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {

        val id = chapter.url.substringAfter("/chapter/").toInt()
        val url = "$api/chapter/$id"

        val json = JSONObject(webClient.httpGet(url).parseRaw())
        val arr = json.getJSONArray("pages")

        val pages = ArrayList<MangaPage>()

        for (i in 0 until arr.length()) {
            val img = arr.getString(i)

            pages.add(
                MangaPage(
                    id = generateUid(img),
                    url = img,
                    preview = null,
                    source = source
                )
            )
        }

        return pages
    }
}
