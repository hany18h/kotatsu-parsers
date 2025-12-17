package org.koitharu.kotatsu.parsers.site.madara.ar

import kotlinx.coroutines.delay
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("AZORAMOON", "AzoraMoon", "ar")
internal class AzoraMoon(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.AZORAMOON, "azoramoon.com", pageSize = 10) {
	
	override val tagPrefix = "series-genre/"
	override val listUrl = "series/"
	override val withoutAjax = true

	// PERMANENT caching system
	private val tagCache = ConcurrentHashMap<String, Set<MangaTag>>()
	private val filterOptionsCache = ConcurrentHashMap<String, MangaListFilterOptions>()
	private val listPageCache = ConcurrentHashMap<String, List<Manga>>()
	
	private var lastRequestTime = 0L
	private val minRequestInterval = 1500L

	// Rate limiting
	private suspend fun rateLimit() {
		val currentTime = System.currentTimeMillis()
		val timeSinceLastRequest = currentTime - lastRequestTime
		if (timeSinceLastRequest < minRequestInterval) {
			delay(minRequestInterval - timeSinceLastRequest)
		}
		lastRequestTime = System.currentTimeMillis()
	}

	// Permanent cache helper
	private suspend inline fun <T> withPermanentCache(
		cache: ConcurrentHashMap<String, T>,
		key: String,
		useRateLimit: Boolean = true,
		crossinline fetcher: suspend () -> T
	): T {
		cache[key]?.let {
			println("AzoraMoon: ✓ Returning cache for $key")
			return it
		}

		println("AzoraMoon: ↓ Making request for $key")

		if (useRateLimit) {
			rateLimit()
		}

		return try {
			val data = fetcher()
			cache[key] = data
			println("AzoraMoon: ✓ Cached $key")
			data
		} catch (e: Exception) {
			println("AzoraMoon: ✗ Failed for $key: ${e.message}")
			if (e.message?.contains("404") == true) {
				@Suppress("UNCHECKED_CAST")
				emptyList<Manga>() as T
			} else {
				throw e
			}
		}
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> = withPermanentCache(
		cache = tagCache,
		key = "tags",
		useRateLimit = true
	) {
		super.fetchAvailableTags()
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = withPermanentCache(
		cache = filterOptionsCache,
		key = "filter_options",
		useRateLimit = true
	) {
		MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.ABANDONED
			),
			availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
		)
	}

	// Generate cache key
	private fun generateCacheKey(page: Int, order: SortOrder, filter: MangaListFilter): String {
		val query = filter.query?.trim()?.replace(Regex("\\s+"), "_") ?: ""
		return buildString {
			append("list_p${page}_${order.name}")
			if (query.isNotEmpty()) append("_q${query}")
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// ✅ CRITICAL: Block pagination for search queries
		if (!filter.query.isNullOrEmpty() && page > 1) {
			println("AzoraMoon: ⚠ Search pagination blocked at page $page")
			return emptyList()
		}

		// ✅ Limit normal browsing to 2 pages max
		if (page > 2) {
			println("AzoraMoon: ⚠ Browse pagination blocked at page $page")
			return emptyList()
		}

		val cacheKey = generateCacheKey(page, order, filter)

		return withPermanentCache(
			cache = listPageCache,
			key = cacheKey,
			useRateLimit = true
		) {
			try {
				println("AzoraMoon: → Fetching page $page")
				super.getListPage(page, order, filter)
			} catch (e: Exception) {
				if (e.message?.contains("404") == true) {
					println("AzoraMoon: → 404 error, returning empty")
					emptyList()
				} else {
					throw e
				}
			}
		}
	}
}
