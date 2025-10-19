package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dilar - موقع مانجا عربي
 * يستخدم API مع تشفير AES للبيانات
 */
@MangaSourceParser("DILAR", "Dilar", "ar")
internal class Dilar(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.DILAR, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("dilar.tube")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isSearchSupported = true,
        )

    private val apiHeaders: Headers
        get() = headersBuilder()
            .add("Accept", "application/json")
            .add("Content-Type", "application/json")
            .add("Referer", "https://$domain/")
            .build()

    // === القوائم ===
    override suspend fun getListPage(
        page: Int,
        order: SortOrder?,
        filter: MangaListFilter
    ): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            return search(filter.query, page)
        }

        val url = "https://$domain/api/releases?page=$page"
        val json = webClient.httpGet(url, apiHeaders).parseJson()

        val releases = json.getJSONArray("releases")
        val mangaMap = mutableMapOf<Int, Manga>()

        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            val manga = release.optJSONObject("manga") ?: continue
            
            // تخطي الروايات
            if (manga.optBoolean("is_novel", false)) continue

            val mangaId = manga.getInt("id")
            if (mangaMap.containsKey(mangaId)) continue

            mangaMap[mangaId] = Manga(
                id = generateUid(mangaId.toLong()),
                url = "/api/mangas/$mangaId",
                publicUrl = "https://$domain/mangas/$mangaId",
                title = manga.getString("title"),
                altTitles = null,
                coverUrl = "https://$domain/uploads/manga/cover/$mangaId/${manga.optString("cover")}",
                rating = manga.optString("rating").toFloatOrNull()?.div(10) ?: RATING_UNKNOWN,
                tags = manga.optJSONArray("categories")?.let { cats ->
                    (0 until cats.length()).mapNotNull { idx ->
                        cats.optJSONObject(idx)?.optString("name")?.let { name ->
                            MangaTag(
                                key = name,
                                title = name,
                                source = source
                            )
                        }
                    }.toSet()
                } ?: emptySet(),
                authors = null,
                state = null,
                source = source,
                contentRating = ContentRating.SAFE,
            )
        }

        return mangaMap.values.toList()
    }

    // === البحث ===
    private suspend fun search(query: String, page: Int): List<Manga> {
        val url = "https://$domain/api/mangas/search"
        
        val jsonBody = JSONObject().apply {
            put("oneshot", JSONObject().put("value", false))
            put("title", query)
            put("page", page)
            put("manga_types", JSONObject().apply {
                put("include", JSONObject())
                put("exclude", JSONObject())
            })
            put("story_status", JSONObject().apply {
                put("include", JSONObject())
                put("exclude", JSONObject())
            })
            put("translation_status", JSONObject().apply {
                put("include", JSONObject())
                put("exclude", JSONObject())
            })
            put("categories", JSONObject().apply {
                put("include", JSONObject())
                put("exclude", JSONObject())
            })
            put("chapters", JSONObject().apply {
                put("min", "")
                put("max", "")
            })
            put("dates", JSONObject().apply {
                put("start", "")
                put("end", "")
            })
        }

        val requestBody = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val response = webClient.httpPost(url, requestBody, apiHeaders).parseJson()
        
        // فك تشفير البيانات
        val encryptedData = response.getString("data")
        val decryptedJson = decryptAES(encryptedData)
        val searchResult = JSONObject(decryptedJson)

        val mangas = searchResult.getJSONArray("mangas")
        return (0 until mangas.length()).map { i ->
            val manga = mangas.getJSONObject(i)
            val mangaId = manga.getInt("id")

            Manga(
                id = generateUid(mangaId.toLong()),
                url = "/api/mangas/$mangaId",
                publicUrl = "https://$domain/mangas/$mangaId",
                title = manga.getString("title"),
                altTitles = null,
                coverUrl = "https://$domain/uploads/manga/cover/$mangaId/${manga.optString("cover")}",
                rating = RATING_UNKNOWN,
                tags = manga.optJSONArray("categories")?.let { cats ->
                    (0 until cats.length()).mapNotNull { idx ->
                        cats.optJSONObject(idx)?.optString("name")?.let { name ->
                            MangaTag(key = name, title = name, source = source)
                        }
                    }.toSet()
                } ?: emptySet(),
                authors = null,
                state = null,
                source = source,
                contentRating = ContentRating.SAFE,
            )
        }
    }

    // === التفاصيل والفصول ===
    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val infoUrl = "https://$domain${manga.url}"
        val chaptersUrl = "https://$domain${manga.url}/releases"

        val infoJson = webClient.httpGet(infoUrl, apiHeaders).parseJson()
        val chaptersJson = webClient.httpGet(chaptersUrl, apiHeaders).parseJson()

        val data = infoJson.getJSONObject("manga_data")
        val releases = chaptersJson.getJSONArray("releases")

        val chapters = (0 until releases.length()).mapNotNull { i ->
            val release = releases.getJSONObject(i)
            
            // تخطي الفصول المدفوعة
            val hasRevLink = release.optBoolean("has_rev_link", false)
            val supportLink = release.optString("support_link", "")
            if (hasRevLink && supportLink.isNotBlank()) return@mapNotNull null

            val releaseId = release.getInt("id")
            val chapterNum = release.optString("chapter", "0")
            val title = release.optString("title", "")
            val timestamp = release.optLong("time_stamp", 0)

            MangaChapter(
                id = generateUid(releaseId.toLong()),
                name = if (title.isBlank()) "Chapter $chapterNum" else title,
                number = chapterNum.toFloatOrNull() ?: 0f,
                volume = 0,
                url = "/r/$releaseId",
                scanlator = release.optString("team_name"),
                uploadDate = timestamp * 1000,
                branch = null,
                source = source,
            )
        }.reversed()

        manga.copy(
            title = data.getString("title"),
            altTitles = data.optString("synonyms").takeIf { it.isNotBlank() }?.let { setOf(it) },
            description = data.optString("summary"),
            coverUrl = "https://$domain/uploads/manga/cover/${data.getInt("id")}/${data.optString("cover")}",
            tags = data.optJSONArray("categories")?.let { cats ->
                (0 until cats.length()).mapNotNull { idx ->
                    cats.optJSONObject(idx)?.optString("name")?.let { name ->
                        MangaTag(key = name, title = name, source = source)
                    }
                }.toSet()
            } ?: manga.tags,
            authors = setOfNotNull(
                data.optString("creator_nick").takeIf { it.isNotBlank() },
                data.optString("editor_nick").takeIf { it.isNotBlank() }
            ),
            state = when (data.optInt("translation_status")) {
                1 -> MangaState.ONGOING
                2 -> MangaState.FINISHED
                else -> null
            },
            rating = data.optString("rating").toFloatOrNull()?.div(10) ?: manga.rating,
            chapters = chapters,
        )
    }

    // === صفحات الفصل ===
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = "https://$domain${chapter.url}"
        val html = webClient.httpGet(chapterUrl).parseHtml()

        val scriptData = html.selectFirst(".js-react-on-rails-component")
            ?.data() ?: throw Exception("Chapter data not found")

        val root = JSONObject(scriptData)
        val release = root
            .getJSONObject("readerDataAction")
            .getJSONObject("readerData")
            .getJSONObject("release")

        val storageKey = release.getString("storage_key")
        
        // استخدام webp إذا كانت متوفرة
        val pagesArray = release.optJSONArray("webp_pages")
            ?: release.getJSONArray("pages")
        val directory = if (release.has("webp_pages") && pagesArray.length() > 0) "hq_webp" else "hq"

        return (0 until pagesArray.length()).map { i ->
            val filename = pagesArray.getString(i)
            val imageUrl = "https://$domain/uploads/releases/$storageKey/$directory/$filename"
            
            MangaPage(
                id = generateUid("${chapter.id}_$i"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }.sortedWith(compareBy({ parsePageNumber(0, it.url) }, { parsePageNumber(1, it.url) }))
    }

    // === دوال مساعدة ===
    
    private fun parsePageNumber(index: Int, url: String): Double {
        return Regex("\\d+").findAll(url)
            .map { it.value }
            .toList()
            .getOrNull(index)
            ?.toDoubleOrNull() ?: Double.MAX_VALUE
    }

    // فك التشفير AES
    private fun decryptAES(encryptedData: String): String {
        val parts = encryptedData.split("|")
        if (parts.size < 4) throw Exception("Invalid encrypted data format")

        val ciphertext = parts[0]
        val ivString = parts[2]
        val key = parts[3]

        val secretKey = key.sha256().hexToBytes()
        val iv = Base64.getDecoder().decode(ivString)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(secretKey, "AES"),
            IvParameterSpec(iv)
        )

        val encryptedBytes = Base64.getDecoder().decode(ciphertext)
        return String(cipher.doFinal(encryptedBytes))
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun String.hexToBytes(): ByteArray {
        val len = length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) +
                    Character.digit(this[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
