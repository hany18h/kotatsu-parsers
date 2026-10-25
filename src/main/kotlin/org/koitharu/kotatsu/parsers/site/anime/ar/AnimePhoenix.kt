package org.koitharu.kotatsu.parsers.site.anime.ar

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
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
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.EnumSet
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("ANIME_PHOENIX", "Anime Phoenix", "ar", ContentType.ANIME)
internal class AnimePhoenix(private val loaderContext: MangaLoaderContext) : PagedMangaParser(
	context = loaderContext,
	source = MangaParserSource.ANIME_PHOENIX,
	pageSize = PAGE_SIZE,
	searchPageSize = PAGE_SIZE,
) {

	override val configKeyDomain = ConfigKey.Domain("anime-phoenix.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	init {
		setFirstPage(1)
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val doc = loadSearchDocument()
		val tags = doc.select(".custom-select[data-filter=genre] .select-option[data-value]")
			.mapNotNullTo(LinkedHashSet()) { option ->
				val key = option.attr("data-value").trim().takeIf(String::isNotEmpty)
					?: return@mapNotNullTo null
				MangaTag(
					key = key,
					title = option.text().trim().ifEmpty { key },
					source = source,
				)
			}
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.UPCOMING,
			),
		)
	}

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val searchConfig = checkNotNull(extractSearchConfig(loadSearchDocument())) {
			"Anime Phoenix search configuration is unavailable"
		}
		val payload = mapOf(
			"action" to "phoenix_search",
			"q" to filter.query?.trim().orEmpty(),
			"type" to "tvshow",
			"genre" to filter.tags.oneOrThrowIfMany()?.key.orEmpty(),
			"status" to when (filter.states.oneOrThrowIfMany()) {
				MangaState.ONGOING -> "releasing"
				MangaState.FINISHED -> "completed"
				MangaState.UPCOMING -> "upcoming"
				else -> ""
			},
			"year" to "",
			"season" to "",
			"sort" to when (order) {
				SortOrder.POPULARITY -> "views"
				SortOrder.ALPHABETICAL -> "az"
				SortOrder.RELEVANCE -> "relevance"
				else -> "date"
			},
			"page" to page.coerceAtLeast(1).toString(),
			"per_page" to PAGE_SIZE.toString(),
			"dropdown" to "0",
		)
		val response = if (searchConfig.optString("api_url").isNotBlank()) {
			loadPublicSearch(searchConfig, payload)
		} else {
			val nonce = searchConfig.optString("nonce").takeIf(String::isNotBlank)
				?: error("Anime Phoenix did not publish a search nonce")
			val ajaxUrl = searchConfig.optString("ajax_url").ifBlank { "https://$domain/wp-admin/admin-ajax.php" }
			webClient.httpPost(ajaxUrl.toHttpUrl(), payload + ("nonce" to nonce), ajaxHeaders("https://$domain/search/")).parseJson()
		}
		check(response.optBoolean("success")) { "Anime Phoenix rejected the search request" }
		val results = checkNotNull(response.optJSONObject("data")?.optJSONArray("results")) {
			"Anime Phoenix returned an invalid search response"
		}
		return buildList {
			for (i in 0 until results.length()) {
				parseSearchResult(results.optJSONObject(i))?.let(::add)
			}
		}.distinctBy(Manga::url)
	}

	private suspend fun loadSearchDocument(): Document {
		// Current public search settings are published on every page. The old
		// /search/ nonce endpoint is no longer needed and is often challenged.
		val home = "https://$domain/"
		val direct = runCatchingCancellable { webClient.httpGet(home).parseHtml() }
		direct.getOrNull()?.takeIf { extractSearchConfig(it) != null }?.let { return it }
		val browser = runCatchingCancellable { loaderContext.evaluateJs(home, """
			(function() {
			  if (typeof PhoenixSearch === 'undefined' && typeof fjSearchPageData === 'undefined') return null;
			  return document.documentElement.outerHTML;
			})()
		""".trimIndent(), mapOf("User-Agent" to config[userAgentKey])) }
		val raw = browser.getOrNull()
		if (raw != null) {
			val html = JSONObject("{\"value\":$raw}").optString("value")
			val document = Jsoup.parse(html, home)
			if (extractSearchConfig(document) != null) return document
		}
		direct.exceptionOrNull()?.let { throw it }
		browser.exceptionOrNull()?.let { throw it }
		error("Anime Phoenix did not return public search settings")
	}

	private suspend fun loadPublicSearch(settings: JSONObject, payload: Map<String, String>): JSONObject {
		val params = payload - setOf("action", "nonce", "status") + ("release_status" to payload["status"].orEmpty())
		val url = settings.getString("api_url").toAbsoluteUrl(domain).toHttpUrl().newBuilder()
			.apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
		val timestamp = (System.currentTimeMillis() / 1000).toString()
		val headers = ajaxHeaders("https://$domain/").newBuilder()
			.add("X-PX-Timestamp", timestamp)
			.add("X-PX-Signature", signSearch(params, timestamp, settings.getString("public_key")))
			.build()
		val direct = runCatchingCancellable { webClient.httpGet(url.toString(), headers).parseJson() }
		direct.getOrNull()?.let { return it }
		// Use the site's normal same-origin browser request if its HTTP edge
		// requires the browser session. Each URL is fetched only once per page.
		val requestHeaders = JSONObject().apply { headers.forEach { (name, value) -> put(name, value) } }
		val browser = runCatchingCancellable { loaderContext.evaluateJs("https://$domain/", """
			(function() {
			  var key = ${JSONObject.quote(url.toString())};
			  var state = window.__kotatsuPhoenixSearch;
			  if (!state || state.key !== key) {
			    state = window.__kotatsuPhoenixSearch = {key: key, result: null};
			    fetch(key, {credentials: 'same-origin', headers: $requestHeaders})
			      .then(function(r) { if (!r.ok) throw new Error(r.status); return r.json(); })
			      .then(function(r) { state.result = JSON.stringify(r); })
			      .catch(function() { state.result = JSON.stringify({success: false}); });
			  }
			  return state.result;
			})()
		""".trimIndent(), mapOf("User-Agent" to config[userAgentKey]))?.let { raw ->
			JSONObject(JSONObject("{\"value\":$raw}").getString("value"))
		} }
		browser.getOrNull()?.takeIf { it.optBoolean("success") }?.let { return it }
		// Keep the host's original interactive-verification action when the
		// browser fallback also fails, rather than replacing it with a timeout.
		direct.exceptionOrNull()?.let { throw it }
		browser.exceptionOrNull()?.let { throw it }
		error("Anime Phoenix did not return a valid search response")
	}

	private fun parseSearchResult(item: JSONObject?): Manga? {
		item ?: return null
		val title = item.optString("title_ar").trim().takeIf(String::isNotEmpty) ?: return null
		val publicUrl = item.optString("url").trim().takeIf(String::isNotEmpty)
			?: item.optString("slug").trim().takeIf(String::isNotEmpty)?.let {
				"https://$domain/animes/$it"
			}
			?: return null
		val path = publicUrl.toRelativeUrl(domain)
		val tags = LinkedHashSet<MangaTag>()
		val genres = item.optJSONArray("genres_array")
		if (genres != null) {
			for (i in 0 until genres.length()) {
				val genre = genres.optJSONObject(i) ?: continue
				if (genre.optString("taxonomy") != "tvshow_genre") continue
				val name = genre.optString("name").trim().takeIf(String::isNotEmpty) ?: continue
				tags += MangaTag(key = name, title = name, source = source)
			}
		}
		return Manga(
			id = generateUid(path),
			title = title,
			altTitles = setOfNotNull(item.optString("title_en").trim().ifEmpty { null }),
			url = path,
			publicUrl = publicUrl,
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = item.optString("thumbnail_url").trim().ifEmpty { null },
			tags = tags,
			state = parseState(item.optString("status")),
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(mangaUrl).parseHtml()
		val series = findSeriesJson(doc)
		val cover = doc.selectFirst(".FJ-Phoenix-Hero-Poster img")?.src()
			?: series?.optString("image")?.takeIf(String::isNotBlank)
			?: manga.coverUrl
		val title = doc.selectFirst(".FJ-Phoenix-Hero-Title")?.text()?.trim()
			?: series?.optString("name")?.trim()
			?: manga.title
		val altTitles = LinkedHashSet<String>().apply {
			addAll(manga.altTitles)
			when (val value = series?.opt("alternateName")) {
				is JSONArray -> addAll(jsonStrings(value))
				is String -> value.trim().takeIf(String::isNotEmpty)?.let(::add)
			}
			remove(title)
		}
		val tags = series?.optJSONArray("genre")?.let { genres ->
			jsonStrings(genres).mapTo(LinkedHashSet()) {
				MangaTag(key = it, title = it, source = source)
			}
		}.orEmpty()
		val rating = series?.optJSONObject("aggregateRating")
			?.optString("ratingValue")
			?.toFloatOrNull()
			?.div(10f)
			?.coerceIn(0f, 1f)
			?: manga.rating

		return manga.copy(
			title = title,
			altTitles = altTitles,
			description = doc.selectFirst(".FJ-Phoenix-Desc-Full")?.let {
				Element("p").text(it.text()).outerHtml()
			} ?: series?.optString("description")?.takeIf(String::isNotBlank)?.let {
				Element("p").text(it).outerHtml()
			} ?: manga.description,
			coverUrl = cover,
			largeCoverUrl = cover,
			rating = rating,
			tags = tags.ifEmpty { manga.tags },
			state = parseDetailsState(doc) ?: manga.state,
			chapters = parseEpisodes(doc),
		)
	}

	private fun parseEpisodes(doc: Document): List<MangaChapter> =
		doc.select("a.FJ-EpPill[href*=/episodes/]").mapIndexedNotNull { index, anchor ->
			val href = anchor.attr("href").trim().takeIf(String::isNotEmpty)
				?.toRelativeUrl(domain)
				?: return@mapIndexedNotNull null
			val number = EPISODE_NUMBER.find(href)?.groupValues?.getOrNull(1)?.toFloatOrNull()
				?: Regex("""\d+(?:\.\d+)?""").find(anchor.text())?.value?.toFloatOrNull()
				?: (index + 1).toFloat()
			MangaChapter(
				id = generateUid(href),
				title = "الحلقة ${formatNumber(number)}",
				number = number,
				volume = 0,
				url = href,
				scanlator = "Anime Phoenix",
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}.distinctBy(MangaChapter::url).sortedBy(MangaChapter::number)

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getVideoStreams(chapter: MangaChapter): List<AnimeStream> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		val result = ArrayList<AnimeStream>()

		doc.select("#player-html-template source[src], video source[src]").forEach { sourceElement ->
			val url = sourceElement.src() ?: return@forEach
			result += createStream("Primary", url, chapterUrl)
		}
		doc.select(".server-link[data-server]").forEach { serverElement ->
			val payload = decodeServerPayload(serverElement.attr("data-server")) ?: return@forEach
			val url = payload.optString("link").trim().takeIf(::isWebUrl) ?: return@forEach
			val name = payload.optString("name").trim()
				.ifEmpty { serverElement.attr("data-server-name").trim() }
				.ifEmpty { "Server" }
			result += createStream(name, url, chapterUrl)
		}
		return result.distinctBy(AnimeStream::url)
	}

	private fun createStream(name: String, url: String, referer: String): AnimeStream = AnimeStream(
		name = "Anime Phoenix • $name",
		url = url,
		headers = mapOf(
			"Referer" to referer,
			"User-Agent" to config[userAgentKey],
		),
		quality = detectQuality(url),
	)

	private fun ajaxHeaders(referer: String): Headers = Headers.Builder()
		.add("Accept", "application/json, text/javascript, */*; q=0.01")
		.add("Referer", referer)
		.add("X-Requested-With", "XMLHttpRequest")
		.add("User-Agent", config[userAgentKey])
		.build()

	private fun parseDetailsState(doc: Document): MangaState? {
		val slugs = doc.select("a[href*='/search/']").joinToString(" ") { it.attr("href") }
		return when {
			"/search/completed" in slugs -> MangaState.FINISHED
			"/search/releasing" in slugs -> MangaState.ONGOING
			"/search/upcoming" in slugs -> MangaState.UPCOMING
			else -> null
		}
	}

	private fun parseState(value: String): MangaState? = when {
		value.equals("completed", true) -> MangaState.FINISHED
		value.equals("releasing", true) -> MangaState.ONGOING
		value.equals("upcoming", true) -> MangaState.UPCOMING
		else -> null
	}

	private fun formatNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

	internal companion object {
		const val PAGE_SIZE = 25
		private val EPISODE_NUMBER = Regex("""episode-(\d+(?:\.\d+)?)(?:/)?$""", RegexOption.IGNORE_CASE)
		private val QUALITY_NUMBER = Regex("""(?:^|[^\d])(\d{3,4})p(?:[^\d]|$)""", RegexOption.IGNORE_CASE)
		private const val SEARCH_CONFIG_VARIABLE = "fjSearchPageData"

		internal fun extractSearchConfig(document: Document): JSONObject? {
			for (variable in listOf("PhoenixSearch", SEARCH_CONFIG_VARIABLE)) {
				for (script in document.select("script")) {
					val raw = extractAssignedJsonObject(script.data(), variable) ?: continue
					val settings = runCatching { JSONObject(raw) }.getOrNull() ?: continue
					if (settings.optString("api_url").isNotBlank() || settings.optString("nonce").isNotBlank()) return settings
				}
			}
			return null
		}

		internal fun signSearch(params: Map<String, String>, timestamp: String, publicKey: String): String {
			val canonical = listOf(timestamp, params["q"].orEmpty(), params["type"].orEmpty().ifEmpty { "all" },
				params["genre"].orEmpty(), params["release_status"] ?: params["status"].orEmpty(),
				params["year"].orEmpty(), params["season"].orEmpty(), params["page"].orEmpty().ifEmpty { "1" },
				params["sort"].orEmpty().ifEmpty { "relevance" }).joinToString(":")
			val mac = Mac.getInstance("HmacSHA256")
			mac.init(SecretKeySpec(publicKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
			return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
		}

		/**
		 * Extracts an object assigned to a JavaScript variable without relying on
		 * regex handling of escaped braces. Android's ICU regex rejects the former
		 * `\{.*?}` pattern during class initialization, crashing the whole source.
		 */
		internal fun extractAssignedJsonObject(script: String, variableName: String): String? {
			val variableIndex = script.indexOf(variableName)
			if (variableIndex < 0) return null
			val assignmentIndex = script.indexOf('=', variableIndex + variableName.length)
			if (assignmentIndex < 0) return null
			val objectStart = script.indexOf('{', assignmentIndex + 1)
			if (objectStart < 0) return null

			var depth = 0
			var quote = '\u0000'
			var escaped = false
			for (index in objectStart until script.length) {
				val char = script[index]
				if (quote != '\u0000') {
					when {
						escaped -> escaped = false
						char == '\\' -> escaped = true
						char == quote -> quote = '\u0000'
					}
					continue
				}
				when (char) {
					'"', '\'' -> quote = char
					'{' -> depth++
					'}' -> {
						depth--
						if (depth == 0) return script.substring(objectStart, index + 1)
						if (depth < 0) return null
					}
				}
			}
			return null
		}

		internal fun findSeriesJson(document: Document): JSONObject? {
			document.select("script[type=application/ld+json]").forEach { script ->
				val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: return@forEach
				val type = json.optString("@type")
					.ifEmpty { json.optJSONArray("@type")?.let(::jsonStrings)?.joinToString().orEmpty() }
				if (type.contains("TVSeries", true) || type.contains("Movie", true)) return json
			}
			return null
		}

		internal fun decodeServerPayload(value: String): JSONObject? = runCatching {
			val decodedBase64 = String(
				Base64.getDecoder().decode(value.trim()),
				StandardCharsets.UTF_8,
			)
			val json = URLDecoder.decode(decodedBase64, StandardCharsets.UTF_8.name())
			JSONObject(json)
		}.getOrNull()

		internal fun detectQuality(url: String): String? {
			val decoded = runCatching {
				URLDecoder.decode(url, StandardCharsets.UTF_8.name())
			}.getOrDefault(url)
			return QUALITY_NUMBER.find(decoded)?.groupValues?.getOrNull(1)?.let { "${it}p" }
		}

		private fun jsonStrings(values: JSONArray): List<String> = buildList {
			for (i in 0 until values.length()) {
				values.optString(i).trim().takeIf(String::isNotEmpty)?.let(::add)
			}
		}

		private fun isWebUrl(value: String): Boolean =
			value.startsWith("https://") || value.startsWith("http://") || value.startsWith("//")
	}
}
