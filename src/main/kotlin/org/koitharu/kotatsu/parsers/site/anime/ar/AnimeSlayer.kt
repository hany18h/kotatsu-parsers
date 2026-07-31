package org.koitharu.kotatsu.parsers.site.anime.ar

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
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
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.EnumSet
import java.util.Locale

/**
 * Parser for the catalogue and episode servers used by the official Anime
 * Slayer Android application. The public website is only a download page.
 */
@MangaSourceParser("ANIME_SLAYER", "Anime Slayer", "ar", ContentType.ANIME)
internal class AnimeSlayer(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.ANIME_SLAYER,
	pageSize = PAGE_SIZE,
	searchPageSize = PAGE_SIZE,
) {

	override val configKeyDomain = ConfigKey.Domain("anslayer.com")

	override val iconUrl =
		"https://raw.githubusercontent.com/hany18h/kotatsu-parsers/master/src/main/kotlin/org/koitharu/kotatsu/parsers/icons/AnimeSlayer.png"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val payload = JSONObject()
			.put("_limit", PAGE_SIZE)
			.put("_offset", (page - 1).coerceAtLeast(0) * PAGE_SIZE)
			.put("_order_by", when (order) {
				SortOrder.ALPHABETICAL -> "anime_name_asc"
				else -> "latest_first"
			})
			.put("list_type", when {
				query.isNotEmpty() -> "filter"
				order == SortOrder.ALPHABETICAL -> "anime_list"
				order == SortOrder.UPDATED -> "latest_updated_episode_new"
				else -> "filter"
			})
		if (query.isNotEmpty()) {
			payload.put("anime_name", query)
		}
		payload.put("just_info", "Yes")

		val response = webClient.httpGet(
			"$API_BASE/animes/get-published-animes?json=${payload.toString().urlEncoded()}",
			apiHeaders(),
		).parseJson()
		val data = response.optJSONObject("response")?.optJSONArray("data") ?: return emptyList()
		return buildList {
			for (index in 0 until data.length()) {
				data.optJSONObject(index)?.let(::parseAnime)?.let(::add)
			}
		}.distinctBy(Manga::id)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val animeId = manga.url.substringAfter(ANIME_PATH).substringBefore('/')
		if (animeId.isBlank()) return manga
		val details = loadDetails(animeId) ?: return manga
		val cover = details.optString("anime_cover_image_full_url")
			.takeIf(String::isNotBlank)
			?: details.optString("anime_cover_image_url").takeIf(String::isNotBlank)
			?: manga.coverUrl
		val tags = parseTags(details.optString("anime_genres"))
		val englishTitle = details.optString("anime_english_title").takeIf(String::isNotBlank)

		return manga.copy(
			title = details.optString("anime_name").takeIf(String::isNotBlank) ?: manga.title,
			altTitles = setOfNotNull(englishTitle).ifEmpty { manga.altTitles },
			publicUrl = "https://anslayer.com/",
			coverUrl = cover,
			largeCoverUrl = cover,
			description = details.optString("anime_description").takeIf(String::isNotBlank)
				?: manga.description,
			tags = tags.ifEmpty { manga.tags },
			state = parseState(details.optString("anime_status")) ?: manga.state,
			rating = parseRating(details.optString("anime_rating")).takeUnless {
				it == RATING_UNKNOWN
			} ?: manga.rating,
			contentRating = parseContentRating(details.optString("anime_age_rating")),
			chapters = parseEpisodes(animeId, details.optJSONObject("episodes")?.optJSONArray("data")),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getVideoStreams(chapter: MangaChapter): List<AnimeStream> {
		val parts = chapter.url.substringAfter(EPISODE_PATH).split('/', limit = 2)
		if (parts.size != 2) return emptyList()
		val animeId = parts[0]
		val episodeId = parts[1]
		val details = loadDetails(animeId) ?: return emptyList()
		val episodes = details.optJSONObject("episodes")?.optJSONArray("data") ?: return emptyList()
		val episode = (0 until episodes.length())
			.asSequence()
			.mapNotNull(episodes::optJSONObject)
			.firstOrNull { it.optString("episode_id") == episodeId }
			?: return emptyList()
		val servers = episode.optJSONArray("episode_urls") ?: return emptyList()

		val result = ArrayList<AnimeStream>()
		for (index in 0 until servers.length()) {
			val server = servers.optJSONObject(index) ?: continue
			result += runCatching { resolveServer(server) }.getOrDefault(emptyList())
		}
		return result.distinctBy(AnimeStream::url)
	}

	private suspend fun loadDetails(animeId: String): JSONObject? {
		val response = webClient.httpGet(
			"$API_BASE/anime/get-anime-details?anime_id=${animeId.urlEncoded()}" +
				"&fetch_episodes=Yes&more_info=No",
			apiHeaders(),
		).parseJson()
		return response.optJSONObject("response")
	}

	private fun apiHeaders(): Headers = Headers.Builder()
		.add("Accept", "application/*+json")
		.add("Client-Id", CLIENT_ID)
		.add("Client-Secret", CLIENT_SECRET)
		.add("User-Agent", config[userAgentKey])
		.build()

	private fun parseAnime(item: JSONObject): Manga? {
		val animeId = item.optString("anime_id").takeIf(String::isNotBlank) ?: return null
		val title = item.optString("anime_name").takeIf(String::isNotBlank) ?: return null
		val tags = parseTags(item.optString("anime_genres"))
		return Manga(
			id = generateUid(animeId),
			title = title,
			altTitles = emptySet(),
			url = "$ANIME_PATH$animeId",
			publicUrl = "https://anslayer.com/",
			rating = parseRating(item.optString("anime_rating")),
			contentRating = ContentRating.SAFE,
			coverUrl = item.optString("anime_cover_image_url").takeIf(String::isNotBlank),
			tags = tags,
			state = parseState(item.optString("anime_status")),
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseTags(value: String): Set<MangaTag> = value
		.split(',')
		.mapNotNullTo(LinkedHashSet()) { raw ->
			val title = raw.trim().takeIf(String::isNotEmpty) ?: return@mapNotNullTo null
			MangaTag(title = title, key = title, source = source)
		}

	private fun parseEpisodes(animeId: String, episodes: JSONArray?): List<MangaChapter> {
		if (episodes == null) return emptyList()
		return buildList {
			for (index in 0 until episodes.length()) {
				val item = episodes.optJSONObject(index) ?: continue
				val episodeId = item.optString("episode_id").takeIf(String::isNotBlank) ?: continue
				val number = item.optString("episode_number").toFloatOrNull()
					?: (index + 1).toFloat()
				val title = item.optString("episode_name").trim().ifEmpty {
					"الحلقة ${formatNumber(number)}"
				}
				add(
					MangaChapter(
						id = generateUid("$animeId/$episodeId"),
						title = title,
						number = number,
						volume = 0,
						url = "$EPISODE_PATH$animeId/$episodeId",
						scanlator = "Anime Slayer",
						uploadDate = 0L,
						branch = null,
						source = source,
					),
				)
			}
		}.distinctBy(MangaChapter::id).sortedBy(MangaChapter::number)
	}

	private suspend fun resolveServer(server: JSONObject): List<AnimeStream> {
		val serverName = server.optString("episode_server_name")
			.trim()
			.ifEmpty { "Server" }
		val serverUrl = server.optString("episode_url").takeIf(String::isNotBlank)
			?: return emptyList()
		val candidates = when {
			isDirectMediaUrl(serverUrl) -> listOf(serverUrl)
			serverUrl.contains("a-reslayer.com", ignoreCase = true) -> {
				loadAlternativeLinks(serverUrl)
			}
			else -> buildList {
				addAll(extractPageLinks(serverUrl, "https://anslayer.com/"))
				alternativeUrlFromCdn(serverUrl)?.let { fallbackUrl ->
					addAll(loadAlternativeLinks(fallbackUrl))
				}
			}.distinct()
		}

		return buildList {
			for (candidate in candidates) {
				// One blocked provider must not discard all remaining servers.
				// MediaFire, in particular, can be unavailable on one network while
				// StreamTape from the same response is still playable.
				addAll(
					runCatching {
						when {
							candidate.contains("mediafire.com", ignoreCase = true) -> {
								listOfNotNull(resolveMediaFire(candidate, serverName))
							}
							candidate.contains("streamtape.", ignoreCase = true) -> {
								listOfNotNull(resolveStreamTape(candidate, serverName))
							}
							isDirectMediaUrl(candidate) ||
								candidate.contains("/get_video?", ignoreCase = true) -> {
								listOf(createStream(serverName, candidate, serverUrl))
							}
							else -> {
								extractPageLinks(candidate, serverUrl).map { direct ->
									createStream(serverName, direct, candidate)
								}
							}
						}
					}.getOrDefault(emptyList()),
				)
			}
		}
	}

	private suspend fun loadAlternativeLinks(serverUrl: String): List<String> {
		for (requestUrl in alternativeEndpointCandidates(serverUrl)) {
			val raw = runCatching {
				webClient.httpGet(
					requestUrl,
					pageHeaders("https://anslayer.com/"),
				).parseRaw()
			}.getOrNull() ?: continue
			val links = parseAlternativeLinks(raw)
			if (links.isNotEmpty()) return links
		}
		return emptyList()
	}

	private suspend fun resolveMediaFire(url: String, serverName: String): AnimeStream? {
		val document = webClient.httpGet(url, pageHeaders("https://anslayer.com/")).parseHtml()
		val direct = extractMediaFireDirectUrl(document) ?: return null
		return createStream("$serverName • MediaFire", direct, url)
	}

	private suspend fun resolveStreamTape(url: String, serverName: String): AnimeStream? {
		val raw = webClient.httpGet(url, pageHeaders("https://anslayer.com/")).parseRaw()
		val direct = extractStreamTapeScriptUrl(raw, url)
			?: extractStreamTapeDirectUrl(Jsoup.parse(raw, url), url)
			?: findStreamTapeUrl(raw)
			?: findDirectMediaUrls(raw).firstOrNull()
			?: return null
		return createStream("$serverName • StreamTape", direct, url)
	}

	private suspend fun extractPageLinks(url: String, referer: String): List<String> {
		val raw = runCatching {
			webClient.httpGet(url, pageHeaders(referer)).parseRaw()
		}.getOrNull() ?: return emptyList()
		return buildList {
			addAll(findDirectMediaUrls(raw))
			findStreamTapeUrl(raw)?.let(::add)
		}.distinct()
	}

	private fun createStream(name: String, url: String, referer: String): AnimeStream {
		val quality = detectQuality(url)
		return AnimeStream(
			name = listOfNotNull("Anime Slayer", name, quality).joinToString(" • "),
			url = absoluteUrl(url, referer),
			headers = mapOf(
				"Referer" to referer,
				"User-Agent" to config[userAgentKey],
			),
			quality = quality,
		)
	}

	private fun pageHeaders(referer: String): Headers = Headers.Builder()
		.add("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
		.add("Accept-Language", "ar,en-US;q=0.8,en;q=0.7")
		.add("Referer", referer)
		.add("User-Agent", config[userAgentKey])
		.build()

	private fun parseState(value: String): MangaState? = when {
		value.contains("finished", true) || value.contains("مكتمل") -> MangaState.FINISHED
		value.contains("not yet", true) || value.contains("upcoming", true) -> MangaState.UPCOMING
		value.contains("airing", true) || value.contains("مستمر") -> MangaState.ONGOING
		else -> null
	}

	private fun parseContentRating(value: String): ContentRating = when {
		value.contains("18") || value.contains("R+", true) -> ContentRating.ADULT
		else -> ContentRating.SAFE
	}

	private fun parseRating(value: String): Float {
		val rating = value.toFloatOrNull() ?: return RATING_UNKNOWN
		return (rating / 10f).coerceIn(0f, 1f)
	}

	private fun detectQuality(url: String): String? {
		QUALITY_NUMBER.find(url)?.groupValues?.getOrNull(1)?.let { return "${it}p" }
		val file = url.substringBefore('?').lowercase(Locale.ROOT)
		return when {
			file.endsWith("_h.mp4") || file.contains("_hd.") -> "HD"
			file.endsWith("_l.mp4") || file.contains("_sd.") -> "SD"
			else -> null
		}
	}

	private fun formatNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

	internal companion object {
		const val PAGE_SIZE = 30
		const val API_BASE = "https://anslayer.com/anime/public"
		const val CLIENT_ID = "android-app2"
		const val CLIENT_SECRET = "7befba6263cc14c90d2f1d6da2c5cf9b251bfbbd"
		const val ANIME_PATH = "/anime/"
		const val EPISODE_PATH = "/episode/"

		val QUALITY_NUMBER = Regex("""(?:^|[_\-.])(\d{3,4})p?(?:[_\-.]|$)""", RegexOption.IGNORE_CASE)
		val DIRECT_MEDIA_URL = Regex(
			"""(?:(?:https?:)?//)[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
			RegexOption.IGNORE_CASE,
		)
		val STREAM_TAPE_URL = Regex(
			"""(?:(?:https?:)?//)[^\s"'<>\\]*streamtape[^\s"'<>\\]*/get_video\?[^\s"'<>\\]+""",
			RegexOption.IGNORE_CASE,
		)
		val ROBOT_LINK = Regex(
			"""(?:robotlink|norobotlink)[^=]{0,100}=\s*["']([^"']+)["']""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
		)
		val STREAM_TAPE_ASSIGNMENT = Regex(
			"""document\.getElementById\(\s*(["'])(captchalink|ideoooolink|norobotlink)\1\s*\)""" +
				"""\.innerHTML\s*=\s*([^;]+);""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
		)
		val JS_STRING_TERM = Regex(
			"""(["'])(.*?)\1\s*\)?((?:\s*\.substring\(\s*\d+\s*\))*)""",
			RegexOption.DOT_MATCHES_ALL,
		)
		val JS_SUBSTRING_CALL = Regex("""\.substring\(\s*(\d+)\s*\)""")
		val ABSOLUTE_URL = Regex(
			"""(?:(?:https?:)?//)[^\s"'<>\\]+""",
			RegexOption.IGNORE_CASE,
		)

		fun alternativeEndpointCandidates(serverUrl: String): List<String> = buildList {
			add(serverUrl.replace("\\", "%5C"))
			add(serverUrl.replace("\\", "/"))
			add(serverUrl)
		}.distinct()

		/**
		 * Anime Slayer's legacy CDN endpoint frequently returns 404 while the same
		 * episode remains available through the alternative-server endpoint. Both
		 * URLs carry the anime slug and episode number, so keep the working endpoint
		 * as a fallback instead of losing the whole server list.
		 */
		fun alternativeUrlFromCdn(serverUrl: String): String? {
			val uri = runCatching { URI(serverUrl) }.getOrNull() ?: return null
			if (!uri.path.orEmpty().endsWith("/vq.php", ignoreCase = true)) return null
			val query = uri.rawQuery.orEmpty()
				.split('&')
				.mapNotNull { part ->
					val separator = part.indexOf('=')
					if (separator <= 0) return@mapNotNull null
					part.substring(0, separator) to URLDecoder.decode(
						part.substring(separator + 1),
						StandardCharsets.UTF_8.name(),
					)
				}
				.toMap()
			val anime = query["f"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
			val episode = query["e"]
				?.substringBefore('|')
				?.trim()
				?.takeIf(String::isNotEmpty)
				?: return null
			return "https://a-reslayer.com/la/public/api/f?n=${"$anime\\$episode".urlEncoded()}"
		}

		fun parseAlternativeLinks(raw: String): List<String> {
			val decoded = Parser.unescapeEntities(raw, false)
				.replace("\\/", "/")
				.replace("\\u0026", "&", ignoreCase = true)
			val fromJson = runCatching { JSONArray(decoded.trim()) }.getOrNull()?.let { array ->
				buildList {
					for (index in 0 until array.length()) {
						when (val value = array.opt(index)) {
							is String -> value.takeIf(::isWebUrl)?.let(::add)
							is JSONObject -> value.keys().forEach { key ->
								value.optString(key).takeIf(::isWebUrl)?.let(::add)
							}
						}
					}
				}
			}.orEmpty()
			if (fromJson.isNotEmpty()) return fromJson.distinct()
			return ABSOLUTE_URL.findAll(decoded)
				.map { it.value.trimEnd('\\', '"', '\'', ')', ']') }
				.filter(::isWebUrl)
				.distinct()
				.toList()
		}

		private fun isWebUrl(value: String): Boolean =
			value.startsWith("https://") || value.startsWith("http://") || value.startsWith("//")

		fun extractMediaFireDirectUrl(document: Document): String? =
			document.selectFirst("#downloadButton[href]")?.attr("href")
				?.takeIf { isDirectMediaUrl(it) }

		fun extractStreamTapeDirectUrl(document: Document, baseUrl: String): String? {
			val value = document.select("#ideoooolink, #norobotlink, #robotlink")
				.asSequence()
				.map { it.text().trim() }
				.firstOrNull { "/get_video?" in it }
				?: return null
			val absolute = when {
				value.startsWith("//") -> "https:$value"
				value.matches(Regex("""^/streamtape\.[^/]+/get_video\?.+""", RegexOption.IGNORE_CASE)) ->
					"https:/$value"
				value.startsWith("/get_video?") -> URI(baseUrl).resolve(value).toString()
				else -> absoluteUrl(value, baseUrl)
			}
			return ensureStreamTapeStreamingUrl(absolute)
		}

		/**
		 * StreamTape intentionally leaves invalid tokens in its hidden nodes.
		 * The working URL is assembled by a small script such as:
		 *
		 * `'//stre' + ('xxxxamtape.to/get_video?...').substring(4)`
		 *
		 * Reading #ideoooolink directly therefore yields a URL which returns a
		 * 500 response. Reproduce only the string concatenation/substring
		 * operations instead of executing arbitrary page JavaScript.
		 */
		fun extractStreamTapeScriptUrl(raw: String, baseUrl: String): String? {
			val decoded = Parser.unescapeEntities(raw, false)
				.replace("\\/", "/")
				.replace("\\u0026", "&", ignoreCase = true)
			val assignments = STREAM_TAPE_ASSIGNMENT.findAll(decoded).toList()
				.sortedBy { match ->
					when (match.groupValues[2].lowercase(Locale.ROOT)) {
						"captchalink" -> 0
						"ideoooolink" -> 1
						else -> 2
					}
				}
			for (assignment in assignments) {
				val expression = assignment.groupValues[3]
				val value = buildString {
					for (term in JS_STRING_TERM.findAll(expression)) {
						var part = term.groupValues[2]
							.replace("\\/", "/")
							.replace("\\u0026", "&", ignoreCase = true)
						for (substring in JS_SUBSTRING_CALL.findAll(term.groupValues[3])) {
							val offset = substring.groupValues[1].toIntOrNull() ?: continue
							if (offset > part.length) {
								part = ""
								break
							}
							part = part.substring(offset)
						}
						append(part)
					}
				}
				if ("/get_video?" !in value) continue
				return ensureStreamTapeStreamingUrl(
					when {
						value.startsWith("//") -> "https:$value"
						value.matches(
							Regex(
								"""^/streamtape\.[^/]+/get_video\?.+""",
								RegexOption.IGNORE_CASE,
							),
						) -> "https:/$value"
						else -> absoluteUrl(value, baseUrl)
					},
				)
			}
			return null
		}

		fun findDirectMediaUrls(raw: String): List<String> {
			val decoded = Parser.unescapeEntities(raw, false)
				.replace("\\/", "/")
				.replace("\\u0026", "&", ignoreCase = true)
			return DIRECT_MEDIA_URL.findAll(decoded)
				.map { absoluteUrl(it.value.trimEnd('\\', '"', '\'', ')', ']'), "") }
				.distinct()
				.toList()
		}

		fun findStreamTapeUrl(raw: String): String? {
			val decoded = Parser.unescapeEntities(raw, false)
				.replace("\\/", "/")
				.replace("\\u0026", "&", ignoreCase = true)
			extractStreamTapeScriptUrl(decoded, "https://streamtape.com/")
				?.let { return it }
			extractStreamTapeDirectUrl(Jsoup.parse(decoded, "https://streamtape.com/"), "https://streamtape.com/")
				?.let { return it }
			STREAM_TAPE_URL.find(decoded)?.value?.let {
				return ensureStreamTapeStreamingUrl(absoluteUrl(it, "https://streamtape.com/"))
			}
			val fragment = ROBOT_LINK.find(decoded)?.groupValues?.getOrNull(1) ?: return null
			return fragment.takeIf { "/get_video?" in it }?.let {
				ensureStreamTapeStreamingUrl(absoluteUrl(it, "https://streamtape.com/"))
			}
		}

		private fun ensureStreamTapeStreamingUrl(url: String): String {
			if (Regex("""(?:[?&])stream=""", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
				return url
			}
			return "$url${if ('?' in url) '&' else '?'}stream=1"
		}

		fun isDirectMediaUrl(value: String): Boolean {
			val path = value.substringBefore('?').lowercase(Locale.ROOT)
			return path.endsWith(".mp4") || path.endsWith(".m3u8")
		}

		fun absoluteUrl(value: String, baseUrl: String): String = runCatching {
			when {
				value.startsWith("//") -> "https:$value"
				value.startsWith("https://") || value.startsWith("http://") -> value
				baseUrl.isNotBlank() -> URI(baseUrl).resolve(value).toString()
				else -> value
			}
		}.getOrDefault(value)
	}
}
