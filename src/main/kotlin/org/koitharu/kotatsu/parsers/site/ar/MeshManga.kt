package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey

@MangaSourceParser("MESHMANGA", "MeshManga", "ar")
internal class MeshManga(
    context: MangaLoaderContext
) : AbstractMangaParser(context, MangaParserSource.MESHMANGA) {

    private val api = "https://meshmanga.com/api"

    override val availableSortOrders: Set<SortOrder> =
        setOf(SortOrder.UPDATED, SortOrder.NEWEST)

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = false
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(ConfigKey.Domain("meshmanga.com"))
    }

    override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {

        val page = (offset / 40) + 1
        val json = JSONObject(webClient.httpGet("$api/series?page=$page").parseRaw())

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
                    tags = emptySet<MangaTag>(),
                    authors = emptySet<String>(),
                    state = null,
                    source = source
                )
            )
        }

        return list
    }

    override suspend fun getDetails(manga: Manga): Manga {

        val id = manga.url.substringAfter("/series/").toInt()
        val json = JSONObject(webClient.httpGet("$api/series/$id").parseRaw())

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
        val json = JSONObject(webClient.httpGet("$api/chapter/$id").parseRaw())
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
