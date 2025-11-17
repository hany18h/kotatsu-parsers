package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MESHMANGA", "MeshManga", "ar")
class MeshManga(
    context: MangaLoaderContext
) : PagedMangaParser(
    context,
    MangaParserSource.create("MESHMANGA"),
    pageSize = 40,
    searchPageSize = 40
) {

    private val domain = "meshmanga.com"

    override val availableSortOrders: Set<SortOrder>
        get() = setOf(SortOrder.UPDATED)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://$domain/api/series?page=$page"

        val raw = webClient.httpGet(url).parseRaw()
        val json = JSONObject(raw)
        val array = json.getJSONArray("data")

        val list = ArrayList<Manga>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)

            val id = item.getInt("id")
            val title = item.getString("title")
            val cover = item.getString("cover")

            val slug = "/series/$id"

            list.add(
                Manga(
                    id = generateUid(slug),
                    url = slug,
                    publicUrl = "https://$domain$slug",
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

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.substringAfter("/series/").toInt()
        val url = "https://$domain/api/series/$id"

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
            val ch = chaptersJson.getJSONObject(i)

            val chId = ch.getInt("id")
            val chTitle = ch.getString("title")

            val chSlug = "/chapter/$chId"

            chapters.add(
                MangaChapter(
                    id = generateUid(chSlug),
                    url = chSlug,
                    title = chTitle,
                    number = (i + 1).toFloat(),
                    volume = 0,
                    scanlator = null,
                    uploadDate = null,
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
        val url = "https://$domain/api/chapter/$id"

        val raw = webClient.httpGet(url).parseRaw()
        val json = JSONObject(raw)

        val imgs = json.getJSONArray("pages")

        val pages = ArrayList<MangaPage>()

        for (i in 0 until imgs.length()) {
            val image = imgs.getString(i)

            pages.add(
                MangaPage(
                    id = generateUid(image),
                    url = image,
                    preview = null,
                    source = source
                )
            )
        }

        return pages
    }
}
