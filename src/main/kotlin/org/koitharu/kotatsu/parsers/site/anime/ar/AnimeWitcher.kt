package org.koitharu.kotatsu.parsers.site.anime.ar

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.AnimeStream
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
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.net.URLDecoder
import java.util.EnumSet

/**
 * AnimeWitcher's website is only a landing page. Its Android client reads the
 * public catalog from Algolia and episode data from public Firestore documents.
 */
@MangaSourceParser("ANIME_WITCHER", "AnimeWitcher", "ar", ContentType.ANIME)
internal class AnimeWitcher(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.ANIME_WITCHER,
	pageSize = PAGE_SIZE,
	searchPageSize = PAGE_SIZE,
) {

	override val configKeyDomain = ConfigKey.Domain("www.animewitcher.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val index = when (order) {
			SortOrder.NEWEST -> "series_year_desc"
			SortOrder.NEWEST_ASC -> "series_year_asc"
			SortOrder.ALPHABETICAL_DESC -> "series_name_desc"
			SortOrder.RELEVANCE -> "series"
			else -> "series_name_asc"
		}
		val body = JSONObject()
			.put("query", filter.query?.trim().orEmpty())
			.put("hitsPerPage", PAGE_SIZE)
			.put("page", page - 1)
			.put("attributesToRetrieve", ALGOLIA_ATTRIBUTES)
		val headers = Headers.Builder()
			.add("X-Algolia-Application-Id", ALGOLIA_APP_ID)
			.add("X-Algolia-API-Key", ALGOLIA_SEARCH_KEY)
			.build()
		val response = webClient.httpPost(
			"https://$ALGOLIA_APP_ID-dsn.algolia.net/1/indexes/$index/query".toHttpUrl(),
			body,
			headers,
		).parseJson()
		val hits = response.optJSONArray("hits") ?: return emptyList()
		return buildList {
			for (i in 0 until hits.length()) {
				hits.optJSONObject(i)?.let(::parseCatalogItem)?.let(::add)
			}
		}.distinctBy(Manga::id)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val animeId = decode(manga.url.substringAfter(ANIME_PATH))
		val fields = fetchDocument("anime_list", animeId).optJSONObject("fields")
			?: return manga
		val details = fields.firestoreMap("details")
		val poster = fields.firestoreString("poster_uri") ?: manga.coverUrl
		val tags = fields.firestoreStrings("tags").mapTo(LinkedHashSet()) {
			MangaTag(title = it, key = it, source = source)
		}
		val altTitles = buildSet {
			addAll(fields.firestoreStrings("other_names"))
			details?.firestoreString("english_title")?.let(::add)
		}
		val studios = details?.firestoreStrings("studio").orEmpty().toSet()
		val rating = fields.firestoreNumber("average_rate")
			?: fields.firestoreMap("rating")?.firestoreNumber("rate")
		val chapters = loadEpisodes(animeId)

		return manga.copy(
			title = fields.firestoreString("name") ?: manga.title,
			altTitles = altTitles.ifEmpty { manga.altTitles },
			publicUrl = "https://$domain/",
			coverUrl = poster,
			largeCoverUrl = poster,
			description = fields.firestoreString("story") ?: manga.description,
			tags = tags.ifEmpty { manga.tags },
			state = parseState(details?.firestoreString("state")) ?: manga.state,
			authors = studios.ifEmpty { manga.authors },
			rating = rating?.let(::normalizeRating) ?: manga.rating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getVideoStreams(chapter: MangaChapter): List<AnimeStream> {
		val path = chapter.url.substringAfter(EPISODE_PATH)
		val parts = path.split('/', limit = 2)
		if (parts.size != 2) return emptyList()
		val animeId = decode(parts[0])
		val episodeId = decode(parts[1])
		val servers = loadServers(animeId, episodeId)
		return buildList {
			for (server in servers) {
				if (server.firestoreBoolean("visible") == false) continue
				val link = server.firestoreString("link") ?: continue
				val direct = toDirectVideoUrl(
					link = link,
					directLink = server.firestoreBoolean("direct_link") == true,
				) ?: continue
				val serverName = displayServerName(server.firestoreString("name"))
				val quality = server.firestoreString("quality")?.takeIf(String::isNotBlank)
				add(
					AnimeStream(
						name = listOfNotNull("AnimeWitcher", serverName, quality)
							.joinToString(" • "),
						url = direct,
						headers = mapOf(
							"Referer" to link,
							"User-Agent" to config[userAgentKey],
						),
						quality = quality,
					),
				)
			}
		}.distinctBy(AnimeStream::url)
	}

	private suspend fun loadEpisodes(animeId: String): List<MangaChapter> {
		val summary = runCatching {
			fetchDocument(
				"anime_list",
				animeId,
				"episodes_summery",
				"summery",
			)
		}.getOrNull()
		val summarizedEpisodes = parseEpisodes(animeId, summary?.optJSONObject("fields"))
		if (summarizedEpisodes.isNotEmpty()) {
			return summarizedEpisodes
		}

		val documents = runCatching {
			fetchCollection(EPISODES_PAGE_SIZE, "anime_list", animeId, "episodes")
		}.getOrDefault(emptyList())
		return parseEpisodeDocuments(animeId, documents)
	}

	private suspend fun loadServers(animeId: String, episodeId: String): List<JSONObject> {
		val summary = runCatching {
			fetchDocument(
				"anime_list",
				animeId,
				"episodes",
				episodeId,
				"servers2",
				"all_servers",
			)
		}.getOrNull()
		val values = summary?.optJSONObject("fields")?.firestoreArray("servers")
		val summarizedServers = buildList {
			if (values != null) {
				for (i in 0 until values.length()) {
					values.optJSONObject(i)
						?.optJSONObject("mapValue")
						?.optJSONObject("fields")
						?.let(::add)
				}
			}
		}
		if (summarizedServers.isNotEmpty()) {
			return summarizedServers
		}

		return runCatching {
			fetchCollection(SERVERS_PAGE_SIZE, "anime_list", animeId, "episodes", episodeId, "servers")
				.mapNotNull { it.optJSONObject("fields") }
		}.getOrDefault(emptyList())
	}

	private fun parseCatalogItem(item: JSONObject): Manga? {
		val animeId = item.optString("objectID").takeIf(String::isNotBlank) ?: return null
		val title = item.optString("name").takeIf(String::isNotBlank) ?: return null
		val details = item.optJSONObject("details")
		val tags = item.optJSONArray("tags").toStringSet().mapTo(LinkedHashSet()) {
			MangaTag(title = it, key = it, source = source)
		}
		val cover = item.optJSONObject("poster")?.optString("large")?.takeIf(String::isNotBlank)
			?: item.optJSONObject("aniList_poster")?.optString("large")?.takeIf(String::isNotBlank)
			?: item.optString("poster_uri").takeIf(String::isNotBlank)
		val rating = item.optJSONObject("rating")?.optDouble("rate", Double.NaN)
			?.takeUnless(Double::isNaN)
			?.let(::normalizeRating)
			?: RATING_UNKNOWN
		return Manga(
			id = generateUid(animeId),
			title = title,
			altTitles = setOfNotNull(
				details?.optString("english_title")?.takeIf(String::isNotBlank),
			),
			url = "$ANIME_PATH${animeId.urlEncoded()}",
			publicUrl = "https://$domain/",
			rating = rating,
			contentRating = parseContentRating(details?.optString("age"), tags),
			coverUrl = cover,
			tags = tags,
			state = parseState(details?.optString("state")),
			authors = details?.optJSONArray("studio").toStringSet(),
			description = item.optString("story").takeIf(String::isNotBlank),
			source = source,
		)
	}

	private fun parseEpisodes(animeId: String, fields: JSONObject?): List<MangaChapter> {
		val episodes = fields?.firestoreArray("episodes") ?: return emptyList()
		return buildList {
			for (i in 0 until episodes.length()) {
				val episode = episodes.optJSONObject(i)
					?.optJSONObject("mapValue")
					?.optJSONObject("fields")
					?: continue
				val episodeId = episode.firestoreString("doc_id") ?: continue
				parseEpisode(animeId, episodeId, episode, i)?.let(::add)
			}
		}.distinctBy(MangaChapter::id).sortedBy(MangaChapter::number)
	}

	private fun parseEpisodeDocuments(animeId: String, documents: List<JSONObject>): List<MangaChapter> =
		documents.mapIndexedNotNull { index, document ->
			val fields = document.optJSONObject("fields") ?: return@mapIndexedNotNull null
			val episodeId = fields.firestoreString("doc_id")
				?: document.optString("name").substringAfterLast('/').takeIf(String::isNotBlank)
				?: return@mapIndexedNotNull null
			parseEpisode(animeId, episodeId, fields, index)
		}.distinctBy(MangaChapter::id).sortedBy(MangaChapter::number)

	private fun parseEpisode(
		animeId: String,
		episodeId: String,
		fields: JSONObject,
		index: Int,
	): MangaChapter {
		val name = fields.firestoreString("name")
		val translatedTitle = fields.firestoreString("title_translated")
			?: fields.firestoreMap("title_translated")?.firestoreString("ar")
			?: fields.firestoreString("title_en")
		val number = firstNumber(name)
			?: firstNumber(episodeId)
			?: (index + 1).toFloat()
		return MangaChapter(
			id = generateUid("$animeId/$episodeId"),
			title = listOfNotNull(name, translatedTitle)
				.filter(String::isNotBlank)
				.distinct()
				.joinToString(" — ")
				.ifBlank { "الحلقة ${formatNumber(number)}" },
			number = number,
			volume = 0,
			url = "$EPISODE_PATH${animeId.urlEncoded()}/${episodeId.urlEncoded()}",
			scanlator = "AnimeWitcher",
			uploadDate = 0L,
			branch = null,
			source = source,
		)
	}

	private suspend fun fetchDocument(vararg segments: String): JSONObject {
		val builder = FIRESTORE_DOCUMENTS.toHttpUrl().newBuilder()
		for (segment in segments) {
			builder.addPathSegment(segment)
		}
		builder.addQueryParameter("key", FIREBASE_API_KEY)
		return webClient.httpGet(builder.build()).parseJson()
	}

	private suspend fun fetchCollection(pageSize: Int, vararg segments: String): List<JSONObject> {
		val result = ArrayList<JSONObject>()
		var pageToken: String? = null
		var page = 0
		do {
			val builder = FIRESTORE_DOCUMENTS.toHttpUrl().newBuilder()
			for (segment in segments) {
				builder.addPathSegment(segment)
			}
			builder.addQueryParameter("key", FIREBASE_API_KEY)
			builder.addQueryParameter("pageSize", pageSize.toString())
			pageToken?.let { builder.addQueryParameter("pageToken", it) }
			val response = webClient.httpGet(builder.build()).parseJson()
			val documents = response.optJSONArray("documents")
			if (documents != null) {
				for (i in 0 until documents.length()) {
					documents.optJSONObject(i)?.let(result::add)
				}
			}
			pageToken = response.optString("nextPageToken").takeIf(String::isNotBlank)
			page++
		} while (pageToken != null && page < MAX_FIRESTORE_PAGES)
		return result
	}

	private fun parseContentRating(
		age: String?,
		tags: Set<MangaTag>,
	): ContentRating = if (
		age?.contains("18") == true ||
		tags.any { it.title.contains("هنتاي") || it.title.contains("بالغ") }
	) {
		ContentRating.ADULT
	} else {
		ContentRating.SAFE
	}

	private fun parseState(value: String?): MangaState? = when {
		value.isNullOrBlank() -> null
		value.contains("مكتمل") || value.contains("منتهي") || value.contains("finished", true) ->
			MangaState.FINISHED
		value.contains("قادم") || value.contains("upcoming", true) -> MangaState.UPCOMING
		else -> MangaState.ONGOING
	}

	private fun displayServerName(value: String?): String? = when (value) {
		"PD" -> "PixelDrain"
		"KF" -> "KrakenFiles"
		"MF" -> "MediaFire"
		else -> value?.takeIf(String::isNotBlank)
	}

	private fun firstNumber(value: String?): Float? =
		value?.let { NUMBER.find(it)?.value?.toFloatOrNull() }

	private fun formatNumber(value: Float): String =
		if (value % 1f == 0f) value.toInt().toString() else value.toString()

	private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

	internal companion object {
		private const val PAGE_SIZE = 30
		private const val EPISODES_PAGE_SIZE = 1000
		private const val SERVERS_PAGE_SIZE = 100
		private const val MAX_FIRESTORE_PAGES = 20
		private const val ANIME_PATH = "/anime/"
		private const val EPISODE_PATH = "/episode/"
		private const val ALGOLIA_APP_ID = "D8LH9I7ZL7"
		private const val ALGOLIA_SEARCH_KEY = "b56c01ef52540ef334bcdbaa00ded9e4"
		private const val FIREBASE_API_KEY = "AIzaSyAcbWRwfFNnCpoydDXlEALWnM_TYVcJOMU"
		private const val FIRESTORE_DOCUMENTS =
			"https://firestore.googleapis.com/v1/projects/animewitcher-1c66d/databases/(default)/documents"
		private val NUMBER = Regex("""\d+(?:\.\d+)?""")
		private val PIXEL_DRAIN = Regex(
			"""^https?://(?:www\.)?pixeldrain\.com/u/([A-Za-z0-9]+)(?:[/?#].*)?$""",
			RegexOption.IGNORE_CASE,
		)
		private val DIRECT_VIDEO = Regex(
			"""\.(?:m3u8|mp4)(?:[?#].*)?$""",
			RegexOption.IGNORE_CASE,
		)
		private val ALGOLIA_ATTRIBUTES = JSONArray(
			listOf(
				"objectID",
				"name",
				"tags",
				"poster_uri",
				"poster",
				"aniList_poster",
				"details",
				"rating",
				"type",
				"story",
			),
		)

		internal fun toDirectVideoUrl(link: String, directLink: Boolean): String? {
			val normalized = link.trim()
			if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
				return null
			}
			PIXEL_DRAIN.matchEntire(normalized)?.groupValues?.getOrNull(1)?.let {
				return "https://pixeldrain.com/api/file/$it"
			}
			if (!directLink && isKnownEmbedPage(normalized)) {
				return null
			}
			return normalized.takeIf { directLink || DIRECT_VIDEO.containsMatchIn(it) }
		}

		private fun isKnownEmbedPage(link: String): Boolean = runCatching {
			val url = link.toHttpUrl()
			when {
				url.host == "streamtape.com" || url.host.endsWith(".streamtape.com") -> true
				url.host == "krakenfiles.com" || url.host.endsWith(".krakenfiles.com") ->
					url.encodedPath.startsWith("/view/")
				url.host == "mediafire.com" || url.host.endsWith(".mediafire.com") ->
					url.encodedPath.startsWith("/file/")
				else -> false
			}
		}.getOrDefault(true)

		private fun normalizeRating(value: Double): Float =
			(value / 10.0).coerceIn(0.0, 1.0).toFloat()
	}
}

private fun JSONObject.firestoreString(name: String): String? =
	optJSONObject(name)?.optString("stringValue")?.takeIf(String::isNotBlank)

private fun JSONObject.firestoreBoolean(name: String): Boolean? {
	val field = optJSONObject(name) ?: return null
	return if (field.has("booleanValue")) field.optBoolean("booleanValue") else null
}

private fun JSONObject.firestoreNumber(name: String): Double? {
	val field = optJSONObject(name) ?: return null
	return when {
		field.has("doubleValue") -> field.optDouble("doubleValue").takeUnless(Double::isNaN)
		field.has("integerValue") -> field.optString("integerValue").toDoubleOrNull()
		else -> null
	}
}

private fun JSONObject.firestoreMap(name: String): JSONObject? =
	optJSONObject(name)?.optJSONObject("mapValue")?.optJSONObject("fields")

private fun JSONObject.firestoreArray(name: String): JSONArray? =
	optJSONObject(name)?.optJSONObject("arrayValue")?.optJSONArray("values")

private fun JSONObject.firestoreStrings(name: String): List<String> {
	val values = firestoreArray(name) ?: return emptyList()
	return buildList {
		for (i in 0 until values.length()) {
			values.optJSONObject(i)?.optString("stringValue")
				?.takeIf(String::isNotBlank)
				?.let(::add)
		}
	}
}

private fun JSONArray?.toStringSet(): Set<String> {
	if (this == null) return emptySet()
	return buildSet {
		for (i in 0 until length()) {
			optString(i).takeIf(String::isNotBlank)?.let(::add)
		}
	}
}
