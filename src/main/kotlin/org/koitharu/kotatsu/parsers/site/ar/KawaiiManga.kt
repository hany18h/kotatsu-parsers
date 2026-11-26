package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import org.json.JSONObject
import okio.ByteString.Companion.decodeBase64
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.EnumSet
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Arabic manga and manhwa from Kawaii Manga's public web client API.
 * The short-lived app token is requested exactly as the website does and is
 * cached until shortly before expiry so opening a chapter does not create a
 * token request for every image.
 */
@MangaSourceParser("KAWAIIMANGA", "كواي مانجا", "ar", ContentType.MANGA)
internal class KawaiiManga(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.KAWAIIMANGA,
	pageSize = PAGE_SIZE,
) {

	override val configKeyDomain = ConfigKey.Domain("kawaiimanga.org")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		if (query.isNotEmpty() && page > 1) return emptyList()
		val action = if (query.isNotEmpty()) "search&q=${query.urlEncoded()}" else buildString {
			append("browse&page=")
			append(page)
			append("&limit=")
			append(PAGE_SIZE)
			append("&sort=")
			append(
				when (order) {
					SortOrder.POPULARITY -> "views"
					SortOrder.RATING -> "rating"
					SortOrder.ALPHABETICAL -> "title"
					else -> "latest"
				},
			)
		}
		val response = apiGet(action)
		val results = response.optJSONArray("results") ?: return emptyList()
		return buildList(results.length()) {
			for (index in 0 until results.length()) {
				results.optJSONObject(index)?.let { add(parseManga(it)) }
			}
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfter("/manga/").substringBefore('/')
		val response = apiGet("series&slug=${slug.urlEncoded()}")
		val details = parseManga(response)
		val chaptersJson = response.optJSONArray("chapters")
		val chapters = buildList {
			if (chaptersJson != null) {
				for (index in 0 until chaptersJson.length()) {
					val item = chaptersJson.optJSONObject(index) ?: continue
					val chapterId = item.optNonNullString("id") ?: continue
					val number = item.optDouble("number", index + 1.0).toFloat()
					add(
						MangaChapter(
							id = generateUid(chapterId),
							title = item.optNonNullString("title") ?: "الفصل ${formatNumber(number)}",
							number = number,
							volume = 0,
							url = "/chapter/$chapterId",
							scanlator = "كواي مانجا",
							uploadDate = parseDate(item.optNonNullString("createdAt").orEmpty()),
							branch = null,
							source = source,
						),
					)
				}
			}
		}.sortedBy(MangaChapter::number)
		return details.copy(
			id = manga.id,
			url = manga.url,
			publicUrl = manga.publicUrl,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfter("/chapter/").substringBefore('/')
		val token = getApiToken()
		val response = webClient.httpGet(
			"$API_BASE/own?action=pages&chapterId=${chapterId.urlEncoded()}",
			apiHeaders(token),
		).parseJson()
		val pages = response.optJSONArray("pages")
			?: response.optJSONObject("box")?.let { decryptPages(it, token) }
			?: return emptyList()
		return buildList(pages.length()) {
			for (index in 0 until pages.length()) {
				val url = pages.optString(index).trim().takeUnless { it.isBlank() || it.equals("null", true) }
				if (url != null) {
					add(MangaPage(generateUid("$chapterId:$index:$url"), url, null, source))
				}
			}
		}
	}

	internal fun decryptPages(box: JSONObject, token: String): org.json.JSONArray {
		val iv = box.optString("i").decodeBase64()?.toByteArray()
			?: error("Kawaii Manga returned an invalid encrypted page IV")
		val encrypted = box.optString("d").decodeBase64()?.toByteArray()
			?: error("Kawaii Manga returned invalid encrypted page data")
		val keyMaterial = (PAGE_KEY_PARTS.joinToString("") + "|" + token.takeLast(24))
			.toByteArray(StandardCharsets.UTF_8)
		val key = MessageDigest.getInstance("SHA-256").digest(keyMaterial)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
		val json = JSONObject(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
		return json.optJSONArray("pages") ?: error("Kawaii Manga returned no decrypted page list")
	}

	internal fun parseManga(json: JSONObject): Manga {
		val slug = json.optNonNullString("slug").orEmpty()
		val id = json.optNonNullString("id") ?: slug
		val titleAr = json.optNonNullString("titleAr")
		val title = titleAr ?: json.optNonNullString("title") ?: json.optNonNullString("titleEn").orEmpty()
		val altTitles = buildSet {
			listOfNotNull(json.optNonNullString("title"), json.optNonNullString("titleEn"), titleAr)
				.filterTo(this) { it != title }
			json.optJSONArray("altTitles")?.let { array ->
				for (index in 0 until array.length()) {
					array.optString(index).trim()
						.takeUnless { it.isBlank() || it.equals("null", true) || it == title }
						?.let(::add)
				}
			}
		}
		val tags = buildSet {
			json.optJSONArray("genres")?.let { array ->
				for (index in 0 until array.length()) {
					val genre = array.optString(index).trim()
					if (genre.isNotEmpty() && !genre.equals("null", true)) add(MangaTag(genre, genre, source))
				}
			}
		}
		val authors = setOfNotNull(
			json.optNonNullString("author"),
			json.optNonNullString("artist"),
		)
		val rating = json.optDouble("rating", Double.NaN).takeUnless(Double::isNaN)
			?.div(5.0)?.toFloat()?.coerceIn(0f, 1f) ?: RATING_UNKNOWN
		return Manga(
			id = generateUid(id),
			title = title,
			altTitles = altTitles,
			url = "/manga/$slug",
			publicUrl = "https://$domain/manga/$slug",
			rating = rating,
			contentRating = ContentRating.SAFE,
			coverUrl = json.optNonNullString("coverUrl"),
			tags = tags,
			state = when (json.optNonNullString("status")?.lowercase(Locale.ENGLISH)) {
				"ongoing" -> MangaState.ONGOING
				"completed", "finished" -> MangaState.FINISHED
				"hiatus", "paused" -> MangaState.PAUSED
				else -> null
			},
			authors = authors,
			source = source,
			description = json.optNonNullString("description"),
			largeCoverUrl = json.optNonNullString("coverUrl"),
		)
	}

	private val tokenMutex = Mutex()
	@Volatile private var apiToken: String? = null
	@Volatile private var tokenExpiresAt = 0L

	private suspend fun apiGet(action: String): JSONObject {
		val token = getApiToken()
		return webClient.httpGet("$API_BASE/own?action=$action", apiHeaders(token)).parseJson()
	}

	private suspend fun getApiToken(): String = tokenMutex.withLock {
		val now = System.currentTimeMillis()
		apiToken?.takeIf { now < tokenExpiresAt }?.let { return@withLock it }
		val response = webClient.httpGet("$API_BASE/token", apiHeaders(null)).parseJson()
		val token = response.getString("token")
		val expiresIn = response.optLong("expiresIn", 1800L).coerceAtLeast(60L)
		apiToken = token
		tokenExpiresAt = now + (expiresIn - TOKEN_EXPIRY_MARGIN_SECONDS) * 1000L
		token
	}

	private fun apiHeaders(token: String?): Headers = Headers.Builder()
		.add("Accept", "application/json")
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.add("User-Agent", config[userAgentKey])
		.add("x-app-key", APP_KEY)
		.apply { if (token != null) add("x-app-token", token) }
		.build()

	private fun parseDate(value: String): Long {
		if (value.isBlank()) return 0L
		return synchronized(DATE_FORMAT) { runCatching { DATE_FORMAT.parse(value)?.time }.getOrNull() } ?: 0L
	}

	private fun formatNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

	internal companion object {
		private const val API_BASE = "https://manga-api.kawaii-anime.com/api/manga"
		private const val APP_KEY = "km_2026_live"
		private const val PAGE_SIZE = 30
		private const val TOKEN_EXPIRY_MARGIN_SECONDS = 60L
		private val PAGE_KEY_PARTS = arrayOf("1153b3c53436", "8ded1f8386fe", "03beaa5ebb16", "6a15f4275e25")
		private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ENGLISH)
	}
}

private fun JSONObject.optNonNullString(key: String): String? = opt(key)
	?.takeUnless { it == JSONObject.NULL }
	?.toString()
	?.trim()
	?.takeUnless { it.isBlank() || it.equals("null", true) }
