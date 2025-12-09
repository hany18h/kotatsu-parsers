package org.koitharu.kotatsu.parsers.site.madara.ar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.mapToSet
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("AZORAMOON", "AzoraMoon", "ar")
internal class AzoraMoon(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.AZORAMOON, "azoramoon.com", pageSize = 10) {
	override val tagPrefix = "series-genre/"
	override val listUrl = "series/"

	// Wrapper class to cache both success and failure results
	private sealed class CacheResult<out T> {
		data class Success<T>(val data: T) : CacheResult<T>()
		data class Failure(val exception: Exception) : CacheResult<Nothing>()
	}

	// PERMANENT caching system - ONE request per action type EVER
	private val tagCache = ConcurrentHashMap<String, CacheResult<Set<MangaTag>>>()
	private val filterOptionsCache = ConcurrentHashMap<String, CacheResult<MangaListFilterOptions>>()
	private val singlePageCache = ConcurrentHashMap<String, CacheResult<List<Manga>>>()
	private val inProgressRequests = ConcurrentHashMap<String, CompletableDeferred<CacheResult<*>>>()
	private val requestMutex = Mutex() // Global mutex to prevent concurrent requests
	private var lastRequestTime = 0L
	private val minRequestInterval = 5000L // 5 seconds between ANY requests
	private val rateLimitErrorDelay = 30000L // Wait 30 seconds after rate limit error

	// Rate limiting helper - GLOBAL for all requests
	private suspend fun rateLimit() {
		requestMutex.withLock {
			val currentTime = System.currentTimeMillis()
			val timeSinceLastRequest = currentTime - lastRequestTime
			if (timeSinceLastRequest < minRequestInterval) {
				val waitTime = minRequestInterval - timeSinceLastRequest
				println("AzoraMoon: Global rate limiting - waiting ${waitTime}ms")
				delay(waitTime)
			}
			lastRequestTime = System.currentTimeMillis()
		}
	}

	// Check if exception is rate limiting related
	private fun isRateLimitError(e: Exception): Boolean {
		return e is TooManyRequestExceptions || 
		       e.message?.lowercase()?.contains("too many") == true ||
		       e.message?.lowercase()?.contains("rate limit") == true ||
		       e.message?.lowercase()?.contains("429") == true ||
		       e.message?.lowercase()?.contains("too man") == true
	}

	// Helper function for PERMANENT caching - one request per operation EVER
	@Suppress("UNCHECKED_CAST")
	private suspend inline fun <T> withPermanentCache(
		cache: ConcurrentHashMap<String, CacheResult<T>>,
		key: String,
		useRateLimit: Boolean = true,
		crossinline fetcher: suspend () -> T
	): T {
		// First check cache WITHOUT lock for performance
		val cached = cache[key]
		if (cached != null) {
			when (cached) {
				is CacheResult.Success -> {
					println("AzoraMoon: Cache HIT for $key")
					return cached.data
				}
				is CacheResult.Failure -> {
					println("AzoraMoon: Cache FAILURE for $key: ${cached.exception.message}")
					throw cached.exception
				}
			}
		}

		// Use mutex to ensure only ONE request per key at a time
		return requestMutex.withLock {
			// Double-check cache after acquiring lock
			val cachedAfterLock = cache[key]
			if (cachedAfterLock != null) {
				when (cachedAfterLock) {
					is CacheResult.Success -> return@withLock cachedAfterLock.data
					is CacheResult.Failure -> throw cachedAfterLock.exception
				}
			}

			// Check if another coroutine is already making this request
			val existingRequest = inProgressRequests[key] as? CompletableDeferred<CacheResult<T>>
			if (existingRequest != null) {
				println("AzoraMoon: Waiting for in-progress request for $key")
				val result = existingRequest.await()
				when (result) {
					is CacheResult.Success -> return@withLock result.data
					is CacheResult.Failure -> throw result.exception
				}
			}

			// Create new request
			val deferred = CompletableDeferred<CacheResult<T>>()
			inProgressRequests[key] = deferred as CompletableDeferred<CacheResult<*>>

			try {
				println("AzoraMoon: Making ONE-TIME request for $key")

				// Apply global rate limiting
				if (useRateLimit) {
					val currentTime = System.currentTimeMillis()
					val timeSinceLastRequest = currentTime - lastRequestTime
					if (timeSinceLastRequest < minRequestInterval) {
						val waitTime = minRequestInterval - timeSinceLastRequest
						println("AzoraMoon: Rate limiting - waiting ${waitTime}ms")
						delay(waitTime)
					}
					lastRequestTime = System.currentTimeMillis()
				}

				var retryCount = 0
				val maxRetries = 1 // Only 1 retry to avoid spam

				while (retryCount <= maxRetries) {
					try {
						val data = fetcher()
						val result = CacheResult.Success(data)
						cache[key] = result
						deferred.complete(result)
						println("AzoraMoon: Successfully cached $key")
						return@withLock data
					} catch (e: Exception) {
						if (isRateLimitError(e)) {
							retryCount++
							if (retryCount <= maxRetries) {
								val waitTime = rateLimitErrorDelay
								println("AzoraMoon: Rate limit error for $key, waiting ${waitTime}ms before retry $retryCount/$maxRetries")
								delay(waitTime)
								lastRequestTime = System.currentTimeMillis() + waitTime // Reset timer
								continue
							}
						}
						
						// Cache the failure IMMEDIATELY to prevent any retries
						val failureResult = CacheResult.Failure(e)
						cache[key] = failureResult
						deferred.complete(failureResult)
						println("AzoraMoon: Cached failure for $key: ${e.message}")
						throw e
					}
				}

				// Should never reach here
				val finalException = Exception("Max retries exceeded for $key")
				val finalFailure = CacheResult.Failure(finalException)
				cache[key] = finalFailure
				deferred.complete(finalFailure)
				throw finalException
			} finally {
				inProgressRequests.remove(key)
			}
		}
	}

	// Override tag fetching with caching and rate limiting (HEAVILY RESTRICTED)
	override suspend fun fetchAvailableTags(): Set<MangaTag> = withPermanentCache(
		cache = tagCache,
		key = "tags",
		useRateLimit = true
	) {
		super.fetchAvailableTags()
	}

	// Override filter options with caching (HEAVILY RESTRICTED)
	override suspend fun getFilterOptions(): MangaListFilterOptions = withPermanentCache(
		cache = filterOptionsCache,
		key = "filter_options",
		useRateLimit = true
	) {
		MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
			availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
		)
	}

	// Generate stable cache key for list pages
	private fun generateListCacheKey(page: Int, order: SortOrder, filter: MangaListFilter): String {
		val query = filter.query ?: ""
		val tags = filter.tags.sortedBy { it.key }.joinToString(",") { it.key }
		val states = filter.states.sorted().joinToString(",")
		val contentRating = filter.contentRating?.toString() ?: ""
		return "list_${page}_${order}_${query}_${tags}_${states}_${contentRating}"
	}

	// Override list page with VERY LIMITED caching - only allow basic browsing
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Only allow page 1 requests to avoid pagination spam
		if (page > 1) {
			println("AzoraMoon: Blocking page $page request - only page 1 allowed")
			return emptyList() // Return empty instead of making request
		}

		// Use simplified cache key for basic browsing only
		val simplifiedKey = if (filter.query.isNullOrEmpty() && filter.tags.isEmpty() && filter.states.isEmpty()) {
			"basic_list_${order}" // Basic browsing
		} else {
			"search_${filter.query ?: ""}_${order}" // Simple search
		}

		return withPermanentCache(
			cache = singlePageCache,
			key = simplifiedKey,
			useRateLimit = true
		) {
			super.getListPage(1, order, filter) // Always request page 1 only
		}
	}
}
