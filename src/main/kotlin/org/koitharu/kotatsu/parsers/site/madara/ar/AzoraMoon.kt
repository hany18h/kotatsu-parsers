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

	// PERMANENT caching system
	private val singlePageCache = ConcurrentHashMap<String, List<Manga>>()
	private var lastRequestTime = 0L
	private val minRequestInterval = 3000L // 3 seconds between requests
	private var requestCounter = 0
	private val maxRequestsPerSession = 5 // Maximum 5 requests per session

	// Rate limiting helper with request counter
	private suspend fun rateLimit() {
		requestCounter++
		if (requestCounter > maxRequestsPerSession) {
			println("AzoraMoon: Maximum request limit reached ($maxRequestsPerSession)")
			throw Exception("Maximum request limit reached. Please restart the app to make new requests.")
		}
		
		val currentTime = System.currentTimeMillis()
		val timeSinceLastRequest = currentTime - lastRequestTime
		if (timeSinceLastRequest < minRequestInterval) {
			val waitTime = minRequestInterval - timeSinceLastRequest
			println("AzoraMoon: Waiting ${waitTime}ms before request #$requestCounter")
			delay(waitTime)
		}
		lastRequestTime = System.currentTimeMillis()
		println("AzoraMoon: Executing request #$requestCounter")
	}

	// Helper function for PERMANENT caching
	private suspend inline fun <T> withPermanentCache(
		cache: ConcurrentHashMap<String, T>,
		key: String,
		crossinline fetcher: suspend () -> T
	): T {
		// If we have cached data, return it immediately
		val cached = cache[key]
		if (cached != null) {
			println("AzoraMoon: Returning cached data for $key")
			return cached
		}

		println("AzoraMoon: Making ONE-TIME request for $key")

		// Apply rate limiting
		rateLimit()

		try {
			val data = fetcher()
			cache[key] = data
			println("AzoraMoon: Successfully cached $key")
			return data
		} catch (e: Exception) {
			println("AzoraMoon: Request failed for $key: ${e.message}")
			throw e
		}
	}

	// Override tag fetching - DISABLED due to extreme rate limiting
	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		println("AzoraMoon: Tags fetching is disabled due to rate limiting")
		return emptySet()
	}

	// Override filter options - return minimal options without making requests
	override suspend fun getFilterOptions(): MangaListFilterOptions {
		println("AzoraMoon: Returning minimal filter options (no tags)")
		return MangaListFilterOptions(
			availableTags = emptySet(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
			availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
		)
	}

	// Override list page with VERY LIMITED caching
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Only allow page 1 to avoid pagination spam
		if (page > 1) {
			println("AzoraMoon: Blocking page $page - only page 1 allowed")
			return emptyList()
		}

		// Simplified cache key - merge similar searches
		val searchQuery = filter.query?.trim()?.lowercase() ?: ""
		val simplifiedKey = when {
			searchQuery.isEmpty() && filter.tags.isEmpty() && filter.states.isEmpty() -> 
				"basic_list_${order}"
			searchQuery.length <= 3 -> 
				"search_short_${order}"
			else -> {
				// Group searches by first 2 words
				val words = searchQuery.split(" ").take(2).joinToString(" ")
				"search_${words}_${order}"
			}
		}

		return withPermanentCache(
			cache = singlePageCache,
			key = simplifiedKey
		) {
			try {
				super.getListPage(1, order, filter)
			} catch (e: Exception) {
				println("AzoraMoon: Failed to fetch page: ${e.message}")
				// Return cached data from any similar search if available
				singlePageCache.values.firstOrNull() ?: emptyList()
			}
		}
	}
}
