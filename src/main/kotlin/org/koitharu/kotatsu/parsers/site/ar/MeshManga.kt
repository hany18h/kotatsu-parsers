package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser(
    name = "MESHMANGA",
    title = "MeshManga",
    locale = "ar"
)
class MeshManga(
    context: MangaLoaderContext
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.MESHMANGA,
    pageSize = 40,
    searchPageSize = 40
) {

    // مهم للـ AbstractParser
    override val configKeyDomain = ConfigKey.Domain("meshmanga.com")

    // الموقع مفيهوش فلترة، فنعمل capabilities بسيطة
    override val filterCapabilities: MangaListFilterCapabilities =
        MangaListFilterCapabilities(
            isSearchSupported = false,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(
            availableTags = emptySet(),
            availableStates = emptySet(),
            availableContentTypes = setOf(ContentType.MANGA)
        )

    private val apiBase = "https://meshmanga.com/api"

    // --------------------------------------------------------------------
    // 1. List Page
    // --------------------------------------------------------------------
    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {

        val url = "$apiBase/series?page=$page"

        val raw = webClient.httpGet(url).parseRaw()
        val json = JSONObject(raw)
        val data = json.getJSONArray("data")

        val list = ArrayList<Manga>()

        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)

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
                    state = null,
                    authors = emptySet(),
                    source = source
                )
            )
        }

        return list
    }

    // --------------------------------------------------------------------
    // 2. Manga Details + Chapters
    // --------------------------------------------------------------------
    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.substringAfter("/series/").toInt()
        val url = "$apiBase/series/$id"

        val raw = webClient.httpGet(url).parseRaw()
        val json = JSONObject(raw)

        val title = json.getString("title")
        val desc = json.optString("description", "")

        val state = when (json.optString("status")) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            else -> null
        }

        val chaptersJson = json.getJSONArray("chapters")
        val chapters = ArrayList<MangaChapter>()

        for (i in 0 until chaptersJson.length()) {
            val c = chaptersJson.getJSONObject(i)

            val chId = c.getInt("id")
            val chTitle = c.getString("title")
            val slug = "/chapter/$chId"

            chapters.add(
                MangaChapter(
                    id = generateUid(slug),
                    title = chTitle,
                    number = (i + 1).toFloat(),
                    volume = 0,
                    url = slug,
                    scanlator = null,
                    uploadDate = 0L, // مهم علشان مش nullable
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

    // --------------------------------------------------------------------
    // 3. Pages (Images)
    // --------------------------------------------------------------------
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val id = chapter.url.substringAfter("/chapter/").toInt()
        val url = "$apiBase/chapter/$id"

        val raw = webClient.httpGet(url).parseRaw()
        val json = JSONObject(raw)

        val arr = json.getJSONArray("pages")
        val list = ArrayList<MangaPage>()

        for (i in 0 until arr.length()) {
            val img = arr.getString(i)

            list.add(
                MangaPage(
                    id = generateUid(img),
                    url = img,
                    preview = null,
                    source = source
                )
            )
        }

        return list
    }
}
