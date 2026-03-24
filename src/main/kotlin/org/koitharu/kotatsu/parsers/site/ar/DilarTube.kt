package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DILARTUBE", "Dilar Tube", "ar", ContentType.MANGA)
internal class DilarTube(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.DILARTUBE, 24) {

    override val configKeyDomain = ConfigKey.Domain("dilar.tube")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
        )

    override val availableSortOrders: Set<SortOrder> = setOf(SortOrder.RELEVANCE)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val response = webClient.httpGet("https://dilar.tube/api/categories").parseJsonArray()
        val tags = mutableSetOf<MangaTag>()

        for (i in 0 until response.length()) {
            val group = response.getJSONObject(i)
            val groupId = group.optString("id").toIntOrNull() ?: group.optInt("id")
            val categories = group.getJSONArray("categories")

            val prefix = if (groupId == 3) "seriesType" else "categories"

            for (j in 0 until categories.length()) {
                val category = categories.getJSONObject(j)
                val catId = category.optString("id").toIntOrNull() ?: category.optInt("id")
                tags.add(
                    MangaTag(
                        key = "$prefix:$catId",
                        title = category.getString("name"),
                        source = source,
                    )
                )
            }
        }
        return tags
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val hasSearch = !filter.query.isNullOrBlank()
        val hasTagFilters = filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty()

        // الصفحة الرئيسية بدون بحث أو فلاتر
        if (!hasSearch && !hasTagFilters) {
            val url = "https://dilar.tube/api/series/?page=$page"
            val response = webClient.httpGet(url).parseJson()
            val series = response.getJSONArray("series")
            return (0 until series.length()).map { i ->
                parseMangaFromJson(series.getJSONObject(i))
            }
        }

        // بحث بالنص فقط أو بحث مع فلاتر — كلاهما يستخدم filter endpoint
        val url = "https://dilar.tube/api/search/filter"

        val seriesTypeInclude = mutableListOf<Int>()
        val seriesTypeExclude = mutableListOf<Int>()
        val categoriesInclude = mutableListOf<Int>()
        val categoriesExclude = mutableListOf<Int>()

        filter.tags.forEach { tag ->
            val parts = tag.key.split(":")
            if (parts.size == 2) {
                val type = parts[0]
                val id = parts[1].toIntOrNull() ?: return@forEach
                if (type == "seriesType") seriesTypeInclude.add(id)
                else if (type == "categories") categoriesInclude.add(id)
            }
        }

        filter.tagsExclude.forEach { tag ->
            val parts = tag.key.split(":")
            if (parts.size == 2) {
                val type = parts[0]
                val id = parts[1].toIntOrNull() ?: return@forEach
                if (type == "seriesType") seriesTypeExclude.add(id)
                else if (type == "categories") categoriesExclude.add(id)
            }
        }

        val jsonBody = JSONObject()

        // أضف query فقط لو مش فاضي — السيرفر بيرفض query فاضي بـ 400
        if (hasSearch) {
            jsonBody.put("query", filter.query)
        }

        val seriesTypeObject = JSONObject()
        seriesTypeObject.put("include", JSONArray().apply { seriesTypeInclude.forEach { put(it) } })
        seriesTypeObject.put("exclude", JSONArray().apply { seriesTypeExclude.forEach { put(it) } })
        jsonBody.put("seriesType", seriesTypeObject)

        jsonBody.put("oneshot", false)

        val categoriesObject = JSONObject()
        categoriesObject.put("include", JSONArray().apply { categoriesInclude.forEach { put(it) } })
        categoriesObject.put("exclude", JSONArray().apply { categoriesExclude.forEach { put(it) } })
        jsonBody.put("categories", categoriesObject)

        val chaptersObject = JSONObject()
        chaptersObject.put("min", JSONObject.NULL)
        chaptersObject.put("max", JSONObject.NULL)
        jsonBody.put("chapters", chaptersObject)

        val datesObject = JSONObject()
        datesObject.put("start", JSONObject.NULL)
        datesObject.put("end", JSONObject.NULL)
        jsonBody.put("dates", datesObject)

        jsonBody.put("page", page)

        val response = webClient.httpPost(url.toHttpUrl(), jsonBody).parseJson()

        val rows = when {
            response.has("rows") -> response.getJSONArray("rows")
            response.has("series") -> response.getJSONArray("series")
            else -> JSONArray()
        }

        return (0 until rows.length()).map { i ->
            parseMangaFromJson(rows.getJSONObject(i))
        }
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val id = json.getInt("id")
        val title = json.getString("title")
        val cover = json.optString("cover", "")
        val summary = json.optString("summary", "")

        val coverUrl = if (cover.isNotEmpty()) {
            if (cover.startsWith("http")) cover
            else {
                val coverName = cover.substringBeforeLast('.') + ".webp"
                "https://dilar.tube/uploads/manga/cover/$id/large_$coverName"
            }
        } else ""

        val rating = json.optString("rating", "0.00").toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN

        val synonyms = json.optJSONObject("synonyms")
        val altTitles = mutableSetOf<String>()
        synonyms?.let { syn ->
            fun addIfValid(value: Any?) {
                when (value) {
                    is String -> if (value.isNotEmpty() && value != "null") altTitles.add(value)
                    is JSONArray -> for (k in 0 until value.length()) {
                        val s = value.optString(k)
                        if (s.isNotEmpty() && s != "null") altTitles.add(s)
                    }
                }
            }
            addIfValid(syn.opt("arabic"))
            addIfValid(syn.opt("english"))
            addIfValid(syn.opt("japanese"))
            addIfValid(syn.opt("alternative"))
        }

        val status = json.optString("story_status", "")
        val state = when (status.lowercase()) {
            "completed" -> MangaState.FINISHED
            "ongoing" -> MangaState.ONGOING
            "hiatus" -> MangaState.PAUSED
            else -> null
        }

        return Manga(
            id = generateUid(id.toLong()),
            title = title,
            url = "/series/$id",
            publicUrl = "https://v2.dilar.tube/series/$id",
            coverUrl = coverUrl,
            source = source,
            rating = rating,
            altTitles = altTitles,
            contentRating = ContentRating.SAFE,
            tags = emptySet(),
            state = state,
            authors = emptySet(),
            largeCoverUrl = null,
            description = summary.takeIf { it.isNotEmpty() },
            chapters = null,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.substringAfterLast("/")
        val url = "https://dilar.tube/api/series/$id"
        val json = webClient.httpGet(url).parseJson()

        val title = json.getString("title")
        val summary = json.optString("summary").nullIfEmpty()

        val cover = json.optString("cover").nullIfEmpty()
        val coverUrl = if (cover != null) {
            val coverName = cover.substringBeforeLast('.') + ".webp"
            "https://dilar.tube/uploads/manga/cover/$id/large_$coverName"
        } else manga.coverUrl

        val statusStr = json.optString("story_status")
        val state = when (statusStr?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            else -> null
        }

        val authors = mutableSetOf<String>()
        json.optJSONObject("creator")?.let {
            authors.add(it.getString("nick"))
        }

        val tags = mutableSetOf<MangaTag>()
        val categories = json.optJSONArray("categories")
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                tags.add(
                    MangaTag(
                        key = cat.getInt("id").toString(),
                        title = cat.getString("name"),
                        source = source,
                    )
                )
            }
        }

        return manga.copy(
            title = title,
            description = summary,
            coverUrl = coverUrl,
            state = state,
            authors = authors,
            tags = tags,
            chapters = getChapters(id),
        )
    }

    private suspend fun getChapters(seriesId: String): List<MangaChapter> {
        val url = "https://dilar.tube/api/series/$seriesId/chapters"
        val response = webClient.httpGet(url).parseJson()
        val chaptersJson = response.getJSONArray("chapters")
        val chapters = mutableListOf<MangaChapter>()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

        for (i in 0 until chaptersJson.length()) {
            val item = chaptersJson.getJSONObject(i)
            val releases = item.getJSONArray("releases")
            if (releases.length() == 0) continue

            val release = releases.getJSONObject(0)
            val releaseId = release.getInt("id")

            val chapterNum = item.optString("chapter").toFloatOrNull() ?: 0f
            val volNum = item.optString("volume").toIntOrNull() ?: 0
            val title = item.optString("title").nullIfEmpty() ?: ""

            val dateStr = item.optString("created_at")
            val date = try {
                dateFormat.parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }

            chapters.add(
                MangaChapter(
                    id = generateUid(releaseId.toString()),
                    title = title,
                    number = chapterNum,
                    volume = volNum,
                    url = "/api/chapters/$releaseId",
                    uploadDate = date,
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            )
        }
        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val id = chapter.url.substringAfterLast("/")
        val url = "https://dilar.tube/api/chapters/$id"
        val json = webClient.httpGet(url).parseJson()
        val pagesJson = json.getJSONArray("pages")
        val storageKey = json.optString("storage_key").nullIfEmpty()

        return (0 until pagesJson.length()).map { i ->
            val page = pagesJson.getJSONObject(i)
            val imageUrl = page.getString("url")

            val fullUrl = if (imageUrl.startsWith("http")) {
                imageUrl
            } else {
                if (storageKey != null) {
                    "https://dilar.tube/uploads/releases/$storageKey/hq/$imageUrl"
                } else {
                    "https://dilar.tube/uploads/$imageUrl"
                }
            }

            MangaPage(
                id = generateUid("$id-$i"),
                url = fullUrl,
                preview = null,
                source = source,
            )
        }
    }
}
