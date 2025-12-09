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
	private val minRequestInterval = 10000L // 10 seconds between requests
	private var requestCounter = 0
	private val maxRequestsPerSession = 3 // Maximum 3 requests per session
	private var failedAttempts = 0
	private val maxRetries = 2 // Maximum 2 retries per request

	// Rate limiting helper with request counter
	private suspend fun rateLimit() {
		requestCounter++
		if (requestCounter > maxRequestsPerSession) {
			println("AzoraMoon: Maximum request limit reached ($maxRequestsPerSession)")
			throw Exception("تم الوصول للحد الأقصى من الطلبات. الرجاء إعادة تشغيل التطبيق.")
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

	// Helper function for PERMANENT caching with retry logic
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

		// Try up to maxRetries times
		var lastException: Exception? = null
		repeat(maxRetries) { attempt ->
			try {
				// Apply rate limiting
				rateLimit()
				
				val data = fetcher()
				cache[key] = data
				failedAttempts = 0 // Reset failed attempts on success
				println("AzoraMoon: Successfully cached $key")
				return data
			} catch (e: Exception) {
				lastException = e
				failedAttempts++
				println("AzoraMoon: Attempt ${attempt + 1}/$maxRetries failed for $key: ${e.message}")
				
				if (attempt < maxRetries - 1) {
					// Wait longer before retry
					val retryDelay = minRequestInterval * (attempt + 1)
					println("AzoraMoon: Waiting ${retryDelay}ms before retry...")
					delay(retryDelay)
				}
			}
		}

		// All retries failed
		println("AzoraMoon: All retries failed for $key")
		throw lastException ?: Exception("فشل تحميل البيانات بعد $maxRetries محاولات")
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

		// Create unique cache key for each search to avoid wrong results
		val searchQuery = filter.query?.trim()?.lowercase() ?: ""
		val simplifiedKey = when {
			searchQuery.isEmpty() && filter.tags.isEmpty() && filter.states.isEmpty() -> 
				"basic_list_${order}"
			else -> {
				// Use exact search query for accurate results
				"search_${searchQuery}_${order}"
			}
		}

		return withPermanentCache(
			cache = singlePageCache,
			key = simplifiedKey
		) {
			super.getListPage(1, order, filter)
		}
	}
}
